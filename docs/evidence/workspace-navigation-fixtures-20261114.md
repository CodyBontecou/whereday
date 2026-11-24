# Expanded workspace navigation fixtures — 2026-11-14

PR #114 remains draft pending the full hosted baseline and downstream gates. This follows the separate [camera contract](workspace-camera-contract-20261114.md) and [consumed-focus production correction](workspace-focus-replay-20261114.md).

## Actual RED boundaries, not inferred from green CI

The original full local resize run had 24 tests, 19 passing and five failing. Focused observer-only runs in the separate diagnostic checkout retained the failing calls and original deadlines:

- `photo-entry-probe-1`: both photo-directory tests fail at `openMapPhotoPlaces` in 6.774 seconds. An added `assertIsDisplayed()` immediately before the unchanged failing scroll succeeds. Its initial stdout diagnostics were not collected; no semantics capture is claimed for this first run.
- `photo-entry-probe-2`: same two failures, now with actual dedicated-tag semantics capture. `browse-photo-places` is a displayed Button at `(115,188)–(386,243)` within the 800×1600 map. Its ancestors have no scroll action; it is floating map chrome. Both original failures occur at `performScrollTo`, before directory navigation or recreation. The same tree shows the current map replay Button tagged `map-replay-dial`, description “Replay the day”; the old “Replay route” text is absent. The original map replay case had failed on that absent text selector in the full 24-case run.
- `timeline-entry-probe-1`: both original remaining cases fail in 24.995 seconds. After real wide recreation the tree contains the primary `outing-detail-content` and companion `journal-native-map`, not a simultaneous timeline list. `TimelineScreen` explicitly returns after rendering the selected outing detail. In the stale-save case, the captured screen before `openStoredOuting` has Settings selected, a saved-place detail/library still open, and Map unselected. Waiting for the Map day-sheet control without navigating there times out at the unchanged 15 seconds.

Raw bounded-depth semantic captures are adjacent as `photo-entry-semantics.log` and `timeline-entry-semantics.log`. All records are synthetic test fixtures. Observers are only in the separate diagnostic checkout; none ship in the PR sources.

## Corrections to the actual navigation paths

Only `WorkspaceActivityResizeTest.kt` changes in this slice; no production behavior is changed:

1. Photo-directory entry explicitly asserts the floating button is displayed and clicks it, rather than requiring a nonexistent scrolling ancestor. Both original complete directory/collection/recreation scenarios and all final row assertions remain.
2. Map replay opens the actual displayed `map-replay-dial`. The original live-window advance, fixed opening range/point-count checks, pause position, both real recreation directions and unchanged database assertions remain.
3. Outing navigation verifies the displayed detail and exact outing title after both recreations, retaining the two actual native-map frame checks in wide mode and no-companion assertion after compact recreation. It additionally presses real Back in **each** size, requires the timeline list to be displayed, and reopens the same outing before the next recreation. The original timeline-exists requirement is thus checked when the list is actually active, not while the replacing detail is active.
4. The stored-outing helper explicitly selects the real Map destination before opening its day sheet. The stale-save test still invokes both old captured approval callbacks while stopped and after resume and checks that neither place nor outing records can change. No production guard, deadline or write assertion is weakened.

## Clean verification

Canonical untraced app/test APKs, explicit owned read-only API35 emulator, two cores/2GiB, private ADB5064/serial5586, actual density200 and actual 800×1600 ↔ 2200×1600 configuration changes:

| Actual selection | Result |
| --- | --- |
| Both photo-directory cases + map replay | 3/3, native `OK (3 tests)`, 47.141s |
| Outing navigation + stopped/old-resumed save callbacks | 2/2, zero failures/errors/skips |
| Entire `WorkspaceActivityResizeTest` | **24/24**, native `OK (24 tests)`, 104.658s, zero failures/errors/skips |

The adjacent `clean-verification.json` contains actual strict verifiers, APK hashes, raw-log hashes and session cleanup/readbacks. A source-to-source audit checks all **23 original methods** still exist, with only the additional padded-camera regression bringing the class to 24. There is no smaller acceptance selection or added ignore.

Every local session restored/read back display, density, IME and animation settings, uninstalled scoped APKs, stopped the collector/emulator/private ADB, and released its lock. No default/physical device was used.

The older hosted `libraryCurrentPhoto...` measure/layout exception did not recur in this full local run or the preceding full local run. **No causal fix for that intermittent exception is claimed.** Full original hosted context and downstream database/permission/recovery gates remain necessary; a local class pass is not whole-job acceptance.
