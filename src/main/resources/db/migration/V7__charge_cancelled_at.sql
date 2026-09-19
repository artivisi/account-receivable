-- When a charge stopped being collectible, as distinct from when its row was last written.
--
-- The write-off review queue needs "this receivable recently became unpayable". updated_at cannot
-- answer that: a multi-year billing history was imported in bulk, so every migrated charge carries a
-- recent updated_at and looks identical to a charge cancelled this morning. Status alone cannot answer
-- it either — thousands of open invoices have charges that are all dead, nearly all of them history
-- that was never collectible through the gateway in the first place.
--
-- So this column records the moment, and is set only where a cancellation actually happens. NULL
-- means "we do not know" — every migrated row — and the queue excludes NULL rather than guessing.
-- The queue therefore starts empty and fills only with cancellations observed from here on, which is
-- the honest boundary: the historical backlog is a finance write-off campaign (see the provision
-- report), not a daily routine, and mixing the two would bury the handful that need a decision under
-- eight years of uncollected history.
--
-- Deliberately not backfilled. Any value invented for the migrated rows would be a fiction that the
-- queue then treats as fact.

alter table charge add column cancelled_at timestamptz;

-- The queue reads: collectible invoice, no live charge, at least one charge cancelled since a cutoff.
create index idx_charge_cancelled_at on charge (cancelled_at) where cancelled_at is not null;
