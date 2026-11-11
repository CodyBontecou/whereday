# Retention messages and offers

The copy and offer settings to paste into App Store Connect and Google Play Console. They come from the design canvases: iOS "Retention Messages" (RM_ boards) and Android "Retention Flow" (RF_ boards). Every price is a `[YOUR PRICE]` placeholder, and every value marked `[CONFIRM]` still needs a decision in the store console.

## iOS: App Store Connect retention messages

App Store retention messages appear in Apple's own cancel sheet. Whereday's Settings › plan › **Manage subscription** button (`PaywallView` footer) presents `.manageSubscriptionsSheet`, so subscribers see these messages without leaving the app.

You can configure one message per subscription. The plan below uses 3 of the 10 allowed. Lifetime products (`com.bontecou.isome.lifetime`, `.lifetime.individual`, `.lifetime.family.upgrade`) aren't subscriptions, so they can't have a retention message.

Apple's rules apply: header, body and image must not mention offers, discounts, free periods or prices. Offers appear only in Apple's offer area.

### Individual Yearly: `com.bontecou.isome.individual.yearly`

- **Reference name:** Keep vs stop · Individual
- **Header (33 chars):**

  ```
  Your journal stays on your iPhone
  ```

- **Body (164 chars):**

  ```
  When your subscription ends, Whereday stops recording new visits and routes. Everything already recorded stays on this iPhone, and you can still view and export it.
  ```

- **Image:** `RM_ImgKeep`, 3840×2160, exported at 1×. It contains no text, so it needs no localization.
- **Source of the facts:** `StoreManager.canTrack` gates automatic visits, route points, manual visits, Watch recording and the Start Tracking shortcut. `canExport` stays true for subscribers who first installed on build 51 or later.

### Individual Weekly: `com.bontecou.isome.individual.weekly`

- **Reference name:** Private by design · Individual
- **Header (30 chars):**

  ```
  Your location history is yours
  ```

- **Body (200 chars):**

  ```
  Whereday keeps your journal on your iPhone, with no Whereday account and no copy on Whereday servers. When your subscription ends, recording stops, and your saved days remain yours to view and export.
  ```

- **Image:** `RM_ImgPrivate`, 3840×2160 at 1×, no text.
- Weekly is offered only to people already on Weekly, so this is a small audience. Use it to compare against Concept A, or assign Concept A to both Individual plans.

### Family Yearly and Family Weekly: `com.bontecou.isome.family.yearly`, `com.bontecou.isome.family.weekly`

- **Reference name:** Family journals · Family
- **Header (31 chars):**

  ```
  Every journal stays where it is
  ```

- **Body (193 chars):**

  ```
  When this Family plan ends, Whereday stops recording for you and for family members who use it through Family Sharing. Each person’s history stays on their own iPhone, ready to view and export.
  ```

- **Image:** `RM_ImgFamily`, 3840×2160 at 1×, no text.

### Legacy cohort: use the neutral body if it matters

People who first installed **before build 51** record for free (`StoreManager.hasLegacyTrackingAccess`). For them a subscription only unlocks export: `canExport(legacy: true, purchased: false)` is false. When their subscription ends, recording **continues**, and export, scheduled exports and webhooks lock. Every body above says the opposite ("recording stops … you can still export"), so it is wrong for this group.

[CONFIRM how many subscribers are legacy.] If the number matters, use this neutral body for every message instead (78 chars):

```
When your subscription ends, everything already recorded stays on this iPhone.
```

### Open items (iOS)

- Character limits for header and body: [CONFIRM in App Store Connect].
- Localize for every storefront the plans are sold in: [STOREFRONT LIST]. The app ships en (primary), ar, bn, es, fr, hi, ja, pl, pt-BR, ru and zh-Hans. Translations: [PLACEHOLDER].
- Recording on Apple Watch also stops. The copy says "on your iPhone", which stays accurate for where the journal lives.

## iOS: retention offers (App Store Connect)

Configure each offer on its subscription. The reference name and identifier are permanent. All four plans already have a 1-week free trial as their introductory offer. The retention offer is separate from it.

| Subscription | Reference name | Identifier | Type | Minimum paid duration | Wait between offers |
|---|---|---|---|---|---|
| Individual Yearly | Individual Yearly Retention 1 | `com.bontecou.isome.individual.yearly.retention.1` | Pay up front · 1 year · [YOUR PRICE] per storefront | 1 year, so people canceling during the free week don't get it [CONFIRM option in ASC] | 1 year [CONFIRM] |
| Individual Weekly | Individual Weekly Retention 1 | `com.bontecou.isome.individual.weekly.retention.1` | Pay as you go · [YOUR PRICE] a week for 4 weeks | [CONFIRM shortest option, e.g. 4 weeks] | [CONFIRM, e.g. 3 months] |
| Family Yearly | Family Yearly Retention 1 | `com.bontecou.isome.family.yearly.retention.1` | Pay up front · 1 year · [YOUR PRICE] | 1 year [CONFIRM] | 1 year [CONFIRM] |
| Family Weekly | Family Weekly Retention 1 | `com.bontecou.isome.family.weekly.retention.1` | Pay as you go · [YOUR PRICE] a week for 4 weeks | [CONFIRM] | [CONFIRM] |

