- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- KML ⇄ GPX

iPhone location journals

# Convert KML to GPX, or GPX to KML, in your browser

See what was kept and what was dropped before you download. Google Earth gx:Track timestamps become GPX times, and GPX times come back out as gx:Track.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 22, 2026

## Choose a KML or GPX file. It is converted on this device.

The direction is detected from the file’s content, not its name. KML becomes GPX 1.1; GPX becomes KML 2.2. You see a report of what was kept, changed and skipped before you download. No account, upload or analytics. The file is read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API) and never leaves the page. Have a KMZ? [Open it in the KML/KMZ viewer](https://whereday.com/tools/kml-viewer/).

JavaScript is needed to convert files in your browser. The explanations and sample downloads below work without it.

Choose a KML or GPX file or drop it here · one file, up to 10 MiB and 50,000 points

Keep timestamps (gx:Track)Applies to GPX → KML. A segment where every point has a time becomes a gx:Track; a plain KML LineString cannot carry time.

Try the sample KMLTry the sample GPXClear

Choose a file, or try one of the synthetic samples first.

### Conversion report

#### In your file

#### In the result

### Skipped or changed

Download converted file

Next: open the downloaded GPX in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) to check it.

On this page
- [What converts](https://whereday.com/tools/kml-to-gpx/#what-converts)
- [Worked example](https://whereday.com/tools/kml-to-gpx/#example)
- [Timestamps](https://whereday.com/tools/kml-to-gpx/#timestamps)
- [Limits](https://whereday.com/tools/kml-to-gpx/#limits)
- [Check the result](https://whereday.com/tools/kml-to-gpx/#check)
- [Questions](https://whereday.com/tools/kml-to-gpx/#faq)
- [Sources](https://whereday.com/tools/kml-to-gpx/#sources)

## What converts to what

KML stores a coordinate as `longitude,latitude[,altitude]`. GPX writes latitude and longitude as separate attributes, so the tool swaps them for you and keeps the digits exactly as written. Positions with missing, non-numeric or out-of-range coordinates are left out and counted.

| KML | GPX | Result
| Placemark with a `Point` | `<wpt>` with name, description, `<ele>` | Kept
| `LineString` | `<trk>` with one `<trkseg>` | Kept; altitude becomes `<ele>`
| `MultiGeometry` of LineStrings | One `<trk>`, several `<trkseg>` | Kept
| `gx:Track` | `<trkseg>` of `<trkpt>` with `<time>` and `<ele>` | Kept, times written in UTC
| `gx:MultiTrack` | One `<trk>`, one segment per `gx:Track` | Kept
| `Polygon`, `LinearRing`, `Model` | None | Dropped and reported, never turned into a track
| Folders, styles, icons, ExtendedData, NetworkLink, overlays, TimeStamp/TimeSpan on features | None | Dropped and reported

| GPX | KML | Result
| `<wpt>` | Placemark with a `Point` | Kept; the waypoint’s time is not carried
| `<trkseg>`, every point has a time | `gx:Track` (or `gx:MultiTrack` for several segments) | Kept with times, when “Keep timestamps” is on
| `<trkseg>` otherwise | `LineString` (or `MultiGeometry`) | Changed: times are lost
| `<rte>` | `LineString` | Changed: times are lost
| Segments or routes with one point | None | Dropped and reported (a LineString needs two positions)
| Extensions, links, comments, symbols, accuracy fields | None | Dropped and counted

KML output declares the `gx` namespace only when a `gx:Track` is written. A line gets altitudes only if every position in it has one, so no elevation is invented. The same input always gives a byte-identical file, named like the input with the new extension.

## Worked example: a synthetic file

These files are invented, near 0° N 10° E, and are not anyone’s recording. [synthetic-kml-to-gpx.kml](https://whereday.com/samples/synthetic-kml-to-gpx.kml) has 2 Points, 1 LineString, 1 `gx:Track` of 5 timed points and 1 Polygon. [synthetic-kml-to-gpx.expected.gpx](https://whereday.com/samples/synthetic-kml-to-gpx.expected.gpx) is the expected result, and [synthetic-kml-to-gpx.expected.kml](https://whereday.com/samples/synthetic-kml-to-gpx.expected.kml) is what that GPX converts back to. The buttons above load the same samples.

| Measure | KML in | GPX out
| Placemarks | 5 | –
| Points → waypoints | 2 | 2
| LineString + gx:Track → tracks (one segment each) | 2 | 2 tracks, 2 segments
| Track points | 3 + 5 | 8
| Positions with a time | 5 | 5 (2000-01-01 12:00 to 12:04 UTC)
| Positions with elevation | 9 | 9
| Polygons | 1 | Skipped, reported

## Why times survive only through gx:Track and GPX <time>

A GPX `<trkpt>` can carry its own `<time>`. A plain KML `LineString` is only a list of coordinates, with no place for a time per point. Google’s `gx:Track` extension fixes that by pairing each `<when>` with a `<gx:coord>`. So GPX → KML keeps times only as gx:Track, and a LineString (or a GPX route) loses them. The report shows how many timestamps went in and came out, and its first and last time.

Times are written as UTC date-times ([RFC 3339](https://www.rfc-editor.org/rfc/rfc3339) style). A time without a time zone, or a date with no time, is not guessed: the position is kept without a time and the report counts it. Not every KML consumer reads gx:Track; if yours doesn’t, turn the checkbox off to get plain LineStrings.

## Limits

- One file, up to 10 MiB and 50,000 points, the same limits as the [GPX viewer](https://whereday.com/tools/gpx-viewer/).
- KMZ is not supported. A KMZ is a zip with a `doc.kml` inside; this page has no unzip step. Have a KMZ? [Open it in the KML/KMZ viewer](https://whereday.com/tools/kml-viewer/), or unzip it yourself and choose the KML. Images inside a KMZ are never converted. Need a CSV instead? [Convert KML or KMZ to CSV](https://whereday.com/tools/kml-to-csv/), which reads KMZ directly.
- Polygons are skipped and reported; GPX has no area shape.
- Styles, icons, ExtendedData, folders and overlays are dropped.
- NetworkLinks are not followed; this page makes no network requests.
- Altitudes are copied as written. KML altitude modes are not interpreted.
- Files with a DOCTYPE or entity declaration are refused.

## Check the result

Open the converted GPX in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) and compare its counts with the report; the [GPX checking tutorial](https://whereday.com/blog/check-gpx-file/) explains each warning. Several converted files can be joined with [Merge GPX](https://whereday.com/tools/merge-gpx/). To see which countries a file covers, use the [visited countries map](https://whereday.com/tools/visited-countries-map/). Want it on Google Maps? See [how to open a GPX file in Google Maps](https://whereday.com/blog/gpx-to-google-maps/). Choosing between formats? Read [how GPX, KML, GeoJSON and CSV differ](https://whereday.com/blog/gpx-kml-geojson-location-history/#differences).

## Questions

### Is my file uploaded?

No. Parsing and writing happen in this page. Its content security policy blocks network connections and the page loads no analytics. The server sees an ordinary request for the page and its scripts, not your file.

### Does it keep elevation?

Yes. KML altitude becomes GPX `<ele>` and back, as written. When only some points in a KML line have an altitude, that line is written without altitudes.

### Can I convert KMZ?

Not directly. Have a KMZ? [Open it in the KML/KMZ viewer](https://whereday.com/tools/kml-viewer/), or unzip it and choose the `doc.kml` inside.

### Why are my times missing?

Plain KML LineStrings and GPX routes cannot carry per-point times, and KML Placemark points here are not given times. Only `gx:Track` and GPX track points do. Check that “Keep timestamps (gx:Track)” is on, and read the report for times without a time zone.

### Does Garmin or Google Earth accept the output?

We haven’t tested specific apps or devices. The GPX is checked against the GPX 1.1 schema in the site’s tests, and the KML is standard KML 2.2 with the `gx` extension only where used.

### Does Whereday import KML?

No. Whereday exports KML and GPX; its import is limited to supported Google Timeline JSON.

## Sources

- [OGC KML 2.2 standard (OGC 07-147r2)](https://www.ogc.org/standard/kml/)
- [Google KML reference](https://developers.google.com/kml/documentation/kmlreference), which defines `gx:Track` and `gx:MultiTrack`
- [Google: time and animation in KML](https://developers.google.com/kml/documentation/time)
- [GPX 1.1 schema documentation (Topografix)](https://www.topografix.com/GPX/1/1/)
- [RFC 3339: date and time on the Internet](https://www.rfc-editor.org/rfc/rfc3339)

Export options in Whereday are described in the [export and import reference](https://whereday.com/docs/export-import/); the [route recording guide](https://whereday.com/blog/record-iphone-route-gpx/) shows how to record and export a GPX.

## Record routes once, export them anywhere.

Record routes once and export them as GPX or KML for any date range. Whereday keeps your location history on your iPhone.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Check the converted GPX](https://whereday.com/tools/gpx-viewer/)

Open it in the GPX viewer and compare points, segments and times.

- [Merge several converted files](https://whereday.com/tools/merge-gpx/)

Combine GPX files into one, without uploading them.

- [Choose between GPX, KML, GeoJSON and CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/)

See what each format keeps, with the same invented trip.

- [Put it on Google Maps](https://whereday.com/blog/gpx-to-google-maps/)

Import the GPX into My Maps on a computer, then view it on iPhone.

Source: https://whereday.com/tools/kml-to-gpx/
