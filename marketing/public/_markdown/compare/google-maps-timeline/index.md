- [Whereday](https://whereday.com/)
- [Compare](https://whereday.com/compare/)
- Google Timeline

iPhone location journals

# Whereday and Google Timeline on iPhone

Choose a location journal by the way you record, review, and keep a copy of your own history.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 30, 2026

## An alternative for your next recorded day

Whereday is an iPhone location journal for people who want to record their own visits and routes without a Google account. From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement.

Google Timeline already keeps history on your device and offers encrypted backup. Choose based on the recording and export workflow you need. Starting Whereday does not move your old Timeline archive.

## Start with the history you need to keep

- Find or move your existing Google history: keep its archive and check [Timeline export and backup](https://whereday.com/compare/google-maps-timeline/#preserve) first. Whereday 1.8 supports selected Google JSON formats; follow the [import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/).
- Record future visits and routes without another account: [check Whereday’s recording access](https://whereday.com/compare/google-maps-timeline/#choose), then inspect the saved points before relying on it.
- Use new records in a map, notes, or your own server: review the [checked export examples](https://whereday.com/compare/google-maps-timeline/#examples) before paying for exports. File compatibility and restoration are separate questions.

## Compare the everyday workflow

iPhone location history: accounts, recording, and portability
| Question | Whereday | Google Timeline
| Account | No Whereday account. | Google account.
| Storage and backup | Local journal. Keep supported export files for restoration; no built-in cloud sync. | Device history, with optional encrypted server backup.
| Recording | Start and Stop control GPS recording and visit monitoring together. Always permission allows background recording. | Visits and routes collected with Timeline enabled.
| Files | JSON, CSV, Markdown, GPX, KML, GeoJSON, OwnTracks, and Overland. OwnTracks and Overland carry GPS points. Preview is free; file writing follows the [new-user and existing-user export rules](https://whereday.com/#export-access). | Location-history JSON export.
| Moving existing history | Version 1.8 previews supported Google Timeline JSON and Whereday JSON, CSV, or Markdown. Select record types and inspect approximate duplicates before saving. | Timeline backup can transfer history to another device.
| Payment | New users from 1.8 choose a subscription or Lifetime access; eligible subscriptions include a trial. Existing users retain free tracking and prior purchase rights. See [current prices and access](https://whereday.com/#pricing). | Timeline is available within Google Maps.

Google features: [Google’s Timeline help for iPhone](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en), checked October 30, 2026. Whereday features are described in its [product documentation](https://whereday.com/docs/). This comparison is written by Whereday’s developer.

## When Whereday fits

- You want a personal route and visit journal without creating another account.
- You want to choose when recording starts and stops, review outings, and replay recorded routes.
- You need GPX for a route viewer, CSV for a spreadsheet, or Markdown for a readable archive.
- You want an optional webhook workflow alongside a local journal.

Use the [private location journal guide](https://whereday.com/blog/private-location-journal-iphone/) to set up your first recording. Start enables detailed GPS as well as visit monitoring; there is no separate passive visit-only switch. Permissions, GPS conditions, and battery can affect the record.

[Download Whereday for iPhone](https://apps.apple.com/app/apple-store/id6761960794?pt=126424970&ct=seo-google-timeline&mt=8)

## When keeping Google Timeline makes sense

Keep Timeline if its existing archive, Maps workflow, and backup meet your needs. Importing selected visits and timed points does not reproduce every Google activity, label, or backup feature. Review [Google’s current settings and backup instructions](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en) before changing your setup.

If you want to try Whereday, record a short session and inspect it before relying on it for a longer trip. Check your trial eligibility and tracking access before starting; existing users retain their earlier rights.

## Preserve your existing history first

Google’s downloadable archive and its device-transfer backup serve different purposes. Keep the archive before changing your setup; check backup separately if you need history on another device.

- In Google Maps on iPhone, open your profile picture or initial, then Settings → Location & Privacy → Export Timeline data.
- Choose Save to Files, select a destination, and save `location-history.json`. Keep that original file separately.
- For Google’s device-transfer backup, open Your Timeline → cloud icon and check Backup. Google says backup requires auto-delete to be off and changes can take a few days to appear.

These steps follow [Google’s iPhone export and backup instructions](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en), checked October 30, 2026. Google notes that settings are being updated, so labels may differ. No Google device-transfer test is claimed here.

Whereday 1.8 supports selected Google Timeline JSON structures. Follow the [preview-and-import walkthrough](https://whereday.com/blog/import-google-timeline-iphone/), including its checked synthetic example and format limits. No importer can reconstruct records missing from the file. An exported file is not proof of a successful restore.

For existing Apple or Google records, start with [the iPhone history guide](https://whereday.com/blog/view-location-history-iphone/). For new Whereday routes, follow [the GPX export walkthrough](https://whereday.com/blog/record-iphone-route-gpx/) and check [export and restore compatibility](https://whereday.com/docs/export-import/). A GPX file is useful for drawing a route elsewhere, but it cannot be imported into Whereday. Keep supported visit and point exports for records you want to restore later.

## Check the output before choosing an export workflow

These examples use invented location records. They show specific file and receiver behavior, with the remaining recording and transfer limits stated in each guide.

- [GPX in gpx.studio:](https://whereday.com/blog/record-iphone-route-gpx/#sample) five points from Whereday’s serializer opened as two segments. This checks synthetic file compatibility, not a completed physical recording.
- [Markdown in Obsidian:](https://whereday.com/blog/iphone-location-journal-obsidian/) a native export with two invented visits opened in a temporary desktop vault, with an optional checked Maps plugin example. Mobile and scheduled delivery need separate checks.
- [Overland delivery to Dawarich:](https://whereday.com/integrations/dawarich/) three invented points persisted after the local receiver and database restarted. Production HTTPS and unattended delivery remain unverified.

For returning data to Whereday, read the [backup and restore guide](https://whereday.com/blog/back-up-iphone-location-history/). That guide preserves a historical 1.7.16 example where nearby visits reconciled and repeated point files appended again. Version 1.8 adds preview and duplicate skipping; inspect the current preview rather than applying the old counts to a new import. Keep the originals and verify destination counts before repeating an import.

## Understand what local storage means

Whereday does not operate a server archive of your location history. Apple services handle place names and map features. Production version 1.8 disables onboarding and purchase analytics; earlier versions can still send the technical events described in the privacy policy. Optional webhooks deliver records to an endpoint you configure. Exported copies follow the storage rules of the destination you choose.

Read the [privacy policy](https://whereday.com/privacy), [recording controls](https://whereday.com/docs/tracking/), and [current pricing](https://whereday.com/#pricing) before choosing a workflow.

![Example Whereday map interface with visit markers and recorded route points](https://whereday.com/screenshots/map-480.webp)

Whereday’s map interface. The screenshot is illustrative and may show sample data or earlier labels.

## Record your next route.
Keep a copy you can use.

Check your tracking and export access, then record a short session before relying on a longer trip.

[Download free for iPhone](https://apps.apple.com/app/apple-store/id6761960794?pt=126424970&ct=seo-google-timeline&mt=8)

## Read next

- [Find your existing records](https://whereday.com/blog/view-location-history-iphone/)

Check the service that recorded them before changing apps.

- [Try the route export workflow](https://whereday.com/blog/record-iphone-route-gpx/)

Check recording access and preview steps before writing a GPX file.

Source: https://whereday.com/compare/google-maps-timeline/
