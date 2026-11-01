# Watch-to-GPX tutorial extension

October 30, 2026. Extended the existing route guide at
https://whereday.com/blog/record-iphone-route-gpx/#watch-to-gpx and linked it
from the Apple Watch reference. Published as c8fd318d:
https://c8fd318d.spotted-8ez.pages.dev.

The practical steps distinguish Whereday Watch recordings from Apple Workout
and Health routes, transfer from export, date-range points from one outing,
and successful file parsing from completeness. Both comparison downloads are
invented data from the existing explicitly synthetic Watch fixture.

## Native handoff

Extended `WatchLocationSyncImportTests.testOfflineWatchSnapshotReplaysAllBatchesAndSurvivesLostAck`
to render the real phone receiver's saved LocationPoints through ExportService
as GPX. The test saves two retained XCTAttachments: first 500 points, then 503
after final delivery and a delayed repeat of the first batch. No native
production behavior was changed.

Ran the required signed runner:

```sh
scripts/test-ios.sh -only-testing:IsoMeTests/WatchLocationSyncImportTests/testOfflineWatchSnapshotReplaysAllBatchesAndSurvivesLostAck
```

One passed, zero failed/skipped, iPhone Duo iOS 27.1 simulator. Result bundle:
`artifacts/test-results/IsoMe-20261030-174943-93489.xcresult`.
Log: `/private/tmp/whereday-watch-gpx-test.log`.

The queue state, receiver, exporter, adapter, export options, fixture and package
lock all byte-match frozen released 1.8 (52). Hashes are recorded in
`docs/seo-watch-gpx-source-2026-10-30.json`.

Extracted attachments with `xcresulttool export attachments`. Independent Python
ElementTree parsing compared every coordinate (within exporter rounding) and
timestamp against the source fixture, plus segment/waypoint counts. Both files
have one segment, no waypoints, and start October 24, 2026 at 14:00 UTC. The
500-point copy ends 14:41:35; the 503-point copy ends 14:41:50. Actual bytes,
hashes and results: `docs/seo-watch-gpx-files-2026-10-30.json`.

This new check uses an in-memory phone store and direct delivery, with
WatchConnectivity disabled. It does not prove physical GPS recording, wireless
transport, a process restart, outing-only export selection or a fresh share
sheet. Older disk/restart and UI checks keep their own dates/versions.

## Browser and site verification

The in-app browser's real file chooser loaded both exports in the local GPX
viewer at 390px: expected500/503 valid points, one segment, zero waypoints,
and expected first/last timestamps. No page overflow. Screenshots:
`watch-gpx-viewer-mobile-2026-10-30.jpg`, `watch-gpx-mobile-2026-10-30.jpg`.

Production guide checked at390px and1280px, no page overflow. The mobile
comparison table is contained in a named focusable scroll region; ArrowRight
moves its horizontal scroll. Desktop table fits. Screenshots:
`watch-gpx-table-mobile-2026-10-30.jpg`, `watch-gpx-desktop-2026-10-30.jpg`.
Viewport override reset; local preview stopped after verification.

Build:39 HTML documents,38 public reader pages,1730 local targets, maximum
reader-link depth2; repeat build byte-identical across199 public files.
16 Python checks pass; git diff --check passes. Full38 HTML/Markdown production
response pairs pass, along with cache/404/API/method/asset checks. New download
bytes match the native attachments; both are200 with GPX MIME and attachment
headers. Raw live checks: `docs/seo-watch-gpx-live-2026-10-30.json`.

Second review confirmed evidence limits and the existing-page extension. It
also caught ambiguous historical file-import behavior in the Watch FAQ; that
example now says1.7.16 and links to the current1.8 duplicate-preview walkthrough.

Prior US research estimates10 monthly searches for Apple Watch GPX export,
difficulty49. No new keyword research was purchased, ranking asserted, or
traffic/indexing gain measured. Timeline-to-GPX remains deferred pending
format and route-reconstruction decisions. This is a useful narrow workflow
extension, not evidence of substantial search demand.
