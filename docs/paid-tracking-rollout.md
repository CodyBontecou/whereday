# Paid tracking rollout

## Live pricing hold — latest instruction

**Do not change live lifetime prices until all four subscription products are approved, the subscription-enabled app update is live, and the user authorizes applying the new prices.** Approval or `READY_TO_SUBMIT` alone is not enough. Do not schedule a date-based increase as a substitute for verifying these release conditions.

The earlier lifetime increases were rolled back on October 29, 2026. App Store Connect readback now reports these U.S. prices:

| Product | Current live U.S. price | Held target price |
|---|---:|---:|
| Individual Lifetime | $19.99 | $49.99 |
| Family Lifetime | $39.99 | $89.99 |
| Family Lifetime Upgrade | $19.99 | $40.00 |

All non-U.S. custom overrides were preserved during rollback (153 Individual, 155 Family, 153 Upgrade). No future U.S. increase is scheduled. New subscription products remain unsubmitted drafts; their configured prices and trials are unchanged. Local StoreKit target prices are intentionally different from the held live IAP prices. Rollback/readback evidence is in `.asc/artifacts/paid-tracking/pricing-hold/`.

## Product contract

New users complete onboarding, then must start an eligible trial or buy access before tracking. There is no skip/dismiss action on this first paywall. Returning subscribers whose access has expired can reach history and export, but starting GPS tracking (including Siri/Shortcuts and Watch) or creating a manual visit requires access again. Existing records are never deleted by entitlement changes.

Existing installations are assigned a persistent legacy cohort before onboarding/tracking defaults are changed. Both iPhone and Watch now use `Shared/PaidTrackingMigration.swift`: the intended first subscription build is **51 (1.7.19)**, and verified original builds **1 through 50** retain legacy free tracking. A verified build-50 history repairs a false cohort marker on a fresh reinstall or another device, and the resulting legacy grant persists across launches. Original builds 51 and later do not receive free tracking from the receipt. See the [readiness audit](subscription-release-readiness.md) for evidence and remaining work.

All four app/extension targets use local version **1.7.19/build 51** in Debug and Release. These numbers are intended, not reserved or uploaded. Before authorized staging, reconfirm that no intervening free-tracking release has used build 51; if it has, coordinate a new first-paid cutoff, project numbers, and tests before building. Freeze the cutoff only when the first paid-tracking release actually ships. Never advance it with ordinary subsequent build bumps. Unknown/unverified app transactions do not grant legacy access; retry restoration online. Existing free users retain the previous export paywall, and every legacy lifetime product remains recognized. Cohort markers are not inferred again from new onboarding/permission state. Sandbox original-app-version values differ from production; validate migration using an actual pre-cutoff App Store download as well as local policy tests.

## App Store Connect catalog

Subscription drafts were configured and verified on October 29, 2026. The lifetime price increases recorded during setup were subsequently rolled back; the live pricing hold above supersedes those earlier changes.

All four subscriptions use **one subscription group**, so changing tiers/durations does not create two concurrent subscriptions or another introductory trial:

| ID | US price | Duration | Family Sharing | Group level |
|---|---:|---|---|---|
| `com.bontecou.isome.individual.weekly` | $0.99 | 1 week | No | 2 |
| `com.bontecou.isome.individual.yearly` | $29.99 | 1 year | No | 2 |
| `com.bontecou.isome.family.weekly` | $1.99 | 1 week | Yes | 1 |
| `com.bontecou.isome.family.yearly` | $49.99 | 1 year | Yes | 1 |

A **7-day free introductory trial** is configured on each subscription in all 175 current app territories. Eligibility is determined by StoreKit, not a local countdown. The UI offers a trial only when StoreKit reports an eligible seven-day free offer; otherwise it says Subscribe. Cancellation retains access through the paid/trial expiration date; a disabled auto-renew setting alone does not revoke access.

Held lifetime targets (not current live prices), using the existing IDs and product types:

- `com.bontecou.isome.lifetime.individual`: $49.99 target, non-consumable, not shareable.
- `com.bontecou.isome.lifetime`: $89.99 target, non-consumable, Family Sharing enabled. Original purchasers still own it.
- `com.bontecou.isome.lifetime.family.upgrade`: $40.00 target, retained for compatibility and matching the new lifetime-tier difference. The user approved this target but then directed that all increases wait until the subscription changes are approved and live.

Family Sharing is on Family products only; members restore access using their own Apple Accounts. English subscription/group localizations and App Review screenshots are configured. Verify the linked terms support subscriptions and Apple auto-renewal disclosures, complete remaining localization/device QA, and obtain release approval before any review submission. Editing `IsoMe.storekit` alone does not create or price live products.

### App Store Connect configuration record

App: `6761960794` (`com.bontecou.isome`). Group: **Whereday Tracking**, `22526448`.

| Plan | Subscription ID | Version ID | Verified state |
|---|---|---|---|
| Individual Weekly | `6828523241` | `d7e61259-4999-4ecb-a433-4ec321e86b68` | `READY_TO_SUBMIT` |
| Individual Yearly | `6828523426` | `a4e3d574-b2c6-4b09-b928-84175d7f89ea` | `READY_TO_SUBMIT` |
| Family Weekly | `6828523427` | `80745c7f-b1b5-4bc2-bad9-2811b25b9eb8` | `READY_TO_SUBMIT` |
| Family Yearly | `6828523204` | `19e9789a-b682-4400-85e7-8acd5d634d4a` | `READY_TO_SUBMIT` |

