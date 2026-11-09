# Whereday 2.0 / build 63 (map-first)

**Latest recorded status (reconciled November 8, 2026):** iOS **2.0 (63)** is staged in App Store Connect; this record contains no Apple submission or release receipt. Android **2.0.1 (4)** was recorded as published November 7; the saved production-track response marks it `completed`. The November 6 Play draft below is historical. These are saved receipts and dated records, not a fresh live-store check.

The current draft [PR #109](https://github.com/CodyBontecou/whereday/pull/109) contains later hardening and the in-app 2.0 release notes. Those changes are **not in staged iOS build 63**, built from `f2959482`. A new Apple candidate and its qualification are needed to ship them. The Android sibling completed #107/main/#109 source integration in draft [PR #111](https://github.com/CodyBontecou/whereday/pull/111); local checks passed, while its own CI and the recorded both-mode reboot recovery failure remain gates. That source work does not establish a new Play rollout.

Current Apple/store readiness and exact checks: [November 8 readiness review](readiness-20261108.md). The sections below preserve the staging history; the build 62 section is superseded.

## November 6 map-first staging history

On November 6, 2026, at the owner's request, both stores were moved to the map-first redesign (#105):

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

**Follow-ups done (November 6, 2026):**
- **Privacy policy:** `whereday.com/privacy` now covers Android (#106, merged as `65504738` and live). It has step-by-step deletion instructions at `#delete-your-data`, which is now the deletion URL in Data safety.
- **Verifier account:** the Worker's `GOOGLE_SERVICE_ACCOUNT` is now the dedicated `whereday-play-verifier@crested-drive-492000-u7.iam.gserviceaccount.com`. In Play Console it has Whereday-only access: read-only app info, View financial data, and Manage orders and subscriptions. Its key is at `~/.android-keys/whereday-play-verifier.json`. `play-publisher` is still used by `gplay`.
- **Release mode:** managed publishing stays off, at the owner's choice, so an approved release goes live straight away.

**Sent for review (November 6, 2026):**
- **Production:** 2.0.0 (3), available in all 178 countries.
- **Submission:** all 11 pending changes (release, countries, listing, App content) were sent to Google. Play's quick checks run first (up to 14 minutes), then review, which Google says typically takes up to 7 days. Managed publishing is off, so approval publishes immediately.
- **Two more declarations the bundle triggered:**
  - Foreground service: `FOREGROUND_SERVICE_LOCATION` under Background location updates → Other, with the demo video `https://www.youtube.com/watch?v=GC-jC1MZww8` (unlisted, uploaded as a Short; the source mp4 is in `.argent/recordings/`).
  - Photo and video permissions: `READ_MEDIA_IMAGES` is justified by automatic photo-to-day matching, which uses EXIF location the photo picker strips.
- **Warning only:** no deobfuscation file was uploaded, so crash reports are harder to read.
- **Still due:** the developer account must declare its trader status by February 2027.

**Bangla and Polish listings (November 7, 2026):** the app ships eleven languages, but the store had nine. Version 2.0 now also has **bn-BD** and **pl**:

- **Listing:** name, subtitle, description, keywords, promotional text and What's New, in `metadata/whereday/app-info/` and `metadata/whereday/version/2.0/` (`bn-BD.json`, `pl.json`), using the app's own terms (Mapa/Atlas/Miejsca/Archiwum; মানচিত্র/অ্যাটলাস/জায়গা/আর্কাইভ). The purchase and legal paragraphs are translated line for line from en-US. Applied with `asc metadata apply` and read back identical. The nine existing locales were not touched (the plan had no updates or deletes).
- **Artwork:** rendered from the "One Long Day" design with Bangla and Polish overlay copy (`translations.py`): 10 iPhone, 5 iPad, 8 Duo, the header, and the same two Watch shots as every other locale. The design came from the November 6 download used for the other locales; its en-US renders are pixel-identical to the uploaded set.
- Receipts: `.asc/artifacts/stage-2.0-bn-pl/`.
- Like the rest, these translations were written by Claude and need a native read before submission.

**App screens localized (November 7, 2026):** the app screens drawn inside the frames were English in every locale. They are now translated in all ten non-English locales (labels, dates, durations, weekday initials; place names stay), using the app's own catalog wording where it exists. See `marketing/brand/whereday-appstore-2.0/screen-translations/` and the brand README. The Watch slot is now the Watch board translated per locale instead of the same English crops everywhere. All ten locales' iPhone, iPad, Watch, Duo and header sets were replaced on version 2.0; en-US is unchanged. Receipts: `.asc/artifacts/stage-2.0-l10n/`.

**Android 2.0.1 (4): eleven languages (November 7, 2026, published; Play submissions 2 and 3 approved the same day):**
- **App:** the Android app now ships the same eleven languages as iOS (PR #107, branch `android/localize-eleven-languages`, commit `d8ec8794`, based on `main` at `65504738`, which already contains the live 2.0.0 (3) source `a36954a1`). It has nine new packs in `android/localization/`, per-language plural categories in the generator, and `generateLocaleConfig` for Android 13's per-app language setting. I checked Arabic (right-to-left) and Russian on an API 35 emulator.
- **Build:** `bundleRelease` with `-Pwhereday.versionCode=4 -Pwhereday.versionName=2.0.1` and the same three purchase settings as 2.0.0 (3). CI's release tests and `lintRelease` passed. The bundle is signed with the upload certificate `E9:C6:2A:0E…93:C6`, and its verifier URL and lease key match the live bundle. SHA-256 `6b7a51fe…17918b`.
- **One Play edit** (`01509094824674190881`, committed): the bundle went to production as a full rollout with notes in 12 languages (`metadata/whereday/play/release-notes/2.0.1.json`). It also added listings and artwork (8 phone, 3+3 tablet, the feature graphic, the icon) for es-ES, es-419, fr-FR, pt-BR, pl-PL, ru-RU, ar, hi-IN, bn-BD, ja-JP and zh-CN, from `metadata/whereday/play/<locale>/` and `marketing/brand/whereday-play-2.0/`. Managed publishing is off, so approval publishes everything together.
- Receipts: `.asc/artifacts/play-2.0.1/`. All translations were written by Claude and need a native read.
- **Listing correction (committed the same day, edit `15000477016717824756`):** the PLANS paragraph in all 12 listings no longer says "Imports and export previews are free", and phone shot 06 now reads "Every plan includes exports" instead of "Recording, imports and export previews are free". New installs hit a hard paywall after onboarding, so neither claim was true for someone reading the store page. This restarts review on the pending changes. The in-app wording (13 strings that said recording stays free or exports need lifetime access) is fixed on PR #107 (`028d38b8`) and ships with the next build. Receipts: `.asc/artifacts/play-next/`.
- **Play Console links:** [Publishing overview](https://play.google.com/console/u/0/developers/8478281120215349264/app/4973006723943783760/publishing) and [Submission activity](https://play.google.com/console/u/0/developers/8478281120215349264/app/4973006723943783760/publishing/submission-activity) (developer `8478281120215349264`, app `4973006723943783760`).

---

# Earlier record: build 62 (superseded)

Everything below describes the November 4 candidate, including its navigation, artwork and checklist. Use build 63 and the readiness review above for current decisions. Build 62 must not be reattached.

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

## Historical build 62 checklist (superseded)

1. **Native-language read** of the nine-locale metadata, What's New and screenshot copy (written by Claude), especially ar-SA, hi and ja.
2. **Review the version in App Store Connect**: metadata, screenshots, header and Duo set. Decide whether to replace or remove the old en-US search-results video.
3. Optionally, put build 62 on a phone through TestFlight. There is no internal tester group; the only group is the public "Beta Testers" link, which needs Beta App Review. No physical-device check has been done.
4. Confirm App Privacy is still accurate (no new data types in 2.0).
5. **Submit 2.0 for review** when ready; manual release is set.
6. Add a 2.0 entry to the in-app What's New (`IsoMe/Views/ReleaseNotes.swift` stops at 1.7.17). Build 62 doesn't have one.
