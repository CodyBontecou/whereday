- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- GPX editor

iPhone location journals

# Edit a GPX file in your browser: trim, split by day, hide home, reverse

Clean up a recorded track before you share or archive it. Hide a home zone, split a long file by day, cut the ends and remove GPS spikes, then check what changed.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 25, 2026

## Add a GPX file. It stays on this device.

Choose one GPX file, make your edits, check the “what changed” summary beside the map, then download. No account, upload or analytics. The file is read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API) and never leaves the page. Your original file is never changed.

JavaScript is needed to read and edit files in your browser. The explanations and the sample download below work without it.

Choose a GPX file or drop it here · up to 10 MiB and 50,000 points

Try with the sample fileClear

Add a file, or try the synthetic two-day walk first.

Time zone for days, readouts and the time pickers

UndoRedoReset all edits

GPX stores times in UTC. The time zone only decides which calendar day a point belongs to and how times are shown here. It starts as this browser’s time zone.

1. Trim

Keep only the points from the first to the last one you choose. Drag the sliders, or type a start and end time in the time zone above.

First point to keep

Last point to keep

Start time

End time

2. Remove outlier points (spikes)

Flag a point when its speed from the previous good point is above (km/h)

Remove the flagged pointsNothing is deleted until you tick this.   3. Hide a home or work zone

Remove every track point, waypoint and route point within the radius of a spot. Click the map to pick the spot, type its coordinates, or use the first or last point.

Hide this zone

Latitude

Longitude

Use first pointUse last point

Radius: 300 m    4. Split Don’t split One file per calendar dayDays in the time zone above. A segment that crosses midnight there is cut at midnight. Several files are saved in one ZIP. Start something new after a gapWhen two points in a row are further apart in time than this many minutes.

Gap (minutes)

After a gap, start a newsegment (same file)file (saved in a ZIP)

5. Reverse and rename Reverse the trackPoints keep their own times, so times will run backwards.

Track name (blank keeps the original)

File name

### Preview and what changed

A local plot with no street tiles, so nothing is fetched. North is up. Dashed grey is the original, the dark line is what remains, the dashed red circle is the hidden zone and red rings are flagged points. Click the map to move the zone.

|  | In | Out

Download GPX

## Sample file: an invented two-day walk

