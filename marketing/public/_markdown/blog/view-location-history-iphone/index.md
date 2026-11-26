- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- View location history

Practical iPhone guides

# iPhone location history: how to see where you’ve been

Find the app and date that hold your saved visits, distinguish iPhone location settings from recorded history, and check existing copies before changing your setup.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 24, 2026

## Quick answer: see where you’ve been on iPhone

On iOS 26, your saved visits are in Apple Maps, not in the Settings screen most guides point to:

- Open Maps and tap the search bar to find Places.
- Choose Visited Places.
- Search, or filter by date, category or city.
- Use All Visits to browse every visit Apple Maps kept.

Visited Places only has data if it was switched on earlier; see [what to turn on](https://whereday.com/blog/view-location-history-iphone/#turn-on). Apple notes that availability depends on region and version ([Apple’s iOS 26 guide](https://support.apple.com/guide/iphone/view-and-manage-visited-places-iph0c2597c4f/26/ios/26)).

Google Maps: profile picture → Your Timeline, then select a date. Whereday: open the Map and choose a date range, or tap Pick a day in the day sheet. These histories are separate, so start with the app that recorded the day.

This guide covers your own visits and routes. Installing a new recorder cannot reconstruct earlier movement.

In this guide
- [Quick answer](https://whereday.com/blog/view-location-history-iphone/#quick-answer)
- [Turn it on first](https://whereday.com/blog/view-location-history-iphone/#turn-on)
- [Why Settings → Significant Locations looks empty](https://whereday.com/blog/view-location-history-iphone/#significant)
- [What each source can show](https://whereday.com/blog/view-location-history-iphone/#which-history)
- [Apple Maps details](https://whereday.com/blog/view-location-history-iphone/#apple)
- [Google Maps Timeline](https://whereday.com/blog/view-location-history-iphone/#google)
- [Download your history](https://whereday.com/blog/view-location-history-iphone/#download)
- [Keep existing records](https://whereday.com/blog/view-location-history-iphone/#preserve)
- [Why a day can be empty](https://whereday.com/blog/view-location-history-iphone/#missing)
- [Common questions](https://whereday.com/blog/view-location-history-iphone/#history-questions)
- [Record your next day](https://whereday.com/blog/view-location-history-iphone/#future)

## Turn it on first

Visited Places records only from the point it is on. Per [Apple’s iOS 26 guide](https://support.apple.com/guide/iphone/view-and-manage-visited-places-iph0c2597c4f/26/ios/26), check:

- Settings → Apps → Maps → Location: turn on Precise Location and Visited Places.
- Settings → Privacy & Security → Location Services: turn it on. Then System Services → Significant Locations & Routes: turn it on.

Turning these on does not reveal earlier days. If you only just enabled them, the list starts today.

## Why Settings → Significant Locations looks empty

Most answers say to open Settings → Privacy & Security → Location Services → System Services → Significant Locations. Older iOS versions use that label (once called [Frequent Locations](https://whereday.com/blog/significant-locations-iphone/#frequent-locations)); newer ones say Significant Locations & Routes. That screen is a switch and a Clear History button. It is not a list you scroll through by date.

Apple’s [Location Services & Privacy](https://www.apple.com/legal/privacy/data/en/location-services/) page describes it as tracking places you recently visited, how often and when, and the routes you took, and says it syncs with end-to-end encryption. The browsable list is in Maps → Places → Visited Places. The [full guide to Significant Locations and Visited Places](https://whereday.com/blog/significant-locations-iphone/) covers clearing it, turning it off and why neither feature exports.

Looking in Find My instead? [Find My keeps no location history](https://whereday.com/blog/find-my-location-history/), only a current or last known location. Want photos on a map? See [how to see your photos on a map on iPhone](https://whereday.com/blog/iphone-photos-map/).

## What each source can show

Looking for one specific day? See [how to see where you were on a specific date on iPhone](https://whereday.com/blog/iphone-location-history-by-date/). “Not documented” means the linked source does not say; it does not mean the feature is missing.

Your own history: what each source documents
| Source | Places | Dates | Times of day | Route lines | Export file
| Apple Maps Visited Places | Yes, searchable by place, category or city | Filter by date | Not documented | Not documented | Not documented
| Significant Locations & Routes | Described as tracking places recently visited | Described as tracking how often and when | Not documented | Described as tracking routes taken | Not documented
| Google Maps Timeline | Yes | Calendar day select | Not documented in the linked help | Yes | `location-history.json`
| Find My | Current or last known location only; no history. See [Find My location history](https://whereday.com/blog/find-my-location-history/).
| Whereday | Yes, visits | Date range or Pick a day | Yes, check the outing’s times | Yes, Points and Path | Whereday JSON, GPX

Sources: [Apple Visited Places](https://support.apple.com/guide/iphone/view-and-manage-visited-places-iph0c2597c4f/26/ios/26), [Apple Location Services & Privacy](https://www.apple.com/legal/privacy/data/en/location-services/), [Google Timeline help](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en) and Whereday’s own documentation. Directions you requested, places you searched for and a recorded visit are different kinds of information. A search for a restaurant does not establish when you arrived.

## Apple Maps Visited Places in detail

- Open Maps, then open Places.
- Choose Visited Places.
- Search for a place or filter by date, category, or city. Use All Visits to browse.

For older entries, review Keep Visits at the bottom of Visited Places. A retention choice may explain a missing date; changing it does not establish that an older visit still exists. For filters and why the list can look empty, read the [full guide](https://whereday.com/blog/significant-locations-iphone/#visited-places). Follow [Apple’s versioned instructions](https://support.apple.com/guide/iphone/view-and-manage-visited-places-iph0c2597c4f/26/ios/26) when labels differ.

## Open Google Maps Timeline

- Open Google Maps → profile picture or initial → Your Timeline.
- Use the calendar to select the day. Confirm the account and device whose history you need.

Google’s iPhone guide describes optional encrypted backup and a device selector under Timeline’s cloud icon. A backup can be imported into Google Maps on another device; inspect that device’s date range afterwards. Recent changes can take days to reach a backup.

Timeline empty or moving to a new phone? See the [Google Maps Timeline on iPhone guide](https://whereday.com/blog/google-maps-timeline-iphone/).

For an independent file, use Settings → Location & Privacy → Export Timeline data → Save to Files. Google names the export `location-history.json`. Follow [Google’s current iPhone instructions](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en) if menus differ.

Keep the archive separately. Whereday 1.8 can preview supported Google Timeline JSON; use the [import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/) to check formats, duplicates, and a synthetic example before saving.

## How to download your iPhone location history

There is no single download that combines every recorder on your iPhone. First identify the app holding the dates you need, then choose a copy for your next task. A route for a map viewer and a file for restoring a journal can contain different records.

Choose the source and destination before saving
| History source | Copy to keep | Next check
| Google Maps Timeline | Export Timeline data to Files as `location-history.json`, using the steps above. | [Identify the JSON structure](https://whereday.com/blog/google-timeline-json-formats/) and inspect supported records. Keep the original even if you convert a copy to GPX.
| Whereday visits and points | Export Visits and Points separately as Whereday JSON for a journal copy. | Follow the [export checklist](https://whereday.com/blog/back-up-iphone-location-history/#export), then compare both record types and the selected dates.
| Whereday route for another viewer | Select Points and GPX in Export, then save through the share sheet. | [Check the GPX points, times and segments](https://whereday.com/blog/check-gpx-file/). GPX is not a Whereday journal restore file.
| Apple Maps Visited Places | The linked Apple iOS 26 guide documents browsing and managing visits; it does not provide a bulk GPX or JSON export procedure. | Keep access to the original history. Do not assume a screenshot or shared place contains a timestamped route.

### Check the saved file, not just the download message

- Locate it in Files. Open the destination folder you chose. Note the filename and export date, and keep an untouched copy before converting or editing.
- Compare its scope. Check the earliest and latest expected dates and both visits and points where relevant. A filename or nonzero file size does not establish that the full period was saved.
- Inspect a known example first. Open the [local GPX viewer](https://whereday.com/tools/gpx-viewer/) and choose Try a synthetic example. The invented file has five points in two segments, from 12:00 to 12:14 UTC on January 1, 2000. The eleven-minute gap remains a break; it is not filled in by downloading or converting the file.
- Check the destination. After importing a supported copy, inspect several actual records and dates there before removing the source. A successful format check does not prove that every field transferred.

The [format comparison](https://whereday.com/blog/gpx-kml-geojson-location-history/) explains when GPX, KML, GeoJSON or CSV fits a downstream tool. Our browser inspectors process selected files locally without uploading them; normal links leave that tool page. Keep personal location archives out of public support posts.

## Keep the records before changing the setup

- Check the old device and the date you need before moving to a different phone or account.
- Review retention and backup settings before clearing history. Google warns that deleting Maps or factory-resetting the phone removes device Timeline data; verify an available backup or export first.
- Keep the original archive until you have checked the destination’s actual records. A backup switch or a successful file download is not proof of a complete copy.

For Whereday records, follow the [backup and restore checklist](https://whereday.com/blog/back-up-iphone-location-history/). Each app’s backup belongs to that app’s workflow; similarly named files are not automatically interchangeable.

## Why a day can be empty

First check the date, account, and app rather than assuming the history is gone. A map showing today may simply be the wrong view for last month’s trip. In Whereday, change the Map’s date range or use Pick a day in the day sheet.

- Recording was off. A location permission lets an app request locations; it is not proof that the app recorded that day.
- The record was removed. Look for retained files or supported backups before clearing more data or replacing a device.
- The device missed part of the trip. Permission changes, a stopped session, GPS conditions, or a flat battery can leave gaps.
- A visit has not appeared. Detected arrivals and departures are not an immediate check-in at every stop. A route can contain points even when a visit is missing.

For an incomplete Whereday day, use the [missing visits and route points checklist](https://whereday.com/blog/missing-location-history-iphone/) to distinguish recording, display, Watch sync, and export issues.

Photos, receipts, and calendar entries may help you remember a day, but they are not a recovered GPS trace. A newly installed recorder cannot recreate coordinates it never captured.

## Record your next day with Whereday

Choosing an app to record from now on? [Compare the options](https://whereday.com/compare/location-history-apps-iphone/).

If you want a separate journal without a Whereday account, try a short recording before a longer trip:

- Install Whereday and complete permission setup. Always location access allows recording in the background.
- On Map, tap the recording control before you leave. Start enables GPS recording and visit monitoring together.
- Stop when you finish. In the map’s day sheet, tap Pick a day, choose the day, and open the outing. Check its Points count, Path, and times; a saved outing alone does not prove a GPS route was captured.
- If you need a portable route file, follow [the iPhone route and GPX guide](https://whereday.com/blog/record-iphone-route-gpx/).

From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement. Read [the setup walkthrough](https://whereday.com/blog/private-location-journal-iphone/) for permissions, battery controls, and keeping supported copies.

Core records are stored on your phone. Apple services supply place names and map features. Production version 1.8 disables onboarding and purchase analytics; earlier versions can still send the technical events described in the privacy policy. Optional webhooks send records to your chosen endpoint. The [privacy policy](https://whereday.com/privacy) explains those boundaries.

## Common location-history questions

### Can I see the times I arrived and left?

It depends on the source. [Apple’s Visited Places guide](https://support.apple.com/guide/iphone/view-and-manage-visited-places-iph0c2597c4f/26/ios/26) documents search and filtering by date, category or city, but does not document arrival and departure times. Apple describes Significant Locations & Routes as tracking how often and when you visited places, without documenting a reader view. In Whereday, open the day with Pick a day and check the outing’s times. See the [guide to a specific date](https://whereday.com/blog/iphone-location-history-by-date/).

### Can I see my iPhone’s location history on another device or iCloud.com?

Apple says Visited Places and Significant Locations & Routes sync across your devices with end-to-end encryption ([Apple](https://support.apple.com/guide/iphone/view-and-manage-visited-places-iph0c2597c4f/26/ios/26)). Neither source documents a web view, so don’t expect one on iCloud.com. For Google, [Timeline on iPhone](https://whereday.com/blog/google-maps-timeline-iphone/) describes backup and device selection.

### Can I use my location history to prove where I was?

A location record is data a service saved about your device. It shows where the device was, not who held it, and gaps are normal. Whether it is accepted as evidence is for the party asking, and this is not legal advice. To rebuild past trips, see [how to find your travel history](https://whereday.com/blog/find-your-travel-history/), and to count days by country use the [country days calculator](https://whereday.com/tools/country-days-calculator/).

### How do I delete it?

In Maps, open Places → Visited Places, then choose Clear History, or use More → Remove on one place. See Apple’s [Clear location history in Maps](https://support.apple.com/guide/iphone/clear-location-history-iph32b15b22f/26/ios/26). Clearing cannot be undone, so export or back up anything else first.

### Can a new app recover a trip from before it was installed?

A new Whereday installation cannot reconstruct GPS points it never recorded. Check the original recorder and any supported retained files. Photos or calendar entries can help you remember a day, but they are not a recovered GPS path.

### Does a saved place prove that I visited it?

No. In Whereday, a saved location is a reusable name and pin for future matching. It can exist without a visit. A manually added visit records the details you enter; it is separate from automatically recorded history. The [places reference](https://whereday.com/docs/places/) explains those records.

### Does location permission mean every trip was recorded?

Permission allows location requests; it does not establish what was saved. Check the day’s actual visits and route points. The [checked recording-state example](https://whereday.com/blog/private-location-journal-iphone/#first-recording) shows an outing with a running timer but no saved GPS points.

![Whereday’s map home: today’s route coloured by place, visit names, date range chips from Today to All, and the day card](https://whereday.com/screenshots/map-home-480.webp)

Whereday opens on a map of the day; swipe up the day card for the day so far. This illustrative screenshot uses an invented journal.

## Remember your next trip.
Start recording before you go.

Check your tracking access, then record and review the route and visits on your iPhone.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

Reviewed November 24, 2026 against the linked Apple iOS 26 guides, Apple’s Location Services & Privacy page, Google’s iPhone Timeline help, and the published Whereday export and file-inspection guides. Earlier native workflow evidence retains its own dates; no new device-transfer test is claimed. Apple and Google steps follow their linked help pages; menus may vary by version and region. The screenshot illustrates Whereday.

## Read next

- [Check what an Apple Watch keeps](https://whereday.com/blog/apple-watch-location-history/)

See where workout routes, Find My and the iPhone store Watch location data.

- [Understand Significant Locations and Visited Places](https://whereday.com/blog/significant-locations-iphone/)

See where each Apple feature lives, why it shows little, and why neither exports.

- [Check missing Whereday records](https://whereday.com/blog/missing-location-history-iphone/)

Diagnose recording gaps, hidden points, Watch transfers, and export filters.

- [Start a journal for future trips](https://whereday.com/blog/private-location-journal-iphone/)

Set up recording before the next trip you want to remember.

- [See your photos on a map](https://whereday.com/blog/iphone-photos-map/)

Use the Photos map and place photos that have no GPS.

Source: https://whereday.com/blog/view-location-history-iphone/
