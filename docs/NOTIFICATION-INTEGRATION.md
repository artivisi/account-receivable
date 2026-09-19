# account-receivable — Notification Integration

AR owns the **trigger and the variables** of a payer notification. Templates, rendering, channel
routing, provider credentials and delivery belong to the deployment's notification hub. AR publishes
facts; it never renders a message.

## Flow

```
CollectionService / dunning (business transaction)
        │  NotificationService.enqueue → notification_outbox (PENDING)
        ▼
NotificationDispatcher  @Scheduled(ar.notification.poll-interval-ms)
        │  NotificationPublisher.publish → JSON on ar.notification.topic
        │  success → SENT ; failure → attempts++, exponential backoff, terminal FAILED (logged + audited)
        ▼
notification hub → email / SMS workers
```

The outbox is written in the business transaction, so a crash between commit and publish loses
nothing. Reliability ends at "published to the topic": a hub typically neither dedupes nor reports
delivery back.

## Triggers

| Event | Where | Hub config id |
|---|---|---|
| Bill issued (VA ready) | `CollectionService`, when a charge is newly opened — the VA number exists only then | `ar.notification.issued-config` |
| Payment received | `CollectionService.applyPayment`, APPLIED branch only — not on replay, not when parked | `ar.notification.payment-config` |
| Overdue | dunning, through the `KafkaNotificationSender` beans (EMAIL always; SMS when `sms-enabled`) | `ar.notification.dunning-config` |

`sms-enabled=false` drops the mobile recipient; the phone still travels in the variables, because the
SMS gate decides recipients, not what an email may print. A debtor with no email and no (gated)
mobile is skipped and audited as `NOTIFICATION_SKIPPED`.

## Message

A message is a config id, the recipients, and the variables (all strings). The hub picks channels
by recipient presence and by which templates the config has; there is no channel field.

AR's own variable names per event are declared in `NotificationPayloadMapper`
(`BILL_ISSUED_VARIABLES`, `PAYMENT_RECEIVED_VARIABLES`, `DUNNING_VARIABLES`).

## Matching the hub's names — `ar.notification.payload-mapper`

A hub validates a message against the variables its template declares and refuses it, in its own
log, when a name does not match. The names are therefore a deployment contract, and choosing them is
required configuration:

- **`PASSTHROUGH`** — AR's own variable names, and envelope fields `configId`, `email`, `mobile`,
  `data`. For a hub built against AR.
- **`TEMPLATE`** — names from `ar.notification.template`, for a hub whose templates predate AR:
  - `envelope` — the hub's field names for config id, email, mobile and data;
  - `bill-issued`, `payment-received`, `dunning` — `hubVariable: template`, where a template is
    literal text with `{variable}` references to AR's variables for that event.

  Deployment text (bank name, contact details) is literal text in the template, typically as
  `${ENV_VAR}` placeholders. A key whose template references an absent or blank variable is left out
  rather than sent empty. Startup fails on a reference AR does not produce for the event, a blank
  value, or an unresolved `${...}`.

The template lives in a deployment file imported with `SPRING_CONFIG_IMPORT=file:<path>`. A complete
example for a hub with Indonesian variable names is
`src/test/resources/notification/template-example.yml`; `TemplateNotificationPayloadMapperTest`
asserts its output key for key.
