-- A payment we accepted that the originating system never booked.
--
-- AR forwards received payments back to the system that issued the bill, and until now assumed they
-- landed. They do not always. Payments taken after a bank cutover were consumed by the upstream
-- billing application and discarded: it logged the message, threw "Invoice # ... tidak memiliki va" because its own VA row was still
-- pending, caught the exception, acknowledged the Kafka record, and moved on. The students had paid
-- in full and were still shown as owing.
--
-- Nothing in this ledger was wrong, which is exactly why it could not be seen from here: the
-- receivable is PAID, the cash is applied, our books balance. The divergence is only visible by
-- comparing against the other system, so it has to be recorded when an external check finds it —
-- there is no state in AR that implies it.
--
-- Kept on cash_application rather than the invoice because the missing thing is a PAYMENT, not a
-- debt, and the gateway payment reference is what the two systems can be compared on. That reference
-- is already unique here (cash application is idempotent on it), so a check can flag by reference
-- without knowing our ids.
--
-- CLEARED, not deleted. "Someone recorded it upstream" is a fact worth keeping next to "it was
-- missing" — a divergence that keeps coming back for the same debtor is a different problem from one
-- that happened once, and deleting the row hides the pattern.

alter table cash_application add column upstream_missing_at   timestamptz;
alter table cash_application add column upstream_missing_note varchar(255);
alter table cash_application add column upstream_cleared_at   timestamptz;
alter table cash_application add column upstream_cleared_note varchar(255);

-- The worklist reads "flagged and not yet cleared", so index only those.
create index idx_cash_application_upstream_open
    on cash_application (upstream_missing_at)
    where upstream_missing_at is not null and upstream_cleared_at is null;
