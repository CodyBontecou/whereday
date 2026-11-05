§ Docs · Guide 2 of 11

# Visits, routes & battery

Start records GPS routes and monitors visits together. Learn the shared controls, permissions, distance filter, and auto-stop timer.

Platform

iOS 17+

Updated

November 5, 2026

Reading

4 min

Back to

[All docs](https://whereday.com/docs/)

## Two kinds of history

Whereday records visits (arrivals and departures) and routes (GPS paths). Start enables visit monitoring, significant location changes, and detailed GPS together; Stop ends all three. There is no separate passive visit-only recording mode. Always location permission allows background recording.

## Starting and stopping

- Recording bar — the bar above the day card on the map reads Recording with the number of saved points, and any auto-stop countdown, while tracking is on. In standby it offers a start button.
- Live Activity — lock screen and Dynamic Island show progress and include a Stop button.
- Siri / Shortcuts — “Start Whereday” and “Stop tracking with Whereday” phrases work hands-free.
- Auto-stop timer — optional safety net that ends tracking automatically.

For a walkthrough, see [how to keep a private location journal](https://whereday.com/blog/private-location-journal-iphone/) or [record an iPhone route and export GPX](https://whereday.com/blog/record-iphone-route-gpx/).

Tracking state survives relaunches: if Whereday was recording when you last used your phone, it resumes when you open it again. If iOS relaunches the app in the background for a location event, recording continues without you touching anything.

## Visit detection

Visits use iOS visit monitoring plus significant-location-change triggers. When you arrive somewhere, Whereday creates a visit; when you leave, it closes it. Behind the scenes Whereday also merges duplicate detections of the same stop (within roughly 100 meters and half an hour) and closes any visit left open when a new arrival makes it clearly stale — so you don't accumulate phantom “current” pins.

New visits are reverse-geocoded to a name and address automatically, unless you've turned Settings → Location tracking → Location names off (this toggle controls whether coordinates are sent to Apple's geocoding service).

## Route recording quality

- Only fixes with reported accuracy of 100 m or better are saved; wilder fixes are dropped.
- Obvious glitches — impossible jumps and out-and-back spikes — are flagged as GPS glitches and excluded from distances and maps by default. Enable Settings → Map display → Show GPS glitches, or Show outliers in the map’s Layers sheet, to see them.

## Tracking settings reference

Tracking settings and defaults
| Setting | Options | Default | What it does
| Distance filter | 5 / 10 / 25 / 50 / 100 / 200 m | 5 m | Movement threshold requested for location updates. Larger values may reduce point density and detail; high-accuracy GPS remains active.
| Stop after | Never / 1 / 2 / 4 / 8 / 12 h | Never | Automatically ends tracking after this long, so a forgotten session can't run all day.
| Location names | On / Off | On | Whether coordinates are sent to Apple's geocoding service to name visits.
| Live Activity | On / Off | On | Lock screen / Dynamic Island status while tracking.
| Units | Metric / US | Metric | Applies everywhere: app, widgets, Live Activity, watch.

Battery. Tracking includes detailed GPS as well as visit monitoring, so it uses battery for as long as recording stays on. Stop when you finish, consider a 25–50 m distance filter, and set an auto-stop timer. Check the resulting route before relying on a larger filter; no battery duration is guaranteed. Follow the [battery-use comparison checklist](https://whereday.com/blog/iphone-location-tracking-battery/) to check the tradeoff on your own phone.

If a recorded day looks incomplete, follow the [missing visits and route points checklist](https://whereday.com/blog/missing-location-history-iphone/) before deleting records or retrying an import.

[← PreviousGetting started](https://whereday.com/docs/getting-started/) [Next →Timeline & outings](https://whereday.com/docs/timeline-outings/)

Source: https://whereday.com/docs/tracking/
