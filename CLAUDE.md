# CLAUDE.md — account-receivable

Generic Accounts Receivable subledger. Read this before changing code.

## What this is

An operational receivables subledger: tracks what is owed and by whom, and collects via a payment
gateway. It is **not** a GL, **not** a payment gateway, **not** a billing/academic system — it is
the operational layer in front of whatever book of record the organization uses.

Sibling product: [`../payment-gateway`](../payment-gateway) (the collection rail). AR is a consumer
of it. Mirror its conventions (stack, fail-loud config, governance, test approach).

## Stack

Spring Boot 4 · Java 25 · PostgreSQL 18 + Flyway · Thymeleaf + HTMX + Tailwind (admin UI). Namespace
`com.artivisi.accountreceivable`. Tests: RestAssured + Testcontainers + Playwright, functional-first.

## Domain model

- `Debtor` — the party that owes (customer/payer). Master data: code, name, contacts. No academic
  fields in the engine.
- `InvoiceType` — receivable category (code + name; reporting/routing dimension).
- `Invoice` — the receivable: debtor + type + `issueDate` + `dueDate` + `amount` + `outstanding` +
  `paymentStatus` (`OPEN` | `PARTIALLY_PAID` | `PAID` | `WRITTEN_OFF`). Has `InvoiceLine`s.
  **Overdue is derived** (`dueDate < today AND outstanding > 0`), not a stored status — an invoice
  can be partially-paid *and* overdue at once. Write-off is full-invoice only (a `paymentStatus`
  flip), not a partial-amount document.
- `InvoiceLine` — line items (description, qty, unit amount). Sum = invoice amount.
- `PaymentSchedule` / `Installment` — for installment receivables: ordered installments with
  due dates and amounts. One invoice → many installments.
- `CashApplication` — applies a received payment to one or more invoices/installments. Idempotent
  on the gateway payment reference.
- `DunningRun`, `ReconciliationRun`.

Amounts on an issued invoice are immutable; corrections are credit notes / adjustments, never edits.

## Subledger ↔ GL boundary

AR owns per-debtor receivable **detail**; the organization's GL owns the **A/R control account**.
**AR does not post to any GL.** Every GL is organization-specific (SaaS, desktop, spreadsheets,
homemade), so journal export is the job of a thin per-organization batch bridge OUTSIDE this
product. AR's source tables are the feed: they are immutable or append-only (invoice issues,
cash applications, credit notes, reversal rows), so a bridge can derive any journal representation
incrementally by timestamp, at the summarization level the target GL wants. The bridge owns its
own posted-state; reconcile AR outstanding total ↔ GL A/R control balance there.

## Collection integration (consume the payment-gateway)

AR is a **Consumer** of the gateway (it holds a client id/secret + a webhook endpoint).

- To collect: open a `Charge` via the gateway Consumer API — one **CLOSED** charge per invoice
  (single-payment) or one **CLOSED** charge per installment, so the webhook maps back to its target
  by `consumerReference` (= the invoice/installment id) with no amount-guessing.
- **AR never computes VA numbers** (decided 2026-09-19, issue #1 — until it lands,
  `EncodedVaNumberSupplier` still does). Prefix, digit length, segment widths and the interbank
  prefix are bank facts that live on the gateway's escrow; copying them into AR config is what
  made AR single-bank. AR sends bank-agnostic inputs — `payerKey` = debtor code, `slotKey` = the
  invoice type's `vaCode` (the `invoice_type_va_code` mapping stays here, because the gateway must
  not learn AR's vocabulary) — and stores the `vaNumber` + `interbankVaNumber` the gateway returns.
  Anything that needs "the number" before the gateway call keys on debtor + `vaCode` instead.
- Gateway charge types `INSTALLMENT` (accumulates to a target) / `OPEN` (deposits) are
  reserved for later deposit-style flows; v1 uses `CLOSED` only. Pay-via-any-bank is the gateway's
  concern.
- On the gateway's payment webhook → **cash application**: idempotent on the gateway payment
  reference; allocate to the target invoice/installment; update `outstanding` + `status`.
  Partial/over/under-payment handled explicitly (fail loud on ambiguous). Idempotency is
  concurrency-safe: a duplicate delivery that races the check-then-insert and trips the unique
  reference constraint is caught and returns the existing application (200), not 500 — the payment
  still applies exactly once (the constraint guarantees it).
- **VA-number reuse / supersede.** A VA number points to at most one payable bill. If a new charge
  targets a number the gateway still holds active (409), AR treats the new bill as superseding the
  old: it cancels the prior still-collectible charge(s) on that number, then creates the new CLOSED
  charge. This supports upstreams that reuse one VA number across successive single bills (a legacy
  way of doing installments, with no installment model). AR's own installment flow uses real
  `INSTALLMENT` semantics (one charge, many payments) and does not reuse numbers, so the path is inert.
