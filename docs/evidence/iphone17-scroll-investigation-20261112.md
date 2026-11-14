# iPhone 17 Pro scrolling investigation

## Observations

- Physical iPhone 17 Pro, iOS 27.0.1; installed Whereday 2.0 (60).
- Initial Argent runner startup failed enabling automation; worked after the phone was made ready.
- In Settings, a swipe from (0.5, 0.78) to (0.5, 0.3) left accessibility element positions unchanged.
- Tapping How Whereday listens navigated successfully, excluding a continuously blocked main thread as an explanation for the entire observation period.
- That page also failed to scroll using both a central swipe and a slower right-edge swipe. Live Activity remained below the visible viewport.
- `.argent/e2e/iphone17-listening-scroll-diagnostic.yaml` reproduced the failure: gesture delivered, Live Activity visibility check timed out. This is a fragment requiring the page at its initial top position; it does not recreate the underlying stuck state.
- An existing simulator build, also 2.0 (60), scrolled Settings successfully. This is not an identical runtime/configuration comparison: simulator iOS 27.0 versus device iOS 27.0.1.
- Both environments expose a nearly full-screen accessibility Toolbar container. Its presence alone does not establish interception.
- Restarting only Whereday restored Settings and How Whereday listens scrolling on the phone.
- The complete recorded relaunch/navigation/scroll replay passed all 11 steps and left Live Activity visible. Its value was 0 (off); no preference toggles were performed.

## Recurrence after user interaction

After the successful replay, the user reported that manual scrolling still failed. Read-only inspection found the phone back at the top of Settings. Without restarting, the same central Argent swipe again left every accessibility element at its original position. Thus automation also fails in the recurrent state; this is not merely a discrepancy between physical finger input and automation. The successful post-relaunch replay demonstrates only temporary recovery, not resolution. The user clarified that manual scrolling failed just after testing finished. This narrows the timing but does not establish whether idle time, navigation, or physical input is the trigger.

Stopping Argent's on-device runner did not restore manual scrolling (user confirmed). This rules out an actively running runner as the sole explanation, but not lingering automation effects.

A native LLDB attach was attempted against the existing app process, without a launch or install command. The initial attempt never reached a stopped process and detach reported `Sending disconnect packet failed`; a subsequent attempt reported the process exited with `no such process`. CoreDevice then showed no Whereday process. The app exited during this debugging sequence; its exact reason was not established, and the frozen in-memory state was lost. No scroll-view runtime values were obtained. No data erase or reinstall was performed. Further runtime diagnosis requires a new reproduction or an instrumented build. User approval was requested before replacing the installed build.

## Conclusion and limits

**Unresolved and recurrent.** Temporarily recovered by app relaunch; the underlying trigger and responsible code are **not identified**. Evidence is consistent with transient UI/gesture state, but does not distinguish a framework presentation issue, app state, or debug SDK interaction. No speculative source fix was applied. No app reinstall or intentional preference/data reset was performed. Existing recording preferences were left unchanged. App startup ran the already-enabled scheduled export.

The phone runs an older built artifact than the current modified working tree, so current source inspection cannot prove the installed binary's exact behavior.

## Replay

```sh
argent run e2e-execute \
  --name iphone17-scroll-after-relaunch \
  --project_root "$PWD" \
  --device '<connected-iPhone-UDID>'
```

The diagnostic YAML deliberately retains raw gesture tools to test specific swipe delivery and raw accessibility waits. The phone's physical-device runner uses the xcuitest tree, not simulator native-devtools. These are diagnostic recordings, not polished cross-device regression tests. The relaunch test proves recovery on this phone; it does not prevent or reproduce the original trigger.

Next investigation if the failure returns: preserve the stuck state, capture gesture recognizer/scroll-view runtime state before relaunch, and compare an otherwise identical build without debug SDK gesture hooks. Do not attribute the bug to a particular SDK without an A/B reproduction.

No application source changes; no iOS unit tests run.
