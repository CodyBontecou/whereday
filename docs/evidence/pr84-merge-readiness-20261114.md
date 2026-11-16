# PR #84 current-main integration

## Scope

Merge main `5344145e` into the accepted Watch manual-visit implementation, preserving both manual visit commands and main's shared phone/Watch recording controls. No further owner manual Watch QA is requested; the existing paired-device acceptance remains recorded in `docs/testing/watch-manual-visits.md`.

Conflicts resolved at `7052864c`:

- Phone activation publishes manual visit status **and** flushes shared recording controls.
- Phone dictionary-message/user-info handlers retain both shared recording dispatch and manual visit snapshot/command dispatch.
- Watch activation cleans orphaned route payloads **and** flushes shared recording controls. Both shared recording replies and manual visit replies remain dispatched.
- Retained durable route-file completion handling and the captured authorization value before the main-actor hop.
- Regenerated both Android reconciliation fixtures using the actual Swift method harness, not a hand-edited hash. All **17** case outputs are unchanged from the accepted PR; only source provenance changes.

## Verification

At `7052864c`:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A'
python3 android/scripts/generate_visit_reconciliation_fixtures.py
python3 android/scripts/generate_visit_reconciliation_fixtures.py --check-source
```

- Full suite: **493 XCTest cases, zero failures; 42 Swift Testing tests, passed**.
- Result: `artifacts/test-results/IsoMe-20261113-224012-53370.xcresult`.
- Local log: `artifacts/merge-readiness/pr84-ios.log`.
- All **30** source-reference checks declared in `.github/workflows/android-tests.yml` passed.
- `git diff --check` passes.

## Remaining gates

Hosted checks on the pushed head, review of the integration, and the existing higher-priority #114 baseline hold remain open. Simulator tests do not independently establish physical WatchConnectivity background delivery. Earlier owner acceptance is not a claim of a new paired-device run on this merge.
