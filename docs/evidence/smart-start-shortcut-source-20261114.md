# Optional Shortcut source — partial issue60 automation support

`StartTrackingIntent` retains its title, simple phrases and non-opening behavior. A new optional `Source` AppEnum identifies generic Shortcut, CarPlay automation or workout automation. Omission maps to the existing `.shortcut` source. CarPlay/workout choices map to the existing legacy-safe `.carPlayShortcut`/`.workoutShortcut` metadata; all three remain explicit, non-inferred starts and do not opt into Smart Start.

The production intent keeps its actual entitlement check and invokes the tested `TrackingIntentSupport.startTracking` boundary. That boundary requires existing Location permission, never prompts from an automation, calls the real manager, and refuses to report successful start when the manager rejected it (for example access lost after the earlier entitlement check). Repeating Start on an existing session remains idempotent: no source takeover, session-date replacement or timer rescheduling.

Five new tests drive that actual helper and real LocationManager with isolated defaults, retained in-memory SwiftData, injected monitor and existing queued-expiry seam:

- omitted Source preserves `.shortcut`, off-by-default Smart Start and Stop After preference;
- CarPlay/workout choices persist their explicit metadata without last-auto-start writes;
- existing manual session identity/date/source/expiry lease survive repeated automation Start;
- missing permission starts no monitor/session and returns the permission explanation;
- access loss starts nothing and cannot return a success result.

Targeted five pass. Full signed `scripts/test-ios.sh` run: **585 XCTest, zero failures;20 Swift Testing tests pass**. Result `artifacts/test-results/IsoMe-20261114-054115-57084.xcresult`; log `artifacts/merge-readiness/shortcut-source-full.log`. All **31** actual CI source-provenance commands pass unchanged (`shortcut-source-provenance.log`). No fixture/hash edit was needed for this change.

## Still deliberately incomplete

Duration and activity profile are **not exposed or implemented**. A naive duration implementation using the existing Stop After preference would alter later manual sessions; a correct design needs session-scoped ownership, restoration and explicit-user-edit semantics. No silent global preference change was added. The five tests are not a native Shortcuts editor/locked/background/device automation acceptance result; that matrix and localized editor/spoken acceptance remain open. The new option/error catalog resources are now covered across all11 locales with actual compiled Bundle lookups in the [localization follow-up](smart-start-localization-20261114.md). Native CarPlay/workout execution is not claimed. Keep PR120 draft.
