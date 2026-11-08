# Android edge case QA November 7 2026

The findings below record the original pass before fixes. See [fixes and continued QA](fixes-and-followup.md) for the current resolved status and verification.

One confirmed bug was found in an Argent exploratory pass across month recaps, saved-place validation, manual visits, local map search, date selection and route replay. No application source was changed.

## Confirmed bug

**P2: Month recap counts only the arrival day of a visit spanning multiple days.**

On the existing synthetic journal, November initially showed **1 DAY**, one visit and approximately 4 km. A temporary confirmed visit named `QA overnight 20261107`, at coordinates `0,0`, was added through Map → Add a place → Log a past stop. It ran from **November 4 at 15:21 to November 6 at 16:21**, in America/Puerto_Rico.

Opening Atlas → Month recap showed **2 DAYS** and two visits. The correct count is **4 DAYS**: November 4, 5 and 6 occupied by the temporary visit, plus November 7 in the existing journal. The dials show the temporary visit across all three days, so the card contradicts its own day count. The incorrect count survived portrait → landscape → portrait recreation.

Evidence: [full resolution recap screenshot](recap-undercount.png). The bug was observed live before and after rotation; the saved smoke tests below do not assert this bug.

The current source is consistent with the symptom: `MonthRecap.of` in `android/app/src/main/kotlin/com/bontecou/whereday/MonthRecap.kt`, lines 87–88, adds a visit only to the arrival day's `perDay` entry. `recordedDays` at line 103 uses those entries, while the dial arcs at lines 91–96 separately include every overlapping day. A fix should count each civil day with a positive visit overlap, respecting the end-exclusive boundary and month clipping.

The temporary visit was deleted through its own Details → Delete visit confirmation. Existing records and the saved place were preserved. The final Archive count was the original **25 route points**.

## Other observations

| Scenario | Observed result |
| --- | --- |
| Previous month with no history | Zero days and empty recap rendered correctly |
| Save a place with all required fields blank | Save rejected with name, coordinate and radius validation message |
| Back from a changed manual visit, then Keep editing | Name draft retained |
| Public coordinate `0,0` and a 49-hour past visit | Accepted and restored correctly in the editor |
| Local map search containing `%` | No matches; it did not act as a wildcard |
| Search for the temporary visit outside Today | Located the record and opened its map sheet and editor |
| Future days in the map calendar | Disabled; current-day visit focus resolved to Show 1 day |
| Replay end control | Reached 10 of 10 points, 1:59 elapsed and 3.67 km |
| Replay start, Play, Home and resume | Controls remained usable; replay was paused on return |
| Replay close and return to Archive | Returned successfully; original point count remained |

The ten-point route completes quickly. End-of-route Play checks establish that controls remain available, not a timing assertion that animation restarted or that no frames advanced while backgrounded.

A past visit focus also displayed November 4–5 for the November 4–6 visit. The current focus implementation deliberately opens the arrival day through the next midnight plus one millisecond. This is retained as a behavior to review, not a second confirmed defect.

## Automated verification

Two recorded paths were polished into semantic selector tests and replayed without manual intervention:

- `android/.argent/e2e/atlas-empty-month-and-place-validation-20261107.yaml`: **PASS**, 24 passed, zero failed or errored, 16.4 seconds.
- `android/.argent/e2e/search-replay-boundary-smoke-20261107.yaml`: **PASS**, 40 passed, zero failed or errored, 43.3 seconds. The runner listed 41 directives, including an uncounted leading echo.

Run with Argent `e2e-execute`, passing the absolute Android project root, the test name and an explicit Android emulator device. The first is a fragment requiring Archive already open and `prerequisiteAcknowledged: true`. Both are scoped to the recorded November 2026 synthetic data and English labels; the second requires the existing Test stop visit and ten in-range replay points. Neither writes journal data. The first ends on Places; the second ends on Archive.

Final tests contain no raw coordinate actions. The Test stop capture initially fell back to a point; the exact text selector `^Test stop$` was subsequently verified by the complete successful replay.

## Environment and evidence limits

- Device: `emulator-5554`, AVD `whereday-redesign-api35`, Android API 35, 1080 × 2400 portrait, English, America/Puerto_Rico.
- Installed application: isolated `com.bontecou.whereday.mapqa`, version `0.1.0-mapqa`, version code 1; package last update reported November 7 2026 at 13:46:05.
- Workspace HEAD at inspection: `f272a27631fd39c47c181c22822db79d08a7af4e`. The installed APK was not rebuilt or replaced in this pass.
- Argent live discovery repeatedly retained stale element trees after navigation. Device-scoped inspection services were refreshed and screenshots checked before judging app behavior. The first failed empty-month wait was a stale-tree artifact; the fresh check and automated replay passed.
- `exploration-recording.yaml` and `search-replay-recording.yaml` are **raw diagnostic transcripts, not portable regression tests**. They retain the stale wait, original coordinate fallbacks and exploratory behavior. The temporary-visit transcript must not be replayed unattended. Only the two polished files under `.argent/e2e` have full replay passes.
- No data clearing, app uninstallation, owner-phone interaction, purchase, message sending or recording start occurred. No iOS tests were needed for this Android-only QA pass.
