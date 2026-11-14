# English TalkBack content observations — limited acceptance surface

Same unmodified #113 `f4bb9e94` mapQa APK as the input-capability receipt: SHA256 `494f3647e179ed33b513a5fa8f82bada0ae88d914eb45efad85c4577b4e52606`. Fresh owned read-only API35 emulator, private ADB5064/emulator-5586, TalkBack15.0.0.639625893; synthetic renewing-yearly standalone preview. No production source/resource changes, actual billing action, purchase grant, physical/default device, or localization-copy change.

The extended evidence-only UHID helper adds physical activation/Back and reading-control commands plus shell-UID/ranchu guards. Adjacent source SHA256 `7dd7392892049150ae4f235f12d11ed5994f2ef3c2cfa7ad2cc5fd09b8fc4a17`. Activation, Back and read-top were exercised; the newly listed reading-control commands were **not** exercised. Nothing under this evidence directory is an app source set.

## Observation-to-artifact checklist

| Surface | Actually observed | Evidence / limitation |
| --- | --- | --- |
| Full English page | Continuous reading traversed Back, Subscription heading, plan/status/renewal/biller fields, Details/Included headings, included features, both action buttons and the entire cancellation warning. | Session4 `english-page-sheet.mp4`; adjacent uncorrected `page-asr.txt`; `page-warning-focus.png` sampled at media159s. ASR misrecognizes `Billed`/brand/`Manage or cancel`; no pronunciation-quality claim. |
| Full English sheet | Continuous reading traversed Before you go heading, lead, What stops heading/three consequences, What you keep heading/three consequences, both buttons, footnote and Close sheet scrim. | Session5 `english-sheet.mp4`; adjacent `sheet-asr.txt`; `sheet-content-focus.png` at175s. All six consequence texts appear; no decorative warning/check-icon narration appears in this recording. This does not establish every other state/language. |
| Back return | Physical Alt+Backspace dismissed the sheet; green accessibility outline and spoken Manage or cancel returned on the same preview. | Separate session4 `back-focus.mp4` and adjacent `back-asr.txt`. Not real Settings/Export owner navigation. |
| Keep return | Keep my plan was reached and spoken. | **Activation was not performed before the session reserve deadline. Keep dismissal remains unverified here.** |
| Scrim | Close sheet was spoken; outline covered scrim, not the underlying page's child controls. | **No scrim activation or exhaustive background-focus exclusion test performed.** A scrim outline alone is not a containment failure. |
| Heading-control navigation | Heading roles were spoken in continuous reading. | **Headings reading-control selection/navigation not tested.** Role speech is not that test. |
| Real owners, large text, locales | Existing native structural/owner tests remain separate. | **Real Settings/Export spoken focus, large text, Arabic/Spanish workflow speech remain open.** Existing owner localization acceptance is preserved. |

This is recorded spoken behavior, not an Argent e2e replay or a synthetic automated verdict. No full #115 acceptance or overall merge-readiness completion follows from these subsets.

## Executed flow

The recording process/container was started before the first preview launch, with `--no-playback --no-control --no-clipboard-autosync`, output-audio capture and video. Launch/record wall times, actual kernel events and dispatched commands are retained. The harness did not launch instrumentation/UiAutomation or another accessibility service. Active-service dumps were not sampled during these two sessions, so no stronger continuous binding audit is claimed.

Both sessions denied TalkBack's notification prompt using physical Alt+Right/Alt+Enter. Both acknowledged the **observed** How continuous reading works dialog by navigating past its checked Always show this message checkbox to OK, then activating OK. The checkbox remained checked; no keymap, reading-control or speech-rate preference was changed. OK resumed the original read-top request. The education dialog is not missing app content.

- Session4 read the page fully, navigated back to Manage, activated it, reached the sheet title, then recorded a separate Back return. This is not one uninterrupted whole-flow pass.
- Session5 deliberately did **not** repeat the page read. Ordinary keyboard Tab first focused Back and then Manage, verified visually, before physical TalkBack activation. Tab is used only to reach that action, not as proof of static traversal. Read-top/OK then read the full sheet. After the final Close sheet node, three previous-item commands reached Keep my plan; no activation followed.

Raw ASR is adjacent and deliberately uncorrected. The same verified base model was reused with four CPU threads/no GPU; original media is unchanged, derived WAVs use16kHz mono/loudness normalization. Token probabilities are not calibrated confidence or language-fluency review. Raw media paths/durations/hashes are in `talkback-english-20261114/media.json`, rooted at `/tmp/whereday-merge-readiness/`.

## Session limits and cleanup — not hidden successes

Session4's primary recording finalized at259.84s. The diagnostic helper exited near its five-minute limit after the separately recorded Back action; the manager raised `UHID helper exited; stop capability branch`. Its exact child exit code was **not retained**, so neither exit0 nor a specific watchdog exit is asserted. Partial observations above remain inspectable, but the whole session is not reported as a clean gate pass.

For session5, a **shorter**235-second input phase reserved cleanup time before the unchanged five-minute helper watchdog. It ended by that reserve, not a forced app-test pass. Helper exited0/DESTROYED; movie finalized at249.41s. All final kernel keys were released in both sessions. The source/domain test assertions and original million-import20-minute limits were untouched.

Both restored/read back all seven saved settings, restored exactly `POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED]`, uninstalled the QA app successfully, removed scoped device scratch, gracefully exited the emulator0 and stopped private ADB0. Viewer SIGINT exceeded its ten-second wait; original receipts preserve that timeout and additive PID audits prove it absent after emulator disconnect. No full shared-AVD image hash claim is made.

Local detailed receipts: `/tmp/whereday-merge-readiness/talkback-owned-{4,5}/`. The current scratch session manager/sender point to **already-existing session5**; do not blindly rerun them or reuse its STOP/READY files. Snapshot source/guards, allocate a fresh output and retain reserve deadlines. No further input-backend hunt or repeat of completed full English page/sheet reading is needed before tackling the remaining surfaces.
