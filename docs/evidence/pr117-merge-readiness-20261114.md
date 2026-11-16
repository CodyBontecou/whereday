# PR #117 current-main integration

## Scope

Integrate main `5344145e` without reverting the shared phone/Watch recording controls. Main already contains the original daily clipping implementation (`88039c65`) and its regenerated Android fixture provenance (`52cca7fa`). Do not duplicate those patches.

The only merge conflict was obsolete independent-Watch-recording guidance in ListeningView. Retained main's shared-control guidance. Reused the previously isolated audit fix `e3eea0be` as `1ca15175`: reload revision invalidates the timeline when visit checkout or session edits change daily membership without changing record counts. Its three regression tests cover same-count membership, bounded exports, and sparse stored sessions.

## Verification

At `1ca15175`:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A'
```

- Full suite: **494 XCTest cases, zero failures; 20 Swift Testing tests, passed**.
- Result: `artifacts/test-results/IsoMe-20261113-223839-51239.xcresult`.
- Log: `artifacts/merge-readiness/pr117-ios.log` (local, not committed).
- All **30** `--check-source` reference checks declared in `.github/workflows/android-tests.yml` passed, including visit reconciliation, outing, distance, Google import, and map focus provenance.
- `git diff --check` passes.

## Remaining gates

Hosted Android build, location-recovery, scale, interoperability and iOS checks must pass on the pushed head. This receipt does not claim those gates passed, nor fresh physical-device QA. Historical screenshots under `diogo-continuous-journal-20261111` predate main's shared Watch controls and are not current Watch-behavior evidence.
