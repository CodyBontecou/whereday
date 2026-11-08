# iOS search, recap, and replay edge QA — 2026-11-07

Explored the existing Whereday fixture using Argent on iPhone 18 Pro, iOS 27.0, UDID `9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A`. App launches used English and a process-only webhook-disabled override. No visits, saved places, or country stays were created, edited, or deleted.

## Findings and fixes

### Monthly recap loses occupied days

The existing November recap showed **6 days**, although the ongoing Ferry Building visit extended into November 7. The computation inserted a visit into only its clipped arrival day. Overnight and multi-day visits therefore understated the days recorded and daily place counts.

Fixed by walking calendar days through each visit's clipped occupancy interval. Departure is exclusive, so a visit leaving at midnight does not occupy the following day. Calendar arithmetic handles daylight-saving transitions, and repeated visits to one place count once per day. Durations remain clipped to the month and now.

Live Argent verification on the rebuilt app showed **7 days**, with the same 8 places and 19.9 km. See [before](recap-before.png) and [after](recap-after.png).

Additional regression cases exposed next-month midnight points being attributed to the first day of the preceding month, next-month arrivals inflating its new-place count, and future records appearing in the recap. These came from inclusive `DateInterval.contains` checks and unbounded arrival filtering. The recap now uses an exclusive month end and ignores future/invalid visits and future points. Route points are sorted before distance calculation to make unordered inputs safe.

### Replay's right edge rolls into the following date

On Yesterday, entering replay and dragging the time scrubber to its far-right edge changed the heading from **Friday Nov 6** to **Saturday Nov 7 · 00:00**, while Yesterday remained selected.

The full-day axis intentionally ends at next midnight. The playhead now clamps to the selected day's last second while retaining the full drawing axis, so intermediate clock positions do not shift. Dragging, VoiceOver steps, and default focus share this clamp. Multi-day ranges retain their exact upper boundary.

Argent verified that the rebuilt app's right edge reads **Friday Nov 6 · 23:59**, pauses playback, and retains all 63 route points and 12.6 km. Done restores the normal Yesterday map. See [before](replay-midnight-before.png) and [after](replay-midnight-after.png).

## Other live checks

- An unmatched map query displays the empty state.
- A query with leading whitespace finds The Grove; selecting it switches to the correct older day and visit.
- A pasted query containing emoji, Japanese text, punctuation, and 240 uninterrupted ASCII letters wraps in the empty state without a crash or covering the clear/close controls. Clearing restores saved places, and cancelling returns to the map. See [long Unicode query](long-unicode-search.png).
- Replay on a day without route points does not start invalid playback. A populated day's replay supports speed changes, scrubbing, and Done.

Argent's keyboard tool rejected the emoji before text entry (`No keycode for character`); the paste-handling check supplied the complete Unicode string successfully. This was a tooling limitation, not an app failure.

## Automated verification

All runs used `scripts/test-ios.sh` with normal simulator signing and the explicit device destination.

| Run | Result | Result bundle |
| --- | --- | --- |
| New recap tests against original computation | All 6 cases failed as expected | `artifacts/test-results/IsoMe-20261107-210255-59998.xcresult` |
| Recap fix plus existing remote-visit/search and replay clock tests | 22 passed | `artifacts/test-results/IsoMe-20261107-210410-63725.xcresult` |
| Final recap and replay fixes | 25 passed, 0 failures | `artifacts/test-results/IsoMe-20261107-210740-70520.xcresult` |

The final run includes 6 MonthRecapTests, 3 TraceDayDomainTests, 12 RemotePastVisitTests, and 4 ReplayClockTests. New cases cover multi-day occupancy, both month boundaries, midnight departure, ongoing stays, duplicate place/day counting, future records, outliers, unsorted points, endpoint overshoot, accessibility steps, unchanged intermediate axis times, DST days of 23/25 hours, and multi-day endpoints. `git diff --check` passes. The full suite was not rerun for this pass.

## Saved replay limitation

Live AX checks and screenshots passed. Portable saved e2e replay is **not verified**. The recorder's native hierarchy incorrectly captured Atlas as Map and recap/Yesterday as AXScrollArea, although the live accessibility tree exposed the correct labels. After correcting the recap draft to the labels actually tapped, replay launched and passed its raw accessibility wait but failed to resolve Atlas in the runner hierarchy. No coordinate fallback was used to turn this into a claimed pass.

[Recap replay diagnostic](recap-replay-blocked.yaml) and [replay capture diagnostic](replay-capture-blocked.yaml) are archived outside the active e2e directory. The latter retains the incorrect captured selectors to document the failure; it is not a runnable regression test. The acceptance evidence is the signed unit tests plus live Argent UI checks above. Existing simulator data was preserved.
