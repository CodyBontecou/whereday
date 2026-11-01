- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Location tracking and battery

Practical iPhone guides

# How to manage battery use while recording iPhone routes

Check whether recording is still running, review iPhone’s battery report, and compare one setting at a time. A larger distance filter still uses high-accuracy GPS.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed October 30, 2026

## Check the recording before changing settings

If Whereday uses more battery than you expected, first check whether a recording is still running. Tap Stop when the trip is finished, then confirm the app has left its recording state. In Whereday, Start enables detailed GPS recording, visit monitoring, and significant location changes together. There is no separate visits-only recording mode.

This guide explains the controls you can change and how to compare the result on your own phone. It does not promise a number of recording hours or a percentage saving. From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement.

Battery and route checklist
- [Read the iPhone battery report](https://whereday.com/blog/iphone-location-tracking-battery/#usage)
- [Choose the recording controls](https://whereday.com/blog/iphone-location-tracking-battery/#controls)
- [Compare a short route](https://whereday.com/blog/iphone-location-tracking-battery/#compare)
- [Check gaps before relying on the result](https://whereday.com/blog/iphone-location-tracking-battery/#gaps)

## 1. Look at the battery report for the recording period

Open iPhone Settings → Battery and inspect app usage for the period when you recorded. The available charts and labels depend on your iOS version. Open the app details to distinguish screen activity from background activity.

Apple includes location tracking among the tasks that can appear as background activity. Its battery guide also identifies weak signal and work after a software update as possible contributors. A busy recording day needs different context from a day when the phone was mostly idle. See [Apple’s battery-use instructions](https://support.apple.com/en-us/120745) for the controls on your version.

Note the recording duration, starting and ending charge, screen use, and whether you charged the phone during the period. Those observations make the next comparison more useful. One app percentage in isolation does not establish how long your next route will last.

## 2. Change the controls that affect your recording

What each control changes in Whereday
| Control | What changes | What to check
| Stop recording | Ends the active recording and stops its GPS, visit, and significant-change monitoring. | Confirm the tracking state changed. Future movement will not be captured by that stopped session.
| Distance filter | Changes the movement threshold requested for location updates. Options are 5, 10, 25, 50, 100, and 200 meters. | Larger values can mean fewer points and less route detail. They do not switch off high-accuracy GPS.
| Stop after | Adds an automatic stopping timer: 1, 2, 4, 8, or 12 hours, or Never. | Choose enough time for the intended route, and still check the final recording state.
| Location names | Controls sending coordinates to Apple’s geocoding service to name visits. | Turning names off does not stop GPS recording or create a visits-only mode.

Find these options under Settings → Location tracking. For a longer outing, you could try a 25 or 50 meter distance filter on a short, familiar route before using it for a full day. That is a starting point for comparison, not a measured battery recommendation.

Whereday continues to request its best location accuracy while recording at any of these filter settings. Apple recommends matching accuracy and distance thresholds to the task because location acquisition consumes energy; changing the threshold does not guarantee a particular saving. See [Apple’s location-efficiency guidance](https://developer.apple.com/documentation/xcode/accessing-the-device-s-location-efficiently).

Use Stop after as a fallback for a forgotten session. If the timer expires during an outing, the remaining movement will not be recorded. It is not a substitute for checking that the session ended when you wanted it to.

## 3. Compare one change on a familiar route

- Choose a short route with turns and stops you can recognize later. Note the current distance filter and intended recording duration.
- Start recording and check that the app shows an active session. Keep screen use representative of how you normally record.
- Stop at the end. In Timeline, open the outing and inspect the path, times, turns, and gaps.
- On another comparable trip, change only the distance filter. Note differences in charge use and route detail rather than assuming the setting is better because it is larger.
- Keep the setting that preserves the detail you need. Repeat if the two trips had substantially different conditions.

A route with fewer points may be adequate for remembering a journey but miss a short detour you care about. An export contains the recorded points; exporting GPX cannot add detail that was never captured. Use the [GPX walkthrough](https://whereday.com/blog/record-iphone-route-gpx/) when you want to inspect a saved route in another tool.

## If battery use improves but the route has gaps

For an incomplete Whereday day, use the [missing visits and route points checklist](https://whereday.com/blog/missing-location-history-iphone/) to distinguish recording, display, Watch sync, and export issues.

Check the recording state, whether the stopping timer elapsed, and the app’s location permission. Confirm you are viewing the intended date and outing. Then inspect whether the filter removed detail you expected to see. A larger movement threshold should not be treated as a promise of evenly spaced points.

Whereday also rejects fixes with reported accuracy worse than 100 meters and filters obvious GPS glitches from its usual map and distance views. Fewer visible points can therefore have several causes. The [tracking reference](https://whereday.com/docs/tracking/) explains the recording and quality controls.

Keep existing records while diagnosing a problem. Reinstalling or deleting history is not needed for this comparison. If you need a separate copy, follow the [backup and restore checklist](https://whereday.com/blog/back-up-iphone-location-history/) first.

## Common questions

### Can I record only visits to save battery?

Whereday currently starts GPS, visits, and significant-change monitoring together. The distance filter changes update spacing; it does not create an independent passive mode.

### Does turning off the Live Activity stop recording?

No. The Live Activity is a status display and control surface. Use Stop and confirm the app’s state when you want to end recording.

### How many hours can I record?

There is no verified universal duration for Whereday. Device condition, recording time, screen use, and other activity vary. Check your own short recording before relying on a longer trip.

## Keep the route you need.
Check where it ends.

Check your tracking access and review your first short outing before choosing settings for a longer day.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

Reviewed October 26, 2026 against Whereday’s recording controls and Apple’s linked guidance. No new device battery benchmark was performed. Controls can vary by app and iOS version.

## Read next

- [Check a gap in your recording](https://whereday.com/blog/missing-location-history-iphone/)

Separate missing records from hidden glitches and incomplete transfers.

- [Save the route you recorded](https://whereday.com/blog/record-iphone-route-gpx/)

Preview points and write a portable GPX file.

Source: https://whereday.com/blog/iphone-location-tracking-battery/
