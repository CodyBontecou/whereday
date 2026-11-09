# Android export clock QA — November 8, 2026

Explored clock filters and free export previews with Argent on `emulator-5554`, AVD `whereday-redesign-api35`, API 35, English, America/Puerto_Rico, 12-hour clock. Used only `com.bontecou.whereday.mapqa`. No new application bug was confirmed, and no application source was changed in this pass.

## Live checks

| Edge case | Observed behavior |
| --- | --- |
| Keyboard hour `99`, then Apply time | Invalid text stays in the dialog; it is not applied |
| Invalid text → landscape → portrait | `99` and keyboard input mode survive both recreations |
| Correct to 11:59 PM | Filter row displays 11:59 PM |
| Set end to 12:00 AM | Midnight displays correctly and the overnight window is accepted |
| Preview the one-minute overnight window | Valid JSON, zero visits and zero points for the existing daytime fixture |
| Reopen midnight, select 9 AM, Cancel | End time remains 12:00 AM |
| Disable the clock filter and preview again | Original 25 points and one visit appear |

No new records were added, no imports committed, no exports delivered, and no permission or recording changes made. Saved places were not edited. All dates, JSON, and All data were retained. Purchases are unavailable in this build, so this pass covers free previews rather than paid delivery.

## Recording and replay

The [raw exploration](raw-exploration.yaml) preserves failed waits and exploratory cleanup mistakes. Do not replay it unattended. The polished test is [export-clock-edge-20261108.yaml](../../../android/.argent/e2e/export-clock-edge-20261108.yaml). It requires the fixture and settings described above, with the clock filter off and advanced options collapsed. It restores that state and returns to Map.

Argent sometimes served stale trees after navigation. Device-scoped service refreshes revealed the current UI. An initial unreadable startup wait was retained in the raw trace and immediately re-recorded successfully with a longer budget. The `export-screen` root tag was not exposed live; the existing `export-history-count` tag identified Archive instead. PM selection initially captured a duplicate Android ID; the polished target combines that ID with exact PM text. The PM spinner text check remains a raw tool check because the runner and discovery trees expose its label differently. Clock dial targets use exact numeric text and the radial-clock role.

The first full replay stopped at the expected 8 AM row because the exploratory cleanup had accidentally re-enabled the daytime filter; the replay therefore toggled it off. That was a test setup mistake. The unintended cleanup tap was excluded from the polished test and the start state was corrected through the UI. A daytime preview returning 25 points alone does not establish that the filter is off: the fixture also matches the default daytime window.

No build or unit test run was needed for this pass because only QA artifacts changed. `git diff --check` passed.

Two consecutive complete replays of unchanged final YAML passed without manual intervention between runs: 81 executable checks/actions (82 entries including echo), zero failed/errored/skipped, in 50.1 s and 52.0 s. Neither run carried warnings. See [replay 1](replay-1.txt) and [replay 2](replay-2.txt); the earlier setup failure is retained in [setup-failure.txt](setup-failure.txt). The second run also proves that the first run's cleanup restored the required clock-filter and advanced-panel state.
