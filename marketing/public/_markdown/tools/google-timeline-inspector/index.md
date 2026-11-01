- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- Timeline JSON inspector

iPhone location journals

# Inspect Timeline JSON and convert supported records to GPX

Check your export, review supported records, and save GPX locally while keeping your original JSON.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 30, 2026

## Open an export. Keep it on your device.

Identify a known Timeline JSON structure, review usable coordinates and times, see what this inspector could not read, and download supported records as GPX. No account, upload, analytics or external map tiles on this tool page.

Choose an extracted JSON file · up to 10 MiB Or try an invented exampleDevice TimelineSemantic TimelineRaw location history

Try selected exampleClear fileDownload supported records as GPX

Choose a JSON file, or try the example without sharing personal history.

JavaScript is needed for local file inspection. The format guide and example downloads below work without it.

## File summary

### Inspection findings

### Checked record preview

| Source entry | Kind | Latitude, longitude | Start / point time in UTC | Visit end in UTC

## Which Google Timeline JSON shapes are recognized?

- `locations`: raw coordinate/time records, including E7 coordinates and millisecond timestamps.
- `timelineObjects`: semantic place visits and activity segments, with supported timed paths.
- `semanticSegments`, or a recognizable device-style array: visits, activity endpoints and supported timed path points.

A matching structure does not authenticate the file’s origin or establish that every field is supported. Mixed containers, ZIP archives, Whereday backups, KML, GPX and unrelated JSON are not inspected here. If the file is not recognized, keep it and read [which Timeline JSON file you have](https://whereday.com/blog/google-timeline-json-formats/).

## Read the counts as records, not import results

Visits require usable coordinates and an ordered start/end interval. Points require usable coordinates and an explicit timestamp or a supported minute offset from a valid parent start time. Untimed waypoints are reported rather than assigned guessed times. Counts are before duplicate handling; several records can describe the same visit or location.

The existing [device example](https://whereday.com/samples/synthetic-google-timeline.json) has two visit records, four timed points and one invalid interval. Its repeated entries are intentional. Whereday’s native preview can produce different new/duplicate counts because it also checks saved records. This independent browser inspector does not validate every optional field, reproduce native duplicate rules, or guarantee import success.

Input is capped at 10 MiB and 50,000 source entries or expanded inspected components. The preview shows 20 checked records and the plot draws at most 2,000 locations. These are browser-tool limits, separate from the native app’s limits. A short detected date range or an empty result cannot establish whether older history exists in another file, device or backup.

## Convert Google Timeline JSON to GPX

Open a supported JSON or try an example, review the findings and GPX counts, then choose Download supported records as GPX. The download includes all accepted records, separate from the 20-row preview and 2,000-location plot limits. No upload or account is needed. Open the result in the [local GPX viewer](https://whereday.com/tools/gpx-viewer/) before sharing it. GPX can be larger than its source JSON; that viewer’s separate 10 MiB and 50,000-point limits still apply to the output.

- Tracks: raw location records and explicitly timed path components become track points. Source paths stay separate. Point order within each source path and repeated points are retained, with no sorting or road matching.
- Breaks: a rejected path component, backwards time, a UTC day change, a gap over ten minutes or a longitude jump over 180 degrees starts another segment. A segment can contain one point and may appear only as a dot in another viewer.
- Waypoints: visits use their start time and put the end time in a description. Activity endpoints become separate waypoints, even if that activity also contains a path. Two endpoints are not evidence of the route between them.
- Omissions: unsupported records and unusable coordinates/times are left out. Place names, addresses, activity labels, altitude and other optional fields are not copied. A provided minute offset can produce a time; missing times are never invented.

Coordinates are rounded to at most fifteen decimal places; longitude 180 is written as the equivalent −180 boundary. Times are normalized to UTC with millisecond precision. The file uses [GPX 1.1](https://www.topografix.com/GPX/1/1/) tracks and waypoints. The ten-minute and UTC-day breaks are this converter’s display choices, not proof that a trip stopped. Neither the browser’s dot plot nor the GPX reconstructs missing travel, merges overlapping records, preserves a complete Timeline archive, or guarantees another app’s import. Read the [worked conversion examples](https://whereday.com/blog/google-timeline-json-formats/#convert-gpx) for expected counts.

## Your selected file stays in this page

JSON inspection and plotting happen in your browser. This tool page omits site analytics and blocks network connections. It does not request map tiles, look up places, upload records, or store your archive in browser storage. Clear erases the rendered file details and results; it never deletes your original.

The server receives ordinary page and public-script requests when you open the tool. Selected filenames, coordinates and results are not sent to it. Following a normal link leaves this tool’s privacy boundary.

## Choose the next step for your archive

Use the [format-identification tutorial](https://whereday.com/blog/google-timeline-json-formats/) for worked examples and unsupported-file cases. For Whereday, follow the [Google Timeline import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/), inspect its actual native preview, and keep the original archive. This tool cannot recover missing records or import a file into Google Maps.

Have a GPX instead? Open the [GPX viewer and file inspector](https://whereday.com/tools/gpx-viewer/). Renaming a JSON file to GPX does not convert it.

Source: https://whereday.com/tools/google-timeline-inspector/
