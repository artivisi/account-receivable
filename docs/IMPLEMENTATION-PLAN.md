# account-receivable — Technical Design & Roadmap

Status: engine complete. Phases 0–5 shipped. Read `CLAUDE.md` first; this document refines it into a build plan.

## 1. Scope recap

Operational receivables subledger. Owns per-debtor detail (debtor, invoice + lines, types,
installment schedules, lifecycle, aging, dunning, cash application). Delegates money movement to
`payment-gateway` (consumer) and the book of record to a GL `aplikasi-akunting` (poster). Generic
engine; deployment specifics (where receivables originate, VA numbering scheme, notification
templates, report dimensions) live in a thin adapter/config layer outside the engine.

## 2. Stack & conventions (mirror payment-gateway)

- Spring Boot 4.1.0 · Java 25 · PostgreSQL 18 + Flyway · Thymeleaf + HTMX + Tailwind.
- Namespace `com.artivisi.accountreceivable`. Package layout: `config / entity / repository /
  service / web / dto / client / exception`.
- IDs: `varchar(36)` via Hibernate `@UuidGenerator`. Money: `numeric(19,2)` (`BigDecimal`).
  Timestamps: `timestamptz` (`Instant`), `@CreationTimestamp` / `@UpdateTimestamp`.
- Enums: `varchar` + `@Enumerated(STRING)`.
- Flyway: `V{n}__{desc}.sql` in `src/main/resources/db/migration`.
- Fail-loud config: `@ConfigurationProperties` + `@Validated` + `@NotBlank/@NotNull`, scanned via
  `@ConfigurationPropertiesScan`; missing env var → startup failure. No defaults anywhere.
- Secrets at rest (GL/gateway client secrets): `@Convert` + AES-256-GCM converter, key from a
  validated `*SecurityProperties` (exactly 32 bytes Base64) — same pattern as the gateway's
  `SecretCipher`/`SecretConverter`.
- Tests: Testcontainers (singleton PG18) + RestAssured (API) + Playwright (UI). Schedulers off in
  tests; drive outbox/dispatch by calling the dispatch method explicitly.

