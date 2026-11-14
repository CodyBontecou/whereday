# Smart Start opt-in Settings slice — partial #60 acceptance

## Implementation

Settings → Recording & Places → Smart Start pushes a Back Pages screen using the existing shared `LocationManager`. Opening it only reads persisted preferences and static permission/capability status; it does not construct another runtime, request Motion permission, or silently enable existing users.

The screen includes the issue's explicit reliability/force-quit/Shortcuts explanation, native opt-in confirmation, Always/Motion statuses and iOS Settings link, driving/walking-running/cycling choices, conservative movement and stationary thresholds (supported maximum900seconds), manual-stop/restart cooldowns, optional stationary stop, battery/false-positive limitations, and existing Start/Stop Shortcuts recipes. No native CarPlay/workout background execution is claimed. Valid custom preference values are preserved in picker choices rather than clamped.

`SmartStartSettingsModel` is a read/save intent boundary: no write on construction or canceled opt-in; only an outstanding confirmed request may enable. Activity/timing edits read the latest stored snapshot, preserve concurrent preference changes and cannot bypass opt-in. Validation precedes the writer. Rejected or silently unapplied writes reload actual settings and show an inline error instead of pretending success.

An alert's presentation binding is separate from pending opt-in intent. A test clearing presentation before the confirmation callback produced an actual RED (eight tests, three failed assertions in one method), then passed after separating those states. The first UI snapshot immediately after a tap was initially interpreted as a persistent no-op; that interpretation was corrected: it was too early to establish settled state or callback ordering on this OS. The regression proves robustness to that event ordering, not that an observed OS trace established it.

## Source/test verification

`SmartStartSettingsModelTests` adds eight cases: open/cancel do not write, confirmation is necessary and exactly once, immediate disable preserves modes, edits use latest stored fields, generic edits cannot enable, thrown saves roll back/show errors, invalid thresholds never reach the writer and can be corrected, and unapplied writes cannot appear successful.

Targeted model/monitor/correction selection: **36 passed**, zero failures. Full signed `scripts/test-ios.sh` run: **580 XCTest cases, zero failures;20 Swift Testing tests passed**. Result: `artifacts/test-results/IsoMe-20261114-044815-92862.xcresult`; log: `artifacts/merge-readiness/smart-settings-full.log`. This is source/runtime regression coverage, not physical background acceptance.

## Actual UI-only simulator observations

A new owned iPhone17Pro/iOS26.5 simulator (`EEBF0516-FB5A-42E8-9759-CCDA6D2139F5`) ran the built app, **without running unit-test fixtures on that guest**. Launch bypassed only existing welcome onboarding (`-hasCompletedOnboarding YES --disable-debug-gestures`); navigation used the actual Archive/Settings/Smart Start path, not a new preview or release-only override. Simulator.app and Argent/Appllama tools were unavailable; existing AXe semantic targeting and explicit-UDID simctl capture were used. No Argent YAML/replay verdict is claimed.

Observed:

- Fresh screen visibly Off, Location Not set, Motion Unavailable; opening and editing the screen produced no permission dialog in this UI-only run.
- Cancel leaves Off; confirmed Turn on reaches settled AX value1; subsequent disable reaches0. Semantic checks wait for the actual destination/value rather than accepting immediate transitional snapshots.
- Native movement picker changes2→5minutes; enabling stationary stop reveals its10-minute threshold.
- Back returns to the actual Settings link. Reopening and terminating/relaunching the app preserve5minutes and Off.
- Actual inspected light screenshots show visible picker labels (an earlier implementation hid them outside Form; `BackPagePickerRow` corrected this). Dark/accessibility-extra-large samples show wrapping/stacked controls and readable limitation text. The stored normal dark screenshot caught a theme transition and is not used as a settled contrast proof; a later stable dark XL sample is retained.

`smart-start-settings-20261114/ui-checkpoints.json` contains actual selected AX values/labels and digests of raw observations; the referenced recording is426.818333seconds /113174999bytes, SHA256 `407d6672d802bb2118252edaa03bb39cd42800143b071c8b87a591a77234b8e1`. Raw artifacts remain `/tmp/whereday-merge-readiness/smart-settings-ui-4/`. Capture began before launch. One invalid scroll-delta command and a tap attempted during deceleration were retained; target was reobserved before retry. This is not represented as a flawless automated e2e pass. Entire recording has **not** received a full-speed/frame-by-frame performance audit; no60fps/release-device claim follows from its existence or screenshot samples.

The separate prior test-host guest (`9DBF4207-832B-4A34-88DB-AE841A6383DA`) had queued Notification/Motion prompts after the full runtime suite. Its interrupted third capture is retained, not a clean UX result or evidence that opening Settings requested Motion. The UI-only guest avoided that ambiguity. Both owned guests are shut down; recorders stopped, UI-only appearance/content size restored/read back to light/large. No physical/default simulator or original dirty fleet lane was used.

## Hosted provenance follow-up

At `37650bee`, hosted iOS tests and location recovery passed, but Android build stopped at the visit-reconciliation source-provenance check. The Settings-facing comment edit in `LocationManager.swift` legitimately changed its whole-file source digest. Ran the actual Swift producer (`python3 android/scripts/generate_visit_reconciliation_fixtures.py`), not a hand-edited hash. All17 generated reconciliation case outputs compare identical to the prior committed cases; both fixture copies match byte-for-byte. Only their provenance digest changes. All **31** actual CI `--check-source` commands then pass; log `artifacts/merge-readiness/smart-settings-provenance-checks.log`. This metadata correction changes no iOS behavior and does not require repeating the already passing580+20 suite locally. New hosted Android build still must complete.

## Still open — keep PR120 draft

Follow-up [localization verification](smart-start-localization-20261114.md) now covers the new Settings/Shortcut copy in all11 supported locales, actual compiled lookups, Spanish/Arabic opt-in cancellation and an RTL caption correction. This is not complete localized/spoken UI acceptance; denied/authorized permission UI on real hardware remains unverified. Optional onboarding/post-onboarding explanation, expanded intent source/duration/profile options, real locked/background/force-quit/reboot delivery, CarPlay/Bluetooth/workout/Focus recipes in actual Shortcuts, battery/false-positive tuning and complete motion/accessibility acceptance remain. This screen, its model tests and simulator observations do not complete issue60 or establish whole-PR merge readiness.
