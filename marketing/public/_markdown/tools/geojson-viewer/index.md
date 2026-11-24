- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- GeoJSON viewer

iPhone location journals

# Open a GeoJSON file in your browser

See what is inside a GeoJSON file without uploading it, with an honest list of anything skipped, then save GPX or KML and see exactly what each export drops.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 24, 2026

## Choose a GeoJSON file. It is opened on this device.

See how many features of each geometry type are inside, which times were found, and a plain list of anything skipped. Then save the points and lines as GPX or KML. No account, upload or analytics. The file is read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API) and never leaves the page.

Not GeoJSON? A Google Timeline `.json` export needs the [Timeline JSON inspector](https://whereday.com/tools/google-timeline-inspector/) instead.

JavaScript is needed to open files in your browser. The explanations and the sample download below work without it.

Choose a .geojson or .json file or drop it here · one file, up to 10 MiB and 50,000 positions

Or paste GeoJSON text

Open pasted textTry a synthetic exampleClear

Choose a file, paste text, or try the synthetic example first.

### File

### Feature table

Names and properties are shown as plain text. Coordinates are longitude,latitude[,altitude], as in GeoJSON. Times are UTC, or “unknown” when none could be read.

| Name | Geometry | Positions | First → last time | Bounding box (west,south to east,north)

### Skipped

Content found in the file that is not drawn, with the reason. Nothing is dropped silently.

### Convert

Download GPXDownload KML

#### GPX

#### KML

### Next steps

[Check the GPX in the GPX viewer](https://whereday.com/tools/gpx-viewer/) · [Check the KML in the KML viewer](https://whereday.com/tools/kml-viewer/) · [Merge GPX files](https://whereday.com/tools/merge-gpx/)

On this page
- [What is GeoJSON?](https://whereday.com/tools/geojson-viewer/#what-is-geojson)
- [How to open one](https://whereday.com/tools/geojson-viewer/#how-to-open)
- [What it shows](https://whereday.com/tools/geojson-viewer/#what-it-shows)
- [Synthetic example](https://whereday.com/tools/geojson-viewer/#example)
- [Convert to GPX or KML](https://whereday.com/tools/geojson-viewer/#convert)
- [Common problems](https://whereday.com/tools/geojson-viewer/#problems)
- [Limits](https://whereday.com/tools/geojson-viewer/#limits)
- [Privacy](https://whereday.com/tools/geojson-viewer/#privacy)
- [Make your own](https://whereday.com/tools/geojson-viewer/#make-your-own)
- [Sources](https://whereday.com/tools/geojson-viewer/#sources)

## What is a GeoJSON file?

GeoJSON is a JSON format for geographic data, defined in [RFC 7946](https://www.rfc-editor.org/rfc/rfc7946). A file holds one GeoJSON object: a FeatureCollection (a list of features), a single Feature (a geometry plus a `properties` object such as a name), or a bare Geometry. The geometry types are Point, MultiPoint, LineString, MultiLineString, Polygon, MultiPolygon and GeometryCollection (RFC 7946 §3.1).

A position is `[longitude, latitude]`, optionally followed by an altitude: longitude first (§3.1.1). RFC 7946 uses the WGS 84 coordinate system only and removed the older `crs` member (§4). Files often use `.geojson` or `.json`; the extension does not change the content.

For how GeoJSON compares with GPX, KML and CSV, see [which format to choose](https://whereday.com/blog/gpx-kml-geojson-location-history/).

## How to open a GeoJSON file

- This page. Choose the file or paste its text above. Nothing is uploaded. It draws shapes only, not a map, and lists what it skips.
- QGIS (desktop). The [QGIS manual](https://docs.qgis.org/latest/en/docs/user_manual/managing_data_source/opening_data.html) lists GeoJSON among the vector formats it opens. Use Layer ► Add Layer ► Add Vector Layer, choose File, browse to the file and press Add, or drag the file into the Layers panel.
- iPhone Files app. We have not checked whether the Files preview draws GeoJSON on a map, so we make no claim about it; expect it to show the text at most. Use a computer for this page’s tested route.

## What the viewer shows and what it skips

| Geometry type | Drawn | GPX | KML
| `Point`, `MultiPoint` | Dots | Waypoints | Point (MultiGeometry for several)
| `LineString` | Line | One track, one segment | LineString, or gx:Track when every position has a time
| `MultiLineString` | Lines | One track, one segment per part | MultiGeometry of LineStrings, or gx:MultiTrack when all parts are timed
| `Polygon`, `MultiPolygon` | Outlines; holes dashed; not filled | Not exported; counted in the summary only | Polygon with its inner rings
| `GeometryCollection` | Each member drawn | Points and lines inside; polygons counted only | One Placemark with a MultiGeometry
| `null` geometry | Nothing; listed as “No geometry” | Nothing | Nothing

The Skipped panel counts and explains: entries that are not Features or have a missing `type`, null or unknown geometries, empty or too-short lines and rings, invalid positions (and how many would fit if latitude came first, or look like projected metres), 3D positions drawn in 2D, `crs` members, and time values that could not be read.

Times. The viewer looks for `time`, `timestamp` or `datetime` in a feature’s properties, and for a `coordTimes` or `times` array (also `coordinateProperties.times`). Only date-times with a time zone, such as `2020-05-01T10:00:00Z`, are read. A number, or a date with no zone, is shown as “unknown” and is never guessed. Per-position times are used in exports only when the array has exactly one entry per position.

## Synthetic example

Invented data near 0° N 10° E, not anyone’s recording: [synthetic-geojson-viewer.geojson](https://whereday.com/samples/synthetic-geojson-viewer.geojson). The button above loads it. Expected result:

- Viewer: 4 features (2 Points, 1 MultiPoint of 3 positions, 1 MultiLineString of two parts), 10 positions, 6 readable times, first `2000-01-01T12:00:00Z`, last `2000-01-01T12:21:00Z`. “Camp” and “Cairns” have no time and show “unknown”. The one 3D position is listed under Skipped.
- GPX: 5 waypoints, 1 track with 2 segments and 5 track points; 6 positions with a time. The extra `note` property is dropped.
- KML: 4 Placemarks: 5 Points (3 of them in one MultiGeometry) and 1 gx:MultiTrack of 2 gx:Tracks; 6 times written.

## Convert GeoJSON to GPX or KML

- GPX 1.1: points become waypoints. A LineString becomes a track with one segment; a MultiLineString becomes one track with a segment per part. A line that jumps more than 180° of longitude is cut there into separate segments (RFC 7946 §3.1.9 describes cutting at the antimeridian); no point is invented at the cut.
- KML 2.2: points, lines and polygons become Placemark geometries. Lines whose every position has a time become Google’s `gx:Track`; a single timed Point gets a `TimeStamp`.
- What is lost: polygons never go into GPX (GPX 1.1 has waypoints, routes and tracks only), every property except `name` and time is dropped, and altitude is written only where it is present (in KML, only when every position in the line has one). Times that cannot be attached to a position are not written. The page lists exact counts for your file before you download.

Check the output in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) or [KML viewer](https://whereday.com/tools/kml-viewer/), convert KML or GPX with the [KML ⇄ GPX converter](https://whereday.com/tools/kml-to-gpx/), or combine GPX results with [Merge GPX](https://whereday.com/tools/merge-gpx/). For the GPX format itself, read [what a GPX file is](https://whereday.com/blog/what-is-a-gpx-file/).

## Common problems

- Latitude and longitude swapped. GeoJSON is longitude first. A point written latitude first lands in the wrong place, or its second number is beyond ±90 and it is skipped. The Skipped panel counts positions that would fit if swapped, but the viewer never swaps them for you.
- Projected coordinates. Values like `500000, 4649776` are far outside ±180 and ±90 and are probably metres in a projected system. RFC 7946 allows only WGS 84 degrees, so they are skipped and counted, not converted.
- Very large files. The page reads one file of up to 10 MiB and 50,000 positions. Split or simplify bigger files first.
- A `.json` that is not GeoJSON. If the top-level object has no GeoJSON `type`, the page says so. Google Timeline exports are a different structure: open them in the [Timeline JSON inspector](https://whereday.com/tools/google-timeline-inspector/).

## Limits

- One file or pasted text, up to 10 MiB and 50,000 positions, in line with the other tools here.
- Newline-delimited GeoJSON (one object per line, `.geojsonl` or `.ndjson`) is read when the text is not a single JSON value.
- No styling. Simplestyle properties such as colour and marker symbols are ignored, and nothing is filled.
- No editing, no tiles. The plot is a simple local projection with no map, streets or labels.
- No reprojection. Positions are read as WGS 84 longitude, latitude. Polygons that cross the antimeridian are drawn as written.
- A plot draws up to 5,000 shapes and the table lists up to 500 features; counts and exports cover the whole file.

## Privacy: your file stays in the browser

Parsing, plotting and conversion all happen in this page. Its content security policy blocks network connections, the page loads no analytics and no map tiles, and property values are inserted as text, not HTML. The web server sees an ordinary request for the page and its scripts, not your file or pasted text. Following a link leaves this page. Clear removes the result from the page and never changes your original file.

## Make your own GeoJSON of where you’ve been

Whereday keeps your location history on your iPhone and exports GeoJSON, GPX, KML, CSV and more; see the [export and import reference](https://whereday.com/docs/export-import/). Browse all the [location file tools](https://whereday.com/tools/).

## Make your own GeoJSON of where you’ve been.

Whereday keeps your location history on your iPhone and exports it in the format you need.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Sources

- [RFC 7946, The GeoJSON Format](https://www.rfc-editor.org/rfc/rfc7946) (geometry types §3.1, position order §3.1.1, antimeridian §3.1.9, `crs` removed §4)
- [GPX 1.1 schema documentation](https://www.topografix.com/GPX/1/1/)
- [OGC KML 2.2 standard](https://www.ogc.org/standard/kml/) and Google’s [gx:Track reference](https://developers.google.com/kml/documentation/kmlreference#gxtrack)
- [QGIS manual: opening data](https://docs.qgis.org/latest/en/docs/user_manual/managing_data_source/opening_data.html)
- [Whereday export and import reference](https://whereday.com/docs/export-import/)

## Read next

- [Check the GPX you saved](https://whereday.com/tools/gpx-viewer/)

Open the exported GPX and compare points, segments and times.

- [Check the KML you saved](https://whereday.com/tools/kml-viewer/)

Open the exported KML and see its placemarks and tracks.

- [Choose between GPX, KML, GeoJSON and CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/)

See what each format keeps, with the same invented trip.

Source: https://whereday.com/tools/geojson-viewer/
