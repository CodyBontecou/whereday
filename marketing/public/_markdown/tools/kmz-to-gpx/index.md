- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- KMZ to GPX

iPhone location journals

# Convert KMZ to GPX, or GPX to KMZ, in your browser

Drop a KMZ, KML or GPX file. A report shows what was converted and what was left out, and the file never leaves this device.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 27, 2026

## Drop a KMZ, KML or GPX file. It stays on this device.

A KMZ becomes a GPX 1.1 file. A GPX becomes a KMZ, with a plain KML as a second download. The direction comes from the file’s content, not its name: a zip signature means KMZ, and a `<gpx>` root means GPX. A plain `.kml` is converted to GPX too. Before you download, a report lists which file inside the KMZ was read, what was converted and what was left out. No account, upload or analytics. The file is read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API), and a KMZ is unzipped with the browser’s built-in [DecompressionStream](https://developer.mozilla.org/en-US/docs/Web/API/DecompressionStream).

JavaScript is needed to convert files in your browser. The explanations and sample downloads below work without it.

Choose a KMZ, KML or GPX file or drop it here · one file, up to 10 MiB and 50,000 coordinates

Keep timestamps (gx:Track)Applies to GPX → KMZ. A segment where every point has a time becomes a gx:Track; a plain KML LineString cannot carry time.

Try the synthetic KMZTry the synthetic GPXClear

Choose a file, or try one of the synthetic samples first. They are invented and contain no personal data.

### Conversion report

#### Inside the KMZ

#### In your file

#### In the result

### Skipped or changed

Next: open the downloaded GPX in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) to check it.

