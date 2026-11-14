# TalkBack input capability: static heading reached, acceptance still incomplete

## Actual tested artifact

Unmodified #113 head `f4bb9e94`, dedicated disposable mapQa APK SHA256 `494f3647e179ed33b513a5fa8f82bada0ae88d914eb45efad85c4577b4e52606`. Installed TalkBack `15.0.0.639625893`, API35 arm64 owned read-only emulator, two cores/2 GiB, private ADB5064/emulator-5586. Synthetic renewing-yearly subscription only. No purchase, billing grant, cache/token change, real cancellation, physical/default device, UiAutomation or additional accessibility service. Existing native20 and JVM49 receipts are separate and not repeated here.

## Distinguishing input failure from missing semantics

A fresh bounded session recorded actual TTS output plus exact-window Peekaboo/scrcpy observations. Foreground `press alt+right` through scrcpy's UHID keyboard moved from Back to Manage or cancel. **Kernel getevent contained Right-down/up, but no Alt event.** Tab and Enter did reach the device. This demonstrates modifier loss on that delivery path, not an app static-text defect.

A distinct SDK `input keyboard -d 0 keycombination -t 120 57 22` also reached Manage. The independent SDK `57 113 66` read-from-top candidate opened the confirmation sheet instead; raw ASR says `Close sheet. Out of list. Bottom sheet.` It was not a successful TalkBack read-from-top command. Kernel absence is not used to judge SDK injection, which is a different virtual-input path. No semantics patch was made from either failed command.

Initial standalone setup used the incorrect activity suffix derived from applicationId; installed manifest names the class `com.bontecou.whereday.SubscriptionPreviewActivity`, not `com.bontecou.whereday.mapqa.SubscriptionPreviewActivity`. Setup exited/cleaned; first error's stderr was not captured. The next setup used the actual fully qualified manifest component and retained failed-command diagnostics. This is tooling setup, not an app regression.

## Verified kernel-backed replacement

The adjacent `talkback-input-20261114/OwnedKeyboardProbe.java` implements a tiny shell-UID UHID keyboard diagnostic using installed NDK27.1 `linux/uhid.h` CREATE2/INPUT2 layouts and an eight-byte generic keyboard report. It is evidence/tooling source only, outside all app source sets. No root, permission grant, accessibility service, input-filter API or app-internal dependency. Its command allowlist, five-minute lifetime and finally release/destroy prevent arbitrary text entry and lingering held modifiers. Run it **only through the owned emulator/session guards**, never on an owner/default device.

Actual runtime reports `READY uid=2000 descriptorBytes=45`. Kernel events now contain Alt-down → Right-down → Right-up → Alt-up. InputReader registers an external alphabetic keyboard. All final pressed keys were empty; counts matched: Alt4down/4up, Right3/3, Tab1/1, Ctrl1/1 and Enter1/1. These include one delivery calibration before TalkBack, not three independent app acceptance cases.

With TalkBack bound and notification prompt dismissed without granting notifications:

1. **Physical Alt+Right from the app's Back control moved the green accessibility outline to the static Subscription title.** Captured uncorrected ASR: `Subscription, heading`. This is actual static-node/heading-role speech and focus, not just keyboard-focusable buttons.
2. Physical Alt+Ctrl+Enter triggered TalkBack's own **How continuous reading works** first-run dialog, not an app action. The retained final video frame shows its explanatory text, checked **Always show this message**, and **OK** button. This validates that TalkBack handled the chord, but interrupts full-page reading. No full-page read pass is claimed.
3. No reading-control settings were changed. Heading-role speech does **not** establish navigation with the Headings reading control.

Raw video/audio and uncorrected base-model ASR remain under `/tmp/whereday-merge-readiness/talkback-owned-3/`; primary `physical-static.mp4` SHA256 `a56adad0e1880978123a36c34e2182ff6a7ac8bc5384eb5522fa45d481461388`. Its final frame is preserved adjacent to this receipt. The previous failed-input controls remain under `talkback-owned-2/` with kernel/SDK command logs, four raw media files and transcripts. Source helper SHA256 `eeca04fe47cf10de5810ae50ca4fc346f3ab7ee88577b0afedb47e7eabcab9a0`.

Local ASR reused the verified multilingual base model (SHA256 `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`), four CPU threads, no GPU. Derived WAVs are 16kHz mono with loudness normalization; original recordings remain unchanged. Brand and `Alt`/`Manage or cancel` recognition errors remain uncorrected; token probabilities are not calibrated confidence or translation/pronunciation acceptance.

## Reproduction boundary

Example build with installed JDK17/SDK35/build-tools36 (no download):

```sh
javac --release 8 -classpath "$ANDROID_HOME/platforms/android-35/android.jar" -d classes OwnedKeyboardProbe.java
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 35 --lib "$ANDROID_HOME/platforms/android-35/android.jar" --output . classes/*.class
zip owned-keyboard.jar classes.dex
```

The initial `-bootclasspath android.jar -source8` build failed on the SDK's LambdaMetafactory stub; `--release8 -classpath android.jar` then D8 compiled successfully. This is not an app build failure.

The retained manager `/tmp/whereday-merge-readiness/talkback-session-manager.py` is currently configured for **already-existing session3** and must not be blindly rerun. It guards ports/runtime lock/named read-only AVD/uninstalled QA package, starts the shell helper, queues fixed commands, records timings and restores state. Allocate a fresh output directory and inspect guards before another session. Private helper jar is pushed only to scoped `/data/local/tmp`, removed during cleanup; never install it into the product.

## Cleanup / next acceptance

Both actual sessions restored/read back all seven saved settings. POST_NOTIFICATIONS returned exactly to `granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED]`. QA package uninstall succeeded; helper released/destroyed and exited0; observer stopped; emulator graceful exit0 and private ADB stop0. Viewer did not exit within its SIGINT ten-second wait; the original receipt retains that timeout, and additive audits prove its exact PID was absent after emulator disconnect. No full shared-AVD byte-identity claim is made beyond read-only invocation and explicit restoration evidence.

Next session can use the **verified kernel-backed path**, not repeat the failed Peekaboo/SDK modifier experiments. Add/reverify physical Alt+Enter and the documented reading-control chords, dismiss the *observed* continuous-reading education dialog via OK while leaving its checkbox unchanged, then record full app/sheet static traversal and heading-control navigation. Real Settings/Export owners, focus restoration, Back/Keep/scrim, large text, Arabic/Spanish workflows remain open. Preserve existing owner localization acceptance; no copy changes or new owner manual QA request follows from this tooling experiment.

**#115 and the overall merge-readiness goal remain incomplete.**
