# Whereday 2.0 / build 63 (map-first)

**Status: staged in App Store Connect and as a Google Play production draft. Nothing submitted or rolled out.** On November 6, 2026, at the owner's request, both stores were moved to the map-first redesign (#105):

- **iOS build:** the `v2.0` GitHub release was published, so `deploy-asc.yml` archived `main` at `f2959482` as **2.0 (63)**. It processed **VALID** (`b1c136de-db11-400c-aa46-437a01c62b20`) and replaces build 62 on App Store version 2.0. Build 62 predates map-first and should not be attached again.
- **iOS listing:** What's New, promotional text and the first description bullet are rewritten for map-first in all nine locales (`metadata/whereday/version/2.0/`, `whats-new.json`). Uploaded and read back identical.
- **iOS artwork:** rerendered from the "One Long Day" artifact: ten iPhone shots in the new order, five iPad, eight Duo and the header, in all nine locales.
- **Apple Watch:** the empty "Standby / 0 m" simulator capture is replaced by two 416×496 shots cropped from the artifact's `Watch` board (day dial at 6.4 km; route recording at 2.81 km), with the bezel and corner rounding removed. Same images in every locale.
- **Android:** the release workflow failed because GitHub has no signing secrets. Instead, the bundle was built and tested from a clean `main` checkout (`f2959482`; same Gradle tests and lint as CI), then signed locally with the upload key and verified by `release_bundle.py`. **2.0.0 (version code 2)** is a **draft on the production track**, with release notes from `metadata/whereday/play/release-notes/`. The native-provenance check from CI was not run locally.
- **Play listing (en-US):** short and full description (`metadata/whereday/play/en-US/`), icon (the launcher icon at 512 px), feature graphic, 8 phone screenshots and 3 each for 7-inch and 10-inch tablets, rendered from the "Whereday — Google Play" artifact.

Fixed in the design artifacts on the way: the new iPad/Duo and Play tablet map screens rendered black (their colour script was missing), the Duo tabletop had no map, shot 07's "FERRY BUILDING" label crowded the headline, and the week shot's "Sunday's loop" note ran off the edge in several locales.

**Play update (November 6, 2026, later):** the production draft is now **2.0.0 (version code 3)**, built from `main` at `f2959482` with purchase verification configured. The values are passed as `-P` flags and are not kept in any Gradle file: the verifier URL `https://whereday-play-purchase-verifier.costream.workers.dev`, the lease public key `~/.android-keys/whereday-lease.pub.b64`, and Cloud project `84338733279`. Code 2 was replaced; it had billing unconfigured.

- **Verifier Worker** (`worker/play-purchase-verifier`): deployed with D1 `whereday-play-purchases` and the `LEASE_PRIVATE_KEY` (`~/.android-keys/whereday-lease.pem`) and `GOOGLE_SERVICE_ACCOUNT` secrets. It uses the `play-publisher` service account, which is a Play **Admin**; a narrower account is recommended.
- **Google Cloud:** the Play Integrity and Android Publisher APIs are enabled on `crested-drive-492000-u7`, and the project is linked under Play Console → Play Integrity API.
- **App content (all 10 done):**
  - privacy policy `https://whereday.com/privacy`;
  - no ads and no advertising ID;
  - target audience 18+;
  - not a government app; no financial or health features;
  - IARC rating: ESRB Everyone, PEGI 3, ClassInd 14+;
  - sign-in details give reviewers a Lifetime promo code. That campaign is "Play review access 2026", 5 codes valid Nov 7, 2026 to Feb 28, 2027, kept in `.asc/artifacts/play-2.0.0/reviewer-promo-codes.csv`.
- **Data safety:**
  - location (approximate and precise) is collected, ephemeral, optional, for app functionality;
  - purchase history and device IDs are collected, stored, required, for app functionality and fraud prevention;
  - nothing is shared, and data is encrypted in transit.
  - The optional deletion-URL question is left blank.
- **Store settings:** category Travel & Local; contact `cody@isolated.tech` and `https://whereday.com/`.
- **Managed publishing is off**, so changes go live as soon as review approves them.

Still open: `whereday.com/privacy` covers only iOS. It needs Android coverage (Google Play Billing and Integrity, the Play purchase verifier, Android geocoding, OpenFreeMap map tiles) before submission. Nothing has been sent for review yet.

---

# Earlier record: build 62

