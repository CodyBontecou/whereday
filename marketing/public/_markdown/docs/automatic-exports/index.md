§ Docs · Guide 7 of 11

# Automatically export iPhone location history to a folder

Save the current local day to a folder you choose. Check a manual run first, then choose how later runs update the file and how you will spot a missed day.

Platform

iOS 17+

Updated

October 30, 2026

Reading

6 min

Back to

[All docs](https://whereday.com/docs/)

Optional scheduled exports. Auto Export appears in the Export tab after you choose a default export folder and have export access. From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement.

## Set up one file you can check

- In Export, choose a default folder that you can find again in Files. Confirm the folder is available before enabling a schedule.
- In Auto Export, choose the data type and format. Visits describe stops; Points describe the recorded path. A file of one type does not preserve every other type.
- Choose Once daily or an interval of 1–23 hours, plus a start time. Interval schedules also offer Rewrite or Append.
- Use Run now, then find and open the actual file. Check the date, record type, and some expected records. A last-run time alone does not show that the file contains what you need.
- Enable the schedule and check a later file yourself. Notification and background permissions can affect delivery opportunities; a successful manual run does not verify an unattended run.

For a restorable archive, first make separate full-history Visits and Points JSON copies using the [backup guide](https://whereday.com/blog/back-up-iphone-location-history/). Auto Export starts with the current day; it does not backfill all earlier history. For a readable note, follow the [manual Obsidian handoff](https://whereday.com/blog/iphone-location-journal-obsidian/). A folder selection alone does not establish delivery into an Obsidian vault.

## Which day is saved?

Every run selects the current local day up to the run time. Its date range is separate from the manual Export tab’s chosen dates and daily splitting preference. Repeated runs use a stable path for that date; the next local date gets its own path.

If an overdue run happens after midnight, it selects the new day. It does not reopen yesterday’s file and fill its missing final hours. Use a separate manual export for an earlier date that you need to recover from retained journal records.

A once-daily run at 21:00 saves the day so far at that time. Records captured after it need another run that day or a later manual export. Choose the schedule with that cutoff in mind rather than treating one dated file as proof of a complete calendar day.

## How interval times work

Once daily uses your chosen local time, initially 21:00. An interval schedule starts from its daily anchor and includes occurrences less than 24 hours after that anchor, including times past midnight. For example, 09:00 every nine hours gives 09:00, 18:00, then 03:00 the next morning; the new cycle starts at 09:00. It is not an endless nine-hour sequence across every day.

These are scheduled opportunities. iOS controls actual background execution. Apple explains that a background task’s requested earliest start is [not a guaranteed launch time](https://developer.apple.com/documentation/backgroundtasks/bgtaskrequest/earliestbegindate).

## Choose Rewrite or Append

How a later run changes the current day’s file
| Mode | What it does | What to check
| Rewrite | Replaces the day’s file with the selected records from local midnight through now. Once-daily runs always use this mode. | Reflects current record values. Keep personal writing in a separate file because generated output can be replaced.
| Append | For interval schedules, selects records from the last successful append cursor through now, clamped to the current day. | It is not a general duplicate or edit sync. A point at the boundary can repeat in CSV; a visit that arrived earlier can retain its old exported fields.

Append is available for CSV, JSON, Markdown, OwnTracks, Overland, and GeoJSON. Their merge rules differ. GPX and KML always rewrite the full day, even when an interval schedule has Append selected. Changing formats or modes warrants checking the next file rather than assuming its contents match an earlier format.

## Checked file behavior in release source

We tested the 1.7.16 (46) automatic-export planner, serializers, and file writer with invented records in a temporary folder on an iOS 26.5 simulator. These are file tests, not a timed background delivery test.

What the native checks found
| Example | Saved result
| Two points at 10:00 and 11:00, then another run through 12:00 with a new point | CSV append saved four rows: the 11:00 point appeared twice. JSON append saved three points in this example.
| A 10:00 visit was exported open, then its departure was set to 12:00 | The next JSON append still had no departure. Rewriting the same day’s file included the 12:00 departure.
| A run at 23:55, another point at 23:59, then a run at 00:10 | Yesterday’s file stayed unchanged and did not gain the 23:59 point. Today’s file included only today’s point.

Choose Rewrite when you need the current values of today’s visits in each snapshot. For important earlier dates, use a manual date-range export and check it separately. The [checked restore example](https://whereday.com/blog/back-up-iphone-location-history/#checked-import) explains a different issue: importing files can reconcile nearby visits and add repeated points.

Checked October 26, 2026 against unchanged 1.7.16 release-source planning and file-writing code. All 27 selected native tests passed. No scheduled wake, notification delivery, folder-picker access, cloud synchronization, or unattended export was newly verified.

## If a scheduled file is missing or incomplete

- Open Whereday and inspect Auto Export’s last run and any error. Check that the schedule and selected data type are still what you intended.
- Check the chosen folder in Files. Confirm you are looking at the intended local date and file type, then open the file itself.
- Use Run now while the app is open, then inspect the result. A folder that has become unavailable needs attention before another scheduled attempt can succeed.
- If yesterday’s file is incomplete, make a separate manual export for yesterday. A run today does not repair it.
- If the file lacks records visible in the journal, compare the record type and date selection. Follow the [export diagnosis](https://whereday.com/blog/missing-location-history-iphone/#export) before deleting or importing anything.

Whereday can request background refresh, register a silent scheduling push, offer a fallback notification, and check for overdue work when opened. These are opportunities to run, not a delivery guarantee. A fallback notification can be tapped to attempt the export; inspect the saved file afterward.

The scheduling service receives routing and timing metadata, including an installation identifier, push token, time zone, and schedule. It does not receive your journal or export contents through this scheduling path. Exported copies follow the storage rules of the folder or service you select. See the [privacy reference](https://whereday.com/docs/privacy-data/).

## Common questions

### Does Auto Export make a full backup when I enable it?

No. It selects the current local day. Make initial full-history visit and point exports separately and keep their originals.

### Does Append keep old exported visits up to date?

Not reliably. Selection uses record time, and a later departure edit does not make an old arrival new. The checked example above needed Rewrite to refresh its departure.

### Can I keep my own notes inside an automatically generated file?

Use a separate note unless you have checked the specific format’s merge behavior. Rewrite replaces the generated file, and different append formats handle existing content differently.

[← PreviousExport and restore location history](https://whereday.com/docs/export-import/) [Next →Webhooks & self-hosting](https://whereday.com/docs/webhooks/)

Source: https://whereday.com/docs/automatic-exports/
