# Native custom-date reference

This isolated UI harness runs the iOS 2.0 `DateRangeFilterSheet` and its `MapRangeCalendar`, extracted unmodified from `IsoMe/Views/MapView.swift` together with `MapDatePreset`, the layer rows and the `TE` tokens and toggle style from `IsoMe/TEDesign.swift`. `App/HarnessFonts.swift` replaces the bundled fonts with system faces. The harness does not open the journal, location services, purchases or the production app. Eighteen accepted iOS 27.0 / iPhone 18 Pro simulator cases in `Tests/RangeCalendarTests.swift` establish the references stored in `app/src/test/resources/ios-custom-date-edits.json`.

Each case launches with a closed date range and a time zone (set through `TZ`), taps days, chips, Reset or the month arrows, records the "Show N days" title, and taps Apply. The harness prints the applied bounds as native reference-date seconds. Absolute cases use past days and assert exact bounds; the closed upper bound is the nextDown of Android's exclusive end. Relative cases follow the clock and assert a preset's shape (Today, Yesterday, This month, Last 7 days, All time) or a span ending today that stops at now. The cases cover whole-day snapping, anchors and restarts, ranges across months, New York spring and fall days, the Lord Howe half-hour day and a Santiago day that starts at 01:00, which iOS ends at the next day's 01:00.

From the repository root:

```sh
python3 android/scripts/verify_custom_date_reference.py --check-source
python3 android/scripts/verify_custom_date_reference.py --prepare /private/tmp/whereday-date-reference
xcodegen generate --spec /private/tmp/whereday-date-reference/project.yml
```

The prepare destination must be new; it reports any source or harness change since the recorded reference. Run the generated `DateParity` scheme's UI tests on a dedicated disposable iPhone simulator with parallel testing disabled, and keep its result bundle and toolchain/runtime identity. `--print-digests` prints the provenance block for a new reference. The script verifies source fragments and harness hashes; it does not execute UIKit. Android JVM tests replay every case at several clocks, and Android instrumentation drives the actual calendar controls and the map flow.

If the sheet, calendar or harness changes, rerun native acceptance before updating provenance or expectations. These references do not certify non-Gregorian calendars, all iOS/Android versions, physical devices or full phone parity.
