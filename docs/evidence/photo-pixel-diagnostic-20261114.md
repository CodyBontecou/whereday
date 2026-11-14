# Post-bitmap photo/pin diagnostics: both controls pass

This follows the distinct failures in [the initial-pin investigation](native-picker-initial-pin-20261114.md), not a production fix. Diagnostic source is isolated on `diagnose/input-pin-603e`; PR114 source stays603efd83.

| Actual run | SHA | Native result | Artifact / digest |
| --- | --- | --- | --- |
| [43606223330](https://github.com/CodyBontecou/whereday/actions/runs/43606223330), original prefix |7e1468765ca5e4bb3d25d26eb404a31731dd97dd|38/38 pass, zero skips/errors/failures|14118915340 / `sha256:4e66ec8bfccd6efb43f23233e64ecdb2c6fc0dc7390e9e87c04e11510cee2d61`|
| [43607694157](https://github.com/CodyBontecou/whereday/actions/runs/43607694157), original full context |bb6eaee5f9cfad3a86f2de24f0bb314acbf705c1|594 outcomes:592 pass, exact2 original Calendar ignores, zero failures/errors;2,951.969s|14120587935 / `sha256:e4d62c639258e02b4851b8974034670df57ac2a0a345cfd6108a6b96358fdd14`|

The full run has all94 original classes. Its592 executed method identities/order and both ignored identities exactly match the earlier original-full diagnostic, not a shortened/default-substitute selection. Adjacent verification records retain the identity/order digest. This is the canonical default **with test observers**, not an untraced candidate heavy gate; expanded workspace, database and downstream suites were not selected. Main production app/platform/data/domain/export sources are unchanged from603e. APK binaries have their own artifact hashes; source equality does not assert byte-identical packaging.

## What the immutable original observations show

Both runs report:

- nearest-light **311** green pixels and group-dark **468**, passing the original displayed-dot assertions;
- intentionally hidden-dark **0**, passing its original absence assertion;
- after each original bitmap/count: interactive=true, keyguard/deviceLocked=false, native/decor focus=true, attached=true, destroyed=false,1080×788, styleLoaded=true;
- the original first selected-pin query returns **1**, not zero; both selection/resume methods pass.

Full run retains63 bounded pin events, zero drops. The original query spans162.661566ms; fully-frame callback delivery follows the recorded original result by119.608µs. This neither measures render-worker completion nor establishes a fully-frame fix; the query itself may synchronize with rendering.

No original-zero bitmap is expected: the observer saves the **same original bitmap**, only after the immutable count is zero, before the unchanged assertion. Neither run reproduced a zero. Missing zero-image files are not an upload failure or evidence of the earlier failing display state.

The photo observer reads power/keyguard/native focus/geometry/style **after** the original screenshot and count. It cannot repair an already-computed zero. `capturePhotoDots=true` enables the existing optional capture path; observer execution, enabled I/O elsewhere, binary/JIT/layout effects and overall timing may still perturb later cases. No claim that these are observation-free controls. Native failures and missing-observation checks remain strict; shell mocks checked selection, forwarding and exit propagation, not native acceptance.

## Interpretation and remaining work

The untraced603e failure43588141287 (initial pin zero) and observer-full failure43599667798 (first photo pixel zero, then first-selection/root failures) remain failed historical evidence. These healthy controls **do not reproduce either pattern or explain its cause**. Healthy post-count state cannot retrospectively prove whether the old window was covered/unfocused, the device noninteractive/locked, or MapLibre presentation incomplete.

No speculative render/frame production patch, relaxed assertion/deadline/guard, original-ignore removal or blind second full retry is justified by this result. Preserve the genuine1M acceptance; do not repeat it to chase unrelated pixels. PR114 still needs defensible resolution of intermittency and actual current-head untraced/default, expanded, database/downstream acceptance. This receipt alone does not make the PR merge-ready or complete the thread goal.

Raw artifacts: `/tmp/whereday-merge-readiness/photo-{pixel,full}-hosted-1/`. Adjacent `verification.json` and original prefix/full observation logs retain the scoped audit. No diagnostic source is shipped to the PR branch.
