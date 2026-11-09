# Apple backup regression — local-only, November 8, 2026

The new full backup omitted four persisted visit correction fields and two recording-session sampling fields. Exporting and restoring such a backup lost Undo Correction provenance and route sampling metadata. The prior JSON-vs-JSON test omitted the fields on both sides, so it could not detect the loss.

Backup admission also accepted inverted visit/session intervals, accuracy below the Core Location `-1` sentinel, and finite timestamps outside the supported date range. The ordinary timeline import path rejects these values. Malformed backups must fail atomically rather than restoring the other rows.

`35ab45f3` adds backward-compatible optional Codable fields, copies them both directions, adds direct restored-model assertions and legacy/malformed XCTest cases, and applies the existing date/accuracy admission invariants to backups. No stored-model schema was changed. The independent field inventory now matches all six persisted models: Visit 32, LocationPoint 11, RecordingSession 9, SavedPlace 10, PhotoMoment 7, CountryStay 5.

## Executed host regression

The committed runner compiles the actual full models, backup service and public timeline importer against macOS SwiftData. Only the UIKit-dependent `ExportFormat` declaration is extracted. All stores and history are synthetic and in-memory. Immutable compiler-input snapshots are SHA-256 hashed in each report.

```sh
python3 scripts/verify_backup_roundtrip.py \
  --service-source /private/tmp/whereday-backup-service-before.swift \
  --output /private/tmp/whereday-backup-before.json
python3 scripts/verify_backup_roundtrip.py --output /private/tmp/whereday-backup-after.json
```

The historical service snapshot is byte-identical to `9ff11d2c:IsoMe/Utilities/WheredayBackupService.swift`; its SHA-256 was checked against `before.json`. It can be recreated with `git show` into a new temporary file. The runner reports the historical override explicitly; it does not attribute historical bytes to the current source.

- [Before](before.json): **1/9 passed**, exit 1. Six-field preservation, Undo Correction and all five malformed-backup cases failed; legacy decoding passed. Each malformed payload was incorrectly committed alongside existing data.
- [After](after.json): **9/9 passed**, exit 0. The same tests now preserve correction/sampling metadata and reject malformed input while keeping an existing SavedPlace's ID/name and total record count unchanged.

This is a host regression check, not an iOS XCTest run. Added XCTest cases were not executed because simulator/device QA is not authorized. A later authorized signed iOS run must use `scripts/test-ios.sh`.

## Executed generic compilation

[Native build summary](native-build.json) records the exact command, log hash, pinned-package lock hash and bundle readbacks. The unsigned Release build succeeded for phone, phone widget, Watch and Watch widget with iOS 17/watchOS 10 minima. Source packages were copied to the task's temporary directory; unrelated builds and caches were preserved. The output uses repository defaults 2.0/60, which are already-consumed numbers and must not be uploaded as a release candidate.

No simulator, emulator, Argent, physical-device interaction, runtime launch, signed archive, store operation, hosted CI or remote write occurred. Logs and generated products remain under `/private/tmp/whereday-ios-compile-20261108/`. Native queue ownership was explicitly released through Herdr after the host checks.
