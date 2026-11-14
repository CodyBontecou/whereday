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

## Observer-only hosted prefix: passes, cause still unresolved

Diagnostic branch `diagnose/input-pin-603e`, exact base603efd83, head **36b7234e205796e6be10a2d7ebe4458a7b638f26**. Run **43597995738**, artifact **14115965430**, digest `sha256:8422296f7b3ad442e6f66edbb73f53d15ad11de2c0daf26ad9379e80139f28b7`: actual native stream **38/38 pass**, 138.468seconds, zero failures/errors/skips. Compared all38 method identities/order to the original-prefix control; equal. Production sources, first query/assertion and original per-case deadlines unchanged. Database/import-scale jobs intentionally skipped; this is not baseline acceptance.

The bounded test-only observer records **57 events, zero drops**. Original first query returns **one** pin. The frame wait returns after a partial-frame callback; the first fully-rendered callback is delivered **108.652 microseconds after the original result was recorded**. The query itself takes114.613929ms. Callback delivery is not render-worker completion, and the query may synchronize native work. Thus neither this successful trace nor the similar local two-case control proves that partial-frame admission caused the original zero result. No post-zero geometry queries execute in this passing run. Initial style/source callbacks may predate listener attachment; absence in the trace is not evidence that style was absent.

Observer effects and differences between a short prefix and the full run remain possible. There is no red-capable reproduction of the original boundary yet, and no production correction is justified. Do not patch the full-render predicate solely from this passing trace. Any eventual correction still needs the full original hosted/default/expanded/downstream gates.

A first diagnostic run43596989019 failed **before instrumentation**, because emulator-action script lines run in separate shells and lost identity variables. The safety guard rejected missing identity. The corrected single-shell wrapper was mock-tested for identity forwarding, original failure status and missing-observation rejection before the second run. That authoring failure is not a product regression or native test result.

Raw successful artifact: `/tmp/whereday-merge-readiness/input-pin-hosted-2/`; adjacent `hosted-observations.log` and `hosted-verification.json` preserve the native evidence. Neither diagnostic source nor workflow was pushed to the PR source branch.
