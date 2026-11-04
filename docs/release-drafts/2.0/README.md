# Whereday 2.0 / build 62

**Status: archived and exported locally as 2.0 (62), not uploaded.** Nothing was uploaded, created or submitted in App Store Connect, and no prices, products, availability or public pages were changed. App Store Connect has no 2.0 App Store version yet.

## Release-build fix (`2e295a2f`)

`main` at `fd2a348c` did not archive in Release:

```
IsoMe/Services/StoreManager.swift:259:45: error: 'offer' is only available in iOS 17.2 or newer
```

The trial reminder from `d494de4a` (paywall carousel) read `Transaction.offer`, which needs iOS 17.2, while the iPhone app targets iOS 17.0. Debug simulator builds and `scripts/test-ios.sh` didn't catch it. `StoreManager.isIntroductoryOffer(_:)` now uses `offer` on 17.2+ and falls back to `offerType` on 17.0–17.1, so the reminder still works on every supported version. This branch carries the fix; it must land on `main` before (or with) the release.

2.0 is the "Whereday Reimagined" redesign of the iPhone app, widgets, Live Activity and Apple Watch app, merged to `main` on November 4, 2026 as #97 (app) and #98 (site, paywall carousel, Lifetime StoreKit test prices, 24-hour clocks). The release candidate is `main` at `fd2a348c` plus the fix above (`2e295a2f` on `release/2.0-build-62`).

## Build number: 62, not 60

The Xcode project says 2.0 (60), but build 60 is already taken in App Store Connect:

| Build | Version | Uploaded (Nov 2, 2026, PT) | What it is |
| --- | --- | --- | --- |
| 59 | 1.9.0 | 05:37 | earlier TestFlight build |
| 60 | 2.0 | 08:21 | pre-merge redesign build (`8f1659a0`), TestFlight beta review approved. It predates #98, so it has no paywall carousel, 24-hour clocks or Lifetime price fixture fix |
| 61 | 1.8.3 | 10:32 | launch-hang fix, **released** to the App Store (READY_FOR_DISTRIBUTION) |

The candidate is therefore archived as **2.0 (62)** with the command-line overrides `MARKETING_VERSION=2.0 CURRENT_PROJECT_VERSION=62`, the same way 1.8.2 was. The project file is unchanged. Build 60 should not be attached to the 2.0 App Store version.

## What's New since 1.8

The released versions since 1.8 are 1.8.1 (illustrated onboarding, export previews, analytics removal), 1.8.2 (iPhone Duo screenshots only) and 1.8.3 (launch hang while checking purchases). Everything user-visible in 2.0 comes from the redesign:

- Tabs are now **Today / Atlas / Places / Archive**. Trace (the map) opens from Today's dial, the timeline from Today's calendar button, and Settings from the gear on Archive.
- **Today**: a 24-hour day dial with stops and routes, plus blank-day and location-limited states.
- **Atlas**: a year in days, colored by country. **Place rhythm** and a **month recap** card.
- **Archive**: "export as a sentence" recipe builder over the existing export options; recipes are saved locally. All options are still reachable.
- **Onboarding** is three steps; "How much should it remember?" picks existing distance-filter/auto-stop presets.
- **Paywall** is a three-page carousel. A new local reminder two days before a free trial ends, which asks for notification permission at that moment.
- New medium "strip" and small "dial" widgets, a restyled Live Activity and Dynamic Island, and a restyled Watch glance, route screens and complications.
- Dial, replay and timeline clocks follow the 24-hour setting.
- Foldable (iPhone Duo): two-pane Today and map.

Localized What's New for the nine listing locales is in [`whats-new.json`](whats-new.json) and in each `metadata/whereday/version/2.0/<locale>.json`.

## Metadata review

Baseline: the live 1.8.3 listing, downloaded from App Store Connect on November 4, 2026 (private copy in `.asc/artifacts/stage-2.0-62/live-1.8.3/`).

| Field | Still accurate for 2.0? | Draft |
| --- | --- | --- |
| Description, intro paragraph | Yes | unchanged |
| Description, "Your location journal" list | No: no Today dial, Atlas, Places, widgets or Live Activity; the format list omits GeoJSON/OwnTracks/Overland | rewritten in all nine locales |
| Description, access / trial / existing users / expiry / Family / privacy paragraphs | Yes. It states no prices, and the 7-day trial, plans, grandfathering (builds through 50) and Family Sharing are unchanged | unchanged, word for word |
| Promotional text | Not set on 1.8.3 | new 2.0 line drafted (≤170 chars) |
| Keywords | Yes, nothing in them is out of date | unchanged |
| What's New | — | new |
| Name / subtitle (`app-info`) | Yes | unchanged |

