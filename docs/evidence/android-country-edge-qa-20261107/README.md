# Country stay edge case QA November 7 2026

**Update:** Both the rotation bug and the subsequently found pending-anchor bug when applying picker dates are now fixed. See [picker fix verification](picker-fix.md) and [original fix verification and follow-up findings](fix-and-followup.md). The rest of this document records the original observation-only pass.

One new reproducible app bug was found in the country-stay calendar. This pass also checked leap dates, future dates, cancelled nested edits, country search, and fold/background transitions. No app source was changed in this pass and no country stay was saved.

## P2 Rotation forgets an unfinished country stay range

Path: Settings → Country Days → Add country stay → inline calendar.

1. Without rotating, tap November 3 and then November 6. The form correctly shows First date November 3 and Last date November 6.
2. Start a new range by tapping November 3 again.
3. Rotate to landscape and back before choosing the end date.
4. Tap November 6.
5. The form now shows **First date November 6 and Last date November 6**, instead of November 3–6. Saving at this point would save a one-day stay rather than the intended four-day stay.

The form and selected date survive recreation; the pending first-tap anchor does not. In `android/app/src/main/kotlin/com/bontecou/whereday/CountryStayEditor.kt`, `StayCalendar` saves its displayed month with `rememberSaveable`, but its `anchor` at line 155 uses plain `remember`. The tap handler at lines 182–183 treats the next tap as a new start after recreation.

The symptom was observed manually and then reproduced by a complete unattended semantic-selector replay. [Screenshot of the resulting one-day draft](anchor-lost-after-rotation.png).

Historical test: [rotation-original-bug-repro.yaml](rotation-original-bug-repro.yaml), archived after the fix. Argent reported **42 passed, zero failed or errored, 31.6 seconds** (43 directives including the leading echo). This was a **known-bug reproduction**: its checks deliberately confirmed the incorrect November 6-only result, alongside the correct control case. The active regression now checks the intended November 3–6 range.

The test discards its draft and verifies Country Days still shows **0 days**. It requires an onboarded English mapQa app on an open foldable with guest Vulkan enabled and the recorded November 7, 2026 date context.

## Other exercised cases

| Case | Observation |
| --- | --- |
| Fresh isolated app onboarding without location or notification grants | Completed the onboarding pages; map opened after correcting guest Vulkan setup |
| Country Days with no history | Zero days and zero countries; export action disabled |
| ISO date 2026-02-29 | Rejected with “Enter two valid ISO dates”; Apply disabled |
| ISO date 2024-02-29 | Accepted as a valid leap date; Apply enabled |
| Through date 2026-11-08 while today is November 7 | Rejected with “Choose dates on or before today”; Apply disabled |
| Cancel ISO edits | Parent country-stay draft retained its prior November 6 dates |
| Mixed-case, unaccented country search `cOtE` | Matched Côte d’Ivoire (CI) |
| Close foldable with country picker open | Search query and matching result survived |
| Home/resume with changed country draft | Country selection retained |
| Discard draft after reopening the foldable | Returned to zero-day Country Days; no stay persisted |

The raw exploratory trace includes unsuccessful cover-screen taps and one unmet wait during the Keep editing check. Those interactions were not counted as a second app bug or as a passing Keep editing test. Cleanup succeeded after unfolding. The focused saved reproduction excludes these automation ambiguities and passed end to end.

## Environment issues distinguished from app findings

The original API 35 QA emulator (`emulator-5554`) stopped answering screen inspection and ordinary Android shell requests promptly. Its journal was not reset. Work continued on the separate `whereday-m3-fold-api35` AVD (`emulator-5562`), installing only `com.bontecou.whereday.mapqa`. The existing main application on that AVD was left untouched.

Argent initially booted the foldable with guest Vulkan disabled. At onboarding completion, the map's renderer crashed with `java.lang.Error: No Vulkan compatible GPU found`. [Crash log](vulkan-disabled-crash.txt). Rebooting only this task-started emulator with `guestVulkan: true` made the same QA APK open the map successfully. This setup-dependent failure is not counted as a new product regression; rendering without a compatible GPU remains a separate compatibility question.

The foldable cover screen also exposed inconsistent normalization between screenshots and the live tree. The final replay uses semantic selectors and tests the rotation bug on the open inner display. The broad manual checks and the focused replay have different scopes.

Evidence files `raw-exploration.yaml`, `stalled-device-recording.yaml`, and `vulkan-disabled-onboarding-recording.yaml` are diagnostic transcripts, not portable regression tests. No purchase, country analysis, recording start, app-data clearing, or owner-phone interaction occurred. No unit or iOS tests were needed for this observation-only QA pass. Prior fixes and unrelated workspace changes were preserved.