Decided tech choices (mirror siblings):
- **Build**: Maven (`pom.xml`), matching gateway + GL.
- **Boilerplate**: Lombok on JPA entities; Java 25 `record`s for DTOs.
- **Outbound HTTP** (gateway + GL clients): Spring `RestClient` (synchronous; virtual threads).
- **Auth**: OAuth2, **self-contained** — AR is its own authorization server (device-flow/token,
  copying `aplikasi-akunting`'s setup) **and** resource server for its API; Spring Security form
  login for the admin UI. Each product owns its users (no shared IdP for now).

## 3. Domain model

Status enums:
- `PaymentStatus`: OPEN · PARTIALLY_PAID · PAID · WRITTEN_OFF. **Deviation from CLAUDE.md:** its
  single `status` enum folded in `OVERDUE`, but an invoice can be partially-paid *and* overdue at
  once. Overdue is therefore **derived** (`dueDate < today AND outstanding > 0`), not a stored
  status; the daily sweep drives dunning, not a column. (CLAUDE.md prose should be updated to match.)
- `ChargeType` (mirror gateway): CLOSED · INSTALLMENT · OPEN. AR opens **CLOSED** charges only
  (one per invoice or per installment); INSTALLMENT/OPEN reserved for later deposit-style flows.
- Write-off is **full only** — a terminal `PaymentStatus = WRITTEN_OFF` flip on the whole invoice,
  journaling the entire remaining outstanding. No partial write-off document.

Core registries & receivables:
- **Debtor** — `code` (unique), `name`, contacts (email, phone). Master data, no academic fields.
- **InvoiceType** — `code` (unique), `name`, **`glRevenueAccount`** (the GL account this type's
  revenue posts to). This is the only GL-account mapping that varies per receivable category.
- **Invoice** — `invoiceNumber` (running number), `debtor`, `invoiceType`, `issueDate`, `dueDate`,
  `amount`, `outstanding`, `status`. Issued amounts immutable.
- **InvoiceLine** — `description`, `quantity`, `unitAmount`; Σ(line amounts) = invoice `amount`
  (enforced on issue).
- **PaymentSchedule** / **Installment** — for INSTALLMENT receivables: ordered installments
  (`sequence`, `dueDate`, `amount`, `outstanding`, `status`). One invoice → one schedule → many
  installments; Σ installments = invoice amount.
- **RunningNumber** — `prefix` (unique) + `lastNumber`; allocation in a service, row-locked.

Collection & cash:
- **Charge** — local mirror of the gateway charge opened for an invoice/installment. Holds
  gateway `chargeId`, `consumerReference` (= our invoice/installment id), `chargeType`, `amount`,
  `status`, VA accounts, `cumulativePaid`. Lets us reconcile and avoid re-opening.
- **CashApplication** — one received payment. **Idempotent on the gateway payment reference**
  (unique constraint). Fields: `gatewayPaymentReference`, `amount`, `receivedAt`, `status`.
- **CashApplicationLine** — allocation of a CashApplication to one invoice/installment with an
  allocated amount. Partial / over / under handled explicitly (see §6).

GL & corrections:
- **JournalPosting** — transactional-outbox row for one AR→GL event (issue / receipt / write-off /
  adjustment). Holds event type, computed debit/credit legs (JSON), `status`
  (PENDING/POSTED/FAILED), `attempts`, `nextAttemptAt`, and on success the GL
  `transactionNumber`/reference. **Never lost.**
- **CreditNote / Adjustment** — corrections to issued invoices (immutable amounts → never edit).
  Each carries its own reversing journal.

Operations:
- **DunningRun** — a reminder batch: selection criteria, generated reminders, per-reminder send
  status (channel pluggable).
- **Tie-out report** (deferred, lightweight) — on-demand compare of AR outstanding total ↔ GL A/R
  control-account balance. Not a `ReconciliationRun`/discrepancy subsystem: the outbox guarantee +
  FAILED-row monitoring is the money-loss control; the report is period-close defense-in-depth and
  needs a GL balance endpoint first. See §10.

Auditing: a `BaseEntity` (id, createdAt/By, updatedAt/By) like invoice-management's, on all
mutable entities.

## 4. GL integration (`aplikasi-akunting` / `balaka`)

**Post via the GL's JournalTemplate feature, not raw journal entries.** Use
`POST /api/transactions` with `{ templateId, merchant, amount, transactionDate, description,
accountSlots?, variables? }`. Each template line carries a fixed `accountId` + `position`
(DEBIT/CREDIT) + `formula`, so a single `amount` drives both legs and **the GL computes and
balances the journal**. Consequences for AR: it does **not** compute legs, resolve account
codes→UUIDs, or check Σdebit = Σcredit — the template owns all three.

- **Template mapping — one template per `InvoiceType`** (decided): `InvoiceType` stores a
  `glIssueTemplateId` (a GL template encoding Dr A/R · Cr [that type's revenue]); receipt and
  write-off use single global template ids in config. No account resolution anywhere in AR; no
  `accountSlots` needed. (The earlier code→UUID `GlAccountResolver` is therefore dropped.)
- **Auth — OAuth2 `client_credentials`** (decided): a `GlAuthClient` obtains and caches a service
  token, refreshing on expiry. Config: GL base URL + client id/secret + token endpoint (fail-loud,
  secret encrypted). The GL must add this grant — **dependency: balaka#28** (device flow is
  interactive and unfit for the unattended dispatcher). Built/tested against a stub token endpoint
  until then.
- **Idempotency — caller-supplied key** (decided): AR sends its `journal_posting` id as an
  `Idempotency-Key` header on `POST /api/transactions`; the GL returns the original transaction on a
  repeat. The GL must honor the key — **dependency: balaka#29**. Until then AR posts the key and a
  stub honors it; AR still posts each row exactly once (retries only while PENDING) and records the
  returned `transactionId`/`transactionNumber`.
- Mappings (template per event; AR sends only `amount`):
  - issue → `InvoiceType.glIssueTemplateId` (Dr A/R control · Cr Revenue)
  - receipt → `ar.gl.receipt-template-id` (Dr Cash/Bank · Cr A/R control)
  - write-off → `ar.gl.writeoff-template-id` (Dr Bad Debt · Cr A/R control)
  - credit note / adjustment → its own reversing template (later phase)

### Outbox (mirror gateway `webhook_delivery`)
`journal_posting` table indexed `(status, next_attempt_at)`. Each row carries **templateId + amount
+ description** (no leg rows — the template owns the legs). Enqueued in the **same transaction** as
the AR event (issue/receipt/write-off). A scheduled `JournalPostingDispatcher` polls due PENDING
rows in batches, posts each in its own transaction with the `Idempotency-Key`, records POSTED +
`transactionId`/`transactionNumber` or reschedules with exponential backoff; terminal FAILED after
max attempts (surfaced, never silently dropped).

## 5. Collection integration (`payment-gateway`, consumer)

- **Auth**: `X-Client-Id` + `X-Client-Secret` headers. Config: gateway base URL + client
  credentials (fail-loud, encrypted) + escrow code(s).
- **Open charge**: `POST /api/charges` with `consumerReference` = invoice/installment id,
  `amount`, and `accounts:[{escrowCode, vaNumber}]`. **AR supplies the VA number** within the
  escrow space (gateway validates, does not generate) — the VA-numbering scheme is a **deployment
  adapter concern**, injected, not hard-coded. Idempotent on `consumerReference` (gateway returns
  200 on repeat).
- **Charge granularity**: a single-payment invoice → one **CLOSED** charge keyed on the invoice id.
  An installment invoice → **one CLOSED charge per `Installment`**, `consumerReference` = installment
  id. This makes webhook→installment matching a direct `consumerReference` lookup (no amount/sequence
  guessing) at the cost of one VA per installment. (OPEN charges reserved for deposit-style
  receivables if introduced later.)
- **Webhook receiver**: `POST /webhooks/gateway`. Verify `X-Signature` (HMAC-SHA256 of body with
  our client secret) — reject on mismatch. Events: PAYMENT_RECEIVED, CHARGE_PAID,
  PAYMENT_REVERSED, CHARGE_CANCELLED. Idempotent on the gateway payment reference
  (`X-Webhook-Id`/`bankReference`); CHARGE_CANCELLED carries no payment and is idempotent on the
  charge already being CANCELLED.
- **The local `charge` table is a mirror, not a record.** It is derived from gateway events and must
  never be authored independently. AR owns the receivable; the gateway owns collectability (see
  §10). `CHARGE_CANCELLED` is what keeps the two in step when something other than AR retires a VA —
  an operator in the gateway admin UI, or an ops repair script. Applying an event AR itself caused is
  a no-op, which is the point: the alternative is trusting that AR is the only actor.
- **On payment** → cash application: locate target via `consumerReference`, allocate, update
  `outstanding` + `status`, enqueue receipt journal — all in one transaction.

## 6. Money rules (fail loud)

- Cash application unique on gateway payment reference (replays are no-ops returning the prior result).
- Allocation: exact → mark line/invoice PAID; partial → PARTIALLY_PAID, reduce outstanding; over- /
  under-payment or ambiguous target → **do not guess**: park as unapplied + raise for review.
- Every journal Σdebit = Σcredit or it is rejected before enqueue.
- Issued amounts immutable; corrections only via credit note / adjustment with their own journal.

## 7. Build sequence

Each phase ends green (Testcontainers + RestAssured; Playwright from phase 5) before the next.

**Phase 0 — Scaffold.** ✅ SB4/Java25 project, `pom.xml`, `compose.yml` (PG18), Flyway baseline,
`@ConfigurationPropertiesScan` fail-loud config (DB + GL + gateway properties, all env-sourced),
secret converter, `BaseEntity`, `AbstractIntegrationTest`. Debtor + InvoiceType registries (CRUD
+ API). Exit: app boots only with all config present; registry round-trips in tests.

**Phase 1 — AR core.** ✅ Invoice + lines + types, running numbers, installment schedules. Lifecycle:
issue (line sum check, immutability) / partial / pay / overdue (scheduled sweep) / write-off.
Aging buckets. No external calls yet — receipts applied via internal test endpoint. Exit:
lifecycle + aging covered by tests.

**Phase 2 — Collection.** ✅ `GatewayConsumerClient` (open/get/cancel charge, header auth). Charge
mirror entity. Webhook receiver with HMAC verification + idempotent cash application → status
update. Pluggable VA-number supplier (adapter SPI). Exit: open-charge + webhook→application
integration test against a stubbed gateway; idempotent replay verified.

**Phase 3 — GL posting.** ✅ Template-based posting: `GlAuthClient` (client_credentials) +
`GlJournalClient` (Idempotency-Key). `JournalPosting` outbox + dispatcher with backoff. Wired
issue / receipt / write-off mappings (per-InvoiceType issue template). Exit met: events post to a
stubbed GL; retry + terminal-fail tested. Reconciliation **deferred to a lightweight tie-out
report** (§10) — outbox + FAILED-row monitoring is the money-loss control. Credit-note mapping with
its phase.

**Phase 4 — Dunning & reporting.** ✅ Reminder framework (channel SPI: email/SMS pluggable, no
provider baked in), DunningRun. Bulk receivable CSV upload (per-row validation, fail-loud error
report — ported from the legacy billing app). Statements, aging/recap reports (report dimensions injected
by adapter). Exit: dunning batch + CSV upload + report queries tested.

**Phase 5 — Admin UI.** ✅ Thymeleaf + Tailwind (v4 CLI) + Spring Security form login. Debtor &
invoice-type CRUD; invoice browse/detail/issue with write-off, open-charge, credit-note actions
(rendered only when the backend would accept them — `InvoiceResponse.collectible()`); Tagihan VA
(charge list) + Aplikasi Kas views; dunning run UI; bulk CSV upload; statement/recap/aging reports;
audit log (`AuditService`); operator management. Bootstrap admin from config. Exit met: Playwright
smoke tests (login, dashboard, create flows, all screens render) green. (Journal-posting browse
existed here pre-2026-07-04; removed with the GL integration.)

## 8. Adapter/config layer (explicitly out of the engine)

The engine is client-agnostic. Deployment adapters own:

- **Receivable origination feeds** — each source system (SPMB registration, academic, sales) has
  its own Kafka-to-REST adapter that consumes domain events and calls `POST /api/invoices` or the
  bulk-upload endpoint. No engine-side feed SPI; the REST API is the ingestion surface.
- **Fee/term policy & running-number schemes** — scheme values via config (`ar.invoice.*`).
- **VA-number allocation** — `InvoiceTypeVaCode` mapping table (engine-managed) + built-in
  `EncodedVaNumberSupplier` driven by config; override via `VaNumberSupplier` SPI for exotic
  schemes. See §10.
- **Notification delivery** — **decided: reuse the deployment's existing notification hub as-is;
  templates stay centralized in the hub, AR only publishes facts.** AR gets a transactional
  notification-outbox → the hub's topic, triggered on bill/VA-ready, payment-received, and
  overdue (the `NotificationSender` SPI is satisfied by a `KafkaNotificationSender` that enqueues
  rather than rendering). The hub's variable names are deployment configuration (`TEMPLATE`
  mapping). Design in **`docs/NOTIFICATION-INTEGRATION.md`**.
  Supersedes the earlier "adapter renders templates via its own provider" sketch.
- **Report grouping dimensions** — the engine exposes per-debtor, per-type, per-period data; the
  adapter's reporting layer joins against its own academic/org structure for dimensional slicing.
  No engine tag/dimension schema.

No client names, credentials, endpoints, or sample data in this repo.

## 9. Feature inventory (full parity is the release target)

All areas are in scope; the §7 phases sequence them. `[engine]` = this repo; `[adapter]` =
per-deployment layer.

A. **Master data** — debtor CRUD `[engine, done]`; invoice types + GL revenue-account code `[engine, done]`;
running-number schemes `[engine, done]` (scheme values `[adapter]`); GL account config `[engine, done]`.
B. **Receivable lifecycle** — invoice + line items w/ sum check + immutability `[engine, done]`; single vs
installment schedules `[engine, done]`; OPEN→PARTIALLY_PAID→PAID, OVERDUE sweep, WRITTEN_OFF `[engine, done]`;
credit notes/adjustments `[engine, done]`; aging buckets `[engine, done]`; bulk CSV upload `[engine, done]`; bulk
void of unpaid `[engine, deferred]`.
C. **Collection** — open CLOSED charge per invoice/installment `[engine, done]` (VA scheme `[engine, pending]`);
HMAC webhook receiver `[engine, done]`; cash application + partial/over/under handling `[engine, done]`; payment
reversal `[engine, done]`; charge cancellation on write-off via outbound outbox `[engine, done]`.
D. **GL posting** — issue/receipt/write-off/adjustment mapping `[engine, done]`; outbox + retry `[engine, done]`;
balancing owned by GL template `[engine, done]`; subledger↔control-account tie-out report `[engine, deferred]`.
E. **Dunning** — reminder framework `[engine, done]`; channel SPI (email/SMS) `[engine, done]`;
notification delivery via a Kafka notification hub `[engine, done]` (outbox → hub topic, variable names
mapped by deployment config, see `docs/NOTIFICATION-INTEGRATION.md`); templates `[hub]`.
F. **Reporting** — debtor statement `[engine, done]`; aging report `[engine, done]` (grouping dims `[adapter]`);
recap by type/period/cost-code `[engine, done]`.
G. **Admin UI** — debtor/invoice mgmt, receivable browse, aging dashboard, recon view, audit log
`[engine, done]`.
H. **Cross-cutting** — fail-loud config, secret encryption, OAuth2 (self-contained) + form-login UI
`[engine, done]`.

## 10. Decisions & open questions

Decided:
- **Source of truth is split, deliberately** — AR owns the *receivable* (is the debt owed, how much,
  is it forgiven); payment-gateway owns *collectability* (is there a live VA, which generation, can
  it be paid right now). Neither can subsume the other: only the gateway sees every consumer's
  charges, so only it can enforce one ACTIVE VA per escrow+number; and the gateway is built never to
  decide whether money is owed, which is what soft expiry means. AR's `charge` table is therefore a
  derived mirror fed by gateway events, and drift detection compares the two and **reports** —
  repairs go through whichever service owns the fact being changed. Adopted 2026-08-18 after both
  directions had leaked in production: invoices written off in AR whose gateway charges kept
  collecting, and charges the gateway had cancelled that AR still billed as collectible. Two lossy one-way pipes with no reconciliation was the root cause of both.
- **GL posting via templates** — post `POST /api/transactions` with a `templateId` (not raw
  journal-entry); the template owns legs/accounts/balance. **One template per `InvoiceType`**
  (`glIssueTemplateId`) for issue; global template ids for receipt/write-off. No account resolution
  in AR (supersedes the earlier code→UUID resolver) (§4).
- **GL auth** — OAuth2 `client_credentials` service token (depends on **balaka#28**) (§4).
- **GL idempotency** — AR sends `journal_posting` id as `Idempotency-Key`; GL returns the original
  on repeat (depends on **balaka#29**) (§4).
- **Installment charges** — **one CLOSED charge per installment**, `consumerReference` = installment
  id (§5).
- **Reconciliation** — no `ReconciliationRun` engine; the outbox guarantee + FAILED-row monitoring
  is the money-loss control. A lightweight on-demand AR-total ↔ GL-control tie-out report is added
  later, once a GL account-balance endpoint exists (defense-in-depth for period close).
- **Charge cancellation on write-off** — outbox-backed (own `charge_cancellation` table +
  dispatcher with retry/backoff, mirroring the GL outbox); marks the local charge CANCELLED on
  success. Reliable eventual cancellation, never a synchronous best-effort call in the write-off path.
- **Release target** — full feature parity (all of §9), phased per §7.
- **Auth** — OAuth2 self-contained (own authz + resource server) + form-login UI (§2).
- **Build/tooling** — Maven, Lombok+records, RestClient (§2).
- **Currency** — single-currency (IDR) in logic for v1, but a `currency` column on monetary
  entities (default IDR) so multi-currency is a later addition, not a schema rewrite. No FX /
  gain-loss logic now.
- **Licensing** — all-rights-reserved/proprietary placeholder header + `LICENSE` during
  development; final license settled before release (README's stance unchanged).
- **VA number encoding** — built-in `EncodedVaNumberSupplier` (engine, pending):
  `prefix + leftPad(invoiceTypeVaCode, N) + leftPad(debtorCode, M)` where N is
  `ar.gateway.va-invoice-type-digits` (1 or 2) and M fills the remaining digits to
  `ar.gateway.va-digit-length`. Invoice type → numeric code stored in `invoice_type_va_code`
  mapping table (engine-managed CRUD). `VaAllocationContext(escrowCode, consumerReference,
  debtorCode, invoiceTypeCode)` passed from `CollectionService` to the supplier. SPI
  (`VaNumberSupplier`) kept as override escape hatch with `@ConditionalOnMissingBean`.
- **VA number reuse** — VA numbers are reusable once inactive (PAID/CANCELLED/EXPIRED).
  Uniqueness enforced only among ACTIVE VAs. Requires payment-gateway change: drop
  `uq_va_escrow_number` table constraint; add partial unique index
  `uq_va_escrow_number_active on virtual_account (id_escrow_account, va_number) where status = 'ACTIVE'`;
  update `VirtualAccountRepository` and `ChargeService` duplicate check accordingly.
- **Receivable origination feeds** — engine is client-agnostic; `POST /api/invoices` and bulk
  CSV are the ingestion surface. Each source system (SPMB, academic) has its own Kafka-to-REST
  adapter outside the engine. No feed SPI in the engine.
- **Report grouping dimensions** — engine exposes per-debtor/per-type/per-period data; the
  adapter's reporting layer handles dimensional slicing against its own org structure. No
  tag/dimension schema in the engine.
- **Bulk void of unpaid** — deferred. Write-off is app-to-app; one-by-one via the API is
  operationally acceptable.

Open: none.

External dependencies (GL-side, AR built to the agreed contract + stub until delivered):
- **balaka#28** — OAuth2 `client_credentials` grant for unattended posting.
- **balaka#29** — `Idempotency-Key` on `POST /api/transactions`.

External dependencies (gateway-side, pending):
- **payment-gateway** — partial unique index on `virtual_account (id_escrow_account, va_number)
  where status = 'ACTIVE'`; `ChargeService` duplicate-VA check updated to ACTIVE-only.
