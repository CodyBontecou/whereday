# Deeper Android QA — import, export and webhook settings

Tested with Argent on the open `whereday-m3-fold-api35` emulator (`emulator-5562`), using only `com.bontecou.whereday.mapqa`. The journal started and ended empty. No import was committed, webhook enabled, delivery triggered, purchase attempted, or owner package changed.

## Bugs found and fixed

1. **Incorrect paid-access wording.** Archive says all Weekly, Yearly and Lifetime plans include exports, but its folder-delivery help claimed verified Lifetime access was required. Related delivery, country CSV, support and webhook labels repeated the outdated restriction. The billing engine authorizes paid capabilities through a verified grant rather than requiring the Lifetime product specifically (`BillingSession.requireAccess`). Updated the affected English strings and Spanish localization inputs to refer to verified plan access, then regenerated localized resources. No billing or entitlement logic changed.
2. **Singular rejected-row wording.** A mixed import preview displayed “1 rows could not be read.” Changed the count-neutral message to “Rows skipped: 1. The first errors appear below.” Spanish uses “Filas omitidas: 1.” The same format works for singular and plural counts. [Before-fix screenshot](import-singular-before.png).

## Exercised cases

| Case | Result |
| --- | --- |
| Disabled webhook draft with `http://127.0.0.1:9/qa?odd=%25`, manual send mode, Save | Rejected with “Use an HTTPS endpoint URL.” Draft discarded; queue stayed at zero |
| Empty JSON export preview | Valid JSON preview with empty arrays and totalPoints 0 |
| Open Android import picker, then Back | Returned to Archive; no import started |
| Same point UUID with different timestamps and opposite dateline coordinates | Entire file rejected with “Conflicting point identities in this file.” |
| Truncated JSON after a valid first point | File rejected; no partial journal import |
| Two unique valid points at longitude +180 and -180, plus latitude 91 | Review showed 2 points and 1 skipped row |
| Deselect the mixed review's only record type | Import action disabled |
| Rotate that deselected review landscape and portrait | Selection remained off and Import stayed disabled in live inspection |
| Discard reviews / dismiss failures | Archive still showed 0 route points |

The duplicate-conflict test fails the whole file before producing a mixed-row review. The separate mixed fixture isolates valid dateline coordinates and invalid-latitude rejection. Synthetic files are included beside this report. They are not user history.

## Verification

`android/.argent/e2e/import-mixed-preview-regression-20261107.yaml` replays the corrected Archive access message, Android document navigation, mixed fixture preview, singular skipped-row message, deselection/rotation, discard, and zero-point end state. Two consecutive full Argent MCP runs passed without manual resets or edits between them:

| Run | Result |
| --- | --- |
| 1 | 37 passed, 0 failed/errored/skipped, 44.3s |
| 2 | 37 passed, 0 failed/errored/skipped, 51.7s |

There are 38 directives including the echo. The disabled-action state was inspected live; the unattended test checks the summary, messages, navigation and zero-point end state. The replay contains no import-confirm action. First run reported residual motion while the system roots drawer settled; the following explicit storage/folder targets and final import summary passed. Second run had no warnings.

Prerequisites: English onboarded empty mapQa, open foldable, Android API 35 document picker with storage label `sdk_gphone64_arm64`, and the supplied fixture directory copied to `/sdcard/Download/whereday-edge-qa-20261107/`. Fixture files remain there for replay. The superseded root-level test file was removed. No hardcoded serial is stored in the YAML, and polished taps/scrolling use semantic targets. This is a device-class-specific test because it traverses Android's system document picker.

`./gradlew :app:lintDebug :app:assembleMapQa` completed successfully after the final wording change. Localization regeneration and `python3 android/scripts/generate_localized_resources.py --check` passed for all 1,680 resources. `git diff --check` passed. Unit tests were not rerun for these resource-only changes; no iOS source changed and no iOS tests were run. English messages were verified on-device; Spanish was checked through generated-resource validation, not a second locale UI run.

## Limits and diagnostic trace

Purchases are unavailable in mapQa, so paid Save as/share/folder delivery was not exercised or bypassed. This pass found no functional/data-integrity failure in the tested flows; the fixes above address wording defects.

`raw-exploration.yaml` preserves the broader walkthrough, not a portable regression. Four live waits failed against stale accessibility snapshots; device-scoped Argent service refreshes exposed the updated UI. The final replay uses the runner's tree and passed. The Downloads-provider view initially omitted newly pushed nested files; switching through the device-storage root exposed them. That provider behavior was not counted as an app defect. Raw file-picker taps with repeated Android IDs were replaced with exact text targets in the final replay.

Earlier fixes and unrelated workspace changes were preserved. Reusable emulator services remain available.
