# Subscription QA presentation harness — isolated build verification

## Source boundary

Based on PR #113 head `0817a948`, with the existing Export management consistency commit `8f97e4e5` reused as `efe80a54`. Copied the existing 16-file presentation-harness draft through a temporary Git index into isolated commit `89e1d458`. The original dirty fleet checkout/index/branch is unchanged. A mistaken preflight file-count expectation (18 vs actual 16) stopped before snapshotting; after inspecting the exact file list, the 16-file snapshot completed.

No screen layout, type ramp, colors, motion, localization resources, billing grants, tokens, purchase verification, or entitlement persistence changed. The adapter only selects presentation values and suppresses external store callbacks for a dedicated synthetic mapQa fixture. Debug retains its existing Settings-only preview; debug Export remains live. Release uses live presentation and contains no fixture writer/provider/entry Activity.

The harness enables future native/TalkBack testing through actual Settings and Export owners. It does not itself establish their spoken behavior. Existing owner localization acceptance remains unchanged.

## Actual build and JVM tests

Ran one bounded offline invocation with Java 17, Android SDK path, two workers, 2 GiB Gradle heap and in-process Kotlin. `qa-variant-build.log`: **BUILD SUCCESSFUL in 3m 52s**, 292 tasks executed.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/codybontecou/Library/Android/sdk \
./gradlew --offline :billing:test \
  :app:testMapQaUnitTest --tests com.bontecou.whereday.SubscriptionTextTest --tests com.bontecou.whereday.SubscriptionPreviewPolicyTest \
  :app:testDebugUnitTest --tests com.bontecou.whereday.SubscriptionDebugPresentationTest \
  :app:testReleaseUnitTest --tests com.bontecou.whereday.SubscriptionProductionPresentationTest \
  :app:assembleMapQa :app:assembleMapQaAndroidTest :app:assembleRelease \
  --no-daemon --max-workers=2 --console=plain -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process
```

Parsed actual JUnit XML: **49 tests, zero failures/skips** (30 billing, 17 mapQa subscription text/policy, one debug compatibility, one release live-only/writer-absence). Both QA application/test APKs and the release APK were produced. This is not native instrumentation execution.

## Actual packaged-artifact inspection

SDK 35 aapt2 inspected each produced AndroidManifest.xml; dexdump inspected declared classes in every classes*.dex. Checked the dedicated QA authority/provider/preview classes are present in mapQa and absent from release, including nested fixture classes and the legacy debug provider. Release minification is disabled, so this does not depend on R8 removing unreachable fixture code.

The first inspection's UTF-8 decoding failed on unrelated non-UTF-8 dexdump string output; the corrected inspection matches class descriptors as bytes and decodes only the known ASCII fixture descriptors. That tooling failure is not relabeled as a test pass.

| Artifact | SHA-256 | Declared classes |
| --- | --- | --- |
| mapQa APK | `494f3647e179ed33b513a5fa8f82bada0ae88d914eb45efad85c4577b4e52606` | 37,127 |
| release APK | `a658a1a016e663f37d5b07cd98e68185a196260cb2606d8c0354ac9ed41841b1` | 37,098 |

Local details: `artifacts/merge-readiness/variant-artifact-audit.json`, `mapQa-manifest.txt`, `release-manifest.txt`. No install, account, real billing, Play action or deployment occurred.

## Native verification follow-up

Reused the separately verified WorkspaceDialog posture synchronization fix as `52611328`: the existing five-second native-bounds helper waits for owner containment rather than merely finding old bounds. Original final containment/positive-size assertions remain intact.

The strict native verifier then passed **all 20 cases, no skips**: six SubscriptionScreen, one SubscriptionSettingsIntegration, six WorkspaceDialog, four ExportSubscriptionManagement, and three SubscriptionQaFixture cases. Actual Settings/Export owners, provider caller policy, and external-action suppression are exercised by those tests. Receipt: `artifacts/merge-readiness/subscription-native-1/native/verification.json`; raw native stream and APK hashes are retained alongside it. This used an owned read-only API 35 emulator, private ADB 5064/serial emulator-5586, not a physical/default device. Native animation settings were restored/read back, scoped packages removed, owned emulator exited gracefully and private ADB stopped. No TalkBack service was enabled or concurrent speech acceptance claimed.

The branch can now be integrated into #113 for fresh hosted validation; its 49 JVM cases and actual release artifact separation remain as documented above. The posture follow-up changes test code only, not those packaged production bytes.

## Remaining spoken acceptance

Follow the retained private speech plan for actual TalkBack static/heading traversal, Settings/Export owner entry/dismissal/focus restoration, large text, scrim behavior and localized workflows. Current session has no exposed Argent device tools; no device interaction was attempted as a substitute. Prior preview Back/Keep speech evidence is narrower and must not be generalized.

The completed build/native fixture gates do not close #115 or establish visual/motion/speech acceptance. Fresh hosted validation is still required after integration into #113. Actual spoken traversal, headings, owners, large text, scrim restoration and localized workflows remain independently pending; no whole-goal completion is claimed.