Next: open the KMZ in the [KML and KMZ viewer](https://whereday.com/tools/kml-viewer/) to check its placemarks and lines.

On this page
- [Synthetic samples](https://whereday.com/tools/kmz-to-gpx/#samples)
- [What a KMZ is](https://whereday.com/tools/kmz-to-gpx/#what-is-kmz)
- [What is kept](https://whereday.com/tools/kmz-to-gpx/#kept)
- [Using the result](https://whereday.com/tools/kmz-to-gpx/#google-earth)
- [Limits](https://whereday.com/tools/kmz-to-gpx/#limits)
- [File privacy](https://whereday.com/tools/kmz-to-gpx/#file-privacy)
- [Questions](https://whereday.com/tools/kmz-to-gpx/#faq)
- [Sources](https://whereday.com/tools/kmz-to-gpx/#sources)

## Synthetic samples and the counts to expect

These files are synthetic, invented data near 0° N 10° E. They are not anyone’s recording. The buttons above load the same files. The numbers below are checked against the tool’s real output in the site’s tests.

[synthetic-kmz-to-gpx.kmz](https://whereday.com/samples/synthetic-kmz-to-gpx.kmz) holds `doc.kml` and `files/legend.png`. The KML has two Point placemarks, one LineString and one `gx:Track` with four `<when>` times. The same KML on its own is [synthetic-kmz-to-gpx.kml](https://whereday.com/samples/synthetic-kmz-to-gpx.kml).

| Report line | Expected
| KML file read | `doc.kml`
| Other KML files ignored | 0
| Images ignored | 1 (`files/legend.png`)
| Placemarks read | 4
| Placemarks with a Point → waypoints | 2
| LineString + gx:Track → tracks | 2 tracks, 2 segments, 7 track points
| Positions with a time | 4 (2000-01-01 12:00 to 12:03 UTC)
| Skipped KML elements | None

[synthetic-gpx-to-kmz.gpx](https://whereday.com/samples/synthetic-gpx-to-kmz.gpx) has two waypoints and one track with two segments, every point timed (3 and 2 points).

| Report line | Expected
| Waypoints → Placemarks with a Point | 2
| Track with two segments → one Placemark | 1 Placemark holding a `gx:MultiTrack` of 2 `gx:Track` elements
| Positions written | 7, of which 5 have a time
| Files offered | `.kmz` (one `doc.kml`) and `.kml`

## What a KMZ is

A KMZ is a zip archive. Google’s [KMZ archives guide](https://developers.google.com/kml/documentation/kmzarchives) describes it as a KML file packaged together with the files it refers to, such as icons and overlay images, so they travel as one file. The KML at the top level is normally called `doc.kml`. This tool reads `doc.kml` and, if there is none, the first `.kml` at the top level of the archive. If you got a `.kmz` from Google Earth, a map you exported or a download link, the coordinates are in that KML, and a GPS app or watch that only reads GPX cannot open the archive.

You can rename a KMZ to `.zip` and look inside, but you do not need to: drop the KMZ as it is. To see what is in it before converting, use the [KML and KMZ viewer](https://whereday.com/tools/kml-viewer/).

## What survives each conversion

KML writes a coordinate as `longitude,latitude[,altitude]`; GPX uses separate `lat` and `lon` attributes. The tool swaps them for you and copies the digits as written. The same converter is used by [KML to GPX](https://whereday.com/tools/kml-to-gpx/), which explains each element in more depth.

### KMZ (or KML) to GPX

| In the KMZ | In the GPX | Result
| Placemark with a `Point` | `<wpt>` with name, description and `<ele>` | Kept
| `LineString` | `<trk>` with one `<trkseg>` | Kept; no times, since a LineString has none
| `gx:Track` with `<when>` | `<trkseg>` whose `<trkpt>` have `<time>` | Kept, times written in UTC
| Polygons, models | None | Skipped and counted
| Styles, icons, folders, ExtendedData, overlays, NetworkLinks | None | Skipped and counted
| Images, extra KML files and other files in the archive | None | Ignored and counted

### GPX to KMZ

| In the GPX | In the KMZ | Result
| `<wpt>` | Placemark with a `Point` | Kept; the waypoint’s time is not carried
| `<trkseg>`, every point has a time | One `gx:Track` per segment | Kept with times, when “Keep timestamps” is on
| `<trkseg>` otherwise | One `LineString` per segment | Changed: times are lost
| A track with several segments | One Placemark: a `gx:MultiTrack` (or `MultiGeometry`) of one line per segment | Kept
| `<rte>` | `LineString` | Changed: times are lost
| Segments or routes with one point | None | Dropped and reported
| Extensions, links, comments, symbols, accuracy fields | None | Dropped and counted

The KMZ this tool writes has one entry, `doc.kml`, stored without compression. The KML download is the same content without the zip. The output is the same every time for the same input.

## Using the result

- GPS apps and watches that ask for GPX: use the GPX from a KMZ. Open it in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) first and compare its counts with the report. To join several, use [Merge GPX](https://whereday.com/tools/merge-gpx/).
- Google Earth or Google My Maps from a GPX: use the KMZ or KML. For Earth, check [Google Earth Help](https://support.google.com/earth/) for the current import steps. For My Maps, Google lists the supported file types in its [import help](https://support.google.com/mymaps/answer/3024836). The site’s guide to [opening a GPX file in Google Maps](https://whereday.com/blog/gpx-to-google-maps/) shows the My Maps steps on a computer.

We have not tested every app or device with this tool’s output, so open a small file first. The GPX is checked against the GPX 1.1 schema in the site’s tests, and the KMZ is reopened in the KML viewer’s reader with the same placemark and line counts. Want a spreadsheet instead? [Convert KML or KMZ to CSV](https://whereday.com/tools/kml-to-csv/). Choosing between formats? Read [how GPX, KML, GeoJSON and CSV compare](https://whereday.com/blog/gpx-kml-geojson-location-history/), or [what a GPX file is](https://whereday.com/blog/what-is-a-gpx-file/).

## Limits

- One file, up to 10 MiB and 50,000 coordinates, the same limits as the sibling tools. A file inside the KMZ is also limited to 10 MiB uncompressed.
- ZIP64 KMZ files are refused with a message. Re-save the file as a standard KMZ, or unzip it and choose the `doc.kml` inside. Encrypted archives and compression methods other than stored and deflate are refused too.
- Browsers without DecompressionStream cannot unzip a KMZ; the page says so. Use a current Safari, Chrome, Edge or Firefox, or unzip the file yourself. GPX and plain KML do not need it. See MDN’s [DecompressionStream](https://developer.mozilla.org/en-US/docs/Web/API/DecompressionStream) page.
- Several KML files. Only one is converted: `doc.kml`, or else the first top-level `.kml`. The others are counted as ignored.
- Linked files. A KMZ includes files it carries. A KML that points to files elsewhere (a `NetworkLink`, an image URL) is not followed, because the page makes no network requests.
- Polygons are skipped; GPX has no area shape.
- Altitudes are copied as written. KML altitude modes are not interpreted.
- Files with a DOCTYPE or entity declaration are refused.

## File privacy

Your file is read, unzipped, converted and saved in this page. The page’s content security policy blocks network connections and it loads no analytics, so it cannot send the file anywhere. The web server sees an ordinary request for the page and its scripts, not your file or its name. Keep your original file: the tool never changes it.

## Questions

### Is my file uploaded?

No. See [file privacy](https://whereday.com/tools/kmz-to-gpx/#file-privacy).

### Why are my photos missing?

GPX holds points, routes and tracks, not pictures. Images inside the KMZ (icons, overlays, photos) are not converted and the report counts them under “Images ignored”. Your original KMZ still has them.

### Can I convert KMZ to CSV?

Yes, with the [KML or KMZ to CSV converter](https://whereday.com/tools/kml-to-csv/), which writes one row per placemark or per vertex.

### My KMZ has several layers. Which one is converted?

A KMZ has one KML that is read: `doc.kml`, or the first `.kml` at the top level. Folders inside that KML are all converted, but GPX has no folders, so their names are lost and the report counts them. If the layers are separate KML files, only the chosen one is converted and the rest are listed as ignored; unzip the KMZ and convert the others one at a time with [KML to GPX](https://whereday.com/tools/kml-to-gpx/).

### Why is my polygon missing?

GPX has no area shape, and the tool does not turn an outline into a track. Polygons are skipped and counted in the report.

### Does Whereday import KMZ or GPX?

No. Whereday exports GPX, KML and other formats; it does not import GPX or KMZ.

## Sources

- [Google KML documentation: KMZ files](https://developers.google.com/kml/documentation/kmzarchives)
- [Google KML reference](https://developers.google.com/kml/documentation/kmlreference), which defines `gx:Track` and `gx:MultiTrack`
- [GPX 1.1 schema documentation (Topografix)](https://www.topografix.com/GPX/1/1/)
- MDN: [DecompressionStream](https://developer.mozilla.org/en-US/docs/Web/API/DecompressionStream) and [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API)
- [Google My Maps: import help](https://support.google.com/mymaps/answer/3024836)

## Record your own routes.

Record routes on your iPhone and export them as GPX, KML, GeoJSON, CSV, JSON or Markdown for any date range.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Look inside the KMZ first](https://whereday.com/tools/kml-viewer/)

See placemarks, lines, polygons and what the viewer skips, without uploading the file.

- [Convert KML or KMZ to CSV](https://whereday.com/tools/kml-to-csv/)

Get one row per placemark or per vertex for Excel or Google Sheets.

- [Check the GPX you saved](https://whereday.com/tools/gpx-viewer/)

Open it in the GPX viewer and compare points, segments and times.

- [Record your next route](https://whereday.com/blog/record-iphone-route-gpx/)

Record in Whereday and export points as GPX.

Source: https://whereday.com/tools/kmz-to-gpx/
