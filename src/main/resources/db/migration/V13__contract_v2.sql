-- The v2 async contract: commands in, events out.
--
-- contract_event_outbox: every event, written in the transaction of the change it announces, then
-- published by a poller. contract_command: processed commands by idempotency key, with the result
-- event stored so a repeat republishes the first outcome verbatim. contract_failure: commands that
-- failed past validation, kept so the partition moves on and a person replays them.

create table contract_event_outbox (
    id              varchar(36)  primary key,
    topic           varchar(128) not null,
    message_key     varchar(200) not null,   -- debtor code, or the correlation id when none is known
    event_type      varchar(64)  not null,
    payload         text         not null,
    status          varchar(20)  not null,   -- PENDING | SENT | FAILED
    attempts        integer      not null,
    max_attempts    integer      not null,
    next_attempt_at timestamptz  not null,
    last_error      varchar(512),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null
);
create index idx_contract_outbox_due on contract_event_outbox (status, next_attempt_at);
create index idx_contract_outbox_key on contract_event_outbox (message_key, created_at);

create table contract_command (
    id              varchar(36)  primary key,
    idempotency_key varchar(200) not null,
    command_type    varchar(64)  not null,
    message_key     varchar(200) not null,   -- debtor code, or the correlation id when none is known
    result_topic    varchar(128),
    result_payload  text,
    received_at     timestamptz  not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    constraint uq_contract_command_key unique (idempotency_key)
);

create table contract_failure (
    id          varchar(36)  primary key,
    topic       varchar(128) not null,
    message_key varchar(200),
    payload     text         not null,
    error       text         not null,
    resolved    boolean      not null default false,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null
);
