# Fixes and verification

Implemented all three findings from the exploratory pass. Unrelated Android and marketing changes were left untouched.

## Complete pre-erase backups

Settings → Clear all data → Export everything first now produces **Whereday Backup.json**, a versioned backup of every model erased by that action: visits, points, recording sessions, saved places, photo matches, and country stays. It fetches storage directly rather than using filtered or partially loaded view-model arrays. Archive recipe exports retain their existing formats and options.

Settings → Import a file recognizes this backup and reviews all six categories before restoring. Restoration preserves stored fields and UUIDs, rechecks existing IDs at commit time, skips duplicates without overwriting changes, and saves once in a separate context. Cancellation or save failure rolls back the transaction. Unknown backup versions, missing collections, duplicate IDs, and invalid records are rejected rather than falling through to a partial visits/points import.

The format follows the existing importer limits of 50 MB and 250,000 records. Export rejects backups outside the supported restore limits before sharing; no erase is performed. Photo matches include metadata and Photos identifiers, not image files. The restore preview explains the original-library requirement.

Argent verified the actual Settings export and the saved-file import preview on the rebuilt iPhone 18 Pro app. The generated file contains **132 visits, 111 points, 3 recording sessions, 6 saved places, 13 country stays, and 0 photo matches**. The database fixture has no photo matches; the round-trip unit test covers a nonempty photo-match collection. Importing the saved file back into the same data correctly reports **0 new records** and disables Restore. The generated backup was saved in the simulator's On My iPhone location; no fixture records were erased or restored over.

Evidence: [backup share sheet](backup-share-fixed.png), [restore review](backup-review-fixed.png), [collection counts](backup-schema-fixed.json).

## Footer navigation

Archive retains its footer outside the navigation stack so it stays above the floating tab bar. Its recording-settings destination now has explicit navigation state, which hides the footer while that destination is open and restores it on Back.

Argent established Preview Export as visible on Archive, opened How Whereday listens, confirmed destination identity and the footer's absence, scrolled the settings to inspect the previously covered rows, and returned to confirm the footer reappears. The original bug-presence diagnostic was moved from the active e2e folder to [footer-leak-before.yaml](footer-leak-before.yaml); it is historical evidence, not an acceptance test for the fixed build.

Evidence: [recording settings](listening-footer-fixed.png), [scrolled privacy/settings rows](listening-scrolled-fixed.png).

## Release notes

Added a 2.0 release-note entry describing the map, day sheet/replay, Atlas/Places, Archive recipes, and refreshed companion surfaces. Earlier releases remain available. Argent verified Settings now says **What's new, 2.0** and opens **The map is now home**.

Evidence: [Settings version](settings-version-fixed.png), [2.0 release notes](release-notes-2.0.png).

## Automated verification

All iOS runs used `scripts/test-ios.sh` with normal simulator signing and the explicit iPhone 18 Pro destination `9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A`.

| Run | Result | Bundle |
| --- | --- | --- |
| Backup + existing timeline importer tests | 17 passed | `artifacts/test-results/IsoMe-20261107-190749-41239.xcresult` |
| Full suite | 398 of 399 XCTest tests passed; the 11 Swift Testing tests also passed | `artifacts/test-results/IsoMe-20261107-190933-44777.xcresult` |
| Final changed-area tests + isolated StoreKit failure | 18 passed, 0 failures | `artifacts/test-results/IsoMe-20261107-191539-53452.xcresult` |

The full-suite failure was `StoreManagerTests.testStoreKitCatalogTrialPurchaseExpirationAndLifetime`, at its `XCTAssertFalse(store.isPurchased)` expiration assertion (line 354). It **passed in isolation on the final build**. The preceding offline-restore test required cancelling a simulator Apple Account prompt; a notification permission prompt was also dismissed. No StoreManager code or StoreKit tests were changed. The full suite is therefore not reported as a clean pass.

The five new backup tests cover every-model/field round trips through the actual import worker, backups containing only saved places/country stays, repeated and stale reviews, malformed/future/partial payloads, and cancellation with no writes. Existing ordinary JSON/CSV/Markdown and Google timeline importer tests pass. `git diff --check` passes.

Hardware behavior and a destructive erase/restore on the simulator's existing data were not exercised. Full restoration was verified in isolated in-memory stores.
