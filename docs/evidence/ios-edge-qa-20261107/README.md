# iOS exploratory edge-case QA — 2026-11-07

The issues below have since been fixed; see [fixes and verification](fixes-and-verification.md). This document preserves the original observations.

Tested the installed Whereday **2.0 (60)** build on the running **iPhone 18 Pro / iOS 27.0** simulator using Argent. English was supplied as a launch-language override. Existing data: 132 visits, 111 points, 6 saved places, and 13 manual country stays. No app code was changed, data erased, or fixtures seeded. This is exploration of the installed build, not a fresh-build acceptance run.

## Confirmed: P1 — “Export everything first” omits data the next action erases

1. Open Archive → Settings.
2. Scroll to Clear all data….
3. Observe the confirmation listing visits, points, outings, saved places, photo matches, and manual country stays as data that will be deleted.
4. Choose **Export everything first**.
5. Inspect the generated Whereday JSON before proceeding with any erase.

Expected: a recoverable backup of the data the dialog is about to delete, or explicit disclosure that the export covers only part of that data.

Observed: the generated JSON has only `exportDate`, `dateRange`, `totalVisits`, `totalPoints`, `visits`, and `points`. It contains all 132 visits and 111 points, but no saved-place collection, manual country stays, photo matches, or original recording sessions. These entities cannot be reconstructed fully from this payload. **No erase or destructive restore was attempted.** The file structure was inspected read-only; raw location records are not retained in this evidence folder.

Source corroboration: `SettingsView.exportEverything()` calls `ExportService.share` with `.all`; `IsoMeExportKitAdapter.renderData` sends `.all` to `combinedData`, whose JSON schema contains visits and points. `LocationViewModel.clearAllData()` deletes `Visit`, `LocationPoint`, `RecordingSession`, `PhotoMoment`, `SavedPlace`, and `CountryStay`.

Evidence: [erase dialog](clear-data-export-first.png), [generated share sheet](backup-share-sheet.png), [payload schema, counts, and hash](backup-schema.json). Prioritize a complete backup/restore format for this path; until available, make the partial export explicit before deletion.

## Confirmed: P2 — Archive export footer overlays recording settings

1. Open Archive.
2. Tap **How Whereday listens** directly on the recipe screen.
3. Scroll through the recording, fine-tune, and privacy settings.

Expected: recording settings without Archive's export actions overlaying their content.

Observed: Preview and Export/Nothing to export remain fixed above the tab bar on the pushed settings screen. The footer covers settings and text as they scroll beneath it; the scrolled screenshot shows it over the Place names/privacy rows. This persisted after settling and reproduced during replay. It was also visible before the clean restart, with a nonempty export recipe, so it is not specific to empty results.

Likely cause: `ExportView.body` attaches `safeAreaInset` containing `exportFooter` **outside** its `NavigationStack`; `listeningLink` pushes `ListeningView` inside that stack. Scope the footer to the recipe content or hide it on pushed destinations.

Evidence: [settings with footer](listening-footer.png), [footer over scrolled settings](listening-footer-scrolled.png).

### Recorded reproduction

`footer-leak-before.yaml` (originally `.argent/e2e/ios-archive-footer-leak-repro-20261107.yaml`) was recorded live and completed one uninterrupted replay: **4 executed checks/actions passed, 0 failed, 0 errored**. This is a diagnostic that asserts the **bug's presence**, not a passing acceptance test for correct behavior.

Prerequisite: English Archive recipe screen, portrait iPhone 18 Pro, with the listening link visible at the recorded position. The recorded run used an empty Nov 7–Nov 8 visits/JSON recipe.

The diagnostic has been moved out of the active e2e directory because it asserts the old bug’s presence and should fail on a fixed build.

One coordinate exception: `(0.5, 0.6315)` comes from the AX button frame. Argent's native view hierarchy did not expose the SwiftUI link's text/id: `native-find-views` returned no matching label, a depth-100 hierarchy exposed no candidate for it, and semantic replay failed on the visible link. Source has no explicit identifier. The point is documented in the YAML and gated by a destination-only accessibility check. The two raw `await-ui-element` checks are retained because recorder probes confirmed those labels exist in AX but not the runner projection. Re-record for another device geometry or recipe layout. Final replay produced no readiness warnings.

## Release-readiness follow-up — old What's New content

Settings identifies the installed build as **2.0 (60)**, while What's New advertises **1.7.17** and opens “Check in and out with Shortcuts.” `ReleaseNotes.swift` also begins with 1.7.17. Confirm whether a 2.0 entry is still pending; this is a content follow-up rather than a functional regression. [Screenshot](version-mismatch.png).

## Other checks

| Area / edge case | Observed outcome |
| --- | --- |
| Saved place: whitespace-only name | Validation requested a location name; Save remained unavailable. |
| Saved place: latitude 91, longitude 181 | Invalid-location message; Save unavailable. [Evidence](invalid-place-coordinates.png). |
| Dirty saved-place draft → Cancel | Discard Changes confirmation appeared; discarded draft did not increase the 6-place count. |
| Places: search with punctuation/spaces, no results, clear | No-results UI appeared; Clear search restored all six entries. iOS converted consecutive spaces to punctuation during typing; this was not treated as a search-engine defect. |
| Export: custom From Nov 8 / To Nov 7 | Summary normalized to Nov 7–Nov 8. No crash. |
| Export: empty selected range | Preview/export disabled and Nothing to export shown. |
| Settings: import picker → Cancel | Returned to Settings without an error or count change. Parsing malformed files was not exercised. |
| Clear data: Export everything first → cancel sharing | Share sheet opened; cancel returned to Settings with 132 visits and 111 points intact. |
| Atlas: country detail → Add a stay, departure before arrival | “Departure must be on or after arrival”; Save/Add stay unavailable. Draft cancelled. [Evidence](invalid-stay-range.png). |

No crashes observed. Hardware behavior, background tracking, real purchases, actual webhook delivery/endpoint validation, malformed import parsing, and destructive backup restoration remain unverified. Existing webhook delivery was enabled; this pass did not change or test-send that configuration. Automated iOS unit tests were not run because this task changed only QA evidence and the diagnostic recording.
