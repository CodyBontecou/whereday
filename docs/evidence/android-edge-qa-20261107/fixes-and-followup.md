# Android QA fixes and continued exploration

The November 7 follow-up fixed the recap undercount, a related daylight-saving dial defect, and a Settings navigation defect discovered during continued Argent exploration. The isolated API 35 QA app was updated with `adb install -r`; its journal was preserved.

## Recap day counts and dials

`MonthRecap.of` now uses the same positive overlap with each civil day for its place counts and dial arcs. A visit spanning November 4–6 counts all three occupied days. Midnight departures exclude the following day; open visits are clipped to the current instant. Month boundaries and repeated place names remain bounded and deduplicated by day.

Dial fractions now use the actual duration between consecutive local midnights. A full 23-hour spring-transition day fills its dial, as does a 25-hour autumn-transition day.

Five new `MonthRecapTest` cases failed before the changes and passed afterward. They cover the original three-day visit plus a point-only day, midnight departure, an open visit at midnight, month clipping and daily place deduplication, and both daylight-saving transitions.

Argent verified **4 DAYS** for the original reproduction, then removed only the temporary visit. See [corrected recap](recap-fixed.png). The complete recorded regression, `recap-fixed-check-20261107`, passed **69 steps**, including fixture setup and cleanup. It uses the two `recap-fixture-*` fragments and requires the recorded November 7 synthetic journal baseline.

## Settings navigation across window changes

Continued exploration found that opening Settings from Archive, searching for MapLibre, opening its ICU notice and rotating to landscape returned to the Settings overview. Returning to portrait then showed Archive. The Settings screen and child document each already used saveable state, but the shell put phone and rail Settings under different saved-state owners.

The shell now gives Settings one shared movable composition and saved-state provider, and uses the existing destination resolver when the window changes. Settings stays open behind Archive when the rail closes. This applies to the Settings host; specific child screens still retain only the state they explicitly save.

Argent verified the ICU notice remained open through portrait → landscape → portrait and Home/resume. Back returned to the component documents, then the library with its mixed-case `mApLiBrE` query, then Settings, then Archive. Compare [before](notices-lost-on-rotation.png) with [fixed landscape](notices-rotation-fixed-landscape.png).

`settings-rotation-fixed-20261107` passed **94 steps** in 43.9 seconds. Its `settings-notices-opening-20261107` fragment also checks the distance filter at 200 m through Home/resume, restores 5 m, tests punctuation resembling a SQL expression as a literal search, and checks case-insensitive library matching. The final test uses semantic selectors; recorder fallbacks for the filter option and MapLibre row were replaced and verified by full replay.

## Verification and remaining scope

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleMapQa`: **BUILD SUCCESSFUL** after the final changes.
- All **102 app unit tests** pass, with zero failures or errors.
- The two new complete Argent regressions pass without manual rescue. The recap end-to-end pass preceded the Settings-only shell change; its five unit cases also passed in the final build.
- Some idle checks reported a small moving region. Live inspection showed an editing caret on search/input screens and loaded document content after rotation. The tests gate the next actions on explicit destination and content checks, not stillness alone.
- Final state: portrait, Archive, **25 route points**, original visit and saved place retained, distance filter restored to **5 m**, no active recording started.
- This is API 35 emulator verification. Physical devices, other API levels, process death, live fold/hinge movement and every Settings child destination have not been validated by this pass.

[Machine-readable verification and QA APK digest](fix-verification.json). Raw pre-fix exploration remains diagnostic evidence, including `settings-notices-rotation-before.yaml`; it is not a portable regression test.
