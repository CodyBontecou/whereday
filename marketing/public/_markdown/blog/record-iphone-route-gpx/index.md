- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Record and export GPX

Practical iPhone guides

# How to record an iPhone route and export a GPX file

Start tracking in Whereday, stop after your trip, then select Points and GPX in Export. Tracking depends on your plan and existing access; Export Preview is free and saving files requires export access.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 30, 2026

## Record first, then export the points

To record an iPhone route as GPX with Whereday, start tracking before your trip, stop when you finish, then choose Points and GPX in Export. Select the right date range, preview the result, and save the file to Files or share it with a compatible route viewer.

From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement. This workflow exports a route recorded in Whereday; it does not extract old Apple Maps or Google Timeline history.

From recording to file
- [Record and check a route](https://whereday.com/blog/record-iphone-route-gpx/#record)
- [Export an Apple Watch route](https://whereday.com/blog/record-iphone-route-gpx/#watch-to-gpx)
- [Select points and export GPX](https://whereday.com/blog/record-iphone-route-gpx/#export)
- [Check the saved file](https://whereday.com/blog/record-iphone-route-gpx/#verify)
- [Inspect a synthetic GPX example](https://whereday.com/blog/record-iphone-route-gpx/#sample)
- [See the checked app-to-Files export](https://whereday.com/blog/record-iphone-route-gpx/#checked-ui-export)
- [Fix an empty or incomplete export](https://whereday.com/blog/record-iphone-route-gpx/#problems)

## 1. Record a short route

- Open Whereday and complete permission setup. Choose Always location access if you want recording to continue in the background.
- On the Map tab, start recording before you leave. Start enables GPS recording, visit monitoring, and significant location changes together.
- After a short walk or trip, stop recording. Stop ends both GPS and visit monitoring.
- Open Timeline and check the outing’s date, start and end times, and map. Confirm that it contains the part of the trip you intend to export.

Try this with a short session before relying on it for a full day. GPS can be noisy or unavailable, and permission changes or a stopped session can leave gaps. There is no separate passive visit-only switch. The [recording reference](https://whereday.com/docs/tracking/) explains distance filtering and auto-stop.

For a longer trip, choose an auto-stop duration that will not end early. Stop when you finish. Detailed GPS uses battery, and a larger distance filter trades route detail for fewer captured points; check the result before settling on a setting. Use the [battery comparison checklist](https://whereday.com/blog/iphone-location-tracking-battery/) to compare your own recordings.

## Export a route recorded on Apple Watch

If you recorded in the Whereday Watch app, transfer the route to its companion iPhone before exporting. This workflow uses Whereday’s saved points; it does not extract an Apple Workout or Apple Health route.

- Stop the recording on the Watch. Bring the paired devices together and open Whereday on both. If available, choose Sync Now on the Watch.
- Open the recording’s date in the phone’s Timeline. Inspect the outing, its map, and its start and end times. A Sync queued message is not confirmation that every point has arrived.
- On the phone, choose Points and GPX in Export, then select the recording’s dates. Review filters and Preview before saving. Points can include other recordings in that period; use the specific outing’s Export action when you need that outing alone.
- Open the saved file in the [local GPX viewer](https://whereday.com/tools/gpx-viewer/). Compare its first and last point times, count, and shape with the phone copy. Keep pending Watch data while investigating missing points.

The [Watch sync reference](https://whereday.com/docs/apple-watch/#checked-transfer) explains pending points and acknowledgements. Sync retries with the same record IDs are separate from importing a GPX file. Whereday does not import GPX.

### A readable GPX can still be incomplete

In a synthetic native check, an invented 503-point Watch queue reached the iPhone in batches of 500 and three. We exported the phone’s saved Points as GPX after the first batch, then again after the remaining points arrived and the first batch was retried.

Both files opened, but only the later export held all 503 example points
| Phone state | GPX track points | Last point, UTC
| First batch saved; transfer incomplete | 500 | October 24, 2026, 14:41:35
| Final batch saved; delayed first batch retried | 503 | October 24, 2026, 14:41:50

[Download the incomplete 500-point example](https://whereday.com/samples/synthetic-watch-first-batch.gpx) and [the complete 503-point example](https://whereday.com/samples/synthetic-watch-complete.gpx). Both start at 14:00 UTC on October 24, 2026 and contain one track segment, with no visit waypoints. Coordinates and times are invented. Load either file in the local viewer to compare counts and endpoints; a line on a map alone cannot prove a transfer is complete.

Checked October 30, 2026 with the native queue, phone receiver, and export code matching released Whereday 1.8 (52). Delivery was supplied directly by an iOS simulator test, with WatchConnectivity disabled. The saved phone records were passed to the export service without a date or accuracy filter. This checks the data handoff, not physical Watch recording, wireless transport, the outing-only export action, or a fresh share-sheet workflow. The separate app-to-Files check below covers the earlier export screen.

## 2. Choose Points and GPX

- Open Export and choose Points as the data type. These are the recorded GPS locations that make up a path.
- Select GPX. If other formats are also selected, the export can produce additional files.
- Choose the date range for your recording. Review any time-of-day, accuracy, and GPS-glitch filters that could exclude points.
- Open Preview. Check the intended date and file contents before paying for or writing an export.
- Export. With a default folder configured, look there for the saved file; otherwise use the share sheet to save it in Files.

You can also open a specific outing in Timeline and use its Export action with GPX selected. For a full reference on filenames, date filters, and saving destinations, see [export and restore compatibility](https://whereday.com/docs/export-import/).

Choose the data you actually need
| Choice | GPX result | Use it for
| Points | Track points grouped into track segments | The recorded path over a selected period
| Visits | Waypoints, when visit coordinates are included | Stops rather than a continuous path
| Outings | The selected outing’s route points | A particular recorded trip

A GPX export with only visits can be valid but show pins instead of a line. Pick Points or an outing if the path is the part you want to keep.

![Example Whereday export interface with data types and file format choices](https://whereday.com/screenshots/export-480.webp)

The export interface. This screenshot illustrates the workflow and may show sample data or earlier labels.

## 3. Check the file in Files and your viewer

Find the exported `.gpx` file in the folder you chose. Confirm the filename and date before sending it elsewhere. If your destination application accepts GPX tracks, use its import or open-file action to select the file.

- Check that the track covers the expected start and end locations.
- Check timestamps against the trip. GPX times may be shown in UTC or converted to the viewer’s local timezone.
- Look for gaps and separate segments instead of assuming every straight line represents captured movement.
- Keep the original file if another tool rewrites or converts it.

GPX describes geographic data; it does not include a street map or guarantee that every app offers the same display. Files preview may show text or a file icon rather than drawing a route. A viewer that supports waypoints alone may not display a track.

Whereday exports recorded points. Do not assume the file follows the same road-matched line shown in a map preview. When consecutive points are more than ten minutes apart, the current exporter starts a new GPX track segment.

### Open a track in gpx.studio

On a computer, open [gpx.studio](https://gpx.studio/app), choose File → Open…, and select the saved GPX. Use View → File tree to inspect its tracks and segments alongside the map. These controls are described in the viewer’s [file help](https://gpx.studio/help/menu/file) and [view help](https://gpx.studio/help/menu/view).

Try the synthetic example below before opening your own location file. A map line connects the saved points; it does not prove continuous GPS coverage between them. Check segment boundaries and timestamps as well as the shape.

## What a GPX track looks like

[Download a synthetic GPX example](https://whereday.com/samples/synthetic-route.gpx) to inspect before sharing your own data. It contains three invented points near latitude zero and longitude zero, with fixed example timestamps. It is not a real trip or a captured Whereday export.

```
<trkpt lat="0.0001000" lon="0.0001000">
<time>2000-01-01T12:01:00Z</time>
</trkpt>
```

The coordinates identify a point. Its time helps a viewer place it in sequence; `Z` denotes UTC. Track points sit inside a track segment. For the same invented coordinates in Whereday’s importable JSON structure, see the [backup file examples](https://whereday.com/blog/back-up-iphone-location-history/#sample). The sample uses the [GPX 1.1 format](https://www.topografix.com/GPX/1/1/), but does not include every optional extension Whereday can export.

### A sample made by Whereday’s GPX serializer

[Download the five-point synthetic app export](https://whereday.com/samples/synthetic-app-export.gpx). We passed invented records through the same GPX serializer included in public version 1.7.16. The input was deliberately out of order; the resulting points are sorted by time. This is a serializer check, not a walk recorded with an iPhone or a test of the purchase and sharing screens.

What to check in the synthetic app export
| Item | Expected value
| Track points | Five invented locations near latitude zero and longitude zero
| Point times | January 1, 2000: 12:00, 12:01, 12:02, 12:13, and 12:14 UTC
| Track segments | Two: three points, then two points after an eleven-minute gap
| Visits | None; this file demonstrates Points export

The metadata time records when the file was generated; it is separate from the invented point times. Preserve both segments when comparing viewers. A program may display them differently or omit Whereday’s optional speed and accuracy extensions.

[Open this public synthetic sample in gpx.studio](https://gpx.studio/app?files=%5B%22https%3A%2F%2Fwhereday.com%2Fsamples%2Fsynthetic-app-export.gpx%22%5D). This link loads only the example hosted on this website. It does not read your journal.

![gpx.studio showing two separate red synthetic track segments and a file tree listing Segment 1 and Segment 2](https://whereday.com/screenshots/gpx-studio-synthetic.png)

Checked October 26, 2026 in gpx.studio on desktop. The synthetic export appears as two segments. The viewer reports 03:00 of segment time; the first and last timestamps span fourteen minutes because the eleven-minute gap is excluded. This is not a real recorded journey.

## A GPX saved through the app and Files

We also checked the export screen in an isolated Debug rebuild of released iso.me 1.7.16 (46), before the Whereday rename, on iOS 26.5. Its journal contained the [three invented points imported from the documentation JSON](https://whereday.com/blog/back-up-iphone-location-history/#checked-picker). This tests exporting stored records; the points were not recorded on a walk.

- Selected Points and GPX, with JSON deselected.
- Opened Preview Export: GPX, three items, one planned file, 1003 bytes.
- Closed Preview, chose Export 1 File → Save to Files → On My iPhone → Save.
- Checked the saved file, then opened that exact public synthetic file in gpx.studio.

![Earlier iso.me (now Whereday) Export Preview in the isolated simulator showing GPX, three items, one file, and a planned size of 1003 bytes](https://whereday.com/screenshots/gpx-preview-synthetic.webp)

The checked preview for the three imported synthetic points. The test build was already unlocked; no App Store purchase was tested.

[Download the exact GPX saved through Files](https://whereday.com/samples/synthetic-ui-export.gpx). The saved copy matched the share-sheet file byte for byte. It contains three track points, one segment, and times 12:00, 12:01, and 12:02 UTC on January 1, 2000. No visit waypoints are included.

In this single-file example, the generated filename was `iso.me - Monday 2026-10-26 - points.gpx`, while the point times were in 2000. The metadata time records file generation. Inspect the actual track timestamps rather than treating the filename as proof of its date range.

[Open the saved synthetic export in gpx.studio](https://gpx.studio/app?files=%5B%22https%3A%2F%2Fwhereday.com%2Fsamples%2Fsynthetic-ui-export.gpx%22%5D). In a fresh desktop browser context, the viewer displayed the angled three-point line, 0.03 km, and 02:00 / 02:00 moving and total time. Those are viewer results for invented data, not a GPS accuracy or distance benchmark.

![gpx.studio showing the angled synthetic track saved from Whereday, with 0.03 km distance and 02:00 moving and total time](https://whereday.com/screenshots/gpx-studio-ui-export.webp)

The 1003-byte UI export opened in gpx.studio, checked October 26, 2026. Only the public synthetic file was loaded.

The app’s Export screen, file creation, share sheet, Save to Files, and viewer handoff were checked with unchanged release-source export code and a separate test identity. This was not the App Store binary, a purchase flow, a physical recording, or an unattended export. Keep those evidence limits separate from the earlier serializer-only sample.

## If the export is empty or the line looks wrong

For an incomplete Whereday day, use the [missing visits and route points checklist](https://whereday.com/blog/missing-location-history-iphone/) to distinguish recording, display, Watch sync, and export issues.

- No points in Preview: check the selected dates and filters, then open the same day in Timeline. Export cannot create a path that was not recorded.
- Pins instead of a line: check whether you exported Visits rather than Points.
- The path has gaps: check recording state, permission, and auto-stop. Inspect the original points before changing filters to include less accurate fixes.
- File saved somewhere unexpected: check the default export folder before repeating the export. Some exports produce more than one file.
- A viewer rejects the file: confirm it supports GPX 1.1 tracks and that the file was completely saved. Keep the original while checking the error.

## Keep a separate copy for restoring your journal

GPX is a portable map format, but Whereday does not import GPX. To retain records you can import later, export visits and route points separately as supported Whereday JSON files and keep both. A map file is not a complete backup of every journal setting or outing edit. Follow [the backup and restore checklist](https://whereday.com/blog/back-up-iphone-location-history/) for a transferable copy of supported records.

For spreadsheet rows, CSV may suit your next task better. Read [the format and restore matrix](https://whereday.com/docs/export-import/) before choosing an archive format. For automatic copies, see [scheduled exports](https://whereday.com/docs/automatic-exports/); background execution is best effort.

Exported coordinates and times can reveal your movements. A file saved in cloud storage or sent to another app follows that destination’s privacy rules. Check the file before sharing it.

## Record the route.
Choose how to keep it.

Check your tracking access and try Export Preview. Export access follows the new-user and existing-user rules above; see [current pricing](https://whereday.com/#pricing).

[Download free for iPhone](https://apps.apple.com/app/apple-store/id6761960794?pt=126424970&ct=seo-gpx&mt=8)

Reviewed October 30, 2026; earlier app and viewer checks retain their own dates above. Steps reflect Whereday’s recording and export controls; labels can vary by app version. For setup, read [the private journal guide](https://whereday.com/blog/private-location-journal-iphone/); for existing Apple or Google records, see [how to view iPhone location history](https://whereday.com/blog/view-location-history-iphone/).

## Inspect a GPX before importing it

Use the [free local GPX viewer](https://whereday.com/tools/gpx-viewer/) to check the points, separate segments and available times in a file you already have. Follow the [five-point tutorial](https://whereday.com/blog/check-gpx-file/) for expected results and CSV limits.

For a different destination, compare [GPX with KML, GeoJSON and CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/) before converting an archive.

## Read next

- [Keep a restorable copy](https://whereday.com/blog/back-up-iphone-location-history/)

GPX is a map file; retain supported visits and points separately for import.

- [Investigate an incomplete route](https://whereday.com/blog/missing-location-history-iphone/)

Check recording state and filters before repeating the export.

Source: https://whereday.com/blog/record-iphone-route-gpx/
