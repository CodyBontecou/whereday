# Android redesign integration — November 8, 2026

The subsequent [local recovery implementation](RECOVERY_HOST_EVIDENCE_20261109.md)
adds process-startup recovery with host persistence/lifecycle regression evidence.
The device-only reboot proof gap remains open.

This is source/build readiness evidence, not a deployed-version claim. No simulator,
emulator, Argent, physical device, store operation or release was used for this pass.

## Integration

- Starting shared checkout: `marketing/map-first-store-art` at `9ff11d2c`; left untouched,
  including both untracked evidence directories.
- Task worktree: `/private/tmp/whereday-android-finish-20261108`.
- Task branch: `android/redesign-finish-20261108`, integrating localization #107
  (`028d38b8`), main (`70f87d46`) and draft #109 (`9ff11d2c`).
- The final diff against #109 contains Android files only. Existing localization
  and redesign worktrees were inspected without changes. Shared Apple/marketing
  readiness is owned separately by the iOS agent in #110.
- #104's head `327f4844` is already an ancestor of main (`git merge-base
  --is-ancestor origin/design/android-range-calendar origin/main` returned 0).
  The calendar is subsumed by #105; no duplicate calendar implementation is needed.
- The original #107 remote branch is unchanged. Its conflict with main is resolved
  in this integration branch, which is proposed on top of #109 so its review diff
  does not duplicate Apple or marketing changes. Reconcile/supersede #107 when
  choosing the final merge order; do not merge its stale resources independently.

## Changes and reproduced failures

Integrating #107 with main produced conflicts in renamed plan resources, Spanish
inputs/generated resources, coverage and the export-notice resource selector.
The integration preserves #109's current resource names and behavior. Nine other
packs lacked the new import reasons and locked-export message; their source hashes
were stale. The generator reproduced that failure before the fixes.

All ten packs now cover 1,730 externalized resources. The nine additional packs
include typed import diagnostics and the locked-export notice. Plan descriptions
cover Weekly, Yearly and Lifetime; imports and export previews remain free, with
no free-recording claim. Generated outputs and coverage were regenerated from
explicit inputs. The packaged debug locale configuration contains the eleven
product languages plus the two debug pseudolocales.

The Android-to-SwiftData CI gate failed because its standalone compiler source list
omitted `WheredayBackup` after #109 added it to `TimelineImportService`. Both the
place-provenance and timeline-admission harnesses now compile the actual backup
service and its model/formatter dependencies. No Apple source was changed.

## Checks executed

From `android/`, JDK 21, installed SDK, serialized with `--max-workers=1 --no-daemon`:

```sh
./gradlew :domain:test :export:test :billing:test :platform:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
```

Both passed. The final incremental run includes the last regenerated copy.
JUnit XML reports 414 domain, 948 export, 15 billing, 144 platform and 107 app
tests: **1,628 tests, zero failures/errors**. Logs are retained locally at
`/private/tmp/whereday-android-gradle-20261108.log` and
`/private/tmp/whereday-android-incremental-20261108.log`.

From the repository root:

- `python3 android/scripts/generate_localized_resources.py --check`: 1,730 resources,
  ten translated locales; passed.
- `python3 android/scripts/test_localized_resources.py`: nine tests; passed.
- `python3 -m unittest discover -s android/scripts -p 'test_*.py'`: 70 tests; passed.
- All 30 unique `scripts/*.py --check-source` commands in `android-tests.yml`:
  passed, including the custom-date/calendar reference.
- `python3 android/scripts/verify_place_provenance.py`: 12 real synthetic Android
  files previewed, committed and deduplicated through macOS SwiftData; passed.
- `python3 android/scripts/verify_timeline_admission.py --output /private/tmp/whereday-timeline-admission.json`:
  seven actual SwiftData admission scenarios; passed.
- APK `verify_native_page_sizes.py`: eight packaged libraries support 16 KB pages.
- APK `verify_runtime_notice_package.py`: 118 software entries, 99 preserved
  embedded entries, 38 exact packaged files; passed.
- APK `verify_native_provenance.py` against the runtime lock and pinned NDK
  `27.0.12077973`: 16 native files from four components; passed. These checks do
  not establish legal review completion.
- `aapt2 dump xmltree ... --file res/xml/_generated_res_locale_config.xml`:
  en-US, ar, zh-Hans, bn, es, fr, hi, ja, pl, pt-BR, ru, en-XA and ar-XB present.
- `git diff --check`: passed.

Full CI streaming-budget/geography jobs were not rerun locally in this pass.
The original #109 build job passed those gates; this branch still needs its own CI.

## Remaining gates

[CI run 42522836942](https://github.com/CodyBontecou/whereday/actions/runs/42522836942)
at #109 passed the Android build/JVM/lint/source/native gates, but failed the
SwiftData interoperability job described above and location recovery. The saved
`android-location-process-recovery` artifact was downloaded read-only to
`/private/tmp/whereday-recovery-ci-42522836942`.

The recovery failure is specifically `both/reboot`, before opening an Activity:
`routeRequested=true; visitsRequested=true; routeRuntime=false; visitRuntime=off`
after the 60-second wait. Route and visits individually and both-mode kill/force-stop
had passed in that run. The available logs do not prove whether the boot receiver
was delivered or whether its reconciliation failed. This task did not change
production recovery logic, weaken that assertion or rerun instrumentation.
The recovery failure remains an open merge/readiness gate requiring authorized
device/CI investigation; the portable checks above do not close it.

Native-language review, complete hardcoded-text externalization, RTL/large-text,
TalkBack and the broader supported-device acceptance matrix remain open. An earlier
published 2.0.1 branch build does not prove these integrated sources are shipped.

## Cleanup

The task worktree, branch, ignored build outputs and local logs are retained for
review. Native Gradle ownership was released to the waiting Health/Vox Android
agents after the final build; no user daemon/server was stopped. No existing
source, worktree, untracked evidence or Herdr tab was removed.
