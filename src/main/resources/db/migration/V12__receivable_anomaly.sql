-- Somewhere to write down that a receivable is wrong, when only an outside check could know it.
--
-- WHAT BELONGS HERE, AND WHAT DOES NOT
--
-- The review queue already classifies what AR can work out by looking at itself: a charge
-- superseded by a live one on the same reused VA, a sibling bill on that number already paid, a
-- bill the source system withdrew. Those are recomputed from current state on every read, so they
-- cannot go stale, and materialising them into a row here would guarantee they eventually disagree
-- with the ledger they describe. Do not copy them in.
--
-- This table is for the opposite case: a fact AR cannot see from its own tables, asserted by
-- something that looked elsewhere. The bank's transaction report omits a payment we hold. A
-- reconciliation run says a payment was notified but never settled. An operator working two
-- systems side by side finds a divergence the mirror never reported. Until now each of those died
-- in whatever log its checker happened to write to — the same failure V8 was written to fix for
-- one specific case, generalised.
--
-- CATEGORY IS DELIBERATELY NOT AN ENUM YET
--
-- We do not know the stable set. It is meant to be discovered by curating the anomalies actually
-- on the books, not decided in advance and then bent to fit. A check constraint can be added once
-- the same handful of values has survived a few months of real use; guessing now would either
-- reject a real finding or leave a column of near-synonyms nobody can group by.
--
-- NOTHING HERE ACTS
--
-- A row is an observation awaiting a person, never an instruction. It does not change
-- payment_status, does not touch outstanding, and does not cancel a charge. An ops script once
-- cancelled hundreds of live receivables on a rule that looked every bit as safe as "this one is
-- obviously a duplicate"; the lesson is that the writing and the deciding stay separate.

create table receivable_anomaly (
    id              varchar(36)  not null,
    id_invoice      varchar(36)  not null,
    -- Set when the finding is about one charge rather than the receivable as a whole; a reused VA
    -- number carries several charges over time and the distinction matters when reading back.
    id_charge       varchar(36),
    category        varchar(64)  not null,
    -- Who asserts it: RECON, BRIDGE, OPS, FINANCE. Kept because "the reconciliation run found this"
    -- and "someone typed this in" deserve different amounts of trust from the person resolving it.
    source          varchar(32)  not null,
    detail          varchar(1000) not null,
    -- The identifier a reviewer can chase in the system that raised it: a journal number, an FT
    -- number, a bridge_failure id. Also what makes re-raising idempotent.
    evidence_ref    varchar(200),
    -- created_at IS the moment it was raised, and re-reporting must never reset it: how long a
    -- finding has been open is the reviewer's main signal about it.
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    raised_by       varchar(100) not null,
    resolved_at     timestamptz,
    resolved_by     varchar(100),
    resolution      varchar(32),
    resolution_note varchar(1000),
    constraint pk_receivable_anomaly primary key (id),
    constraint fk_receivable_anomaly_invoice foreign key (id_invoice) references invoice (id),
    constraint fk_receivable_anomaly_charge foreign key (id_charge) references charge (id),
    -- Resolved means decided, and a decision carries who and what. Half-filled resolutions are how
    -- a queue stops being trustworthy.
    constraint ck_receivable_anomaly_resolved check (
        (resolved_at is null and resolved_by is null and resolution is null)
        or (resolved_at is not null and resolved_by is not null and resolution is not null))
);

-- A check that runs daily must be able to re-report an unresolved finding without stacking
-- duplicates or resetting created_at, which is what tells a reviewer how long it has been open.
-- coalesce because a null evidence_ref is not distinct here: same invoice, same category, no
-- external reference means the same finding.
create unique index uq_receivable_anomaly_finding
    on receivable_anomaly (id_invoice, category, coalesce(evidence_ref, ''));

-- The screen reads the open ones.
create index idx_receivable_anomaly_open
    on receivable_anomaly (created_at desc) where resolved_at is null;
