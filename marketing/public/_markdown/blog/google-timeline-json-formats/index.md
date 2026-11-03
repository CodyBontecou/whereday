- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Timeline JSON formats

Practical iPhone guides

# Identify your Google Timeline JSON file

Look inside the export, compare a known example, and separate a format check from an app’s import preview.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 30, 2026

## Identify the structure inside the JSON

A filename such as `location-history.json` is a starting point, not a format guarantee. Google exports have used several structures. Open a copy in the [local Timeline JSON inspector](https://whereday.com/tools/google-timeline-inspector/) to see which supported shape it matches. Matching keys do not authenticate who created a file.

Keep the original archive before editing or converting anything. If your download is a ZIP, extract it first and inspect the JSON files individually. Renaming a ZIP, KML or GPX to JSON does not convert it.

| Top-level structure | What the inspector looks for | What to check next
| `locations` | Raw coordinates and timestamps | Coordinate scale and usable point times
| `timelineObjects` | Semantic place visits and activity segments | Visit intervals and timed path components
| `semanticSegments`, or a recognizable device-style array | Device visits, activities and timeline paths | Intervals, explicit path times and minute offsets

These are supported families, not an exhaustive list of every Google export. Mixed containers, unexpected field types or an unfamiliar format can prevent inspection. An unrecognized file does not establish that your history is lost.

## Try three invented files with known results

Use the example selector in the inspector before choosing personal data. Every coordinate and date in these files is invented. Counts describe the supported coordinates and times before duplicate handling.

- [Raw location history JSON](https://whereday.com/samples/synthetic-google-raw.json): three source entries produce two timed points and one unusable-coordinate finding. Latitude 91 is deliberately outside the valid range.
- [Semantic Timeline JSON](https://whereday.com/samples/synthetic-google-semantic.json): three entries produce one visit and three timed points. A waypoint without a time is reported, and an unknown row is left unsupported. The activity’s endpoints have times; its untimed waypoint does not acquire an invented one.
- [Device Timeline JSON](https://whereday.com/samples/synthetic-google-timeline.json): three entries produce two visit records and four timed points, with two point times derived from explicit minute offsets and one invalid interval. Repeated entries are intentional.

The device example is also used in the [Whereday import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/). In an empty native journal, that preview plans one new visit and two new points after handling repeated records. The browser’s two/four counts are earlier checks, so they are not a forecast of native import results.

For an unsupported-file case, try [this unrelated JSON example](https://whereday.com/samples/synthetic-google-unknown.json). The inspector should identify that it is not a supported Timeline structure. Valid JSON alone does not make a file location history.

## Read coordinates and times without guessing

Fields named `latitudeE7` and `longitudeE7` store degrees multiplied by ten million. A longitude of `100000000` therefore represents 10 degrees. Latitude and longitude are separate fields; exchanging them can move a location even when both numbers remain in range. The raw and semantic examples start at latitude 0, longitude 10 to make that distinction visible.

`timestampMs` means milliseconds since the Unix epoch. An ISO timestamp instead includes a date, time and timezone, such as `2000-01-01T12:00:00Z`. This inspector does not guess that a seconds value was intended to be milliseconds. Check the displayed date range against the period you expected.

The preview displays UTC. A source timestamp with an offset can appear on another clock time, or even another calendar day, when shown in UTC. That alone does not mean the record shifted. A path’s supported minute-offset field is used only with a valid parent interval and within that interval. An untimed coordinate remains untimed.

## Separate a format check from an import preview

The inspector checks known coordinate/time fields. It does not validate every optional field, reproduce Whereday’s duplicate rules or guarantee that an app will accept your file. Several records may describe the same visit, and repeated coordinates overlap in the plot. Dots are deliberately not joined into an inferred route.

A recognized empty container can contain no records. A short range can reflect one file rather than your whole archive. Unsupported rows and missing timestamps may explain part of a result, but cannot tell you whether another device or backup contains more history. Keep other copies while investigating.

The browser tool accepts up to 10 MiB and 50,000 source entries or expanded inspected components. It previews 20 checked records and plots up to 2,000 locations. Those are its own limits, separate from the native importer. Large files are rejected rather than silently trimmed.

## Convert a supported Timeline JSON to GPX

- Open the [local Timeline inspector](https://whereday.com/tools/google-timeline-inspector/#inspect-json), select your extracted JSON, and read the detected shape, dates and findings.
- Check the GPX output counts. Visits and activity endpoints become waypoints. Raw locations and explicit timed path components become track points. The converter keeps repeated records rather than merging them.
- Choose Download supported records as GPX. Keep your original JSON; this conversion omits names, addresses and other optional fields.
- Open the downloaded file in the [GPX viewer](https://whereday.com/tools/gpx-viewer/). Compare track points and waypoints separately, then inspect times and segment boundaries before sharing. The viewer accepts up to 10 MiB and 50,000 points; a converted GPX can be larger than its source JSON.

Expected results for the three invented JSON examples above
| Example | Track points / segments | Visit / endpoint waypoints | Expected GPX download
| Raw | 2 / 1 | 0 / 0 | [Raw example GPX](https://whereday.com/samples/synthetic-google-raw-converted.gpx)
| Semantic | 1 / 1 | 1 / 2 | [Semantic example GPX](https://whereday.com/samples/synthetic-google-semantic-converted.gpx)
| Device | 4 / 4 | 2 / 0 | [Device example GPX](https://whereday.com/samples/synthetic-google-device-converted.gpx)

The raw example leaves out its invalid latitude. The semantic example exports two activity endpoints as waypoints and its single timed path point as a one-point segment; the untimed waypoint stays omitted. The device example retains both repeated visits and paths. Its thirty-minute path gaps exceed the converter’s ten-minute rule, so its four track points occupy four one-point segments. A destination viewer may show dots instead of lines for those segments.

Source paths stay separate. Rejected components, backwards time, UTC day changes, gaps over ten minutes and longitude jumps over 180 degrees break segments. Point order within each source path is retained; nothing is road-matched or assigned a guessed time. These rules prevent some misleading connections, but cannot establish continuous recording or recover a missing route. Read the [full conversion rules and omitted fields](https://whereday.com/tools/google-timeline-inspector/#gpx-conversion). Coordinates are rounded to at most fifteen decimal places; longitude 180 is written as the equivalent −180 boundary. Times are normalized to UTC with millisecond precision. GPX is not a complete Timeline backup, and Whereday does not import GPX.

## Choose the next step

For a fresh export on iPhone, Google’s [official Timeline help](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en) describes its export controls. Follow our [export-and-import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/) for the Files handoff and Whereday’s actual preview. A Google device-transfer backup and an exported JSON file serve different workflows.

If your destination needs a map file, first [compare GPX, KML, GeoJSON and CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/). The inspector can convert supported coordinate/time records to GPX using the rules above; it does not import a file into Google Maps. For a GPX you already have, use the [GPX viewer](https://whereday.com/tools/gpx-viewer/).

On the inspector page, file reading and plotting happen locally, without analytics, uploads, browser storage or external map tiles. Clear removes rendered details without deleting your original. Ordinary page and public-script requests still reach the server; following another link leaves that tool page.

Source: https://whereday.com/blog/google-timeline-json-formats/
