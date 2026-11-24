# PR114: actual stale-focus admission trace and clean RED → GREEN

## What the ordered observation established

Six unchanged full `RoadDisplayProducerTest` class runs reported **24 passing cases**, yet every covered-source trace contained four provider invocations and `test.finally value=4`. The old predicate read `calls==3` **before** a native-map query which synchronizes Compose. That query could return a rendered road after invocation four had already started. A green class result therefore did not establish its exact-count requirement.

The bounded synchronous observer retained every event (zero drops/stamp gaps). Representative `before.trace` identifies:

1. Request one belongs to the initial session; covering closes its admission while its noncancellable fake worker remains held.
2. The successor gets request two after the held worker completes. Distinct immutable request stamps correlate the workers with their sessions.
3. CREATED revokes that successor. On RESUMED its existing coroutine runs before composition consumes window-focus loss.
4. Immediately before request three, **both native View.hasWindowFocus and live WindowInfo.isWindowFocused are false**, while the composition-captured Boolean is still true. Preparation is admitted from that stale capture.
5. Composition then consumes false, removes the old session; native focus returns, and a new session starts request four.

This is an observed gate disagreement, not cross-thread timestamp ordering claimed as universal causality. Native view identity remains unchanged across the surface lifecycle; distinct request stamps exclude a duplicate log/callback explanation. `trace-audit.json` records all six initial traces, the intermediate probe, the actual strengthened RED, and six fixed traces, with raw-file digests and no missing events. The initial session manifests' `reproduced=false` field only meant the old *test did not fail*; it did not mean no four-call pattern occurred. The audit explicitly corrects that interpretation.

Observation-only tracing remains in the separate local diagnosis branch (`4ea012e6`, based on bounded trace draft `b16b5eda`). `diagnostic-only.patch` preserves those six observation-related files relative to `6a6b7fe0` for reproduction (including the fixed control); it is documentary only, not a production patch to apply. No trace hook, reflection, native-view lookup, diagnostic logger, or ownership-context wrapper is compiled/shipped by this PR.

## Regression that actually fails old production

A post-idle count assertion alone was insufficient: the test could still finish before focus restoration and the fourth request. That intermediate run is retained as `road-settled-red-1`; it is **not** labeled a failing regression.

The retained test strengthens its existing ten-second resume predicate:

- native rendered-road query first;
- actual activity window focused and current road-estimate semantics present;
- exact request count **last**, after the synchronizing UI reads;
- an additional main-thread settled exact-three assertion.

All original assertions, cases, expected counts, and deadlines remain; nothing is skipped or relaxed. The strengthened test failed old producer logic with **calls=4**, rendered road, focus and RESUMED state valid. It failed both with the observer (`road-settled-red-2`) and on the **clean PR source without any observer** (`road-clean-red-1`: four cases, one failure, zero skips/errors). Thus the regression does not depend on diagnostic timing/hooks or a simulated focus flag.

## Narrow production correction

Capture the `WindowInfo` object, rather than relying only on its composition-time focused Boolean. Inside each RESUMED block, `snapshotFlow { windowInfo.isWindowFocused }.first { it }` must observe live focus **before** `session.foreground()` and preparation. The existing lifecycle and composition cancellation removes that suspension when backgrounded, covered, disabled, or disposed. No delay, polling interval, new timeout, controller/cache mutation, or public interface is introduced. `WindowInfo` is an effect dependency.

The six fixed traced class runs pass all **24** cases; every trace has provider ordinals `[1,2,3]`, final count three, native and live focus flags `[2,2,2]` immediately before preparation, and zero dropped events/stamp gaps. `after.trace` is representative.

## Clean verification on the actual PR implementation

- **Six clean full-class runs: 24 native cases passed**, no failures/errors/skips, without any trace code.
- Related integration selection: **16 native cases passed** (`RoadDisplayProducerTest` four, `RoadConsentScreenTest` six, `WorkspaceDialogTest` six), zero failures/errors/skips.
- **107 app mapQa JVM +154 platform JVM tests passed**, zero failures/errors/skips.
- Counterfactual clean RED and all clean GREEN strict-verifier/session receipts are preserved in `road-resume-focus-20261114/clean-native-verification.json`.

Local raw receipts: `artifacts/merge-readiness/{road-clean-red-1,road-clean-green-1,road-integration-native-16}`. Diagnostic raw traces: sibling `whereday-baseline-diagnosis/artifacts/merge-readiness/{road-repeat-1,road-settled-red-1,road-settled-red-2,road-settled-green-1}`. Each owned API35 run used a read-only/no-snapshot 2-core/2GiB guest, private ADB5064/emulator-5586, restored/read back animation settings, and stopped its collectors/emulator/private ADB. No physical/default device or dirty fleet lane was used.

## Remaining acceptance

Queue the full unchanged hosted database/UI selection and downstream suites on this fix; these local selections are not the complete baseline. Keep PR114 draft until they and current-head ordinary checks are verified. The independent original import gate **43570950739** passed all four original cases at `6a6b7fe0`; this app-only correction does not change any platform import source, Gradle input, schema, or original scale-test assertion. See the point-tuple receipt for actual full-scale artifacts, not extrapolation from the 100k control.