## Android: Google Play Console offers

Products, from the Android code:

- Subscription `whereday_individual` has two base plans, Weekly (P1W) and Yearly (P1Y). The base plan IDs aren't in the code: `PlayPurchaseStore` finds plans by billing period. Fill in [WEEKLY BASE PLAN ID] and [YEARLY BASE PLAN ID] from Play Console.
- One-time product `whereday_export_lifetime` is Lifetime. There's nothing to cancel, so it has no retention offer, Plan Change or win-back.
- Each base plan already has a free-week trial offer [TRIAL OFFER ID]. The paywall picks the first offer whose first phase is free, and falls back to the base plan.

Offer IDs follow Play's rule (lowercase letters, numbers and hyphens). They live under `whereday_individual`, so they don't repeat the product name.

### Yearly: [YEARLY BASE PLAN ID]

| Offer ID | Where it shows | Phases | Eligibility |
|---|---|---|---|
| `yearly-retention-1` | Retention Offer in Play's cancel flow | 1 discounted payment: 1 year at [YOUR PRICE], then the base price | Active Yearly subscribers who have paid at least one year [CONFIRM option]; not people canceling during the free week; one per [CONFIRM, e.g. 1 year] |
| `yearly-winback-1` | Win-back Offer on the Play Store | First year back at [YOUR PRICE], then the base price | Lapsed Yearly subscribers, [CONFIRM days since lapse] |
| `yearly-inapp-stay-1` (optional, not built) | In-app Before you go sheet | 1 year at [YOUR PRICE], then the base price | Offer tag `retention-in-app` · developer determined |

`yearly-inapp-stay-1` needs app work before it can be shown. `PlayPurchaseStore` reads only the trial or base offer today, and applying a discount to a live subscription needs a replacement purchase [CONFIRM replacement mode]. The Android Before you go sheet ships without the offer card.

### Weekly: [WEEKLY BASE PLAN ID]

| Offer ID | Where it shows | Phases | Eligibility |
|---|---|---|---|
| `weekly-retention-1` | Retention Offer | [YOUR PRICE] a week for 4 weeks, then the base price | Paid at least [CONFIRM, e.g. 4 weeks]; one per [CONFIRM, e.g. 3 months] |
| `weekly-winback-1` | Win-back Offer | [YOUR PRICE] a week for 4 weeks, then the base price | Lapsed Weekly subscribers, [CONFIRM days since lapse] |

This matches iOS: Yearly gets 1 year paid up front, and Weekly gets 4 weeks pay as you go. Android has no Family plans, so iOS Concept C has no Android counterpart. Retention Offers are rolling out partly through Google's Early Access Program [CONFIRM this account has access].

### Plan Change: none today

Play shows a Plan Change only to subscribers who aren't eligible for the Retention Offer, and only to a lower-priced base plan that exists.

- Yearly → none. The only other base plan is Weekly. Each charge is smaller, but a year of weekly charges costs more than Yearly [CONFIRM with your prices], so offering it as the cheaper plan would mislead.
- Weekly → none. There's no lower-priced base plan.
- Lifetime → not applicable.

To add one later, create a real lower-priced base plan [NEW BASE PLAN, e.g. monthly]. `PlayPurchaseStore` maps only P1W/P7D to Weekly and P1Y/P12M to Yearly and skips any other period, so the app has to learn the new period first.

### What the Android in-app copy says

The Before you go sheet and the "Your subscription ended" page state only what the code enforces:

- **Stops** (access checked when each one starts or runs): starting a recording or visits-only monitoring (`Capability.recording`); exporting files (`fileExport`); automatic exports, webhooks and automation exports (`scheduledExport`, `webhook`, automation results under `fileExport`).
- **Kept:** everything already recorded stays on the phone; Map, Atlas, Places, Archive and Country Days stay open (a lapse never brings back the onboarding gate); imports and export previews stay free (`FREE_CAPABILITIES`).
- No free week is promised after a lapse. The ended page mentions a free week only while Play lists a trial offer for the account.

### Offline subscription status verification — November 11, 2026

A signed local entitlement proves cached access, not current auto-renewal.
When Play cannot provide a purchase snapshot, the subscription page uses neutral
cached-access copy and retains Google Play management. Cached Lifetime access
also keeps management available because a separate subscription may still renew.
A fresh Play snapshot can establish renewal even if server verification fails;
the existing verified cache still controls access.

Verification: 28 billing tests and 8 subscription-text unit tests pass. All six
native `SubscriptionScreenTest` cases pass on the disposable mapQa emulator:
keep/continue cancellation navigation, Back dismissal, offline subscription,
offline Lifetime, Lifetime with a renewing subscription, and lapsed access.
The URI handler is a test double, so these tests never cancel a real subscription.
The mapQa-only preview activity supplies presentation fixtures without modifying
purchases or entitlement access. Manual multilingual and accessibility acceptance
and fresh-head CI remain separate gates.
