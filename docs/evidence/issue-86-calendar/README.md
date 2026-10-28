# Issue 86: Add timeline events to Calendar

Verified on October 28, 2026 with the Whereday Release Tests iOS 26.5 simulator.

- Long-pressing a timeline outing exposes **Add Event to Calendar**.
- Its system editor shows the original name, full start/end times, and start coordinates.
- Cancel → Discard Changes returns to the timeline. A read-only query of the simulator Calendar database found **0** events named `Morning Commute` afterward.
- The visit detail screen exposes the same action. Its editor showed `Valencia Street Stop`, `375 Valencia St, San Francisco, CA`, and October 28, 11:54 AM–12:18 PM.
- Confirming the visit editor returned to visit details. The simulator Calendar database contained the saved event with UTC timestamps `2026-10-28 15:54:00`–`2026-10-28 16:18:00`, corresponding to the displayed times.
- No Calendar permission prompt appeared. The app delegates saving to EventKitUI and never directly saves an event or requests calendar access.

Screenshots: [timeline menu](timeline-menu.png), [visit action](visit-action.png), [prefilled visit](prefilled-visit.png).

Validation:

```sh
xcodebuild test -project IsoMe.xcodeproj -scheme IsoMe \
  -destination 'platform=iOS Simulator,id=4457437A-95AB-44D9-B5D1-7371B30B32E7' \
  -derivedDataPath /tmp/isome-calendar-86-build \
  -only-testing:IsoMeTests/CalendarEventDraftTests CODE_SIGNING_ALLOWED=NO
```

All six tests passed: renamed visit/title, full multi-day dates, address/place/coordinate fallback, unsaved draft identity, ongoing visit/outing snapshots, completed outing location, inferred outing with no points, and preservation of source data. Final app build including all 11 localized action labels passed. `git diff --check` and project plist validation passed.

Physical-device tracking was not exercised. Test data was created with the existing `--seed-screenshot-data` debug argument on the simulator.
