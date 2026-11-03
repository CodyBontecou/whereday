- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Choose a file format

Practical iPhone guides

# GPX, KML or GeoJSON: choose a file you can use

Choose for your destination, check what the file preserves, and keep an original you can return to.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 30, 2026

## Choose the format your next tool can use

GPX is a useful starting point for a GPS track or route. Choose KML when your destination expects Google Earth-style features, GeoJSON for mapping or GIS work, and CSV for a point table. Check the destination’s actual import requirements before converting.

The extension does not tell you everything a file preserves. A line on a map, a timed recording, and a restorable location journal are different outputs. Keep the original file alongside any converted copy.

- [Match a format to your task](https://whereday.com/blog/gpx-kml-geojson-location-history/#choose)
- [Download the same invented trip in four formats](https://whereday.com/blog/gpx-kml-geojson-location-history/#example)
- [Inspect coordinates, timestamps and segments](https://whereday.com/blog/gpx-kml-geojson-location-history/#differences)
- [Check a conversion before relying on it](https://whereday.com/blog/gpx-kml-geojson-location-history/#check)
- [Choose a Whereday export or restore workflow](https://whereday.com/blog/gpx-kml-geojson-location-history/#whereday)

## GPX vs KML vs GeoJSON vs CSV

Start with the task, then verify the receiving app
| Format | Useful for | Check before using it
| GPX | GPS tracks, routes and waypoints in compatible viewers | Which objects are present, whether points have times, and whether segments stay separate
| KML | Google Earth-style placemarks, geometry and presentation | Support for the actual geometry and extensions; an ordinary line is different from a timed track
| GeoJSON | Mapping applications and geographic information systems (GIS) | Geometry type, longitude-first coordinate order, and the meaning of custom properties
| CSV | Spreadsheets, filtering and row-based analysis | Column names, timezone, units and group identifiers; CSV does not define a universal route model

A planned route may have coordinates without recording times. A recorded track can contain several segments. Neither a valid file nor a continuous line proves that every part of a real journey was captured.

## One invented trip, four downloadable files

The comparison starts with five points in two segments: three points from 12:00 to 12:02, then two from 12:13 to 12:14 on January 1, 2000, in UTC. The break between segments is 11 minutes. The coordinates are invented and there is no measured elevation.

```
Segment 1: 12:00 → 12:01 → 12:02
[11-minute break]
Segment 2: 12:13 → 12:14
```

- [Download the source GPX](https://whereday.com/samples/synthetic-format-trip.gpx) — one track, two segments and five timed points.
- [Download the timed KML example](https://whereday.com/samples/synthetic-format-trip.kml) — two `gx:Track` elements inside a `gx:MultiTrack`, with interpolation between them disabled.
- [Download the GeoJSON example](https://whereday.com/samples/synthetic-format-trip.geojson) — two LineString features with three and two positions; each has a custom `times` array.
- [Download the CSV point table](https://whereday.com/samples/synthetic-format-trip.csv) — five data rows, plus a header, using the free viewer’s documented CSV output.

These are controlled examples created for this guide, not personal history or newly captured native-app exports. Automated checks compare their coordinates, times and group boundaries. That verifies the contents, not that every third-party viewer will import every field.

## What changes between the files?

### Coordinate order needs attention

The first point is latitude 0, longitude 10. GPX labels those values as attributes; GeoJSON positions and KML track coordinates put longitude first. Swapping them can move a point while leaving both numbers within valid coordinate ranges.

```
GPX:     <trkpt lat="0" lon="10">…</trkpt>
GeoJSON: "coordinates": [[10, 0], …]
KML:     <gx:coord>10 0 0</gx:coord>
CSV:     latitude=0, longitude=10
```

The KML example is clamped to the ground: its third-coordinate zeros are placeholders, not measured elevation. The GPX has no elevation elements, the GeoJSON positions are two-dimensional, and the CSV elevation cells are blank. For real height data, check the units and height reference rather than assuming a copied number has the same physical meaning.

### Time must have a defined place

In the source GPX, each point has a `time` value. The KML example pairs each `when` with a `gx:coord` in the same track. This uses Google’s KML track extension; the destination must support it. A simple KML LineString is a different representation.

GeoJSON describes geometry and feature properties, but these examples’ `times` arrays are our own convention: entry 1 describes coordinate 1, and so on within each feature. A reader must explicitly understand that convention to use the times. Keeping the property in a file does not guarantee timed playback in another app.

The CSV includes both `time_utc` and `original_time`. The first is normalized to UTC; the second retains the original timestamp text. When inspecting a real export, compare timezone-aware times rather than only the clock labels shown in a spreadsheet or viewer.

### Keep the break instead of inventing a connection

The GPX uses two `trkseg` elements. The KML has two timed tracks and `gx:interpolate` set to 0. The GeoJSON has two separate LineString features. In CSV, `group` is 1 for the first three rows and 2 for the last two; point numbering restarts in each group.

A consumer that ignores the group column or merges the lines could draw an extra connection. Preserve the grouping and check the rendered result. A CSV row sequence alone is not a guarantee that a map will understand the break.

## Check a conversion with a small file first

- Keep the original. Save the converted file separately. Renaming the extension does not convert the contents.
- Inspect counts and dates. For this example, expect five points, two groups and times from 12:00 to 12:14 UTC. Check that one segment contains three points and the other contains two.
- Check a known coordinate. The first point is 0° latitude, 10° longitude. Check the destination’s field order and units.
- Open it in the intended destination. Look for a joined gap, absent times, changed point counts or ignored properties. File parsing checks and actual app behavior answer different questions.
- Document what was omitted. These derived examples preserve selected point data and grouping; they do not carry every possible source field, extension or application setting.

To inspect the GPX, use the [free local GPX viewer](https://whereday.com/tools/gpx-viewer/) and select the source GPX downloaded above. Expect five valid points, one track and two segments. The viewer accepts GPX, not the KML or GeoJSON examples. Its CSV download should match the CSV example byte-for-byte.

The [worked GPX inspection tutorial](https://whereday.com/blog/check-gpx-file/) explains missing timestamps, invalid coordinates and CSV limits. Warnings identify things to review; they do not automatically prove that a pause, duplicate position or gap is an error.

## Which file should you keep from Whereday?

For a portable route, read the [recording and GPX export guide](https://whereday.com/blog/record-iphone-route-gpx/). Whereday’s native format options also include KML, GeoJSON and CSV; see the [export and import reference](https://whereday.com/docs/export-import/) for supported record types and requirements. This guide’s comparison fixtures are not assertions about every native exporter’s field choices.

For a restorable journal copy, follow the [Whereday JSON backup workflow](https://whereday.com/blog/back-up-iphone-location-history/). Supported Whereday CSV or Markdown imports have their own required fields. A generic point table is not automatically compatible, and the GPX, KML and GeoJSON examples are not Whereday restore files.

New-user tracking access and native file-writing access depend on your plan and existing rights. The free browser GPX viewer does not require Whereday or an account. Keep the original recording or archive as well as any file made for another tool.

## Specifications behind the examples

Use the [GPX 1.1 schema documentation](https://www.topografix.com/GPX/1/1/) for point and segment structure, the [KML track reference](https://developers.google.com/kml/documentation/kmlreference#gxtrack) for timed tracks and interpolation, and [GeoJSON RFC 7946](https://www.rfc-editor.org/rfc/rfc7946) for positions, geometry and properties. App support must be checked separately from the format’s capabilities.

## Convert an existing GPX locally

The [GPX viewer now offers GeoJSON download](https://whereday.com/tools/gpx-viewer/#geojson-output). Its custom properties differ from this article’s controlled comparison file: use the [worked converter tutorial](https://whereday.com/blog/check-gpx-file/#geojson) and exact generated example to check its field mapping. The converter keeps parts disconnected at invalid coordinates and dateline jumps, and preserves elevation in a property array rather than assuming a height reference for geometry.

Source: https://whereday.com/blog/gpx-kml-geojson-location-history/
