-- The debtor ledger and the dashboard used to read cash applications with findAll() and filter in
-- memory. At production volume (tens of thousands of cash applications) that exhausted a 512 MB heap
-- and took the whole application down, which in turn made the bridge's invoice POST fail and dropped
-- a live bill. Those reads are now SQL that selects only the rows it needs, joining
-- cash_application_line to its target — but the only index on that table was on id_cash_application,
-- so every such join fell back to a sequential scan.
--
-- One target column is always null (chk_cash_line_one_target enforces exactly one), so both indexes
-- are partial: they carry only the rows that can match, and stay small.

create index if not exists idx_cash_line_invoice on cash_application_line (id_invoice)
    where id_invoice is not null;

create index if not exists idx_cash_line_installment on cash_application_line (id_installment)
    where id_installment is not null;

-- IF NOT EXISTS so these can be created ahead of the deploy to measure the gain on live data,
-- without the migration then failing on rollout.

-- Received-at drives the dashboard's month window and the ledger's trailing-12-month stats.
create index if not exists idx_cash_application_received_at on cash_application (received_at);
