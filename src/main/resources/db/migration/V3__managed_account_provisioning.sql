-- Accounts are issued by administrators; legacy and bootstrap accounts have no known issuer.
-- Do not infer or backfill an issuer for existing rows.
ALTER TABLE users ADD COLUMN provisioned_by BIGINT;
ALTER TABLE users ADD COLUMN provisioned_at DATETIME(6);
ALTER TABLE users ADD CONSTRAINT fk_users_provisioner FOREIGN KEY(provisioned_by) REFERENCES users(id);
CREATE INDEX ix_users_provisioner ON users(provisioned_by);
