# Native custom-date reference

This isolated UI harness executes `DateRangeFilterSheet` extracted from the current iOS source. It does not open the journal, location services, purchases or the production application. Nine accepted iOS 27.0 / iPhone 18 Pro simulator cases in `Tests/PickerTests.swift` establish the Gregorian date-binding references stored in `app/src/test/resources/ios-custom-date-edits.json`.

The cases cover edits of either bound, selecting the same day, changing back to the original day, New York spring gaps and repeated hours from either offset, a Lord Howe half-hour gap, and retaining an adjusted clock through another edit. An unchanged bound retains its original precision; a changed day keeps its current hour/minute and clears seconds/fractions. A repeated hour resolves to the first occurrence. Gap resolution moves the clock forward by the gap. Android converts the inclusive upper bound to its half-open millisecond history window.

From the repository root:

```sh
python3 android/scripts/verify_custom_date_reference.py --check-source
python3 android/scripts/verify_custom_date_reference.py --prepare /private/tmp/whereday-date-reference
xcodegen generate --spec /private/tmp/whereday-date-reference/project.yml
```

The prepare destination must be new. Run the generated `DateParity` scheme's UI tests on a dedicated disposable iPhone simulator, with parallel testing disabled. Preserve its result bundle and exact toolchain/runtime identity. The script verifies the source fragment and harness hashes; it does not execute UIKit. Android JVM tests replay the recorded date-edit sequences, and Android instrumentation checks the actual controls and map flow.

If the sheet or harness changes, run native acceptance again before updating provenance or expected bounds. These selected references do not certify non-Gregorian calendars, all iOS/Android versions, physical devices or full phone parity. The Android UI rejects invalid retained instant order instead of constructing an invalid closed range.
