- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- GPX viewer

iPhone location journals

# See your route. Understand your GPX.

A free GPX viewer with clear file checks and a local route plot. Your file stays on your device.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 30, 2026

## Open your file. Keep it on your device.

See your route shape, check points and timestamps, and save a CSV table or GeoJSON file. No account, upload, analytics or external map tiles on this tool page.

Choose a GPX file · up to 10 MiB / 50,000 points

Try a synthetic exampleClear fileDownload points as CSVDownload as GeoJSON

Choose a file, or try the example without sharing any personal history.

JavaScript is needed to read and plot files locally. The guide and sample downloads below work without it.

## File summary

### File checks

### Point preview

| Kind | Group / point | Latitude, longitude | Time in UTC

## What this GPX viewer checks

It reads GPX 1.0 and 1.1 track points, routes and waypoints. The route plot keeps segments separate and breaks lines at invalid coordinates. It highlights missing or unusable timestamps, consecutive duplicate positions, backwards timestamps, and gaps longer than 10 minutes within the same segment or route.

A time gap can be intentional. A valid file does not prove an accurate GPS recording. This is a practical inspector, not full GPX schema validation, route navigation, file repair, or a complete journal backup tool. Google Timeline JSON, KML, FIT, TCX and ZIP files are not supported.

Choosing between file types? Compare [GPX, KML, GeoJSON and CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/) with the same invented trip in four formats.

Start with [the worked GPX inspection tutorial](https://whereday.com/blog/check-gpx-file/) or [download the exact synthetic example](https://whereday.com/samples/synthetic-app-export.gpx). It has five points in two segments. Its 11-minute break is between segments and is deliberately not joined.

## What the CSV contains

The table contains all points with usable coordinates, including ones without a usable time. Columns are kind, group, original point number, latitude, longitude, UTC time, elevation in meters, point name, and original timestamp text. Unusable times and elevations leave the normalized value blank; invalid-coordinate points are omitted and counted above.

Group numbers preserve track-segment, route and waypoint separation. The CSV is a flat point table, not a lossless conversion or a Whereday restore file: metadata, styling and extensions are not included. User text that could become a spreadsheet formula is prefixed with an apostrophe. Keep your original GPX.

## Convert GPX to GeoJSON locally

Open a GPX, review the checks, then choose Download as GeoJSON. The output is a `FeatureCollection` with longitude-first `[longitude, latitude]` positions in decimal degrees. Each continuous track-segment or route part with two or more valid points becomes a `LineString`. A single remaining point or independent waypoint becomes a `Point`.

Parts stay separate at invalid coordinates and longitude jumps over 180 degrees, including the antimeridian. The converter does not interpolate new boundary points or connect separate parts. Duplicate points and point order within each group remain. Missing times do not remove usable coordinates.

Geometry is two-dimensional. The custom properties `point_numbers`, `times_utc`, `original_times`, `elevations_m` and `point_names` are arrays aligned with each feature’s coordinates; a Point has one entry in each array. Missing or unusable normalized values are `null`. Original timestamp text is retained when present. `kind`, `group` and `part` identify the viewer group and output part. Group numbers follow the viewer’s order: track segments, then routes, then independent waypoints. They do not preserve a complete track hierarchy.

These property arrays are this tool’s convention, not a standard GeoJSON time or elevation model. A receiving app can ignore them. Elevation stays in properties rather than becoming a third geometry coordinate with an assumed height reference. File metadata, track/route names, styling and extensions are omitted. This is not a lossless conversion, route repair or Whereday restore file. Keep your GPX and [follow the worked GeoJSON check](https://whereday.com/blog/check-gpx-file/#geojson) before using a personal archive.

## Your location file stays in this page

File parsing, plotting, CSV and GeoJSON creation happen in your browser. This page blocks network connections and does not include the website’s analytics listener. There are no external map tiles: the plot shows coordinates and shape, not street names. Clear removes the result from the page; it never deletes your original file.

The web server still receives an ordinary request when you visit the page or load its public scripts. Your chosen file, coordinates, filename and results are not sent to it. Following a link leaves this tool’s privacy boundary. This page’s memory is not a backup; keep a separate original copy.

## Need to record your next route?

Whereday records your own visits and GPS points on iPhone and Apple Watch. See the [recording and GPX export guide](https://whereday.com/blog/record-iphone-route-gpx/) for tracking access, permissions, and file-writing requirements. This browser viewer is free and does not require the app.

Source: https://whereday.com/tools/gpx-viewer/
