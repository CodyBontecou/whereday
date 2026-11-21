# Whereday iOS 2.0.3 / Android 2.0.5 (8)

**Status (November 21, 2026): shipped.** iOS 2.0.3 is waiting for App Review (7-day phased release after approval); Android 2.0.5 (8) is on Play production at a 20% staged rollout. Both built from the merge of #146, `26682d6c`, tag `v2.0.3`.

- **Source:** `main` at `a39703189acd8520441a0a649f31c8c34319943b` (#144). The release tag goes on the merge of this branch, or on `a3970318` if the docs are merged separately. The docs do not change the binary.
- **iOS build:** `202611200112` (`71dc234a-bd3a-4166-a412-c4258307bd88`, VALID), from deploy-asc run 44908496057 on `v2.0.3`. Earlier TestFlight had 2.0.3 build `202611200111` (`64131dc9-dcae-4538-a4ae-adf66382396b`, VALID, uploaded November 19), built before the merge; it was not attached.
- **Android build:** version code **8**, version name **2.0.5**, built from `26682d6c` (clean tree) with `playReviewEnabled=true` and the purchase verifier configured (checked in the release `BuildConfig`).
- **Live now:** iOS 2.0.2 (69, source `fdf475e7`, version `b9c04c6e-73d0-4988-a2ce-91970a0fc49d`); Android 2.0.4 (7, source `d7fdd49c`).

## Changes

iOS since 2.0.2 (`fdf475e7..a3970318`):

- Apple Watch manual visits: check in and out from the Watch, kept offline with the tapped time and delivered to iPhone later (#84, `11653b35`).
- Watch fixes: cold-launch retries and late location permission (`1228bafb`), and large route batches delivered through files (`dcd25b4c`).
- Continuous recordings stay bounded to each timeline day (`a5bbf053`).
- Timeline refreshes after visit edits that keep the same visit count (#117, `1ca15175`).
- The map's day sheet follows the finger while dragged (`16ceda60`).
- Store-review prompts only at success moments, at most once per version and 120 days apart (#144).

Android since 2.0.4: only #144. On Android the in-app review flow does nothing unless the build passes `-Pwhereday.playReviewEnabled=true` (`android/platform/build.gradle.kts`, `android/README.md` "Google Play reviews").

## Store copy

- iOS What's New: `metadata/whereday/version/2.0.3/whats-new.json`, all 11 locales of 2.0.2 (en-US, es-ES, fr-FR, pt-BR, pl, ru, ja, zh-Hans, ar-SA, hi, bn-BD). English approved by the owner; translations by Claude, Whereday and Apple Watch left untranslated.
- Play notes: `metadata/whereday/play/release-notes/2.0.5.json`, the 12 locales of 2.0.4. The owner replaced the review-prompt sentence with "Reliability improvements and fixes."; translations by Claude.
- App Review notes: [`reviewer-notes.txt`](reviewer-notes.txt). They no longer ask for a manual developer release, because the version ships on a 7-day phased release. Keep the 2.0.2 contact (Cody Bontecou, `cody@isolated.tech`, same phone). No demo account is required.
- Lifetime prices and product IDs are unchanged (`docs/paid-tracking-rollout.md`).

## Android build inputs

The four release properties are passed only as `-P` flags. They are not in `~/.gradle/gradle.properties`, `android/gradle.properties` or `android/local.properties`:

| Property | Value |
|---|---|
| `whereday.purchaseVerifierUrl` | `https://whereday-play-purchase-verifier.costream.workers.dev` (also found in the 2.0.4 bundle) |
| `whereday.purchaseVerifierPublicKey` | contents of `~/.android-keys/whereday-lease.pub.b64` |
| `whereday.playCloudProjectNumber` | `84338733279` |
| `whereday.playReviewEnabled` | `true` for this release (no earlier record says which value was used) |

Signing: the Gradle `upload` signing config reads `whereday.uploadStoreFile` (`~/.android-keys/whereday-upload.jks`), `whereday.uploadKeyAlias`, `whereday.uploadStorePassword` and `whereday.uploadKeyPassword` from `~/.gradle/gradle.properties`. `release_bundle.py` re-signs and verifies the bundle using the env vars `WHEREDAY_STORE_PASSWORD`, `WHEREDAY_KEY_PASSWORD` and `WHEREDAY_KEY_ALIAS`. Upload certificate SHA-256: `e9c62a0ecf2ab4f6b755d6a48c31726560aa7852cf543e650d3b008d0d5893c6`.

Play upload: `gplay` with the default profile, service account `~/.gplay/play-publisher.json`. Managed publishing is off, so the release goes live as soon as Google approves it.

## Qualification

CI on #146 (`IsoMe XCTest`) passed. The Android build ran `:platform:testReleaseUnitTest :app:testReleaseUnitTest :app:lintRelease` before `:app:bundleRelease`; all passed. `asc validate --strict` reported 0 errors and 17 non-blocking warnings: an `APP_IPHONE_DUO` screenshot display type the asc catalog doesn't know (11 locales), no promotional images on the four subscriptions, and keywords that repeat app-name and subtitle terms.

## Receipts

Local only, not committed: `.asc/artifacts/release-2.0.3/` and `.asc/artifacts/play-2.0.5/` in the release worktree (removed after release; copies of the signing receipt values are below).

iOS (App Store Connect app 6761960794):

- Version `5125833c-821e-4097-95de-1e8ef4f5e1bb`, release type AFTER_APPROVAL, build `202611200112`.
- What's New set for all 11 locales; review contact copied from 2.0.2; notes from `reviewer-notes.txt` with the build number filled in; no demo account.
- Phased release `5125833c-821e-4097-95de-1e8ef4f5e1bb`, state INACTIVE until release.
- Review submission `edfdbf3e-5ad5-4cc2-afbc-1389f742e8d8`, submitted 2026-11-21T13:40:12Z, WAITING_FOR_REVIEW.

Android (`com.bontecou.whereday`):

- Signed bundle `whereday-2.0.5-8.aab`, SHA-256 `136db40d108030eabc63dfe6dbd6e67239431b9f9e7dfa02417a1595f0ab6bb1`, upload certificate `e9c62a0e…5893c6`, verified by `release_bundle.py` (bundletool 1.18.3, minSdk 29, targetSdk 36).
- `gplay release` edit `12319965513320665269`: production, version code 8, `inProgress`, user fraction 0.2, notes in the 12 locales of `2.0.5.json`.
- Tracks after release: production 2.0.5 (8) inProgress 0.2; 2.0.4 (7) completed.
