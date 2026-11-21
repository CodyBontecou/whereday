# Whereday iOS 2.0.3 / Android 2.0.5 (8)

**Status (November 21, 2026): prepared, not built.** Nothing has been built, uploaded, tagged, pushed or submitted for this release. These drafts were written on branch `release/2.0.3`.

- **Source:** `main` at `a39703189acd8520441a0a649f31c8c34319943b` (#144). The release tag goes on the merge of this branch, or on `a3970318` if the docs are merged separately. The docs do not change the binary.
- **iOS build:** TBD. TestFlight already has 2.0.3 build `202611200111` (`64131dc9-dcae-4538-a4ae-adf66382396b`, VALID, uploaded November 19), but it was built before the merge. Do not attach it. Upload a new build from the release source.
- **Android build:** TBD, version code **8**, version name **2.0.5**.
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

- iOS What's New: `metadata/whereday/version/2.0.3/whats-new.json` (en-US only; 10 locales still to translate).
- Play notes: `metadata/whereday/play/release-notes/2.0.5.json` (en-US only; 11 locales still to translate).
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

TBD: CI on the release commit (`xcodebuild-tests`, `android-tests`), `scripts/test-ios.sh`, and Watch manual-visit QA on device or simulator before submitting.

## Receipts

TBD: `.asc/artifacts/release-2.0.3-<build>/` and `.asc/artifacts/play-2.0.5/`.
