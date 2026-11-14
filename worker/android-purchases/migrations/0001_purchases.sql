CREATE TABLE challenges (
  id TEXT PRIMARY KEY,
  nonce TEXT NOT NULL,
  device_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER
);
CREATE INDEX challenges_expiry ON challenges(expires_at);

-- Hashes only. Raw Play tokens, account identifiers, orders and integrity proofs are never stored.
CREATE TABLE grants (
  token_hash TEXT PRIMARY KEY,
  grant_id TEXT NOT NULL UNIQUE,
  product_id TEXT NOT NULL,
  verified_at INTEGER NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('active','revoked'))
);
CREATE TABLE rate_windows (
  bucket TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX rate_windows_expiry ON rate_windows(expires_at);
