- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- KML ⇄ GPX

iPhone location journals

# Convert KML to GPX and GPX to KML in your browser

Drop in a KML, KMZ or GPX file and see exactly what was kept and what was skipped before you download. Your file never leaves this page.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 21, 2026

## Choose a KML, KMZ or GPX file. It stays on this device.

The direction is detected from the file’s content: KML or KMZ becomes GPX, and GPX becomes KML. You see a report of what was kept and what was skipped before you download anything. No account, upload or analytics. Files are read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API) and never leave the page.

JavaScript is needed to convert files in your browser. The explanations and sample downloads below work without it.

Choose a KML, KMZ or GPX file or drop it here · one file, up to 10 MiB and 50,000 points

Keep timestamps (gx:Track)For GPX → KML only. A segment whose points all have a time is written as a Google `gx:Track`, because a plain KML LineString cannot carry times. Turn this off for plain LineStrings that work everywhere, without times.

Try the synthetic KML exampleClear

Choose a file, or try the synthetic example without using any personal data.

### Conversion report:

### Checks

### Skipped, and why

Download converted file

Open result in the [GPX viewer](https://whereday.com/tools/gpx-viewer/): save the file first, then choose it there. The viewer reads GPX only, and it also stays on your device.

## What converts to what

KML is Google Earth’s format, standardised as [OGC KML 2.2](https://www.ogc.org/standard/kml/), with Google’s `gx:` extension for timed tracks. GPX 1.1 is the [Topografix](https://www.topografix.com/GPX/1/1/) schema for waypoints (`wpt`), routes (`rte`) and tracks (`trk`, made of `trkseg` segments). The converter reads and writes exactly these elements; everything else is listed in the report.

### KML or KMZ to GPX

| KML element | Becomes in GPX | Result
| `Point` | `<wpt>` with the Placemark’s name and description; a Placemark `TimeStamp` becomes its `<time>` | Kept
| `LineString` | One `<trk>` with one `<trkseg>` | Kept, without times
| `MultiGeometry` of LineStrings | One `<trk>` with one segment per LineString; Points inside become waypoints | Kept
| `gx:Track` | `<trkpt>` per `<gx:coord>`, with `<time>` (ISO 8601, UTC) from the matching `<when>`, and `<ele>` when an altitude is present | Kept, with times
| `gx:MultiTrack` | One `<trk>`, one segment per `gx:Track` | Kept, with times
| Coordinates `lon,lat,alt` | `lat="…" lon="…"` attributes, the right way round, and `<ele>` | Changed (order)
| `Polygon`, `LinearRing` | Nothing | Dropped, counted
| `Style`, `StyleMap`, icons | Nothing | Dropped, counted
| `NetworkLink`, `GroundOverlay`, `Model` | Nothing | Dropped, counted
| `ExtendedData`, `gx:angles` | Nothing | Dropped, counted
| `Folder` | Flattened; names are kept | Changed

### GPX to KML

| GPX element | Becomes in KML | Result
| `wpt` | A `Placemark` with a `Point`, name and description; its time goes in a `TimeStamp` | Kept
| `trkseg`, every point timed | A `gx:Track` with `<when>` and `<gx:coord>` pairs (several segments in one track become a `gx:MultiTrack`) | Kept, with times
| `trkseg`, any point without a time, or the checkbox off | A `LineString`; several segments become a `MultiGeometry` | Kept, times dropped (counted)
| `rte` | A `LineString` | Kept
| `ele` | The third coordinate, with `altitudeMode` set to `absolute` | Kept
| `sym`, `cmt`, `link`, `extensions`, other point fields | Nothing | Dropped, counted

The output is the same every time you convert the same file. The `xmlns:gx` namespace is declared only when a `gx:Track` is written. A segment with one point is written as a Point, because a LineString needs two.

## Worked example: a synthetic KML

This example is synthetic: the points are invented, near 0° N 10° E, and are not anyone’s recording. Try the synthetic KML example loads it. You can also download both files and choose the KML with the file picker:

- [synthetic-kml-example.kml](https://whereday.com/samples/synthetic-kml-example.kml): 2 Points, 1 LineString, 1 `gx:Track` of 5 timed points (with altitudes), and 1 Polygon.
- [synthetic-kml-example-expected.gpx](https://whereday.com/samples/synthetic-kml-example-expected.gpx): the GPX this page writes for it. The site’s tests check that the output matches this file exactly.
- [synthetic-kml-example-expected-back.kml](https://whereday.com/samples/synthetic-kml-example-expected-back.kml): the KML written when you convert that GPX back, with the timed segment as a `gx:Track`.

| KML in | Count | GPX out | Count
| Placemarks | 5 | Waypoints | 2
| Points | 2 | Tracks | 2
| LineStrings | 1 | Track segments | 2
| `gx:Track` | 1 | Track points | 8
| Points with a time | 5 | Points with a time | 5
| Points with an altitude | 6 | Points with elevation | 6
| Polygons | 1 | Skipped, listed in the report | 1

The timed track runs from 2000-01-01 12:00 to 12:04 UTC. The 3-point LineString has no times, and that is the one thing a plain LineString cannot give you. Convert the expected GPX back and you get a KML with the same coordinates, times and elevations, with the timed segment written as a `gx:Track`.

## Why times survive only through gx:Track and GPX <time>

A KML `LineString` is a list of coordinates and nothing else: there is nowhere to put one time per point. KML does let you attach a single `TimeStamp` or `TimeSpan` to a whole feature (see Google’s [time and animation documentation](https://developers.google.com/kml/documentation/time)), but that is one time for the whole line. Timed paths in Google Earth use the `gx:Track` extension instead: one `<when>` for each `<gx:coord>`, in order, documented in Google’s [KML reference](https://developers.google.com/kml/documentation/kmlreference#gxtrack). A GPX track point has a `<time>` of its own.

- KML to GPX: `gx:Track` times become `<time>` in UTC. Times must be full date-times with a zone (as in [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339), like `2000-01-01T12:00:00Z`); a time that is a date only or has no zone is not guessed at, and the point is written without a time.
- KML to GPX, plain LineString: the points have no times and the track has none. A LineString inside a Placemark that has a `TimeSpan` keeps the points but not the span, and the report says so.
- GPX to KML: with Keep timestamps (gx:Track) on, a fully timed segment keeps every time. A segment where even one point has no time becomes a LineString and loses all its times, counted in the report. Switch the checkbox off to get plain LineStrings.
- A program that does not understand `gx:Track` will not show those lines. Switch the checkbox off if your destination only reads standard KML 2.2.

## What is not converted, and the limits

- Polygons. GPX has no areas. They are listed in the report and not turned into tracks, so a boundary is never mistaken for a route.
- Styles and icons. Colours, line widths and icons are dropped; GPX has no styling. Waypoints keep their names and descriptions. Descriptions are copied as plain text, so HTML in a KML description appears as markup.
- NetworkLinks. They are never followed, so the converter makes no network request. Open the linked KML separately.
- ExtendedData, `gx:angles`, overlays and 3D models. Not copied, and counted.
- Altitude. KML altitudes are copied as `<ele>` as they are. KML’s default `altitudeMode` is `clampToGround`, where altitude is ignored, and many exporters write 0 there. When a file says its altitudeMode is not `absolute`, the report warns you; check elevation before relying on it.
- KMZ. A KMZ is a zip with `doc.kml` inside. It is unzipped in your browser with the browser’s built-in decompression, with no server and no library. Only the KML is read. Images and other files in the archive, such as custom icons, are ignored. Your browser needs `DecompressionStream` support (current Chrome, Edge, Safari and Firefox have it); if yours does not, unzip the KMZ and choose `doc.kml`.
- Size. One file, up to 10 MiB (and up to 10 MiB once a KMZ is unzipped) and 50,000 points, the same limits as the other tools.
- Invalid coordinates (missing, not a number, latitude beyond ±90, longitude beyond ±180, or a `gx:Track` with unequal numbers of times and coordinates) are rejected, counted in the report and never written.
- Not other formats. Google Timeline JSON, GeoJSON and CSV are not converted here. Use the [Timeline JSON inspector](https://whereday.com/tools/google-timeline-inspector/) or the [GPX viewer’s GeoJSON download](https://whereday.com/tools/gpx-viewer/#geojson-output).

## Check the result

Open the converted GPX in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) and compare its counts with the report. The viewer flags missing times, gaps and backwards timestamps; the [GPX checking tutorial](https://whereday.com/blog/check-gpx-file/) explains each. To combine several converted files, such as one per day, use [Merge GPX](https://whereday.com/tools/merge-gpx/). To see which countries a KML or GPX passes through, drop it into the [visited countries map](https://whereday.com/tools/visited-countries-map/); it reads KML Points and LineStrings but not `gx:Track`, so convert a track-based KML to GPX first. For when each format is the right one, read [GPX, KML or GeoJSON: what changes between the files](https://whereday.com/blog/gpx-kml-geojson-location-history/#differences).

The GPX this tool writes is checked against the official [GPX 1.1 XSD](https://www.topografix.com/GPX/1/1/gpx.xsd) in the site’s tests. The KML follows the KML 2.2 element order and uses Google’s `gx` namespace for timed tracks.

## Your file stays in this page

Reading, converting, the report and the download all happen in your browser. This page blocks network connections and does not load the website’s analytics. The web server receives an ordinary request when you open the page and its scripts, but not your file, its name or the result. Clear, or closing the tab, removes the file from the page; your original is never changed.

## Questions

### Is my file uploaded?

No. The page reads the file with your browser’s File API and builds the result in memory. Its security policy blocks network connections, so the page could not send your file even if it tried.

### Does it keep elevation?

Yes, where the file has it. A KML altitude (the third coordinate, or the third number of a `gx:coord`) becomes GPX `<ele>`, and GPX `<ele>` becomes the third coordinate with `altitudeMode` `absolute`. See the altitude note under [limits](https://whereday.com/tools/kml-to-gpx/#limits): KML files that clamp to the ground often hold zeros.

### Can I convert KMZ?

Yes, KMZ to GPX. The KMZ is unzipped in your browser and its KML is converted. Images and other files inside the archive are ignored. GPX to KMZ is not offered; the output is a plain KML.

### Why are my times missing?

Usually the KML holds a plain LineString, which has no per-point times, or the times are not complete date-times with a zone. Check the report’s “Points with a time” count. In the other direction, a GPX segment with even one untimed point becomes a LineString, and the report counts the times dropped.

### Does Garmin or Google Earth accept the output?

We have not tested the output in specific apps or devices, so we make no claim about them. It is standard GPX 1.1, checked against the GPX 1.1 schema, and KML 2.2 with the `gx` extension only where timestamps are kept. If your destination does not read `gx:Track`, turn Keep timestamps off.

### Does it change my original?

No. The converter reads a copy in memory and saves a new file with the same base name and the new extension.

## Sources

- [OGC KML 2.2 standard (OGC 07-147r2)](https://www.ogc.org/standard/kml/)
- [Google KML reference](https://developers.google.com/kml/documentation/kmlreference), including [`gx:Track` and `gx:MultiTrack`](https://developers.google.com/kml/documentation/kmlreference#gxtrack)
- [Google KML time and animation documentation](https://developers.google.com/kml/documentation/time)
- [GPX 1.1 schema documentation (Topografix)](https://www.topografix.com/GPX/1/1/)
- [RFC 3339: Date and Time on the Internet: Timestamps](https://www.rfc-editor.org/rfc/rfc3339)

Related: [record a route on iPhone and export GPX](https://whereday.com/blog/record-iphone-route-gpx/) and the [export and import reference](https://whereday.com/docs/export-import/). Whereday exports KML and GPX; its own import accepts supported Whereday files and supported Google Timeline JSON, not KML or GPX.

## Record routes once. Export them in the format you need.

Record routes once and export them as GPX or KML for any date range. Whereday keeps your location history on your iPhone.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Check the converted GPX](https://whereday.com/tools/gpx-viewer/)

Open it in the GPX viewer and compare points, segments and times with the report.

- [Merge several converted files](https://whereday.com/tools/merge-gpx/)

Combine daily or split GPX files into one without uploading them.

- [Choose between GPX, KML and GeoJSON](https://whereday.com/blog/gpx-kml-geojson-location-history/)

Compare what each format keeps, using one invented trip.

Source: https://whereday.com/tools/kml-to-gpx/
