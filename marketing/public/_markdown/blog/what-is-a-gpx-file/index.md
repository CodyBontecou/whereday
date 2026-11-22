- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- What is a GPX file

Practical iPhone guides

# What is a GPX file, and how do you open one on iPhone?

What GPX is, what it holds, which kind you have, and how to open it without uploading it.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 22, 2026

In this guide
- [What a GPX file is](https://whereday.com/blog/what-is-a-gpx-file/#quick-answer)
- [What is inside](https://whereday.com/blog/what-is-a-gpx-file/#inside)
- [Track, route or waypoints?](https://whereday.com/blog/what-is-a-gpx-file/#which-kind)
- [Open a GPX file on iPhone](https://whereday.com/blog/what-is-a-gpx-file/#open-iphone)
- [Open one on Mac or Windows](https://whereday.com/blog/what-is-a-gpx-file/#open-computer)
- [If it won’t open](https://whereday.com/blog/what-is-a-gpx-file/#problems)
- [Convert or combine](https://whereday.com/blog/what-is-a-gpx-file/#convert)
- [Make a GPX of your own trips](https://whereday.com/blog/what-is-a-gpx-file/#make-your-own)
- [Questions](https://whereday.com/blog/what-is-a-gpx-file/#faq)

## A GPX file is a text file of GPS points

GPX (GPS Exchange Format) is an open XML format for GPS data: coordinates, usually with elevation and time. It is plain text, so you can open it in any text editor and read it. Apps that understand GPX draw it as a line or pins on a map.

The quickest way to see one is the [free Whereday GPX viewer](https://whereday.com/tools/gpx-viewer/). It works in iPhone Safari and on a computer, and reads the file in your browser without uploading it. [Open your GPX in the free browser viewer](https://whereday.com/tools/gpx-viewer/).

## What is inside a GPX file

This is an excerpt of an invented file: no real person or trip. The full file has five points, one track, two segments, no routes and no waypoints, timed from 12:00 to 12:14 UTC on 2000-01-01. (The real file also holds a metadata block and app-specific extension data, left out here.) [Download the full invented example](https://whereday.com/samples/synthetic-app-export.gpx).

```
<gpx version="1.1" creator="Whereday"
xmlns="http://www.topografix.com/GPX/1/1">
<trk>
<name>Whereday track</name>
<trkseg>
<trkpt lat="0.0000000" lon="0.0000000">
<ele>0.00</ele>
<time>2000-01-01T12:00:00.000Z</time>
</trkpt>
<!-- two more points, to 12:02 -->
</trkseg>
<trkseg>
<!-- two points, 12:13 and 12:14 -->
</trkseg>
</trk>
</gpx>
```

- `<gpx>`: the root element. `version="1.1"` is the GPX version and `creator` names the program that wrote the file.
- `<trk>`: one track, a recorded path.
- `<trkseg>`: a track segment, a run of points that belongs together. A new segment starts where the recording was broken, so a viewer should not draw a line across the gap. Here there are two.
- `<trkpt lat lon>`: a track point. `lat` and `lon` are decimal degrees; latitude comes first.
- `<ele>`: elevation, in meters.
- `<time>`: the date and time of the point, in UTC (the trailing `Z`).

GPX 1.1 is the current version of the schema; GPX 1.0 files still exist. The element definitions are in the [GPX 1.1 schema documentation](https://www.topografix.com/GPX/1/1/), with an overview on the [Topografix GPX page](https://www.topografix.com/gpx.asp).

The three kinds of data a GPX file can hold
| Kind | Element | What it is
| Track | `<trk>` with `<trkseg>` and `<trkpt>` | A recorded path: points in time order, split into segments.
| Route | `<rte>` with `<rtept>` | An ordered list of points to travel through, such as turn points. Points may have no times.
| Waypoint | `<wpt>` | A single point of interest, usually with a name.

## Track, route or waypoints: which do you have?

Open the file in a text editor and search for these elements:

- `<trkpt>` points, each with a `<time>`: probably a recording of where someone went.
- `<rte>` with no times: a planned route to follow.
- Only `<wpt>`: a list of places, with no path between them.

It matters because a planned route is not a record of where anyone went, and a waypoint list cannot be drawn as a journey. A file can hold more than one kind. The viewer shows track points, route points and waypoints separately and reports the counts, so you can compare them with what you expected.

## How to open a GPX file on iPhone

- Save it to Files first. From a Mail attachment, a Messages attachment or a Safari download, use the share sheet and look for Save to Files (labels can vary by iOS version). Apple describes [downloading email attachments](https://support.apple.com/guide/iphone/download-email-attachments-iph7fe7a50a7/ios), [finding downloaded files](https://support.apple.com/guide/iphone/find-your-downloaded-files-on-iphone-iphc9cd7266c/ios) and [Files basics](https://support.apple.com/guide/iphone/files-basics-iphe9d46e90f/ios).
- Open it in the browser viewer. In Safari, go to the [Whereday GPX viewer](https://whereday.com/tools/gpx-viewer/), choose Choose a GPX file and pick the file from Files. It draws the route shape and lists point counts and dates. Nothing is uploaded.
- Or send it to an app you already use. If an app says it supports GPX import, send the file to it from the share sheet.

Files stores the file; a GPX-capable app or the viewer draws it. This page does not claim that any built-in Apple app shows a GPX map.

## How to open a GPX file on Mac or Windows

- Use the same [browser viewer](https://whereday.com/tools/gpx-viewer/).
- Open the file in any text editor to read the XML.
- Desktop mapping software can load GPX. For example, QGIS documents [loading a GPX file](https://docs.qgis.org/latest/en/docs/user_manual/working_with_gps/plugins_gps.html).

## If it won’t open

- Wrong format. A FIT, KML or JSON file renamed to .gpx is still not GPX. Renaming does not convert it.
- Too large for the viewer. The viewer takes files up to 10 MiB and 50,000 points. Export a shorter range.
- Malformed XML. A cut-off download or hand edit can break the file. The viewer also rejects DTD and entity declarations.
- Missing times. Routes and waypoints often have none, so there is nothing to date.

For the full list of warnings and what to do about each, see [Check a GPX file before importing it](https://whereday.com/blog/check-gpx-file/).

## Convert or combine GPX files

To choose between GPX, KML, GeoJSON and CSV, read the [format comparison](https://whereday.com/blog/gpx-kml-geojson-location-history/). Several GPX files, such as one per day, can be [merged into one](https://whereday.com/tools/merge-gpx/). The viewer can also save the points as CSV or GeoJSON. The [visited countries map](https://whereday.com/tools/visited-countries-map/) accepts GPX as input too. GPX holds coordinates and times, not photos or app-specific notes, and it is not a backup format for a location journal.

## Make a GPX of your own trips

Record a trip in Whereday, then export Points as GPX; the [route guide](https://whereday.com/blog/record-iphone-route-gpx/) has the steps. Whereday exports GPX but does not import GPX into its journal. Its supported imports are Google Timeline JSON and Whereday JSON; see [Export and import](https://whereday.com/docs/export-import/).

From version 1.8, new users need an eligible trial, subscription or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights.

## Questions

### Is a GPX file safe to open?

It is text in XML, not a program. The browser viewer reads it locally and rejects DTD and entity declarations. Only open files from sources you trust, as with any download.

### Does a GPX file include my home address?

It holds coordinates, not addresses, but a personal track that starts or ends at home reveals where home is. Treat personal tracks as private before sharing them.

### What is the difference between a GPX track and a route?

A track is a recorded path in time order; a route is a planned list of points to travel through. See [the table above](https://whereday.com/blog/what-is-a-gpx-file/#inside).

### Can I edit a GPX file?

For small fixes, a text editor works. Keep the original and edit a copy.

## Open it now.
Record your own next.

[Open your GPX in the free browser viewer](https://whereday.com/tools/gpx-viewer/), then record your own trips and export them as GPX.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Open your GPX file](https://whereday.com/tools/gpx-viewer/)

See the route, point counts and dates locally in your browser.

- [Check it before importing](https://whereday.com/blog/check-gpx-file/)

Follow a worked example of warnings, gaps and segments.

- [Record your own route](https://whereday.com/blog/record-iphone-route-gpx/)

Record in Whereday and export Points as GPX.

Source: https://whereday.com/blog/what-is-a-gpx-file/
