-- When a charge was cancelled because a NEWER bill took its VA number, as distinct from every other
-- reason a charge is cancelled.
--
-- The legacy billing system reuses one VA number across successive bills of a debtor. AR handles the
-- gateway's 409 by cancelling the older charge (CollectionService.supersedeActiveChargesOnVa) so the
-- new bill can have the number. What nothing did was put the older receivable back once the newer
-- bill was paid and the number came free: AR kept it OPEN, the billing master kept it AKTIF, and the
-- gateway answered NOT_FOUND on its VA. Owed on paper, uncollectible in practice, silent on all three
-- sides.
--
-- cancelled_at (V7) cannot distinguish these: a write-off cancellation and a gateway-initiated
-- cancellation both stamp it, and neither may be reopened automatically — a written-off debt is
-- forgiven, and a cancellation we did not initiate is explicitly a decision for a human. Only a
-- supersession is a cancellation this system performed for its own bookkeeping reasons, against a
-- debt that was never in question. So only a supersession may be undone without asking.
--
-- Deliberately not backfilled, for the same reason as V7: the 46 rows above were superseded before
-- this column existed, and inventing the moment for them would let the automatic path act on a guess.
-- They are repaired by hand through POST /api/invoices/{id}/charge (which now opens a fresh
-- generation) after verify-open-without-va.sh has checked each one against the billing master.

alter table charge add column superseded_at timestamptz;

-- Read as: "this VA number just came free — is there a superseded receivable waiting for it?"
create index idx_charge_superseded_va on charge (va_number, superseded_at)
    where superseded_at is not null;
