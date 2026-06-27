-- Per-consumer manual delivery suspend (the ops kill-switch for a misbehaving endpoint).
alter table consumer add column webhook_suspended boolean not null default false;
