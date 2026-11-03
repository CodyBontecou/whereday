-- One-use verification challenges, bound to the device key that asked for them.
CREATE TABLE challenges (
  id TEXT PRIMARY KEY,
  nonce TEXT NOT NULL,
  key_hash TEXT NOT NULL,
  package_name TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX challenges_expiry ON challenges (expires_at);

-- Which device keys a purchase has unlocked. Only hashes: no purchase tokens, accounts or locations.
CREATE TABLE purchase_devices (
  token_hash TEXT NOT NULL,
  key_hash TEXT NOT NULL,
  first_seen INTEGER NOT NULL,
  PRIMARY KEY (token_hash, key_hash)
);