All four have 175 priced/available territories and 175 `ONE_WEEK`, `FREE_TRIAL`, one-period introductory offers starting October 29, 2026, without an end date. Availability follows the current app territories and includes future territories automatically. Each has an English display name/description and a paywall review screenshot. The group version `03de59bf-5d9d-4eb8-a02e-7eee133e85a7` remains `PREPARE_FOR_SUBMISSION`, with the English name Whereday. No group/subscription version or build was submitted or released.

New subscription prices use Apple's equalized pricing matrix from the specified U.S. base. The first price write populated only USA; explicit `asc subscriptions pricing prices set --force` materialized the full 175-territory matrix, which was then verified by readback. Trials, availability, Family Sharing, levels, and U.S. prices were also read back and asserted.

Lifetime IAP IDs: Individual `6807491383`, Family `6761962715`, legacy Upgrade `6807491599`. All three U.S. prices have been restored to the prior live prices in the hold table. Rollback preserved every custom regional price-point ID and removed any pending U.S. increases; verification found no dated future manual prices. Apple's automatic territories can recalculate from the restored base. Existing purchasers retain their purchases.

Local API evidence is under `.asc/artifacts/paid-tracking/` (gitignored). Screenshot responses include temporary signed upload URLs; do not publish those raw files.

## Verification

The [October 29 readiness audit](subscription-release-readiness.md) found the catalog ready to submit but the app update not yet staged, outdated subscription/legal metadata, and remaining device/review checks. Its migration/versioning blocker has since been fixed locally for 1.7.19/build 51. Neither the audit nor the local fix changed App Store Connect or the live pricing hold.

Use the signed runner:

```sh
scripts/test-ios.sh -only-testing:IsoMeTests/StoreManagerTests
scripts/test-ios.sh
npm --prefix worker/onboarding-analytics test
```

The StoreKit configuration is included in the test bundle. Debug iPhone-simulator builds use `IsoMe/SimulatorDebug.entitlements`, including `get-task-allow`, so StoreKit recognizes a development install; device and Release entitlements are unchanged. Without it, `storekitd` can reject SKTestSession with `SKInternalErrorDomain Code=3` and “not installed for development.” Do not classify that setup failure as a purchase-logic regression.

StoreManager tests cover the catalog, eligibility, purchase, expiration, lifetime access, entitlement priority, migration persistence/cutoff, and expired-user export access. StoreKit entitlement snapshots may lag purchase/expiration, so integration assertions wait for convergence. Entitlement refreshes are serialized to prevent stale startup/foreground/update snapshots overwriting each other.

Before release, manually verify:

- Fresh App Store user: onboarding → required paywall; cancel/pending/failed purchase does not allow tracking. Restore, retry, terms, and privacy remain available.
- Trial, weekly, yearly, and lifetime purchases; selected tier/duration matches the confirmation sheet and displayed localized renewal price.
- Existing free user after upgrade and reinstall: free tracking preserved, old export gate preserved.
- Existing Individual/Family Lifetime and shared Family Upgrade: access survives update/restore.
- Cancel subscription: access remains until expiration; expiration/refund/revocation stops tracking without deleting history. History and all export entry points remain available for the new cohort.
- New/existing Apple Watch users, including offline sessions, renewal, expiration, shared family access, and reconnect/import. Watch evaluates StoreKit directly; this needs real-device verification.
- Light/dark appearance, accessibility text sizes, small phones/iPad, plan selection, and full-motion onboarding/purchase flow.

For simulator review, use `--use-storekit --review-hard-paywall` (with the local StoreKit configuration active); add `--review-family-paywall` for the Family selector. Without `--use-storekit`, ordinary Debug builds retain their development unlock. Review flags are not production purchase substitutes.

## Implementation verification record

- After the migration fix, the full signed iOS suite passed: **351 XCTest cases plus 11 Swift Testing tests**. Result: `artifacts/test-results/IsoMe-20261029-154039-99910.xcresult`. StoreManager includes 16 tests covering build-50 receipt restoration, sticky/new cohorts, invalid version strings, the build-51 boundary, and embedded iPhone/Watch/widget version alignment. The build-50 boundary regression was first observed failing against the previous cutoff.
- The shared policy is compiled into iPhone and Watch, and all eight Debug/Release target configurations were checked locally for 1.7.19/build 51. These checks do not replace physical-device receipt/Family Sharing/Watch QA.
- Analytics worker: 8 tests passed and TypeScript typecheck passed.
- Inspected simulator screenshots: `artifacts/paywall-review/individual-light.png`, `family-dark.png`, and `family-dark-xxxl.png`. These use the real local StoreKit catalog, not static prices.
- Interactive/full-motion QA was not completed: Argent device tools were unavailable, and the native Simulator GUI application was absent on this host. Screenshots do not verify gestures, purchase-sheet navigation, or animation. Physical-device Family Sharing and Watch QA remain required.

## Local release copy drafts

The [1.7.19 draft package](release-drafts/1.7.19/README.md) contains listing copy for nine locales, proposed subscription display names/descriptions, terms/EULA-related changes, and reviewer instructions. It has not been published or staged. The proposed terms are outside the website's publish roots, and no effective date or custom EULA selection has been changed. Review the legal/license wording and translations before use. Listing copy omits fixed dollar prices; reviewer notes use held live lifetime prices. Earlier target-price review screenshots need to be reconciled with the held prices and final UI before any authorized upload.

## Deployment boundaries

Subscription drafts are configured, but lifetime increases are on hold and have been rolled back as recorded above. Keep live pricing unchanged until the release conditions and explicit user approval in the hold section are satisfied. No build or subscription was submitted, no build was released, and no live service was deployed. Deploy the analytics worker allowlist before shipping subscription purchase events. Update the marketing site, App Store metadata/localizations, screenshot captions, and any “tracking is free”/“no subscriptions” copy to explain the new-user policy at launch; existing public/generated marketing content has not been rewritten or deployed here.
