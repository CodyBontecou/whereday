# Consumed focus replay during real Activity recreation

## Different from the padded-camera fixture mismatch

The unchanged `restoredCameraDoesNotReplayAnAlreadyAppliedVisitFocusButAcceptsANewRequest` fails with an **unpadded** native camera. Before recreation, both target and visual center are18.2/-66.2; afterwards both are18.0/-66.0. The request UUID is unchanged. Thus correcting padded/unpadded test coordinates cannot address this second failure.

Bounded observation in the isolated diagnosis checkout establishes the actual order:

1. The old renderer saves18.2/-66.2, zoom11/bearing25/tilt30, and the already-consumed visit-focus request.
2. The new renderer restores that exact camera and consumed token.
3. An initial render with temporarily absent current focus **clears `appliedVisitFocus`**.
4. The original request reappears with the same UUID. It now compares against null, so `applyVisitFocus` replays it and recenters at the visit's18.0/-66.0.

Raw bounded logs are adjacent. This rules out failure to save the latest manual camera in the observed run; it does not speculate from timestamps alone.

## Narrow production correction

`appliedVisitFocus` records the last **consumed request**, not the current visible selection. Do not erase that consumed identity merely because current `visitFocus` is temporarily null. Current selection is still assigned/null independently and controls highlight/display. A real new user focus action has a new requestId, so it still applies. No coordinate, camera tolerance, timeout, user record, service guard or native map API is changed.

Actual existing native test RED before the correction, GREEN after it: real recreation preserves manual18.2, then the test's next explicit map action generates a new request and successfully centers at18.0. Both assertions remain unchanged. Observation/logger code is isolated; the clean PR production delta is just removal of the incorrect reset plus its explanatory comment.

## Broader clean verification and remaining failures

- Full real display/recreation class now runs **24 tests** (original23 plus additional padded-viewport coverage): **19 pass, five fail**, no skipped cases. All three camera cases pass. The five remaining failures are navigation/scroll semantics and stale-action timeout, recorded in `clean-verification.json`; do not label the entire class green.
- All **eight VisitMapFocusTest native cases pass**, including repeated focus, explicit follow clearing current selection, dirty restoration/review and deletion/recovery paths.
- **107 app mapQa JVM tests pass**, zero failures/errors/skips.
- The prior hosted `libraryCurrentPhoto...` measure/layout exception did not occur in this local full class run. No independent fix or robust resolution of that intermittent failure is claimed.

Local receipts: PR worktree `artifacts/merge-readiness/{workspace-resize-clean-24-1,visit-focus-after-8}` and diagnosis checkout `artifacts/merge-readiness/{workspace-focus-camera-probe-1,workspace-focus-camera-probe-2,workspace-focus-camera-fixed-1}`. Original timeouts/assertions and explicit emulator resize guards remain. All owned read-only API35 runs restored/read back display/density/IME/animation state and stopped scoped collectors/emulator/private ADB; no physical/default device used.

Keep draft: the five reproducible expanded failures, intermittent measure/layout behavior and complete downstream acceptance remain. This is not a full baseline pass. Platform import sources are unchanged; the existing original million-record acceptance is not rerun for this UI correction.
