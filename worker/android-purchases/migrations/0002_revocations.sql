-- Terminal token revocations also protect tokens not yet present in grants.
-- Never store raw purchase proofs, orders, notification bodies, or sender JWTs.
CREATE TABLE revocations (
  token_hash TEXT PRIMARY KEY,
  revoked_at INTEGER NOT NULL,
  source TEXT NOT NULL CHECK(source IN ('verification','rtdn','voided'))
);
INSERT INTO revocations(token_hash,revoked_at,source)
  SELECT token_hash,verified_at,'verification' FROM grants WHERE state='revoked';

CREATE TABLE purchase_notifications (
  message_hash TEXT PRIMARY KEY,
  lease_id TEXT NOT NULL,
  lease_until INTEGER NOT NULL,
  processed_at INTEGER,
  outcome TEXT CHECK(outcome IN ('handled','ignored','test')),
  expires_at INTEGER NOT NULL
);
CREATE INDEX purchase_notifications_expiry ON purchase_notifications(expires_at);
