§ Docs · Guide 7 of 11 

# Automatic exports

Auto Export writes your day to a folder you choose, on a schedule you choose — daily or as often as every hour — with rewrite and append modes that keep each day's file consistent.

Platform

iOS 17+

Updated

August 22, 2026

Reading

4 min

Back to

[All docs](https://whereday.com/docs/)

Pro feature. Auto Export appears in the Export tab once you've chosen a default export folder and unlocked Pro.

## The idea

You pick a folder, a format, and a schedule. Each run exports the current local day so far into one file for that day — always the same file name for the same day, no matter how many times it runs. Yesterday's file is never touched; tomorrow gets a new one.

## Schedules

- Once daily — at a time you choose (default 21:00). 
- Every 1–23 hours — from a start time you choose. A 3-hour schedule starting at 09:00 fires at 09:00, 12:00, 15:00…; intervals that don't divide the day simply restart from the next day's start time.  

## Rewrite vs append

| Mode | Behavior 
| Rewrite (default) | Each run rewrites the day's file with everything recorded so far today. Simple and self-healing. 
| Append | Each run adds only records captured since the last successful run — no duplicates, tiny writes. (Once-daily schedules always rewrite.) 

Append works for CSV, JSON, Markdown, OwnTracks, Overland, and GeoJSON. GPX and KML always rewrite the full day, because valid XML files can't be naively concatenated.

## How the app wakes up

iOS never guarantees background execution, so Auto Export layers its chances:

- Background refresh — iOS schedules the app to wake near each run. 
- A silent push — Whereday's scheduling service nudges the app at the fire time. The service only ever holds timing metadata (your time zone, the schedule, a push token) — never location records, file contents, or folder names. 
- A fallback notification — if nothing ran by a minute after the scheduled time, you get a tappable “Daily Export Ready” notification; tapping runs it. 
- Catch-up — opening the app runs any overdue occurrence automatically.  

If a run fails you'll get a notification with the reason and a retry; the schedule itself keeps going.

## Manual control

Run now in the Auto Export section performs an export immediately regardless of schedule — useful after a big day. The section also shows the last run time and format.

[← PreviousExport and restore location history](https://whereday.com/docs/export-import/) [Next →Webhooks & self-hosting](https://whereday.com/docs/webhooks/)

Source: https://whereday.com/docs/automatic-exports/