- `consumerReference` on the gateway `Charge` carries this app's invoice/installment id for matching.

## Governance

Generic ArtiVisi product. The **engine code** carries no client names, credentials, endpoints, or
sample data; neutral domain naming throughout. A deployment (e.g. a campus) is onboarded via a
**thin adapter/config layer** outside the engine core:

- where receivables originate (academic / registration / sales feeds)
- fee/term policy, running-number schemes
- notification templates + channels (e.g. an external email/SMS service)
- deployment-specific report dimensions (faculty/program → grouping tags)
- **branding** — logo + palette applied at runtime by shadowing `/img/logo.svg` + `/css/branding.css`
  from an external static location (`spring.web.resources.static-locations`, listed before
  `classpath:/static/`); the engine ships neutral defaults. See `docs/UI.md`.

If a requirement is deployment-specific, it belongs in that layer, not the engine core. This is the
mistake the predecessors made (billing coupled to bank rails / GL); do not repeat it.

**Fixtures and examples are invented, and the repository is public.** Test data, javadoc and
comment examples, migration comments and commit messages never carry a real debtor's name, phone,
id or VA number, a real bill number, an operator's bank-assigned prefix, or production figures.
Every leak this repository has had came through one of those — a phone column's example in a
migration, a captured hub payload used as a test fixture, a regression's real debtor code in
javadoc, reconciliation totals in commit messages. A third party's name in a fixture is PII
whatever the repository's visibility. An applied migration is immutable, comments included: editing
one needs a checksum repair on every deployment that has applied it.

**This repo doubles as a portfolio/showcase.** A real deployment's branding overlay is committed
under `deploy/<name>/` (e.g. `deploy/tazkia/` — Tazkia blue/orange + emblem) as an advertised demo.
Those `deploy/*` overlays are **intentional** and are NOT wired into the engine build (they apply via
the runtime static-locations shadow); the engine itself stays deployment-neutral. Do not remove them
as governance violations.

## Prior art to mine (read before building; all carry coupling to strip)

- A 2019 AR application (Java 8) — **cleanest AR domain model** (debtor / invoice / invoice_type /
  schedule / payment_schedule, with installment scheduling). Used as the schema blueprint.
- `~/workspace/produk/invoice-management` — invoicing with **line items** (`Invoice` +
  `InvoiceDetail`), `Customer` master, `InvoiceType`, running number. Bundled with VA/bank payment
  (strip that — it is the gateway's job now).
- A legacy production billing application — the source of the ported **features**: bulk
  receivable upload (CSV), email/SMS dunning, recap/aging reports. Its Kafka VA fan-out was dropped.
- `~/workspace/produk/aplikasi-akunting` — a GL (`Journal`, `JournalTemplate`, `Account`, chart of
  accounts), reference only. **AR no longer posts to any GL** (removed 2026-07; journal export is an
  external per-organization bridge — see Subledger ↔ GL boundary), so this is not an integration
  target. Its `StudentReceivables` stub is the kind of per-debtor detail AR now owns.
- `~/workspace/produk/payment-gateway` — the **collection rail** (sibling product). Consume its
  Consumer API + webhooks; mirror its stack, fail-loud config, Testcontainers/Playwright approach,
  and `@SnapSpec`-style traceability if a spec applies.

## Principles

- **Fail loud, no fallbacks.** Missing/invalid config errors explicitly; no default debtor, no
  silent default amounts.
- **Money is never lost.** Cash application is idempotent on the gateway payment reference; outbound
  side-effects (charge cancellation, notification publish) use a transactional outbox that retries,
  never drops.
- **Issued amounts are immutable.** Adjust via credit notes, not edits.
- **Never log secrets.**

## Build sequence

0. **Scaffold** — SB4/Java25, PostgreSQL+Flyway, debtor + invoice-type registries, fail-loud config.
1. **AR core** — debtor, invoice + lines, invoice types, installment schedules; receivable
   lifecycle (issue / partial / pay / overdue / write-off); aging.
2. **Collection** — gateway Consumer client: open Charge per invoice/installment; webhook receiver
   → idempotent cash application → status update.
3. **~~GL posting~~** — *removed 2026-07.* AR does not post to a GL; journal export is an external
   per-organization bridge (see Subledger ↔ GL boundary). Credit notes / adjustments remain in AR.
4. **Dunning + reporting** — reminder framework (channels pluggable), statements, aging/recap reports.
5. **Admin UI** — debtor/invoice management, receivable browse, aging dashboard, audit. Dark sidebar
   rail + light content; runtime deployment branding hook (see `docs/UI.md`).
