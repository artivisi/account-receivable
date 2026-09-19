-- Two facts the review queue needs and the ledger could not previously hold: that the originating
-- system says a bill is gone, and that a person has already looked at it.
--
-- WITHDRAWN_AT — the source system reports this receivable retired.
--
-- AR mirrors an originating billing system (see invoice.source_bill_number), and that system can
-- retire a bill: replaced by a corrected one, or deleted outright. Until now the only way that fact
-- could reach us was a write-off call, which either succeeded or vanished. When it was refused —
-- because the invoice was already PAID, or CANCELLED — the signal died in the mirror's failure log,
-- a table nobody reviews. Such refusals sat there across deploys, holding receivables open against
-- bills the source system had already withdrawn, and nothing surfaced them.
--
-- So the retirement is now recorded as its own fact, separate from the decision it might justify.
-- It NEVER changes payment_status. Forgiving a debt is a judgement, and the rail reporting the
-- retirement is not always right: on 2026-08-18 it called a live UKT instalment retired while both
-- other rails held the same student's previous instalment paid in full. A flag invites review; an
-- automatic write-off would have erased a real receivable.
--
-- REVIEW_KEPT_AT — a person looked at this and decided to keep collecting.
--
-- Without it a queue is write-only: the rows a reviewer deliberately keeps come back every morning,
-- indistinguishable from the ones nobody has read yet, and the queue is abandoned within a week.
-- The note is required by the service for the same reason a write-off reason is: a judgement nobody
-- recorded cannot be reviewed later.

alter table invoice add column withdrawn_at       timestamptz;
alter table invoice add column withdrawn_reason   varchar(255);
alter table invoice add column review_kept_at     timestamptz;
alter table invoice add column review_note        varchar(255);

-- The queue reads "withdrawn upstream and still open", so index only the rows that can match.
create index idx_invoice_withdrawn_at on invoice (withdrawn_at) where withdrawn_at is not null;

-- Deliberately not backfilled, for the same reason charge.cancelled_at was not (V7): any value
-- invented for the bills already cancelled during the history import would be a fiction the
-- queue then presents to a reviewer as an observed event. The queue starts empty and fills from
-- here; the historical backlog remains a finance campaign driven by the provision report.