Try with the sample file loads a synthetic GPX file and sets the time zone to UTC so the numbers below match. The points are invented, near 0° N 10° E, and are not anyone’s recording. You can also [download synthetic-two-day-walk.gpx](https://whereday.com/samples/synthetic-two-day-walk.gpx) and add it with the file picker.

It holds 1 track with 3 segments and 140 track points, 1 waypoint (“Home (synthetic)”), 1 route with 3 points and one invented `<extensions>` value. The first 10 and last 10 track points sit in a “home” cluster at 0° N 10° E. One point, number 31, is a deliberate GPS spike about 31 km away. It runs from 2000-01-01 09:00 to 2000-01-02 17:59 UTC, with a segment of 20 points that crosses midnight UTC.

Every operation gives these results on it. The site’s tests check them against the same file:

| Edit | Result
| Nothing changed | 140 track points, 3 segments, 1 track, 1 waypoint, 3 route points, 1 file
| Hide zone at 0, 10 with a 300 m radius | 140 → 114 track points (26 removed), waypoint 1 → 0, route points 3 → 2, still 3 segments
| Speed check at 300 km/h | 1 point flagged (point 31, 2000-01-01 09:30 UTC). Removing it gives 139 track points.
| Split by day, UTC | 2 files: 2000-01-01 with 70 track points and 2000-01-02 with 70 (the midnight segment is cut into 10 + 10)
| Split by day, Australia/Brisbane (UTC+10) | 3 files with 60, 20 and 60 track points, because the same instants fall on different local days
| Split by a 60-minute gap into files | 3 files with 60, 20 and 60 track points
| Trim to points 11 to 130 | 120 track points kept, 20 removed
| Reverse | 140 track points, same segments, in the opposite order. The last point becomes first.

## What each edit does

A GPX file can hold waypoints (`<wpt>`), routes (`<rte>`, a planned path) and tracks (`<trk>`, a recorded path). A track is a list of segments (`<trkseg>`), and each segment is a list of points (`<trkpt>`) that viewers draw as one connected line. These are defined in the [GPX 1.1 schema documentation](https://www.topografix.com/GPX/1/1/); the format is introduced in the [GPX overview](https://www.topografix.com/gpx.asp). Edits are applied in this order: trim, remove spikes, hide a zone, reverse, split, rename.

| Edit | `<trk>` and `<trkseg>` | `<wpt>` and `<rte>` | `<time>`, `<ele>`, `<extensions>`
| Trim | Points before the first and after the last you chose are removed. A segment or track left with no points is dropped. | Not changed. | Kept on every remaining point. Picking by time only chooses which points are the first and last.
| Remove spikes | Flagged points are removed; their segment stays one segment. | Not checked or changed. | The speed check reads `<time>`. Other values are not touched.
| Hide a zone | Points inside the radius are removed. If that leaves points on both sides, the segment is cut in two, so no line is drawn across the zone. | Waypoints and route points inside the radius are removed. A route with no points left is dropped. | Kept on remaining points.
| Reverse | Tracks, segments and points are put in the opposite order. | Route points are reversed. Waypoints keep their order. | Not changed. Each point keeps its own time, so times decrease along the reversed line.
| Split by day | Each point goes to the file for its local day. A segment that spans midnight is cut there. The track header is repeated in each file. | A waypoint goes to the file for its own day, or the first file if it has no time or no point falls on that day. Routes go in the first file. | Kept. Times stay in UTC in the file.
| Split by gap | A new segment, or a new file, starts when two timed points in a row are further apart than your limit. | New segments leave them as they are. With new files, waypoints and routes go in the first file. | Kept.
| Rename | Sets the `<name>` of each output track (blank keeps the original). The file name only changes the download. | Not changed. | Not changed.

Always dropped or recalculated: the output is GPX 1.1 with its own `creator` and recalculated `<bounds>`. Of the file’s `<metadata>` the name and description are kept; the summary lists anything else that was in the file, such as author, copyright, links, keywords, a file time or file-level extensions, as dropped. `<extensions>` on points, tracks and routes are copied as they are, but the tool does not read them, so it cannot promise another app understands them. Distances for the radius and the speed check use the haversine great-circle formula on a sphere with a 6,371 km radius; [Movable Type Scripts describes the formula](https://www.movable-type.co.uk/scripts/latlong.html).

Trimming by time sets the first and last point from the nearest timed points, in the time zone you chose. Points that have no time are kept if they lie between the two.

## Hide your home before you share a track

A recording almost always starts and ends where you live, because that is where you pick up your phone or watch, or put it down. Anyone who gets the file can read the first and last coordinates and see the doorstep. The same goes for a work place, a school or a regular parking spot.

Removing the start and end by hand is fiddly, because the points near a door are the most crowded. Hide a zone picks the spot for you and removes everything inside a circle around it: track points, waypoints and route points. A track that passes through the zone in the middle is cut in two so there is no straight line pointing at the spot.

- Add the file and tick Hide this zone. Click the map where you live, or choose Use first point or Use last point.
- Choose a radius from 100 m to 1,000 m. Watch the dashed red circle on the map and the “Removed” line in the summary.
- Look at the plot. The dark line should start and end outside the circle, with no short stub left that still leads back to the door.
- Download, and open the result in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) to check where it now starts.

Picking a radius. The circle must be big enough that the first point you share is not recognisable as one particular building. In a dense street 100 m can still be one block; in a quiet area it can leave the lane to your house visible, so a larger radius is safer. A bigger radius removes more of your route, so there is a trade-off. If you want to remove a whole neighbourhood, use 1,000 m. Check the map each time rather than trusting a number.

This removes points from the file you share. It does not change the original, anything you already sent, or a copy kept somewhere else.

## Split a multi-day file into one file per day

Choose One file per calendar day. The tool reads each point’s `<time>`, works out which date it falls on in the time zone shown at the top, and writes one GPX file per date. When there is more than one file, they are saved together in a single ZIP, with names such as `my-track-2000-01-01.gpx`. One file is saved on its own as a normal `.gpx`.

The time zone matters. GPX times are UTC (the [GPX 1.1 schema](https://www.topografix.com/GPX/1/1/) defines `time` as a UTC date and time), but a person’s day is local. The tool starts with your browser’s zone and shows its name. Change it if you recorded somewhere else. On the sample, the same instants make 2 files in UTC and 3 files in Australia/Brisbane.

A segment that crosses midnight is cut at midnight local time. The last point before midnight stays in one day’s file and the first point after in the next. No point is invented at midnight, so each day’s line ends at its own last recorded point. Waypoints go to their own day; routes go in the first file. Points with no time follow the day of the point before them.

You can also start something new after a gap: a gap of N minutes between two timed points starts a new segment in the same file, or a new file. Use it for a recording that paused for lunch or an overnight stop.

## Spikes: why tracks have jumps, and how the check works

A phone or watch works out its position from signals, and when the signal is weak the position can briefly be wrong. This is common right after you start recording, indoors, in a car park or between tall buildings. The result is a lone point far from the rest of the track and a long straight line out and back.

The check is generic: for each point it works out the speed implied by the distance and time from the previous point, and flags it when the speed is above your threshold. The default of 300 km/h is a suggestion, well above anything you do on foot, by bike or in a car; lower it for a walk, raise it for a plane. Because a real point after a spike would otherwise look like a jump too, each point is compared with the last point that was not flagged.

Nothing is deleted until you tick Remove the flagged points. Flagged points are listed and circled on the map first. The check cannot look at points with no time or with equal or backwards times, and a spike at the very start of a segment can’t be told from a real jump, so look at the map as well.

## Clean up a Whereday GPX export

Whereday can export GPX for any date range, and it can write one file per day. Each daily file holds one track called “Whereday track”, with a new segment wherever points are more than 10 minutes apart. Load a file here to trim it, hide your home or reverse it before sharing. The [export and import reference](https://whereday.com/docs/export-import/) has the options and limits, and the [route recording guide](https://whereday.com/blog/record-iphone-route-gpx/) covers a single GPX export step by step.

Whereday does not import GPX, so an edited file can’t be brought back into the app. Keep a Whereday JSON backup for restoring your records, and treat the edited GPX as a copy for sharing or archiving. To join daily files first, use the [GPX merge tool](https://whereday.com/tools/merge-gpx/#daily-exports).

## What the tool accepts

- GPX 1.0 and 1.1. Output is always GPX 1.1, checked against the official [GPX 1.1 XSD](https://www.topografix.com/GPX/1/1/gpx.xsd) in the site’s tests. GPX 1.0 fields with no 1.1 equivalent, such as speed and course, are left out and counted.
- One file at a time, up to 10 MiB and 50,000 points, the same limit as the [GPX viewer](https://whereday.com/tools/gpx-viewer/). The site’s logic tests run every edit on a 50,000-point synthetic file.
- Tracks, routes and waypoints. All three are read and written. Hide a zone and reverse touch routes; the others work on tracks.
- Passed through unchanged: `<time>`, `<ele>`, point and track names, and vendor `<extensions>` on points, tracks and routes. Dropped: other metadata (listed in the summary), file-level extensions, and values that are not valid GPX, such as an unreadable time or a point with missing coordinates.
- GPX only. Renaming a file does not convert it. Turn KML into GPX with the [KML to GPX converter](https://whereday.com/tools/kml-to-gpx/) first.
- Browsers. This is a browser tool that needs the File API, for desktop or mobile browsers. We have not tested it in iOS Safari, so we don’t promise it there.

## Your files stay in this page

Reading, editing, the preview and the download all happen in your browser, and the map is drawn locally with no tiles fetched. This page blocks network connections and does not load the website’s analytics. The web server receives an ordinary request when you open the page and its scripts, but not your file, its name or the result. Clear, or closing the tab, removes the file from the page; your original file is never changed. Edits only exist in the page until you choose Download. For a plain explanation of the file format, read [What is a GPX file?](https://whereday.com/blog/what-is-a-gpx-file/), and to check the result, follow [how to check a GPX file](https://whereday.com/blog/check-gpx-file/).

## Questions

### Can I edit GPX on iPhone in Safari?

This is a browser tool for desktop or mobile browsers. We haven’t tested it in iOS Safari, so this page doesn’t claim it works there.

### Does it change timestamps?

No. Every point keeps the exact `<time>` text it had. Trimming, splitting and hiding only choose which points stay. Reversing keeps each point’s own time, so times run backwards along the reversed line. The time zone setting only affects how days and pickers are read, never the times in the file.

### Can I undo?

Yes. Each change you make is a step. Undo and Redo move between them and Reset all edits returns to the original. The edits are only applied to the copy you download, so your original file on disk is never altered.

### Can I merge files?

Not here. Use the [GPX merge tool](https://whereday.com/tools/merge-gpx/) to join files, then come back to cut or split the result.

### Can I convert to KML or GeoJSON?

Edit the GPX here, then convert it with the [KML ⇄ GPX converter](https://whereday.com/tools/kml-to-gpx/) or save points and lines from a GeoJSON file with the [GeoJSON viewer](https://whereday.com/tools/geojson-viewer/).

### Is this a route planner?

No. It edits tracks that were already recorded, such as an exercise recording or a location-history export. It doesn’t draw new routes.

## Record routes without editing files afterwards.

Want routes and places recorded automatically, then exported as GPX, KML, GeoJSON, CSV, JSON or Markdown for any date range? Whereday keeps your location history on your iPhone.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Merge several GPX files](https://whereday.com/tools/merge-gpx/)

Join daily or split files into one, without uploading them.

- [Check the edited file](https://whereday.com/tools/gpx-viewer/)

Open it in the GPX viewer and compare points, segments and times.

- [Convert KML to GPX or back](https://whereday.com/tools/kml-to-gpx/)

Keep gx:Track times and see what was dropped.

- [Record your next route](https://whereday.com/blog/record-iphone-route-gpx/)

Record in Whereday and export points as GPX.

Source: https://whereday.com/tools/gpx-editor/
