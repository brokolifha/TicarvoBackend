-- ADR-013 (KVKK art. 7): a deleted account is anonymized in place; rows that point at it (orders, audit) stay.
ALTER TABLE app_users ADD COLUMN deleted_at DATETIME(6) NULL;
