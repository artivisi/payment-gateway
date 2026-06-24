-- Transactional outbox for consumer webhook notifications.
-- Rows are enqueued in the same transaction as the payment; a scheduled dispatcher
-- delivers them with retry/backoff.

create table webhook_delivery (
    id                 varchar(36)  primary key,
    id_consumer        varchar(36)  not null references consumer (id),
    id_charge          varchar(36)  not null references charge (id),
    id_payment         varchar(36)  references payment (id),
    event_type         varchar(40)  not null,   -- PAYMENT_RECEIVED | CHARGE_PAID
    target_url         varchar(512) not null,
    payload            text         not null,
    status             varchar(20)  not null,   -- PENDING | DELIVERED | FAILED
    attempts           integer      not null,
    max_attempts       integer      not null,
    next_attempt_at    timestamptz  not null,
    last_response_code integer,
    last_error         varchar(512),
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null
);
create index idx_webhook_due on webhook_delivery (status, next_attempt_at);
create index idx_webhook_charge on webhook_delivery (id_charge);
