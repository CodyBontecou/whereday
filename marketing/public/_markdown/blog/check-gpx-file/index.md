- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Check a GPX file

Practical iPhone guides

# Check your GPX before you import it

Start with a known example, check your file’s actual records, and keep the original before making changes.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 30, 2026

## Inspect the file before importing it

Open the [free GPX viewer and file inspector](https://whereday.com/tools/gpx-viewer/), choose a GPX, and compare the point counts, segments and available dates with the trip you expected. Your file is read in the browser; there is no upload or account.

Choose a destination that explicitly supports GPX; this viewer does not import the file into Whereday. This tutorial uses invented points from a checked Whereday export. It shows what to verify, not proof that a real journey was recorded correctly. Keep your original GPX before converting or editing it.

## 1. Try the five-point example

- Open the viewer and choose Try a synthetic example.
- Expect 5 valid points, 1 track, 2 track segments, 0 routes and 0 waypoints.
- Check the UTC dates: 2000-01-01 12:00 through 12:14.
- The two segments contain three points and two points. The 11-minute break between 12:02 and 12:13 is not connected by a line.

[Download the exact GPX example](https://whereday.com/samples/synthetic-app-export.gpx) and open it with the file picker to check the same result. The demo and download contain the same records.

## 2. Open your own GPX and check the result

Choose a GPX 1.0 or 1.1 XML file of at most 10 MiB and 50,000 points. On iPhone, save a local copy in Files first if a cloud provider cannot supply it. The tool reads track points, route points and waypoints; these are different GPX objects.

- Point count: Compare valid points with the count in the source app. Points with missing or out-of-range coordinates are counted as omitted.
- Segments: Separate segments can represent pauses or separate recordings. The viewer keeps them separate rather than drawing a trip you did not record.
- Dates: Check UTC dates and times, including the day at either end. UTC can differ from the date displayed in your local timezone.
- Route shape: The local plot has north at the top and no street tiles. It is a shape check, not turn-by-turn navigation. Large files draw a sample of up to 2,000 points; the CSV and GeoJSON downloads include every valid point.

File checks can find structural problems, but they cannot establish whether the GPS followed the road, whether a day is complete, or whether every exported field will be understood by another app.

## 3. Understand warnings before changing the file

| Finding | What it means | Next step
| Missing timestamp | A coordinate can be drawn, but its time is unknown. | Check whether you exported a planned route or a recorded track. Do not invent times.
| Unusable timestamp | The value is not a supported ISO date with a timezone. | Keep the original text and check the recorder’s export settings.
| Invalid coordinates | Latitude/longitude are missing, nonnumeric or outside their ranges. | Inspect the original and try a fresh export. The tool omits those points and breaks the line there.
| Time gap over 10 minutes | Adjacent timed points in the same segment or route are far apart in time. | A pause or missing recording may explain it; this is not automatic proof of corruption.
| Duplicate position | Consecutive points have the same coordinates. | You may have stood still. Keep the points unless your downstream workflow requires a documented change.
| Backwards time | An adjacent timestamp precedes the previous one. | Check recording/export order. This viewer does not reorder your file.

For a deliberately limited example, [open the two-point file with a missing timestamp](https://whereday.com/samples/synthetic-gpx-missing-time.gpx). Expect two valid points and one missing-time warning. A [second sample has one out-of-range coordinate](https://whereday.com/samples/synthetic-gpx-invalid-coordinate.gpx): expect one valid point and one omitted point. All sample coordinates are invented.

## 4. Save a point table as CSV

Choose Download points as CSV. For the five-point demo, expect five data rows plus a header: three rows in group 1 and two in group 2. Latitude/longitude are decimal degrees; elevation is meters; normalized times are UTC. Original timestamp text is retained in its own column.

The table includes only usable-coordinate points. It does not preserve all metadata, extensions or GPX structure and is not a Whereday restore file. User text that could be interpreted as a spreadsheet formula is prefixed with an apostrophe. Keep the original GPX alongside the CSV.

## 5. Convert GPX to GeoJSON and check the output

- In the [local GPX viewer](https://whereday.com/tools/gpx-viewer/), choose Try a synthetic example, then Download as GeoJSON.
- Open the downloaded `gpx-points.geojson` in a text viewer. Expect a `FeatureCollection` with two `LineString` features: three coordinate pairs in group 1 and two in group 2.
- Check `times_utc`: group 1 runs 12:00–12:02 UTC; group 2 runs 12:13–12:14 UTC on January 1, 2000. The eleven-minute break stays between features.
- Check `elevations_m`: the invented example has values 0, 1, 2 in the first group and 3, 4 in the second. Geometry remains two-dimensional; elevations are custom properties.
- Before using a personal file in another app, check that app’s support for the property arrays. Seeing the line does not establish that it read times, elevations or names.

[Download the exact converted five-point example](https://whereday.com/samples/synthetic-gpx-converted.geojson) to compare with the browser’s output. This is the converter’s output from the unchanged synthetic GPX above. The [format-choice article’s GeoJSON](https://whereday.com/blog/gpx-kml-geojson-location-history/) is a different, hand-controlled comparison representation with a different custom time field; property conventions can vary between files.

A single-point part or independent waypoint becomes a Point feature. An invalid coordinate or a longitude jump over 180 degrees starts a separate part; no boundary coordinates or times are interpolated. Empty parts produce no feature. Coordinate order is longitude, then latitude. Missing or unusable normalized times and elevations remain `null`; original timestamp text stays in `original_times` when present. Each custom property array has one value per coordinate, including a single value for a Point.

Point order within each group, duplicates and the viewer’s group numbers are retained. File metadata, track/route names, styling and extensions are omitted; this is not a lossless archive conversion. GeoJSON’s geometry rules and optional height reference are defined in [RFC 7946](https://www.rfc-editor.org/rfc/rfc7946). This tool keeps elevation outside geometry and uses its own documented time/elevation fields. Keep the original GPX.

## If the file will not open

- Wrong format: Renaming JSON, FIT, KML or ZIP to .gpx does not convert it. For a Google archive, use the [Google Timeline import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/).
- Malformed XML: Export a fresh GPX from the original recorder rather than discarding the archive. DTD and entity declarations are not supported by this tool.
- Too large: Export a shorter date range in the original app. The viewer caps input at 10 MiB and 50,000 points.
- No points: An outing title or time range is not a GPS track. Check what the recorder actually saved; [the missing-history checklist](https://whereday.com/blog/missing-location-history-iphone/) separates recording and export problems.

## Keep recording and backup separate

The browser viewer is free and does not require Whereday. To record your own future route, follow the [iPhone route and GPX export guide](https://whereday.com/blog/record-iphone-route-gpx/). New-user tracking access and native file-writing access have separate requirements. For a restorable journal copy, use the [JSON backup workflow](https://whereday.com/blog/back-up-iphone-location-history/) instead of treating a point table as a full backup.

For GPX’s track, route, waypoint and segment definitions, see the [GPX 1.1 schema documentation](https://www.topografix.com/GPX/1/1/).

## Need another format?

Use the [GPX, KML, GeoJSON and CSV comparison](https://whereday.com/blog/gpx-kml-geojson-location-history/) to check coordinate order, timestamps and grouping before choosing a file for another app. It includes four representations of the same invented trip.

Source: https://whereday.com/blog/check-gpx-file/
