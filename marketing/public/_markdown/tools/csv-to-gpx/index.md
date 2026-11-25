- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- CSV to GPX

iPhone location journals

# Convert CSV to GPX, or GPX to CSV, in your browser

Convert a CSV of coordinates to a GPX file, or a GPX file to CSV, in your browser. Nothing is uploaded.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 25, 2026

## Add a CSV or GPX file. It stays on this device.

Convert a CSV of coordinates to a GPX file, or a GPX file to CSV, in your browser. Nothing is uploaded. Add a `.csv` or `.txt` file, or paste the rows, to get a GPX file. Add a `.gpx` file to get a CSV. No account and no analytics. Files are read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API) and never leave the page.

JavaScript is needed to read and convert files in your browser. The explanations and the sample downloads below work without it.

Choose a CSV, TXT or GPX file or drop it here · up to 10 MiB and 50,000 rows  Or paste the rows here (include the header row)

Try a synthetic exampleTry a synthetic GPX (GPX to CSV)Clear

Add a file, or try the synthetic example first. It is invented and contains no personal data.

1. Columns

The tool picks columns by header name (`lat`, `lon`, `time` and the others listed under [what your CSV needs](https://whereday.com/tools/csv-to-gpx/#columns)). Change any of them here. Latitude and longitude are required and must be decimal degrees.

Latitude

Longitude

Time (optional)

Elevation in metres (optional)

Name (optional)

Group or segment (optional)

SeparatorDetect (comma, semicolon or tab)CommaSemicolonTab

First rowDetectIs a headerIs a point

2. Track or waypoints

A track is one recorded line through the rows in file order. Waypoints are separate named places with no line between them. [Track, route and waypoint explained](https://whereday.com/blog/what-is-a-gpx-file/). A time column selects Track by default; without one the default is Waypoints.

TrackRows become `<trkpt>` points in `<trk>` and `<trkseg>`. A new segment starts where the group column changes. WaypointsEach row becomes a `<wpt>` with a `<name>` (the name column, or “Point 1”, “Point 2” and so on). As in the fileThis CSV has a `kind` column, like the one the [GPX viewer](https://whereday.com/tools/gpx-viewer/) exports. Rows marked track, route or waypoint come back as those, with segments from the group column. File and track name

### Summary and preview

A local plot with no street tiles, so nothing is fetched. North is up. Separate segments alternate between two colours; single dots are waypoints.

Download GPX

### GPX to CSV

The CSV has the same columns as the [GPX viewer](https://whereday.com/tools/gpx-viewer/) download: `kind, group, point, latitude, longitude, time_utc, elevation_m, name, original_time`. Text that a spreadsheet could read as a formula gets a leading apostrophe.

Download CSV

## What your CSV needs

One row per point, a header row, and two columns that hold decimal degrees: latitude from −90 to 90 and longitude from −180 to 180. Everything else is optional. Headers are matched without regard to case; the first name in each list that your file has is used, and you can pick another column in the dropdowns.

| Field | Header names recognised | Written to GPX as
| Latitude (required) | `lat`, `latitude`, `y` | `lat` attribute
| Longitude (required) | `lon`, `lng`, `long`, `longitude`, `x` | `lon` attribute
| Time | `time`, `timestamp`, `time_utc`, `date`, `datetime` | `<time>`, in UTC
| Elevation | `ele`, `elevation`, `elevation_m`, `alt`, `altitude` | `<ele>`, metres
| Name | `name`, `title`, `label` | `<name>`, XML-escaped
| Group or segment | `group`, `segment`, `track` | A new `<trkseg>` when the value changes

Separators can be commas, semicolons or tabs. In a file that uses semicolons or tabs, a comma is read as a decimal mark, so `52,5` means 52.5. A file with no header row is read as latitude, then longitude. Quoted fields follow [RFC 4180](https://www.rfc-editor.org/rfc/rfc4180). Row numbers in the skipped-row list count the header as row 1.

### Worked example: synthetic data

This is synthetic, invented data, not anyone’s trip. [synthetic-format-trip.csv](https://whereday.com/samples/synthetic-format-trip.csv) has five rows in the GPX viewer’s CSV layout. The first point is latitude 0 and longitude 10, so a swapped pair would show up at once as latitude 10, longitude 0.

```
kind,group,point,latitude,longitude,time_utc,elevation_m,name,original_time
"track",1,1,0,10,"2000-01-01T12:00:00.000Z",,"","2000-01-01T12:00:00Z"
"track",1,2,0.001,10.001,"2000-01-01T12:01:00.000Z",,"","2000-01-01T12:01:00Z"
"track",1,3,0.002,10.002,"2000-01-01T12:02:00.000Z",,"","2000-01-01T12:02:00Z"
"track",2,1,0.003,10.003,"2000-01-01T12:13:00.000Z",,"","2000-01-01T12:13:00Z"
"track",2,2,0.004,10.004,"2000-01-01T12:14:00.000Z",,"","2000-01-01T12:14:00Z"
```

Click Try a synthetic example and the tool detects `latitude`, `longitude`, `time_utc` and `group`, and writes 5 points in 1 track with 2 segments, 0 rows skipped, from 2000-01-01T12:00:00Z to 2000-01-01T12:14:00Z. Segment 1 has 3 points and segment 2 has 2, because the group value changes from 1 to 2 after the third row. The file name here is `synthetic-format-trip`; the output is:

```
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Whereday CSV to GPX (whereday.com/tools/csv-to-gpx/)" xmlns="http://www.topografix.com/GPX/1/1">
<metadata><name>synthetic-format-trip</name></metadata>
<trk>
<name>synthetic-format-trip</name>
<trkseg>
<trkpt lat="0" lon="10"><time>2000-01-01T12:00:00Z</time></trkpt>
<trkpt lat="0.001" lon="10.001"><time>2000-01-01T12:01:00Z</time></trkpt>
<trkpt lat="0.002" lon="10.002"><time>2000-01-01T12:02:00Z</time></trkpt>
</trkseg>
<trkseg>
<trkpt lat="0.003" lon="10.003"><time>2000-01-01T12:13:00Z</time></trkpt>
<trkpt lat="0.004" lon="10.004"><time>2000-01-01T12:14:00Z</time></trkpt>
</trkseg>
</trk>
</gpx>
```

The same points are in [synthetic-format-trip.gpx](https://whereday.com/samples/synthetic-format-trip.gpx). The site’s tests convert the sample, check the result against the official GPX 1.1 schema, and compare it point by point with that GPX.

A smaller file works too. A CSV with only `lat,lon` columns becomes waypoints named Point 1, Point 2 and so on. Add a `time` column and the default becomes a track.

## Track or waypoints?

Choose Track when the rows are one journey in order, such as a GPS logger or phone recording. The points are joined into a line, and the times say when each was visited. Choose Waypoints when the rows are places, such as a list of addresses, campsites or photo locations. They are drawn as separate pins, with no line between them and no order implied.

GPX has a third kind, the route, which is a planned path. This tool writes a route only when it reads a CSV that was exported from a GPX file and has a `kind` column marking rows as route. The [GPX file guide](https://whereday.com/blog/what-is-a-gpx-file/) explains all three. The [GPX 1.1 documentation](https://www.topografix.com/GPX/1/1/) defines them: a track is an ordered list of points describing a path, divided into segments, and a waypoint is a point of interest. How a particular app draws or lists each one is up to that app, so open the result in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) to check it, then try your destination app with a small file first.

Segments. If your rows come from several recordings, add a group column (`group`, `segment` or `track`). A new segment starts wherever its value changes from one row to the next, so the line does not run from the end of one recording to the start of the next. Values do not need to be numbers, and a value that comes back later starts another segment. Without a group column, all rows form one segment.

## Time zones: why a bare time is a problem

GPX stores times in UTC; the [GPX 1.1 schema](https://www.topografix.com/GPX/1/1/) defines them as a date and time with a `Z` or offset. A CSV from a spreadsheet often has a time such as `2000-01-01 12:00:00` with no zone. That could be UTC, the logger’s local time or the time zone of your computer, and the file does not say.

- With a zone (`Z`, or an offset such as `+02:00`), the time is converted to UTC and written as, for example, `2000-01-01T10:00:00Z`. The formats follow [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339), a profile of ISO 8601.
- Without a zone, the tool treats the time as UTC and shows a warning with the number of times affected. If your times were local, every one of them will be off by your UTC offset. Add the offset to the column in your spreadsheet and convert again.
- Times it cannot read (for example `1/2/2000 10:00`, a date with no time of day, or a day that does not exist) are left out of that point. The point is kept, and the summary counts them.

The tool does not guess a time zone from the coordinates or from your browser, and it does not shift times for daylight saving.

## GPX to CSV

Add a `.gpx` file in the box above, or press Try a synthetic GPX (GPX to CSV). The tool reads GPX 1.0 and 1.1 with the same code as the [GPX viewer](https://whereday.com/tools/gpx-viewer/) and downloads the same CSV. The columns are:

- `kind`: track, route or waypoint
- `group`: the number of the segment, route or waypoint the point belongs to
- `point`: the point’s number within its group
- `latitude`, `longitude`: decimal degrees
- `time_utc`: the time converted to UTC, blank when missing or unusable
- `elevation_m`: elevation in metres, blank when missing
- `name` and `original_time`: the point’s name and its time exactly as written in the file

Names and times that start with `=`, `+`, `-` or `@` get a leading apostrophe so that a spreadsheet shows them as text rather than running them as a formula. When you convert that CSV back to GPX, a leading apostrophe in front of one of those characters is removed. Points with unusable coordinates are left out. In Excel, open the CSV through Data → From Text/CSV if you want to see the raw text of the time columns before the program reformats them. Microsoft’s [text and CSV import guide](https://support.microsoft.com/en-us/excel/get-started/import-or-export-text-txt-or-csv-files) describes it.

Converting GPX to CSV and back keeps the points, their order and the segment boundaries. The test suite checks this with a file that has a waypoint, a route and several track segments. It does not keep anything else in the GPX, such as extensions.

## Limits

- Decimal degrees only. Values like `52°31′12″N` or `52 31.2 N` are not converted, and those rows are skipped. Convert them to decimal degrees in your spreadsheet first. Scientific notation such as `1.2E+01` is not read either.
- One file at a time, up to 10 MiB and 50,000 rows, the same limits as the other tools here.
- No route reconstruction. Points are written in file order and are not snapped to roads or paths. Sort rows by time in the spreadsheet if the order matters.
- Not lossless. Only the columns above are written. Other columns, and GPX extensions or custom fields in a GPX file, are not carried over in either direction.
- Longitude 180 is written as −180, which is the same place and the form the GPX 1.1 schema accepts.
- Browsers. It needs the File API in a desktop or mobile browser. We have not tested it in every browser, so we don’t promise one.

The output is checked against the official [GPX 1.1 schema](https://www.topografix.com/GPX/1/1/gpx.xsd) in the site’s tests. That shows the file is well-formed GPX. It does not show that your coordinates are right, so check the plot.

## Open the GPX next

- [GPX viewer](https://whereday.com/tools/gpx-viewer/): check points, segments and times.
- [GPX editor](https://whereday.com/tools/gpx-editor/): trim, split by day or hide your home.
- [Merge GPX files](https://whereday.com/tools/merge-gpx/): join several converted files.
- [KML to GPX](https://whereday.com/tools/kml-to-gpx/): for files that are KML rather than CSV.
- [Open a GPX file in Google Maps](https://whereday.com/blog/gpx-to-google-maps/)
- [What is a GPX file?](https://whereday.com/blog/what-is-a-gpx-file/)
- [Opening a GPX-derived CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/#spreadsheet): the CSV checklist.
- [Record an iPhone route as GPX](https://whereday.com/blog/record-iphone-route-gpx/)

## Your files stay in this page

Reading, converting, the plot and the download all happen in your browser. The plot is drawn locally with no map tiles. This page blocks network connections and does not load the website’s analytics, and it stores nothing in your browser. The web server sees an ordinary request for the page and its scripts, not your file, its name or the result.

## Questions

### Can Excel save a GPX file?

No. Excel has no GPX option. Save the sheet as CSV (File → Save As → CSV), then convert the CSV here.

### Does this upload my file?

No. The file is read and converted in your browser. This page blocks network connections, so it cannot send the file anywhere.

### Why are some rows skipped?

A row is skipped when its latitude or longitude is empty, is not a decimal number, or is out of range (latitude beyond ±90, longitude beyond ±180). The summary lists how many rows were skipped for each reason and the first row numbers, counting the header as row 1. A common cause is degrees-minutes-seconds text, or latitude and longitude in the wrong columns.

### Can I convert GPX to Excel?

Convert it to CSV here, then open the CSV in Excel or any spreadsheet program. Use Data → From Text/CSV if you want control over how the columns are read.

## Want a GPS log you don’t have to build in a spreadsheet?

Whereday records your visits and routes on your iPhone and exports GPX, CSV and GeoJSON.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Check the converted GPX](https://whereday.com/tools/gpx-viewer/)

Open it in the GPX viewer and compare points, segments and times.

- [Trim or split the result](https://whereday.com/tools/gpx-editor/)

Cut the ends, split by day or hide your home, without uploading it.

- [Merge several GPX files](https://whereday.com/tools/merge-gpx/)

Join converted files into one, without uploading them.

- [Record your next route](https://whereday.com/blog/record-iphone-route-gpx/)

Record in Whereday and export points as GPX.

Source: https://whereday.com/tools/csv-to-gpx/
