# Native authorization versus injected-monitor ownership

Follow-up to the isolated [Shortcut session options](smart-start-shortcut-session-20261114.md), not a change to PR120/main.

## Actual failure and feedback loop

Hosted run43622903816, exact173cb83f259e791e0c458e903d3e51951a103bcc, job132532303312: **611 XCTest/one failure +20 Swift Testing pass**. All26 new session tests pass; the existing async `SmartStartCorrectionTests.testCachedPreSessionFixDeliveredAfterStartCannotReachAnyRecordingConsumer` fails its first `trackingStartTime` unwrap. It never reaches cached-fix admission assertions. This is not a Keychain entitlement/setup failure. Artifact14125585944 digest `sha256:3efd37811b522fa85061e3e30d2a7d5f8b4a6b35d7714c0bbcdb75d7b15c6cfc`; full hosted log `/tmp/whereday-merge-readiness/shortcut-options-hosted-ios.log`.

The unchanged isolated case passes locally. Ranked hypotheses were native permission callback overriding a fixture, access notification stopping a session, and stale defaults/expiry. Hosted logs show Not asked yet callbacks around that case, but **lack owner identities**: they do not prove which instance delivered the event.

A deterministic native regression now replays the actual native authorization-handler boundary **after** fixture setup installs authorizedAlways but **before** manual start. The old constructor still attached an actual CLLocationManager delegate even when monitoring was injected. Delivered notDetermined overwrites the fixture; permission becomes false and start metadata remains nil. Actual RED: **one method/three failed assertions**, including missing start timestamp. The test avoids raising a real permission dialog after a failed precondition; its expected permission/start assertions remain strict. Source-only forwarding extraction initially preserves old behavior. This proves the modeled fixture-isolation defect, not an owner-correlated trace of the historical CI event.

Command: `scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9DBF4207-832B-4A34-88DB-AE841A6383DA' -only-testing:IsoMeTests/SmartStartCorrectionTests/testNativeAuthorizationDeliveryCannotOverwriteAnInjectedMonitoringFixture`; logs `artifacts/merge-readiness/shortcut-native-authorization-{red,green}.log`.

## Correction and scope

The default production monitor retains its actual native delegate unchanged. If a caller injects monitoring, the otherwise-unused internal CLLocationManager no longer registers a competing native delegate. The native authorization bridge checks delegate ownership before forwarding; explicit test/provider `handleAuthorizationChange` calls remain intact. No permission, recording-access, foreground, service, lifecycle or billing guard is bypassed, no TCC grant/reset, no test deadline/selection change, no repeating authorization assignment immediately before assertions.

A second regression verifies the **default native** bridge still publishes restricted access and reduced accuracy without starting tracking/ambient monitoring. Together with the original cached-fix test this covers both sides, not merely ignoring authorization globally.

Focused correction/session/source selection: **50/50 pass**. Final full signed suite: **613 XCTest +20 Swift Testing, zero failures**; result `artifacts/test-results/IsoMe-20261114-140644-27719.xcresult`, log `artifacts/merge-readiness/shortcut-native-authorization-full.log`. Original cached-fix assertions are unchanged. Current compiled561 lookups and localization tests pass. Actual Swift reconciliation producer rerun:17 outputs identical, provenance only; all31 actual CI source checks pass.

Adjacent verification retains source hashes, failure/regression surfaces and local results. Fresh hosted current-head verification is still required; the earlier173cb run remains failed historical evidence. No unchanged retry, native-spoken/editor/device or whole-epic/goal completion claim. Main-sync authorization and other acceptance gaps remain open.
