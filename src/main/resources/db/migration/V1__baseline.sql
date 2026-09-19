-- Baseline schema for the Accounts Receivable subledger (consolidated).
--
-- Registries -> receivables (+ lines, installment schedules) -> collection (gateway charge mirror,
-- cash application) -> dunning + bulk-upload tracking. GL posting is out of scope:
-- journals are derived from the source tables by org-specific bridges.
--
-- Conventions: ids varchar(36) (UUID), money numeric(19,2), timestamps timestamptz, enums varchar.
-- Issued invoice amounts are immutable (corrections via credit note / write-off). Overdue is
-- derived (due_date < today AND outstanding > 0), never stored.

-- ---------------------------------------------------------------------------- registries

create table debtor (
    id         varchar(36)  primary key,
    code       varchar(64)  not null unique,
    name       varchar(255) not null,
    email      varchar(255),
    phone      varchar(32),
    status     varchar(20)  not null,   -- ACTIVE | INACTIVE
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);

create table invoice_type (
    id         varchar(36)  primary key,
    code       varchar(64)  not null unique,
    name       varchar(255) not null,
    active     boolean      not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);

-- Maps each invoice type to a 1–2 digit numeric code used in VA number encoding.
create table invoice_type_va_code (
    id              varchar(36) primary key,
    id_invoice_type varchar(36) not null unique references invoice_type (id),
    va_code         varchar(2)  not null unique,
    created_at      timestamptz,
    created_by      varchar(255),
    updated_at      timestamptz,
    updated_by      varchar(255)
);

create table running_number (
    id          varchar(36) primary key,
    prefix      varchar(64) not null unique,
    last_number bigint      not null,
    created_at  timestamptz not null,
    updated_at  timestamptz not null
);

-- ---------------------------------------------------------------------------- receivables

create table invoice (
    id              varchar(36)   primary key,
    invoice_number  varchar(64)   not null unique,
    id_debtor       varchar(36)   not null references debtor (id),
    id_invoice_type varchar(36)   not null references invoice_type (id),
    issue_date      date          not null,
    due_date        date          not null,
    currency        varchar(3)    not null,
    amount          numeric(19,2) not null,
    outstanding     numeric(19,2) not null,
    payment_status  varchar(20)   not null,   -- OPEN | PARTIALLY_PAID | PAID | WRITTEN_OFF
    is_installment  boolean       not null,
    description     varchar(512),
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null
);
create index idx_invoice_debtor on invoice (id_debtor);
create index idx_invoice_payment_status on invoice (payment_status);
create index idx_invoice_due_date on invoice (due_date);

create table invoice_line (
    id          varchar(36)   primary key,
    id_invoice  varchar(36)   not null references invoice (id),
    line_no     integer       not null,
    description varchar(512)  not null,
    quantity    numeric(19,4) not null,
    unit_amount numeric(19,2) not null,
    line_amount numeric(19,2) not null,
    created_at  timestamptz   not null,
    updated_at  timestamptz   not null,
    constraint uq_invoice_line_no unique (id_invoice, line_no)
);

create table payment_schedule (
    id                varchar(36) primary key,
    id_invoice        varchar(36) not null unique references invoice (id),
    installment_count integer     not null,
    created_at        timestamptz not null,
    updated_at        timestamptz not null
);

create table installment (
    id             varchar(36)   primary key,
    id_schedule    varchar(36)   not null references payment_schedule (id),
    sequence       integer       not null,
    due_date       date          not null,
    amount         numeric(19,2) not null,
    outstanding    numeric(19,2) not null,
    payment_status varchar(20)   not null,   -- OPEN | PARTIALLY_PAID | PAID | WRITTEN_OFF
    created_at     timestamptz   not null,
    updated_at     timestamptz   not null,
    constraint uq_installment_sequence unique (id_schedule, sequence)
);
create index idx_installment_schedule on installment (id_schedule);

-- Corrections that reduce an issued invoice's outstanding (issued amounts are immutable; never
-- edited). Each carries its own reversing journal (Dr Revenue · Cr A/R control).
create table credit_note (
    id                 varchar(36)   primary key,
    credit_note_number varchar(64)   not null unique,
    id_invoice         varchar(36)   not null references invoice (id),
    id_debtor          varchar(36)   not null references debtor (id),
    issue_date         date          not null,
    currency           varchar(3)    not null,
    amount             numeric(19,2) not null,
    reason             varchar(512),
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null
);
create index idx_credit_note_invoice on credit_note (id_invoice);

-- ---------------------------------------------------------------------------- collection

