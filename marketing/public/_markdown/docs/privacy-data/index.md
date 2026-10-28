§ Docs · Guide 11 of 11 

# Privacy & your data

A plain-language map of every place your data can go: on-device storage, Apple system services, first-party analytics, and the optional features you control.

Platform

iOS 17+ · watchOS 10+

Updated

October 26, 2026

Reading

4 min

Back to

[All docs](https://whereday.com/docs/)

## The short version

Your location history — visits, routes, outings, saved places, photo matches — is stored in the app's on-device database and nowhere else. There is no Whereday account, no cloud sync, and no Whereday server holding your location data. The full legal text is the [Privacy Policy](https://whereday.com/privacy); this guide is the plain-language version.

## Everything that can leave your device

| Where | What's sent | When | Off switch 
| Apple geocoding | Coordinates of a visit (to fetch its name/address) | When naming visits | Settings → Location names 
| Apple Maps | Search terms / route segment endpoints (place search, suggestions, road-matched lines, lock-screen map images) | When you use those features | Features are opt-in per use; road-matching toggle in map filters 
| Whereday analytics (first-party, on by default) | Which onboarding steps were viewed, permission choices, purchase outcomes, app version, and a random install ID — never coordinates, visits, routes, or anything you type | During onboarding and purchases | None in-app today; deleting the app removes the install ID 
| Export scheduler | Time zone, schedule times, push token, random install ID — timing metadata only, no location or file contents | Only if you enable Auto Export | Turn Auto Export off 
| Your webhook | Location points and visits, in your chosen format | Only after you configure an endpoint | Disable webhooks (off by default) 

## Photos

The photo feature reads your library on-device to match pictures to places. Photo files are never copied or uploaded; Whereday keeps only local identifiers and match metadata, and respects limited-access mode.

## Deleting your data

- Everything, in-app: Settings → Data → Clear all data permanently removes every visit, point, outing, photo match, and saved place. 
- Everything, bluntly: deleting the app removes your entire journal and the local analytics install ID from that device. 
- Single items: delete any visit or outing from its detail page.  

## Backups are your job (by design)

Because nothing is synced to a cloud, there is no server copy to fall back on. Export regularly — supported Whereday JSON files can restore visit and point records, and Auto Export can do it for you every day. See [Export & import](https://whereday.com/docs/export-import).

## Permissions Whereday requests

| Permission | Why 
| Location — Always | Automatic visit detection in the background (While-Using still works for manual sessions). 
| Photos (optional) | Matching your pictures to places; never modifies your library. 
| Notifications (optional) | Auto Export status and retry notices. 

[← PreviousRecord Apple Watch GPS routes without your iPhone](https://whereday.com/docs/apple-watch/)

Source: https://whereday.com/docs/privacy-data/
