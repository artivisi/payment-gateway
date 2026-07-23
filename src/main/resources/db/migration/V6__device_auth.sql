-- OAuth 2.0 Device Authorization Grant (RFC 8628) for CLI/agent access to the API.
--
-- Why not a shared client id/secret: a device token is issued TO a named operator, carries that
-- operator's permissions (never more), is individually revocable, and every call it makes is
-- attributable in the audit log. A shared secret in a script is none of those things.

create table device_code (
    id               varchar(36)  not null primary key,
    device_code      varchar(128) not null unique,   -- long random, polled by the CLI
    user_code        varchar(16)  not null unique,   -- short, typed by a human: WDJB-MJHT
    client_id        varchar(64)  not null,          -- e.g. claude-code
    device_name      varchar(100),                   -- what the operator is authorising
    status           varchar(20)  not null,          -- PENDING | AUTHORIZED | DENIED | EXPIRED
    id_operator      varchar(36) references operator (id),
    created_at       timestamptz  not null default now(),
    expires_at       timestamptz  not null,
    authorized_at    timestamptz
);

create table device_token (
    id           varchar(36)  not null primary key,
    id_operator  varchar(36)  not null references operator (id),
    token_hash   varchar(255) not null,   -- bcrypt; the plaintext is shown once and never stored
    device_name  varchar(100) not null,
    client_id    varchar(64)  not null,
    created_at   timestamptz  not null default now(),
    expires_at   timestamptz,             -- null = no expiry; default is 30 days
    last_used_at timestamptz,
    last_used_ip varchar(45),
    revoked_at   timestamptz,
    revoked_by   varchar(100)
);

create index idx_device_token_operator on device_token (id_operator);
create index idx_device_code_user_code on device_code (user_code);

-- Managing your own device tokens is part of being an operator, so no new permission is introduced;
-- the token simply inherits the authorising operator's role permissions at issue time.