-- A charge / cash-application line targets EITHER an invoice (single payment) OR an installment.
create table charge (
    id                 varchar(36)   primary key,
    gateway_charge_id  varchar(64)   not null unique,
    consumer_reference varchar(128)  not null unique,
    charge_type        varchar(20)   not null,   -- CLOSED | INSTALLMENT | OPEN (v1: CLOSED)
    id_invoice         varchar(36)   references invoice (id),
    id_installment     varchar(36)   references installment (id),
    amount             numeric(19,2) not null,
    currency           varchar(3)    not null,
    status             varchar(20)   not null,   -- ACTIVE | PARTIALLY_PAID | PAID | EXPIRED | CANCELLED
    cumulative_paid    numeric(19,2) not null,
    escrow_code        varchar(64)   not null,
    va_number          varchar(64)   not null,
    expires_at         timestamptz,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    constraint chk_charge_one_target check ((id_invoice is not null) <> (id_installment is not null))
);
create index idx_charge_invoice on charge (id_invoice);
create index idx_charge_installment on charge (id_installment);

-- Cash application is idempotent on the gateway payment reference.
create table cash_application (
    id                        varchar(36)   primary key,
    gateway_payment_reference varchar(128)  not null unique,
    id_charge                 varchar(36)   references charge (id),
    amount                    numeric(19,2) not null,
    currency                  varchar(3)    not null,
    received_at               timestamptz   not null,
    status                    varchar(20)   not null,   -- UNAPPLIED | PARTIALLY_APPLIED | APPLIED | REVERSED
    note                      varchar(512),
    created_at                timestamptz   not null,
    updated_at                timestamptz   not null
);

create table cash_application_line (
    id                  varchar(36)   primary key,
    id_cash_application varchar(36)   not null references cash_application (id),
    id_invoice          varchar(36)   references invoice (id),
    id_installment      varchar(36)   references installment (id),
    allocated_amount    numeric(19,2) not null,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    constraint chk_cash_line_one_target check ((id_invoice is not null) <> (id_installment is not null))
);
create index idx_cash_line_application on cash_application_line (id_cash_application);

-- Outbound outbox for cancelling gateway charges when a receivable is written off.
-- Enqueued in the write-off transaction; dispatched with retry so the payer cannot pay
-- a written-off charge.
create table charge_cancellation (
    id                 varchar(36) primary key,
    id_charge          varchar(36) not null references charge (id),
    gateway_charge_id  varchar(64) not null,
    status             varchar(20) not null,   -- PENDING | DONE | FAILED
    attempts           integer     not null,
    max_attempts       integer     not null,
    next_attempt_at    timestamptz not null,
    last_response_code integer,
    last_error         varchar(512),
    created_at         timestamptz not null,
    updated_at         timestamptz not null
);
create index idx_charge_cancellation_due on charge_cancellation (status, next_attempt_at);

-- ---------------------------------------------------------------------------- dunning

create table dunning_run (
    id               varchar(36) primary key,
    run_date         date        not null,
    channel          varchar(20) not null,   -- EMAIL | SMS
    min_days_overdue integer     not null,
    status           varchar(20) not null,   -- RUNNING | DONE
    total_reminders  integer     not null,
    sent_count       integer     not null,
    error_count      integer     not null,
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create table dunning_reminder (
    id             varchar(36)  primary key,
    id_dunning_run varchar(36)  not null references dunning_run (id),
    id_invoice     varchar(36)  not null references invoice (id),
    channel        varchar(20)  not null,
    recipient      varchar(255),
    status         varchar(20)  not null,   -- PENDING | SENT | ERROR
    sent_at        timestamptz,
    error          varchar(512),
    created_at     timestamptz  not null,
    updated_at     timestamptz  not null
);
create index idx_dunning_reminder_run on dunning_reminder (id_dunning_run);

-- ---------------------------------------------------------------------------- bulk upload

create table bulk_upload_batch (
    id            varchar(36)  primary key,
    filename      varchar(255) not null,
    uploaded_at   timestamptz  not null,
    total_rows    integer      not null,
    success_count integer      not null,
    error_count   integer      not null,
    status        varchar(30)  not null,   -- COMPLETED | COMPLETED_WITH_ERRORS
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null
);

create table bulk_upload_error (
    id         varchar(36)  primary key,
    id_batch   varchar(36)  not null references bulk_upload_batch (id),
    line_no    integer      not null,
    message    varchar(512) not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);
create index idx_bulk_upload_error_batch on bulk_upload_error (id_batch);

-- ---------------------------------------------------------------------------- admin users

-- Admin UI users (form login). Single role per user for v1.
create table app_user (
    id                   varchar(36)  primary key,
    username             varchar(64)  not null unique,
    password_hash        varchar(255) not null,
    display_name         varchar(255) not null,
    role                 varchar(20)  not null,   -- ADMIN | OPERATOR
    enabled              boolean      not null,
    must_change_password boolean      not null,   -- set on creation and admin reset
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null
);

-- ---------------------------------------------------------------------------- audit

-- Audit log: who did what. Recorded in the same transaction as the audited action.
create table audit_event (
    id          varchar(36)  primary key,
    event_type  varchar(64)  not null,
    entity_type varchar(64)  not null,
    entity_id   varchar(64),
    actor       varchar(64)  not null,
    detail      varchar(512),
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null
);
create index idx_audit_event_created on audit_event (created_at);
