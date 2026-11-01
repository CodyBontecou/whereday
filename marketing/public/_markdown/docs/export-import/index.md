§ Docs · Guide 6 of 11

# Export and restore location history

Choose among eight export formats; content depends on the record type. Preview and supported import are free. Export access depends on when you joined; new users retain exports after tracking expires.

Platform

iOS 17+

Updated

October 30, 2026

Reading

5 min

Back to

[All docs](https://whereday.com/docs/)

Preview, import and export access. Export Preview and supported import are free. New users from version 1.8 retain file exports after tracking access expires. Existing free users keep their previous export-unlock requirement. Auto Export and webhook settings follow the same export access; folder selection, scheduling and receiver setup still need to be configured. See [the access explanation](https://whereday.com/#export-access).

## Eight formats

| Format | Best for
| JSON | Structured visit and point records, scripts, and tooling.
| CSV | Spreadsheets and analysis.
| Markdown | Human-readable journals — single outings export as a front-matter page with visits and route points.
| GeoJSON | Mapping tools and GIS.
| GPX | GPS tools and route viewers; visits become waypoints, routes become tracks.
| KML | Google Earth and Earth-compatible viewers.
| OwnTracks | OwnTracks-ecosystem tools (route points).
| Overland | Overland-ecosystem tools (route points).

Choose any mix of formats in one export; each becomes its own file. OwnTracks and Overland carry GPS points. In the released 1.8 export flow, choosing either uses Points even if Visits or All is selected; other selected formats keep their selected data type. For an outing, these two formats contain its route points, not visit rows or outing notes. Check Preview for each format. For receiver delivery, follow the [Dawarich Overland walkthrough](https://whereday.com/integrations/dawarich/) and verify the destination’s saved points.

## What gets exported

- Data: visits, route points, outings, or all of it.
- Date range: all time (default), today, yesterday, last 7 or 30 days, this month, or a custom range.
- Time of day (off by default): keep only records between two times — windows that cross midnight work too.
- Filters: only completed visits, minimum visit duration, exclude GPS glitches, and a maximum-accuracy cap (≤ 10 / 25 / 50 / 100 m).
- Fields: per-format toggles for names, addresses, durations, coordinates, notes, altitude, speed, accuracy, and glitch flags.

Your entire setup — formats, data kind, filters, field toggles — is remembered between launches.

## Filename templates

File names come from a template you control. These tokens are available:

`{date}` `{year}` `{month}` `{dayNumber}` `{weekday}` `{monthName}` `{quarter}` `{datetime}` `{time}` `{day}` `{type}` `{title}` `{name}` `{format}`

- Use `/` in a template to build dated subfolders.
- Built-in presets: Readable (default) `Whereday - {day} {date} - {type}`, Compact, and Dated `{year}/{year}-{month}/Daily Track - {date}`.
- A live preview shows the resolved path as you type.

## Output layout

- Single file (default) or one file per day — handy for journals and incremental backups.
- Exporting outings produces one file per outing automatically.
- Collisions are auto-suffixed so nothing overwrites.

## Saving and sharing

Pick a default folder (with auto-save on by default) and exports land there silently with a confirmation toast; otherwise the standard share sheet appears. The Preview button renders everything without writing — free, and a good way to sanity-check filters. Single outings can also be exported straight from their detail page.

## Import supported visit and point files

Compare [GPX, KML, GeoJSON and CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/) with checked synthetic files before choosing an output for another app.

Settings → Data Import → Import File accepts supported Whereday JSON, CSV, or Markdown and, in version 1.8, supported Google Timeline JSON. Importing is free. Review format, dates, selected record types, and approximate duplicates before confirming. A matching file extension alone does not make another app's file compatible. Follow the [Google Timeline import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/) for supported structures and a checked synthetic example.

Choose a file for the next task
| Format | Useful for | Import into Whereday
| Whereday JSON | Structured visit or point records | Supported visit and point exports
| Whereday CSV / Markdown | Spreadsheets / readable records | Supported visit and point exports; required fields must remain
| Google Timeline JSON | Moving supported Google visits and timed points | Version 1.8: classic raw, legacy semantic, and device Timeline formats; preview first
| GPX / KML / GeoJSON | Mapping tools and route viewers | No
| OwnTracks / Overland | Compatible location tools | No

If Preview is unexpectedly empty, follow the [export filter diagnosis](https://whereday.com/blog/missing-location-history-iphone/#export) before changing or importing journal records. It covers record type, arrival dates, time of day, accuracy, and visit completion.

## Keep a copy and check it

- Select visits or route points, JSON, and the dates you want to retain. Export each data type you need; keep all generated files.
- Preview the result. Check that filters, field choices, and dates have not excluded required records.
- Export to a folder you control and confirm the files exist in Files before deleting or replacing anything.
- When importing later, keep the originals and inspect several dates, visits, and points after the import. Do not assume every setting, photo association, or edited outing is restored.

[Automatic exports](https://whereday.com/docs/automatic-exports/) can keep scheduled copies, subject to iOS background execution. For the recording workflow, see [the private location journal guide](https://whereday.com/blog/private-location-journal-iphone/). For a route file, follow [the GPX recording walkthrough](https://whereday.com/blog/record-iphone-route-gpx/). For restorable records, use [the backup and restore checklist](https://whereday.com/blog/back-up-iphone-location-history/). Comparing apps? Read [Whereday and Google Timeline](https://whereday.com/compare/google-maps-timeline/).

Version 1.8 previews and skips approximate duplicate visits and points. In the historical 1.7.16 release-source example, post-import visit reconciliation combined two nearby visits into one, while importing the point file again added repeated points. The import result is not necessarily the final stored visit count. See the [checked disk import and JSON re-export](https://whereday.com/blog/back-up-iphone-location-history/#checked-import). Inspect the current preview and individual records before retrying; the historical 1.7.16 counts do not describe the newer duplicate-skipping importer. The [synthetic JSON examples](https://whereday.com/blog/back-up-iphone-location-history/#sample) show the visit and point structures without using private history.

For a readable note in a desktop vault, follow the [Markdown-to-Obsidian workflow](https://whereday.com/blog/iphone-location-journal-obsidian/). It covers manual file placement and keeps generated notes separate from personal writing.

[← PreviousPhotos on the map](https://whereday.com/docs/photos/) [Next →Automatically export iPhone location history to a folder](https://whereday.com/docs/automatic-exports/)

Source: https://whereday.com/docs/export-import/
