# PR #114: initial picker pin missing in full hosted context

Still unresolved. No production/test assertion change or unchanged heavy retry is justified by this receipt.

## Actual full-run failure

Run **43588141287**, head **603efd83fb7ac0dce1e78cf73b05e3ab4e7619e0**, artifact **14112629280**, digest `sha256:54b3b990910e42030a4284e88fcb7afce772287aea41083919aa30e64902d453`.

Downloaded the artifact and failed-step log. The actual default instrumentation stream completes **594 distinct terminal cases: 591 pass, one failure and exactly the two original Calendar ignores**; raw summary `Tests run: 592, Failures: 1`, 3127.966seconds. This is neither a missing-result failure nor a baseline pass. The strengthened covered-road regression passes. Expanded/recreation/database/downstream selections did not run.

The sole failure is `NativeMapInputReadinessTest.pickerInputWaitsForTheFirstFrameAndWorksAfterResume`, line95: `expected:<1> but was:<0>`.

**Important boundary correction:** despite its method name, the failure occurs at the **initial** `selected-pin` rendered-feature query immediately after `awaitNativeMapFrame` and the enabled assertion. It occurs **before** `select(50f)` and before either pause/resume loop. It is not evidence of a callback failing after resume. Emulator ColorBuffer warnings alone do not establish an environmental cause.

The adjacent raw failure excerpt and `audit.json` preserve case counts, the exact failure, ignores, source/artifact identities and local control verifiers. Full raw artifact: `/tmp/whereday-merge-readiness/pr114-603e-database/`; failed-step log has the same prefix plus `-failed.log`.

## Bounded local controls, not a fix

Canonical untraced APKs, original test/deadlines, owned read-only API35 AVD, two cores/2GiB, private ADB5064/serial5586:

1. Six fresh instrumentation runs of the complete two-case class: **12/12 pass**, no initial-pin failure. Stop-at-first-failure loop exhausted its six-attempt bound.
2. One run of the actual **36 preceding hosted cases plus the same two-case class**, all nine original classes in original order: **38/38 pass**. Compared all38 completed method identities/order to the hosted stream, not merely class counts.

Raw local `artifacts/merge-readiness/input-readiness-{before-1,prefix-1}/` contains the native verifiers/streams and cleanup records. All settings restored/read back, scoped APKs uninstalled, collectors/emulator/private ADB stopped, locks released. No physical/default device used.

These controls do not reproduce the hosted boundary and do not establish a correction. No source patch, tolerance change, extra wait, new ignore or acceptance-selection shrink was made. In particular, a passing short prefix is not the original full baseline.

## Next diagnostic surface

A new observer-only hosted probe can inspect the original first rendered-feature result, native frame/full-render callbacks, style/source/layer readiness and projection without adding synchronizing reads before the assertion or feeding observations into UI state. Preserve the actual original prefix and first-pin assertion. If additional frame/placement work heals the failure, record that observer effect rather than declaring the product fixed. Any eventual correction still needs the full original hosted/default/expanded/downstream gates.
