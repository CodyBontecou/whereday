# Subscription release readiness — October 29, 2026

**Not ready to approve/release the app update yet.** The four subscription products are configured and have no catalog validation errors, but there is no new app version/build staged for this feature. All App Store Connect access during this audit used `asc --read-only`; nothing was changed, submitted, or released.

The [live pricing hold](paid-tracking-rollout.md#live-pricing-hold--latest-instruction) remains in force. Readback confirmed U.S. prices of $19.99 Individual Lifetime, $39.99 Family Lifetime, and $19.99 Family Upgrade. Do not apply target prices or schedule increases until all subscriptions are approved, the subscription-enabled app is live, and the user explicitly authorizes changing prices.

## What is already ready

- Four subscriptions remain `READY_TO_SUBMIT`. `asc validate subscriptions` reports **0 errors, 0 blocking findings, 8 warnings**.
- Four warnings are the unsubmitted product states; the other four concern optional promotional images. Those images are distinct from App Review screenshots and are only needed for the relevant promotion/redemption/win-back surfaces.
- Subscription pricing, introductory trials, availability, Family Sharing, levels, English localizations, and review screenshots were configured and read back in the earlier catalog setup. The current validator reports no catalog blockers.
- All three existing lifetime IAPs pass `asc validate iap` with **0 errors and 0 warnings**.
- The existing live version has complete review contact fields and no build/encryption/screenshot completeness errors from the validator. This is evidence about the old version, not a sign-off on a future app submission.

## Required before preparing the final release build

### 1. Grandfathering/versioning — fixed locally, staging still gated

App Store Connect now reports **1.7.18/build 50 already live**:

- Version ID: `1fb126db-0b15-46e7-8d0d-adabc2d99d22`, `READY_FOR_DISTRIBUTION`.
- Build ID: `5503e18c-86e0-4fc1-82af-9945aff40718`, build `50`, processing `VALID`, exempt encryption.
- There are **no editable iOS versions**, and the latest uploaded build is 50.
- At audit time, source defaults were 1.7.17/build 50, and both receipt cutoffs excluded original build 50. That local blocker has now been corrected; see below.

That threshold must match the actual first paid-tracking release, not a build that has already shipped. Otherwise someone who originally downloaded build 50 before subscriptions launched may lose free tracking on reinstall/on another device, even though the local-install migration can preserve access during an in-place update.

The local target is now **1.7.19/build 51** in Debug and Release for iPhone, iPhone Widget, Watch, and Watch Widget. `Shared/PaidTrackingMigration.swift` is compiled into both iPhone and Watch and owns the single first-paid cutoff of 51. Verified original build 50 now restores/persists legacy tracking even when a new-install marker was initially false; original builds 51 and later do not acquire legacy access from their receipts. Tests cover the boundary, malformed values, fresh-device/relaunch persistence, sticky grants, and all embedded bundle version/build numbers.

The build-50 regression was observed failing before the fix. Afterward, all 16 StoreManager tests passed as part of the full signed suite: **351 XCTest cases and 11 Swift Testing tests**, result `artifacts/test-results/IsoMe-20261029-154039-99910.xcresult`. All eight project target configurations were also checked locally for 1.7.19/build 51.

No remote version was created or reserved, and no upload/submission/release/pricing command was run for this fix. Before authorized staging, reconfirm that build 51 remains unused by a free-tracking release. If another free-tracking release ships first, reassess the shared cutoff and project numbers/tests before building. Freeze the cutoff once the first paid-tracking release ships.

The local archived 1.7.18/build 50 binary contains the legacy lifetime product IDs but none of the new subscription/cohort strings inspected. Its live English description/review notes still describe free tracking and lifetime export. This audit did not install/reverse-engineer the App Store-delivered binary or certify its runtime behavior; it did establish that build 50 is already used and cannot be the next release build.

### 2. Complete feature QA on the final candidate

The full signed local suite was rerun successfully after the migration/versioning fix, but local tests do not certify a final distribution archive. Rerun if source changes further, then test the intended sandbox/TestFlight candidate:

- Fresh user → mandatory paywall; cancellation, pending approval, product-loading failures, and restore failure cannot start tracking.
- Eligible/ineligible trials, all six plans, renewal/cancellation, expiration/refund/revocation, restore, and subscription changes within the group.
- Existing free users from build 50 and older: in-place upgrade, reinstall, another device, and offline/online receipt resolution.
- Existing lifetime owners and Family Sharing recipients retain their entitlements.
- History/export remain available after expiration, while GPS/manual check-in and Siri/Shortcuts/Watch recording are appropriately gated.
- Physical-device Family Sharing and Apple Watch offline/reconnect behavior.
- Full-motion navigation, accessible text sizes, light/dark, small phones and iPad.
- Paywall prices must come from StoreKit. The held live lifetime prices differ intentionally from local target-price fixtures until the authorized post-release change.

No tests/builds were run during the original read-only audit. The subsequent migration fix ran only local simulator tests/builds; nothing was uploaded.

## Required before App Review

### 3. Prepare truthful subscription metadata, legal copy, and reviewer instructions

A [local 1.7.19 draft package](release-drafts/1.7.19/README.md) is now prepared: nine canonical app-version localizations, 36 proposed subscription product localizations, proposed website terms/EULA-related changes, and reviewer notes. Listing drafts include both legal links and explain new-user-only subscriptions, retained existing-user access, and record access after expiration. Public listing copy avoids fixed dollar prices; reviewer notes retain the held live lifetime prices. Owner/legal/native-language review, the authorized legal-page update, screenshot refresh against held prices, and remote staging are still outstanding. No live metadata, terms, prices, or EULA selection was changed.

The live English description still says “Tracking is free and unlimited” and describes export as a one-time purchase. The current website/terms similarly say tracking remains free and discuss only lifetime export purchases. Keep current live-product messaging accurate while preparing new-version copy explaining the **new-user-only** policy and grandfathered users.

Validation flags a missing **Terms of Use/EULA link in all nine listing descriptions**: ar-SA, en-US, es-ES, fr-FR, hi, ja, pt-BR, ru, zh-Hans. Include the applicable terms/EULA link in each new-version localization. The paywall already links to `https://whereday.com/terms`, but the current public page needs subscription/trial/auto-renewal/cancellation language and an accurate new-user policy before it can explain the new service to reviewers/users.

Existing review notes describe free tracking, only lifetime export purchases, and no new products. Prepare new notes explaining the hard paywall, seven-day trials, prices supplied by Apple, existing-user/lifetime preservation, restore, Family Sharing, and review steps. Reuse contact details only after confirming they remain correct. English subscription localizations exist; consider localizing plan/group display names for the app's supported languages as a quality task, not a catalog error reported by the validator.

Prepare updated screenshots/release notes and a coordinated marketing-site change. Do not publish future pricing as current or change live pricing as part of metadata preparation.

### 4. Stage a new version and the correct subscription-enabled build

After local fixes/QA and explicit authorization to stage/upload:

- Create an editable iOS version and upload the intended signed Release build.
- Wait for `VALID`, verify its version/build identity and feature content, and attach it to that version.
- Stage the new metadata, screenshots (including iPad for this universal app), review notes, and any required compliance declarations.
- Preserve manual release so approval does not automatically release the app.
- Rerun full validation against the **new version**, optionally with its exact IPA for iPad screenshot checking.

The audit validated live 1.7.18 because no candidate version exists. Its only API blocking finding is `version.state.editable`: a live version cannot be resubmitted. This is not a verdict that the future subscription candidate is otherwise complete.

### 5. Associate first-time subscriptions/group with the new app review

The four subscription products are not approved yet. Their `READY_TO_SUBMIT` states are not approval. They need to be included in the first subscription-enabled app review, with the group version as required by Apple's current flow.

First-of-type association must be checked on the app-version page/appropriate Apple web-session flow. The audit found two pre-existing empty `READY_FOR_REVIEW` review-submission shells, with zero items each; neither demonstrates a staged subscription review. They were left untouched.

There is no cached authenticated Apple web session (`authenticated: false`). Deep validation therefore could not verify first-review attachment, App Privacy publication, or required agreement status. These are **unverified**, not asserted missing. Confirm them in App Store Connect or an explicitly authorized authenticated session; do not start a login, publish privacy changes, attach items, or submit as part of this audit.

## Release ordering and pricing guard

1. Fix migration/versioning, finish QA, and prepare the candidate.
2. With user authorization, stage and submit the app plus its first subscriptions for review.
3. Obtain approval for the app and all four subscriptions; verify agreements/privacy and review readiness.
4. With release authorization, release the subscription-enabled app and verify it is actually live and purchasable.
5. **Only then, with separate explicit authorization, apply target lifetime prices** ($49.99 Individual, $89.99 Family, $40 Upgrade), preserving regional overrides again.

No calendar-based price increase substitutes for these checks. Deploy/verify the analytics worker's new product allowlist separately before relying on subscription funnel events; no service deployment was performed by this audit.

## Evidence and safe rechecks

Read-only JSON evidence: `.asc/artifacts/paid-tracking/readiness-audit/` (gitignored). Commands used include:

```sh
asc --read-only versions list --app 6761960794 --platform IOS --include build --paginate
asc --read-only builds list --app 6761960794 --platform IOS --sort=-uploadedDate --limit 5 --include preReleaseVersion
asc --read-only validate subscriptions --app 6761960794
asc --read-only validate iap --app 6761960794
asc --read-only validate --app 6761960794 --version-id 1fb126db-0b15-46e7-8d0d-adabc2d99d22 --platform IOS --deep
asc --read-only iap pricing summary --app 6761960794 --territory USA
```

After an authorized candidate exists, use its actual version ID for validation; do not reuse the live 1.7.18 ID. Public API fixes include candidate/build staging and listing metadata. Apple web-session/manual checks include first-subscription review association, App Privacy publication, and agreement confirmation. Device/runtime QA cannot be replaced by catalog validation.
