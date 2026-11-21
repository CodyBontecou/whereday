- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- Merge GPX

iPhone location journals

# Merge GPX files in your browser. Nothing is uploaded.

Join a recording split across days, devices or app restarts, or turn daily exports into one trip file. Choose what the merge does and check the result first.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 21, 2026

## Add your GPX files. They stay on this device.

Choose two or more GPX files, pick what the merge should do, check the summary and preview, then download one GPX 1.1 file. No account, upload or analytics. Files are read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API) and never leave the page.

JavaScript is needed to read and merge files in your browser. The explanations and sample downloads below work without it.

Choose GPX files or drop them here · up to 50 files, 10 MiB and 50,000 points each

Try with sample filesClear all

Add files, or try the three synthetic sample files first.

### Files, in merge order

Order Sort files by their first timestampFiles without timestamps keep the order you added them, after the timed files. Moving a file with Up or Down turns sorting off.   Merge mode Keep separate (safest)Each input track stays its own track, in time order. Nothing is joined. One track, separate segmentsOne track; every source segment stays its own segment, so viewers do not draw lines across gaps. One continuous segmentEvery point in one line. Most viewers will draw a straight line across each gap; [see why](https://whereday.com/tools/merge-gpx/#gaps).  Drop exact duplicate track pointsSame latitude, longitude and time, as often happens where two files meet. Points without a time are always kept.

### Summary of the merged file

### Checks

### Preview

Download merged GPX

## Sample files: an invented three-day walk

Try with sample files loads three synthetic daily GPX files. The points are invented, near 0° N 10° E, and are not anyone’s recording. You can also download them and add them with the file picker:

- [synthetic-walk-day-1.gpx](https://whereday.com/samples/synthetic-walk-day-1.gpx): an evening walk on 1 January 2000 that runs past midnight UTC. 1 track, 2 segments (a 2 h 56 min pause), 6 track points, 1 waypoint.
- [synthetic-walk-day-2.gpx](https://whereday.com/samples/synthetic-walk-day-2.gpx): starts with the same midnight point that ends day 1, then an overnight gap of 6 h 58 min before a short morning walk to camp. 1 track, 2 segments, 6 track points, 1 waypoint (Camp).
- [synthetic-walk-day-3.gpx](https://whereday.com/samples/synthetic-walk-day-3.gpx): a morning walk a day later. 1 track, 1 segment, 4 track points, the same Camp waypoint again, a planned route with 3 points, and one invented `<extensions>` value.

Together they hold 3 tracks, 5 segments, 16 track points, 3 waypoints and 1 route. Merged with duplicates dropped, the file runs from 2000-01-01 21:00 to 2000-01-03 07:03 UTC and is named `merged-2000-01-01-to-2000-01-03.gpx`.

## What each merge mode does

A GPX file can hold waypoints (`<wpt>`), routes (`<rte>`, a planned path) and tracks (`<trk>`, a recorded path). A track is a list of segments (`<trkseg>`), and each segment is a list of points that viewers draw as one connected line. These are defined in the [GPX 1.1 schema documentation](https://www.topografix.com/GPX/1/1/). The merge mode only changes how tracks and segments are combined:

| Mode | Tracks | Segments | Track points | Lines across gaps
| Keep separate | 3 | 5 | 15 | None
| One track, separate segments | 1 | 5 | 15 | None
| One continuous segment | 1 | 1 | 15 | 4 joins
| Any mode, duplicates kept | same | same | 16 | same

In every mode the sample merge has 2 waypoints (the second, identical Camp is left out), 1 route with 3 points, and 1 duplicate track point removed: the midnight point that both day 1 and day 2 contain. The largest gap is 23 h 58 min and 124 m, between the end of day 2 and the start of day 3.

- Keep separate is the default because it loses nothing: each track keeps its own name and segments. Use it when you only need one file to open or share.
- One track, separate segments gives one track with a new name, such as “Merged track, 2000-01-01 to 2000-01-03”. Use it when an app shows only the first track, or you want one entry in a list.
- One continuous segment puts every point into a single line. Use it only when a destination needs exactly one segment and you accept the straight lines across gaps.

Waypoints and routes are kept in every mode. An exact duplicate waypoint or route is left out; anything that differs, even by one field, is kept. Each point keeps its elevation, time and other GPX fields. Vendor `<extensions>` on points, tracks and routes are copied as they are where GPX 1.1 allows them, but there’s no guarantee every app will read them. Track names and extensions are dropped in the two one-track modes, and file-level metadata from the inputs is not copied. The merged file gets its own metadata: a name from the date span, a description with the first and last UTC times, the coordinate bounds and a `creator` attribute.

## Why one segment draws straight lines

Viewers connect every point in a segment, in order. If you stopped recording overnight, or one file ends somewhere else than where the next begins, a single segment gets a straight line from the last point before the gap to the first point after it. Distance and moving-time totals can include that line too. Separate segments tell the viewer not to join them.

A straight join is fine when the gap is short and you really did walk or ride between the two points, such as an app restart mid-route. It misleads when the gap is long or the points are far apart. The tool shows the largest gap, as a time and a distance, before you download, and the preview draws the line exactly as a viewer would.

When the times overlap (one file starts before the previous one ends), the tool warns you: in one segment the line would jump back in time. It often means two copies of the same recording.

## Why copying files together in a text editor breaks them

A GPX file is XML with exactly one root element, `<gpx>`. Pasting two files together in Notepad, or running `type a.gpx b.gpx > c.gpx` on Windows or `cat a.gpx b.gpx > c.gpx` on a Mac, gives a second XML declaration and a second root:

```
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" …>
<trk>…</trk>
</gpx>
<?xml version="1.0" encoding="UTF-8"?>   ← not allowed here
<gpx version="1.1" …>                     ← a second root element
<trk>…</trk>
</gpx>
```

That is not well-formed XML, so strict apps refuse it and lenient ones may read only the first file. Doing it by hand means removing the extra headers and moving every `<wpt>` before the routes and tracks, because GPX 1.1 requires waypoints, then routes, then tracks. This tool does that for you and checks each file first. Its output is checked against the official [GPX 1.1 XSD](https://www.topografix.com/GPX/1/1/gpx.xsd) in the site’s tests.

## Merge Whereday’s one-file-per-day GPX into a trip file

Whereday can write a separate GPX for each calendar day. Each daily file holds one track called “Whereday track”, with a new segment wherever points are more than 10 minutes apart. To turn a week of them into one file:

- In Whereday, open Export.
- Under Data, choose Points. Under Date range, choose the days of the trip (for example Last 7 days, or a custom range).
- Under Formats, choose GPX. Under Output, turn on One file per day. Check Preview, then save the files to Files. Preview is free; saving export files depends on your plan or existing access, as the [route guide](https://whereday.com/blog/record-iphone-route-gpx/) explains.
- Open this page, choose all the daily files at once and keep Sort files by their first timestamp on.
- Choose Keep separate for one track per day, or One track, separate segments for a single trip track that still breaks at each pause. Check the summary, then download.

Export options, filename templates and saving limits are described in the [export and import reference](https://whereday.com/docs/export-import/). The [route recording guide](https://whereday.com/blog/record-iphone-route-gpx/) covers recording and a single GPX export step by step. Whereday does not import GPX; for a restorable copy of your journal, keep a Whereday JSON backup as well.

## Check the merged file

Open the download in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) and compare its counts with the summary here. The viewer flags missing times, gaps over 10 minutes within a segment, and backwards timestamps. The [GPX checking tutorial](https://whereday.com/blog/check-gpx-file/) explains each warning. Need a different file type for another app? [Compare GPX, KML, GeoJSON and CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/).

## What the tool accepts

- GPX 1.0 and 1.1 only. Output is always GPX 1.1. GPX 1.0 fields with no 1.1 equivalent, such as speed and course, are left out and counted.
- Up to 50 files, each up to 10 MiB and 50,000 points (the same limit as the viewer), and 50 MiB or 250,000 points in total.
- Not Google Timeline JSON. Convert it to GPX first with the [Timeline JSON inspector](https://whereday.com/tools/google-timeline-inspector/).
- Not KML or GeoJSON. Renaming a file does not convert it. Convert KML or KMZ first with the [KML to GPX converter](https://whereday.com/tools/kml-to-gpx/); the [formats guide](https://whereday.com/blog/gpx-kml-geojson-location-history/) explains the differences.
- Problem files are listed, not merged. A file that is empty, not GPX, malformed or too large shows its error in the list. The other files still merge.
- Points with missing or out-of-range coordinates, and values that are not valid GPX (such as an unreadable time), are left out and counted in the checks.

## Your files stay in this page

Reading, merging, the preview and the download all happen in your browser. This page blocks network connections and does not load the website’s analytics. The web server receives an ordinary request when you open the page and its scripts, but not your files, their names or the result. Clear all, or closing the tab, removes them from the page; your original files are never changed.

## Questions

### Are my files uploaded?

No. The page reads files with your browser’s File API and builds the merged file in memory. Its security policy blocks network connections, so the page could not send your files even if it tried.

### Will times and elevation be kept?

Yes. Each point keeps its original time text and elevation, plus other GPX fields such as name, accuracy values and vendor extensions where GPX 1.1 allows them. A value that is not valid GPX, such as a time with no timezone, is left out and counted.

### Can I merge GPX with KML or GeoJSON?

Not directly: this tool accepts GPX only. Convert a KML or KMZ file with the [KML to GPX converter](https://whereday.com/tools/kml-to-gpx/) first, then merge the results here. For GeoJSON, convert it to GPX in the app that made it, or read the [formats guide](https://whereday.com/blog/gpx-kml-geojson-location-history/) to choose one format for everything.

### Why is there a straight line on my map?

The merged file probably has one continuous segment across a gap, or your source file already had a gap inside a segment. Merge again with Keep separate or One track, separate segments. [More on gaps](https://whereday.com/tools/merge-gpx/#gaps).

### How many files can I merge?

Up to 50 at once, each up to 10 MiB and 50,000 points, with at most 250,000 points in total. For more, merge in batches and then merge the results.

## Record routes without stitching files.

Want routes and places recorded automatically, then exported as GPX, KML, GeoJSON, CSV, JSON or Markdown for any date range? Whereday keeps your location history on your iPhone.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Check the merged file](https://whereday.com/tools/gpx-viewer/)

Open it in the GPX viewer and compare points, segments and times.

- [Have a KML file instead?](https://whereday.com/tools/kml-to-gpx/)

Convert KML or KMZ to GPX, or GPX to KML, with a report of what was kept.

- [Understand GPX warnings](https://whereday.com/blog/check-gpx-file/)

Follow a worked example of gaps, missing times and segments.

- [Record your next route](https://whereday.com/blog/record-iphone-route-gpx/)

Record in Whereday and export points as GPX.

Source: https://whereday.com/tools/merge-gpx/