**Status: staged in App Store Connect, not submitted.** On November 4, 2026, at the owner's request:

- The 2.0 (62) IPA built from `2e295a2f` was uploaded and processed **VALID**: build `176ecaeb-e401-4844-9235-8bed28663383`, minimum iOS 17.0, encryption exempt.
- iOS App Store version **2.0** was created: `41f370e9-3029-4ff5-9664-90ba13a871c9`, **PREPARE_FOR_SUBMISSION**, **MANUAL** release, © 2026 Whereday. Nine-locale metadata was copied from 1.8.3, then replaced with the drafts in `metadata/whereday/version/2.0/`. The read-back matches in every locale.
- Build 62 is attached, and the App Review notes are set from `reviewer-notes.txt`.
- New screenshots and header are in place for all nine locales (see Screenshots).

It was **not submitted for review**. No prices, products, availability, App Privacy or public pages were changed, and no TestFlight group was touched. Receipts are in `.asc/artifacts/stage-2.0-62/`.

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

Every locale of version 2.0 now has the redesign's artwork. Live 1.8.x listings are unchanged.

| Slot | Per locale | Source |
| --- | --- | --- |
| iPhone 6.9" (`APP_IPHONE_67`) | 10 | "One Long Day" design artifact, rendered and translated by `marketing/brand/whereday-appstore-2.0/render.py` |
| iPad 13" (`APP_IPAD_PRO_3GEN_129`) | 5 | same |
| iPhone Duo (Asset Library) | 8 (7 inner, 1 outer) | same, placed by `place-assets.py` |
| Product page header (Asset Library) | 1 | same, `StoreHeaderB`, 3840×1646 |
| Apple Watch (`APP_WATCH_SERIES_10`) | 1 | simulator capture of the redesigned Watch glance (Standby; the Watch app has no sample-data mode) |

Previously only en-US had a header and Duo screenshots; all nine locales have them now. Overlay copy is translated per locale. The mock app screens inside the frames are drawings and stay in English everywhere; `marketing/brand/whereday-appstore-2.0/README.md` explains why.

Not replaced: the en-US **App Store search-results video** (`APP_STORE_SEARCH_RESULTS_ASSET`) inherited from 1.8.x still shows the old design. The design artifact only has a storyboard for it.

The simulator pipeline (`scripts/capture_localized_screenshots.py`) was also fixed for 2.0: the new tab numbers, a Debug-only seeder that fills Today, Trace, Atlas and Places, iPad and Watch support, and caption-layout fixes. Its captures were superseded by the design set; the Watch capture is the one still used.

## Qualification

- `scripts/test-ios.sh --destination 'platform=iOS Simulator,id=<iPhone 18 Pro, iOS 27.0>'` on the fixed source: **364 XCTest cases passed, 0 failures; 11 Swift Testing cases passed** (`** TEST SUCCEEDED **`). iOS 27.2 simulators aren't installed, so the default destination wasn't used. The same result on `fd2a348c` before the fix is kept in `failed-archive-fd2a348c/`.
- Release archive and App Store Connect export (`destination=export`, not upload): **succeeded**. All four bundles in the IPA (phone, phone widget, Watch, Watch widget) are **2.0 / 62**; see `ipa-verification.json`.
- Upload processed **VALID** in App Store Connect. No physical-device check yet; build 62 can go to internal TestFlight testers for one.
- Logs, archive and IPA: `.asc/artifacts/stage-2.0-62/`.
- After the Debug-only screenshot-seeder change (`176221cd`), `scripts/test-ios.sh` passed again: 364 XCTest + 11 Swift Testing tests. That change compiles out of Release, so uploaded build 62 is unaffected.

## Still to do (owner)

1. **Native-language read** of the nine-locale metadata, What's New and screenshot copy (written by Claude), especially ar-SA, hi and ja.
2. **Review the version in App Store Connect**: metadata, screenshots, header and Duo set. Decide whether to replace or remove the old en-US search-results video.
3. Optionally, put build 62 on a phone through TestFlight. There is no internal tester group; the only group is the public "Beta Testers" link, which needs Beta App Review. No physical-device check has been done.
4. Confirm App Privacy is still accurate (no new data types in 2.0).
5. **Submit 2.0 for review** when ready; manual release is set.
6. Add a 2.0 entry to the in-app What's New (`IsoMe/Views/ReleaseNotes.swift` stops at 1.7.17). Build 62 doesn't have one.
