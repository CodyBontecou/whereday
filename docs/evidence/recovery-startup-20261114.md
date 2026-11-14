# Recovery startup observations: healthy nine-case control

Diagnostic-only run **43599179208**, head **0fa0a3f28e3cfaa9b368dc8577323997b52cb662**, exact product/test base **d3faede5309648c6a600ae8c3031d01201f62292** (#120). Artifact **14115442503**, digest `sha256:43d8885028fe62313773d590ba1ff8943433d2fb5835d6ca317ce19ac0c9f3a3`.

## Why this run

Original #120 run43591483840 failed before the first route/kill enrollment: the original20-second foreground wait expired while a pre-existing Nexus Launcher ANR owned focus. Historical #121 has a similar boundary. Existing artifacts omit the ANR's startup origin, so compilation/boot contention was only a hypothesis, not an accepted cause.

The new diagnostic retains the original API35 Google APIs x86_64 Pixel6 guest, build-inside-booted-emulator order, original25-minute job limit, all nine route/visits/both × kill/force-stop/reboot cases, original driver/production guards and per-condition deadlines. No app or native test source changes. Other workflow jobs are intentionally skipped; this is not an ordinary CI rollup.

Before/after the original build and on exit, bounded read-only snapshots capture last ANR, CPU accounting, window state and ANR events. A host-only five-second scalar sampler records CPU ticks/load/available memory. No input, focus repair, ANR dismissal, retry or extra readiness condition. Observer overhead and the startup snapshot delay can affect timing; this run is not a controlled attribution experiment.

## Actual outcome

**All nine original native recovery cases pass**, each actual recover log `OK (1 test)`, proof phase `recovered`, one preserved point, changed PID, and boot identity changed exactly for reboot cases. Adjacent `verification.json` records each proof. This provides a healthy real recovery result for the unchanged #120 Android product/test source, not a fix for the previous environmental failure.

- Before build: 13:21:11Z, `<no ANR has occurred since boot>`.
- After build: 13:26:45Z, same no-ANR report.
- Final: 13:31:23Z, same report for the final boot.
- ANR event snapshots are empty; window snapshots do not show an ANR.
- 123 host samples; maximum observed one-minute load7.68, minimum sampled available memory5110MiB. These observations do not establish a threshold, a causal explanation or an emulator performance guarantee.

The original failed runs remain failed. Healthy completion under the original build ordering refutes any claim that this ordering necessarily causes an ANR; it does not rule out intermittent contention. No workflow ordering fix or foreground-guard bypass is justified by these results. No unchanged retry was submitted to recolor the failed PR check.

Raw artifact: `/tmp/whereday-merge-readiness/location-startup-hosted-1/`. Diagnostic source/workflow remain on `diagnose/recovery-startup-d3fa`, not a feature PR. No physical/default/local device was operated.

## Orchestration verification

Mock-only shell checks covered the exact nine-case ordering, preserving build exit9/native exit7, and refusing a physical-device identity before any build/test command. An initial shell-guard check exposed Bash3.2's failure to apply `errexit` to this compound `[[...]]` expression; explicit guarded exits replaced that reliance before dispatch. No real device was involved in the mock. These checks are not native acceptance.
