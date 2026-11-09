# Photos and nested map navigation QA — November 8, 2026

Used Argent with the isolated `com.bontecou.whereday.mapqa` app on `whereday-m3-fold-api35` (`emulator-5562`). Explored photo picker cancellation, denied library access, a photo without EXIF date/location, duplicate selection, a disappeared source image, reference removal, and Road display entered from Map Layers.

## Fixed: Road display navigation lost on rotation

Path: Map → Layers → Match roads → rotate landscape. Before the fix, Road display disappeared and Layers returned. A recorded `road-settings-screen` visibility assertion failed with an unmet condition; the live tree showed `map-layers-content` instead. [Before-fix screenshot](road-screen-lost-before-fix.png), [recorded reproduction](raw-road-reproduction.yaml).

`MapLayersSheet.kt` held its nested `roads` flag in plain `remember`. Changed it to `rememberSaveable`. Also passed the existing localized Layers label into Road display's back header; it previously announced “Back to settings” despite returning to Layers. The Settings entry retains its default label. No routing preference, consent or provider behavior changed.

Verification on the updated APK:

- The focused 13-directive landscape probe passed: 12 passed, zero failures/errors/skips, 28.7s. Its last idle gate warned of residual motion; subsequent live inspection confirmed Road display and the Layers back label remained visible.
- The separately recorded continuation verified Road display in landscape, the Layers label, rotation to portrait, Back to Layers, Back to Map, and “No places yet.” All eight live steps completed successfully. [Continuation trace](raw-road-return-tail.yaml).
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleMapQa` succeeded; **102 unit tests**, zero failures/errors/skips. `git diff --check` passed. iOS source was unchanged, so iOS tests were not run.
- QA APK updated with `adb install -r`; no app data was cleared.

The combined test `android/.argent/e2e/map-road-navigation-regression-20261108.yaml` retains the full expected behavior, semantic targets, identity checks and idle gates. **A complete unattended pass of that final 26-directive test remains blocked by the emulator issues below.** It is not certified as a passing full regression. The landscape probe and recorded live continuation are narrower evidence. Prerequisites are English onboarded mapQa, open foldable initially in portrait, and an empty current day. No routing provider or permission grant is needed.

## Photo edge cases exercised

| Case | Result |
| --- | --- |
| Cancel Android photo picker after rotation | Returned to Photos with zero references |
| Cancel Android image-file picker | Returned without saving references |
| Deny photo/video library access | Access remained “not allowed”; sync and original-location actions stayed disabled |
| Explicitly choose repository image through file picker despite denied library access | One reference added; image displayed without inventing capture time/location |
| Choose the same URI again | Still one saved reference |
| Remove only the disposable source file, then Refresh | Reference retained and marked unavailable |
| Open unavailable reference | Viewer displayed “Photo unavailable”; navigation for a single image was disabled; removal remained available |
| Cancel removal confirmation | Viewer/reference remained |
| Confirm removal | Returned to zero saved photo references |

The fixture was a copy of `android/app/src/main/res/drawable-nodpi/journal_clouds.png`, uploaded as `/sdcard/Download/whereday-photo-edge-20261108.png`. Only that disposable device copy was deleted; the repository image and other device media were untouched. Its Whereday reference was removed through the UI. No visits, points, saved places, purchases, routing consent or recording were created. Denial of library permission remains in effect. No functional photo defect was established in these checks.

The broad [photo trace](raw-photo-exploration.yaml) is a manual diagnostic recording, not a portable automated test: source-file upload/removal happened outside the recorder, it includes an orientation-related mistap into the file picker, and a final unreadable wait when the app stalled. System-picker repeated IDs caused coordinate warnings. Those raw taps are not claimed as robust regression targets. The initial attempted launch used a serial whose AVD had stopped; it failed before recording a step, then the task AVD was booted without clearing its data.

## Unresolved emulator rendering/connection failure

Opening Map Layers after the photo checks produced Android's “app isn't responding” dialog. [Screenshot](map-layers-anr.png), [activity ANR summary](lastanr.txt), [latest QA-app DropBox ANR entry](dropbox-anr.txt). Other applications' DropBox entries and older QA-app entries were excluded from the retained dump.

The app main thread was waiting in `HardwareRenderer.syncAndDrawFrame`; Android's RenderThread was in `eglSwapBuffers` → `rcCreateSyncKHR_enc` → `QemuPipeStream::commitBufferAndReadFully` / `qemu_pipe_read`. This localizes the sampled stall to the emulated rendering path, but does not establish its root cause or prove an app-side fix for it. No speculative renderer change was made.

A restart temporarily restored device control and allowed the successful fixed landscape probe and continuation above. The subsequent extended test errored at an idle gate before rotation because the tree source stopped answering. Another restart then failed because the old emulator process remained stuck; its console acknowledged shutdown but the process did not exit. A normal termination signal also failed to release it. No force kill or data wipe was used. A fallback read of the other Whereday emulator also failed to produce a usable tree and was cancelled; its app/data were not modified.

Thus the full replay and further map-layer stress testing remain unverified. This failure is retained separately from the reproduced/fixed navigation defect. Prior fixes and unrelated workspace edits were preserved.
