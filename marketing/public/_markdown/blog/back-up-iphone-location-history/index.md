- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Back up and restore history

Practical iPhone guides

# How to back up and restore your Whereday location history

Export Visits and Points separately as Whereday JSON, check the saved files, and keep the originals when importing on another iPhone.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 26, 2026

## Keep the files before changing phones

To keep a restorable copy of Whereday location history, export Visits and Points separately as Whereday JSON files. Include the dates and fields you need, confirm both files exist, and retain the originals when importing them on another iPhone.

Recording, preview, and importing supported files are free. Writing export files requires the optional one-time unlock. This guide covers Whereday records; it is not a way to restore Apple Maps or Google Timeline archives.

Backup and restore checklist
- [Decide what the files must preserve](https://whereday.com/blog/back-up-iphone-location-history/#scope)
- [Export visits and points](https://whereday.com/blog/back-up-iphone-location-history/#export)
- [Verify and keep the copies](https://whereday.com/blog/back-up-iphone-location-history/#check)
- [Inspect synthetic JSON examples](https://whereday.com/blog/back-up-iphone-location-history/#sample)
- [Import on the destination iPhone](https://whereday.com/blog/back-up-iphone-location-history/#restore)
- [Understand scheduled exports](https://whereday.com/blog/back-up-iphone-location-history/#scheduled)

## Choose records, not just a file extension

A GPX track can be useful in a map viewer without being a journal backup. Whereday’s importer accepts supported Whereday visit and point exports in JSON, CSV, or Markdown. It does not accept every file that happens to have one of those extensions.

What you are trying to keep 
| Your goal | What to save | What to verify  
| Restore visit records | Visits exported as Whereday JSON | Dates, coordinates, names, and the optional fields you need 
| Restore the recorded path | Points exported as Whereday JSON | The whole date range and required point fields 
| Open a route elsewhere | GPX, KML, or GeoJSON as appropriate for the viewer | Viewer compatibility; these formats cannot be imported into Whereday 
| Keep every app preference or edit | A record of important settings alongside the exported files | Do not assume an import restores settings, photo associations, or edited outing summaries  

For restoration, choose Visits and Points individually. An outing summary is a different export from the visit and point records the importer expects. The [export reference](https://whereday.com/docs/export-import/) lists supported formats and fields.

## 1. Export your visits

- Open Export and select Visits. 
- Select JSON. Deselect other formats if you only want this file. 
- Choose the date range. For the full available visit archive, choose all history rather than Today or a recent period. 
- Review time, duration, completion, and accuracy filters. A filter can omit records even when the date range is correct. 
- Keep coordinates and the other fields you want to restore. Open Preview and check representative dates. 
- Export to a folder you control. Note whether the settings produce one file or several dated files.  

### Then export your route points

Repeat the process with Points and JSON. Use the intended full date range and review its filters too. Keep both sets of files. Exporting visits alone does not preserve all of the GPS points between stops.

Stop an active recording before taking a final transfer copy if you want the session to be finished first. New records captured after an export will not be in that earlier file.

![Example Whereday Export screen with data types and file format controls](https://whereday.com/screenshots/export.webp)

The Export screen illustrates the workflow. The screenshot may contain sample data or earlier labels.

## 2. Confirm what was saved

Open Files and find the destination you selected. Confirm that you have a visit export and a point export, not just two copies of one type. If you chose daily splitting, retain every file needed for the period.

- Check the filenames and that the files are not empty. 
- Use Preview or a text viewer to check early and recent dates from the intended archive. 
- Keep an additional copy somewhere you will still be able to reach after changing phones. 
- Preserve the originals while transferring or importing them. Check the destination copy before relying on it.  

A folder stored only on the old phone does not help if you erase that phone before transferring the files. If you choose a cloud folder or another service, that exported copy follows the destination’s storage and privacy rules. Location records can reveal detailed movements, so choose the destination deliberately.

## Inspect the two JSON structures before using your own files

These examples contain invented coordinates near zero and fixed dates on January 1, 2000. They are documentation fixtures, not a real trip or captured app exports:

- [Download the synthetic Visits JSON](https://whereday.com/samples/synthetic-visits.json): two named stops with arrival and departure times. 
- [Download the synthetic Points JSON](https://whereday.com/samples/synthetic-points.json): three timestamped locations matching the [synthetic GPX example](https://whereday.com/samples/synthetic-route.gpx).  

```
{
"visits": [{
"latitude": 0.0,
"longitude": 0.0,
"arrivedAt": "2000-01-01T11:45:00Z",
"departedAt": "2000-01-01T12:00:00Z"
}]
}
```

The visit file has a `visits` array; the route file has a `points` array. Visits need coordinates and an arrival time. Points need coordinates and a supported timestamp. A valid JSON file can still be unsuitable for import if those fields are missing.

Both downloadable fixtures were parsed with Whereday’s current Swift importer: two visits and three points, with their expected names, coordinates, and times. This checks file compatibility; it does not verify persistence, merging, or a complete device restore.

Open the samples as text to inspect them. Importing them into your everyday journal would add invented records dated January 1, 2000. They are not needed to back up your own data.

## 3. Import the supported files on the destination

- Make the saved visit and point files available in Files on the destination iPhone. 
- Open Whereday, then choose Settings → Import file. 
- Select a supported Whereday file and read the import result. Repeat for the other data type and any additional dated files. 
- Check several dates on Map and Timeline. Look for both visits and route points, including the earliest and most recent records you expected. 
- Keep your originals until you have checked the result. An import cannot restore records that were excluded from the export.  

Import adds records; do not assume it merges an overlapping archive or skips duplicates. Import each intended file once and inspect the result before retrying. If a file fails, retain it and read the reported format or field error before editing it. A JSON archive from Google Timeline has a different structure and is not a supported Whereday restore file.

Restore Purchases is separate. It restores an eligible paid entitlement through the App Store; it does not download a copy of your location history. Whereday does not provide an account-based server archive or built-in iCloud journal sync.

## Scheduled exports are ongoing copies, not an initial full archive

Auto Export writes the current local day to the chosen folder. It can help keep future records, but turning it on today does not by itself back up every earlier day. Make your initial full exports separately.

Background execution is best effort. Check the latest saved file, use Run Now or open the app if a scheduled copy is overdue, and confirm the records you need are present. A configured schedule is not proof that the last file was written successfully.

See [the automatic export reference](https://whereday.com/docs/automatic-exports/) for timing, rewrite/append behavior, and how the scheduling service works.

## Need a readable note or a route file?

For a readable visit table, follow the [Markdown-to-Obsidian workflow](https://whereday.com/blog/iphone-location-journal-obsidian/). Keep a separate supported backup when omitting fields for readability.

Follow [the GPX route walkthrough](https://whereday.com/blog/record-iphone-route-gpx/) for a path you want to open in another tool. If you are still trying to find old Apple or Google records, start with [how to view your iPhone location history](https://whereday.com/blog/view-location-history-iphone/). For the recording setup, use [the private location journal guide](https://whereday.com/blog/private-location-journal-iphone/).

## Keep the records.
Check the copy.

Try free recording and export preview. See [current pricing](https://whereday.com/#pricing) for the optional file-export unlock.

[Download free for iPhone](https://apps.apple.com/app/id6761960794) 

Reviewed October 26, 2026 against Whereday’s export, import, and scheduling behavior. Importing restores supported records; it is not a promise to reproduce every app setting or edited view.

## Read next

- [Make a readable Markdown note](https://whereday.com/blog/iphone-location-journal-obsidian/)

Copy a visit table into a desktop Obsidian vault.

- [Check scheduled copies](https://whereday.com/docs/automatic-exports/)

Understand current-day exports, rewrite behavior, and background limits.

Source: https://whereday.com/blog/back-up-iphone-location-history/