Pricing check: the description and reviewer notes do not quote prices. The live Lifetime prices (Individual $49.99, Family $89.99, Family Upgrade $40.00 in the U.S. since October 30, 2026) and the subscription prices ($0.99/$29.99 Individual, $1.99/$49.99 Family, with a 7-day free trial) match `IsoMe.storekit` on `main`. The 1.8 reviewer notes' "$19.99/$39.99/$19.99 Lifetime" line is out of date and was not carried into the 2.0 notes.

**Translations were written by Claude, not by native speakers.** As with 1.8, they need a native-language read before submission, especially ar-SA, hi and ja. Field lengths are checked against App Store limits.

## Screenshots

The redesign makes every live screenshot set stale:

| Set | Live (from 1.8 / 1.8.2) | 2.0 status |
| --- | --- | --- |
| iPhone 6.7"/6.9" (`APP_IPHONE_67`), 9 locales × 5 | old map, settings, export and webhook screens | **story and captions ready, captures not usable yet**: see below |
| iPad 12.9"/13" (`APP_IPAD_PRO_3GEN_129`) | old four-page onboarding | **not regenerated**: no iPad simulator is installed on this Mac. The capture script now uses the three 2.0 onboarding pages |
| Apple Watch | old Watch UI | **not regenerated**: no watchOS simulator runtime is installed |
| iPhone Duo (en-US only, from 1.8.2) | old design | **not regenerated**: these come from the separate `marketing/brand/whereday-duo-v1` pipeline |

`scripts/capture_localized_screenshots.py` had the pre-2.0 tab numbers (PR #97 noted this). It now uses the 2.0 launch tabs and takes `--devices iphone,ipad,watch`, so the iPad and Watch sets can be captured separately on a Mac with those runtimes. New captions for all 11 caption locales are in `screenshots/localized-copy.json`. They are hand-written, so don't rerun `scripts/localize_screenshot_copy.py`: it machine-translates from en-US and applies the pre-2.0 positional `OVERRIDES`.

An en-US trial capture on the iPhone 18 Pro Max (iOS 27.0) ran end to end, but `--seed-screenshot-data` doesn't fill the redesigned screens. Today shows "0 m moved, 0 points kept", Trace "Nothing recorded", Atlas "No country days yet", and Places "No Saved Locations". Only the Archive recipe screen looks right. The seed data needs to cover today's date, country stays and saved places (Debug-only app code) before the nine-locale run is worth doing. The composed images are git-ignored under `screenshots/whereday/` and weren't uploaded.

## Qualification

- `scripts/test-ios.sh --destination 'platform=iOS Simulator,id=<iPhone 18 Pro, iOS 27.0>'` on the fixed source: **364 XCTest cases passed, 0 failures; 11 Swift Testing cases passed** (`** TEST SUCCEEDED **`). iOS 27.2 simulators aren't installed, so the default destination wasn't used. The same result on `fd2a348c` before the fix is kept in `failed-archive-fd2a348c/`.
- Release archive and App Store Connect export (`destination=export`, not upload): **succeeded**. All four bundles in the IPA (phone, phone widget, Watch, Watch widget) are **2.0 / 62**; see `ipa-verification.json`.
- No physical-device check and no App Store Connect validation yet: validation needs the upload.
- Logs, archive and IPA: `.asc/artifacts/stage-2.0-62/`.

## Still to do

1. Merge this branch (it carries the Release-build fix), then upload `.asc/artifacts/stage-2.0-62/export/IsoMe.ipa` (2.0 / 62) once approved. The IPA was built from `2e295a2f`; if anything else lands first, re-archive.
2. Make `--seed-screenshot-data` populate Today, Trace, Atlas and Places. Then run `scripts/capture_localized_screenshots.py --devices iphone` for the nine listing locales, and capture iPad and Watch on a Mac with those simulators. Redo the en-US Duo set.
3. Get a native-language read of the drafted metadata and captions.
4. In App Store Connect (owner): create the 2.0 iOS version with manual release, apply `metadata/whereday/version/2.0/*.json` and the reviewer notes, attach build **62 (not 60)**, replace the iPhone, iPad, Watch and Duo screenshots, and check App Privacy is unchanged. Submitting for review needs the owner's go-ahead.
