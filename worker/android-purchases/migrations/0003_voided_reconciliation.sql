-- One resumable scan per app. The opaque pagination cursor is AES-GCM ciphertext.
CREATE TABLE voided_reconciliation (
  scope TEXT PRIMARY KEY,
  last_completed_end INTEGER,
  run_start INTEGER,
  run_end INTEGER,
  run_started_at INTEGER,
  cursor TEXT CHECK(cursor IS NULL OR length(cursor)<=16384),
  lease_id TEXT,
  lease_until INTEGER NOT NULL DEFAULT 0,
  last_success_at INTEGER,
  last_error_at INTEGER,
  restarts INTEGER NOT NULL DEFAULT 0,
  coverage_gap INTEGER NOT NULL DEFAULT 0 CHECK(coverage_gap IN (0,1)),
  CHECK((run_end IS NULL AND run_start IS NULL AND run_started_at IS NULL AND cursor IS NULL)
    OR (run_end IS NOT NULL AND run_started_at IS NOT NULL)),
  CHECK(run_start IS NULL OR run_start<=run_end)
);
