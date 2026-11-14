# Actual resize camera contract — padded target is not visual center

## Original real failure and distinguishing observation

The unchanged `manuallyPositionedCameraSurvivesBothActualWindowRecreationDirections` fails locally in5.449seconds at its first wide recreation, expected latitude18.2 versus18.180823939081414. Hosted run43575545218 failed at the same assertion with18.188158208335. Real Activity recreation, density200 and800→2200pixel display width are preserved; this is not a setup-only failure.

A separate diagnosis checkout added only a native scalar observation inside existing Main-thread assertion boundaries. Its production app sources match871b40fd. Coordinates are the explicit synthetic resize fixture, not a tracked person's location. Actual `native-camera-observations.log`:

| Stage | Native target latitude/longitude | Actual view-center latitude/longitude | Native padding | Zoom/bearing/tilt |
| --- | --- | --- | --- | --- |
| Before resize |18.200000000000003 /-66.20000000000002 |18.180823939081407 /-66.19744855227552 |45,218,125,328 |11 /25 /30 |
| Wide restored |18.180823939081414 /-66.19744855227555 |18.180823939102368 /-66.19744855230076 |0,0,0,0 |11 /25 /30 |

The visual geographic center is preserved within about2.6e-11degrees; all other camera quantities agree. Existing `JournalMapRenderer.cameraSnapshot` explicitly documents and implements saving the **screen middle** when a floating-chrome fit has padded the native camera. Restoring without old padding would otherwise shift the visible view. The failing test compared the old padded target against the new unpadded target, two different quantities, despite correct visual preservation.

## Preserve both contracts, not a looser camera assertion

The original test now explicitly clears inherited auto-fit padding before its manual camera placement. This makes its literal18.2/-66.2 target a visual-center request. Its exact original coordinates, zoom11, bearing25, tilt30,1e-6tolerances, both recreation directions and stored-record assertion remain intact.

A **new additional case**, `paddedManualCameraPreservesVisualCenterThroughBothActualRecreations`, preserves coverage of the original padded input. It asserts that native padding is actually nonzero, places the same native18.2 target, captures the real native projection of screen center, then verifies that center, zoom, bearing and tilt after **both** actual recreation directions at the same1e-6tolerance. No old case is removed, no limit raised and no source record mutated. The resize class grows23→24cases.

## Actual counterfactual and clean execution

- Original unchanged test: native RED with the latitude mismatch.
- Correct existing renderer + explicit unpadded case and additional padded case: **two native cases pass**.
- Isolated diagnostic mutant disables only the renderer's padded-center conversion. The new padded case goes **RED**: expected visual latitude18.180823939081407, actual18.19999999997502. The unpadded literal18.2case still passes. Thus the additional case catches the behavior that merely resetting padding would otherwise stop testing.
- Mutant was reverted **byte-exact** (`git diff --exit-code -- android/app/src/main`) and the diagnostic APK rebuilt from original production source. No mutated/stale build is left as the canonical candidate.
- Clean PR checkout, without observer or production change: **both native cases pass**, zero failures/errors/skips, through the strict instrumentation verifier.

`workspace-camera-contract-20261114/counterfactual-verification.json` preserves actual session/verdict/log digests, including RED failures. Local raw receipts: PR worktree `artifacts/merge-readiness/{workspace-camera-before-1,workspace-camera-clean-1}` and separate location-diagnosis worktree `artifacts/merge-readiness/{workspace-camera-probe-1,workspace-camera-contract-1,workspace-camera-mutant-1}`. All owned read-only API35 runs restore/read back actual display, density, IME and animation settings; scoped packages, collectors, emulator and private ADB are removed/stopped. No physical/default device used.

## Boundary

This is a test input/quantity correction with additional mutation-tested coverage, **not a production camera fix** or a license to relax restoration requirements. No observer, disabled conversion or renderer change ships. The separate `restoredCameraDoesNotReplayAnAlreadyAppliedVisitFocusButAcceptsANewRequest` failure and six other expanded resize failures are still unresolved. Full24-case and later downstream hosted acceptance remain required; no full baseline or PR-completion claim follows.
