# Local process recovery implementation

This local-only continuation starts at #111 commit `2740bb55`. No remote branch,
PR, hosted CI, device, emulator, simulator, store or production system was changed.

## Reproduced gap and fix

The saved #109 CI artifact shows `both/reboot` with boot count 3 → 4 and, after
60 seconds, `routeRequested=true; visitsRequested=true; routeRuntime=false;
visitRuntime=off`. It does not contain boot receiver delivery or reconciliation
failure diagnostics, so it cannot establish why that particular receiver did not
finish recovery.

Production recovery previously had only Activity and boot-broadcast entry points.
Opening `AppGraph` through another component did not reconcile abandoned durable
requests. A host regression with actual `AppGraph`, `HistoryRepository`, generated
Room DAO/schema and transactions reproduced that gap: both requests remained true
without an Activity/broadcast. The assertion timed out at 2 seconds. This is the
same persistent-state symptom, not a claim to reproduce Android's broadcast
delivery behavior.

`AppGraph.from` now starts a process-owned `LocationRecoveryController` after
publishing the graph. It waits for journal-clear recovery, then runs the existing
production reconciliation. Recording/Visits-only commands and the direct
permission-result recording path await it before creating start tickets; the
permission path rechecks focus and generation after waiting. Recovery never
restarts either location provider.

The owner shares in-flight work across callers. Activity cancellation cancels only
that caller's wait, failures propagate and remain retryable, and a later lifecycle
check can reconcile again. Existing runtime/start-ticket and persisted-identity
guards still protect current owners. Failures are logged once per attempt.

The first mutex-based owner failed three focused lifecycle tests (cancellation,
failure reporting and concurrent checks). It was replaced with process-owned
deferred work. These failures concern the intermediate local implementation;
they are not attributed to the old shipped source.

## Host fidelity and limits

`LocationRecoveryStartupTest` uses Robolectric API 35 on the JVM, without launching
an Activity/service or delivering a synthetic broadcast. The expanded fixture
closes and reopens a disk database, changes boot evidence from 3 to 4, opens the
production graph, and verifies both persisted interruptions, history preservation,
zero open sessions, and closure of a stay at its last supporting observation.
It also checks delayed reconciliation against live runtimes and real pending
start tickets, followed by a same-boot interruption.

The database factory accepts a SQLite driver for host testing. Production still
defaults to pinned `BundledSQLiteDriver`; the host uses `AndroidSQLiteDriver`
through Robolectric's host SQLite implementation. Schema, generated DAO,
repository, migrations and driver-backed transaction code are the actual
production code. No fake DAO or replacement recovery algorithm is used. The
old `bundled` flag was renamed `driverBacked` to describe that transaction seam.
This does not prove Android's packaged native SQLite or boot delivery behavior.

The remaining three tests exercise the production recovery controller's
single-owner cancellation, retry and concurrency behavior with explicit barriers,
without sleeps or timeout extensions.

## Preserved evidence

The original downloaded CI directory remains at
`/private/tmp/whereday-recovery-ci-42522836942`. Relevant SHA-256 values:

| Artifact file under `both-reboot/` | SHA-256 |
| --- | --- |
| `seed-proof.json` | `64ac4e7c17f8b890c237ac696e6543766e9f1ae1fdcf8173014060c43dc651c1` |
| `boot-before.log` | `1121cfccd5913f0a63fec40a6ffd44ea64f9dc135c66634ba001d10bcf4302a2` |
| `boot-after.log` | `7de1555df0c2700329e815b93b32c571c3ea54dc967b89e81ab73b9972b72d1d` |
| `recover.log` | `548ab7db3a3f8c022d50d97d5d54725b42b033d890a2ea4f205585015504a919` |

Local red evidence is preserved in `/private/tmp/whereday-recovery-host-red.log`
and `.xml`; the first startup green run is in
`/private/tmp/whereday-recovery-host-green.log`. Intermediate owner failures are
in `/private/tmp/whereday-recovery-lifecycle-red.log` and `.xml`. A hash manifest
is retained at `/private/tmp/whereday-recovery-evidence-hashes.json`.

## Verification

Final serialized command (JDK 21, installed Android SDK; no device launch):

```sh
cd android
./gradlew :domain:test :platform:testDebugUnitTest :app:testDebugUnitTest :data:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
```

`BUILD SUCCESSFUL in 1m 26s`; 170 tasks (52 executed, 118 up-to-date). JUnit
XML reports **669 tests, zero failures/errors**: 414 domain, 148 platform and
107 app. The four new recovery tests all passed, including the disk-reopen
fixture and all three previously failing intermediate-owner lifecycle cases.
The exact log is `/private/tmp/whereday-recovery-final.log`.

`python3 -m unittest discover -s android/scripts -p 'test_*.py'` passed all
70 Python checks (`/private/tmp/whereday-recovery-python.log`). Localization
`--check` verifies 1,730 resources across ten translated locales. `git diff
--check` passed.

The final APK passed `verify_native_page_sizes.py` (eight libraries, 16 KB
alignment), `verify_runtime_notice_package.py` (118 software entries, 99
preserved embedded entries, 38 exact packaged files), and
`verify_native_provenance.py` against the runtime lock and pinned NDK
27.0.12077973 (16 native files from four components). The native report is
`/private/tmp/whereday-recovery-native-provenance.json`.

The original device test and its 60-second assertion remain unchanged. Actual
reboot/broadcast/foreground-service proof is still required on an authorized
Android runtime; host tests cannot close that proof gap. The historical CI
failure must not be labeled device-verified by these host results.

The 25 runtime-notice and 13 native-provenance checker tests passed. The checkers
correctly retain `legalReviewComplete=false`; provenance correspondence does not
establish human legal approval. No notice lock, original upstream document or
translation-review designation was changed to imply otherwise.


## Local handoff and cleanup

The source/test diff is Android-only in `/private/tmp/whereday-android-finish-20261108`,
branch `android/redesign-finish-20261108`. The Apple owner can merge this stable
local commit into its integration branch; publication remains separately gated by
user approval. Gradle was released to Health integration after the final command,
with GitSync then Vox queued. No user daemon/server or Herdr tab was stopped.
Original source worktrees and untracked QA evidence were preserved.
