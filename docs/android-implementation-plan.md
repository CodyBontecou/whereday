# Whereday Android implementation plan

Plan date: October 26, 2026. Scope: Android phone release first; Wear OS afterward, as requested. This is an implementation proposal, not authorization to publish or provision paid services.

Approved product decision, October 27, 2026: the Android launch uses an individual lifetime purchase. A family account/invitation service is outside this launch. The billing, verification, restore and revocation requirements below still apply; cross-store eligibility and myself.md claims require separate decisions.

Approved catch-up policy, October 27, 2026: the owner requested matching the current iOS application. `DailyExportScheduler.runIfDue` selects the latest due occurrence, with completion tracked by `lastRun`; it does not enumerate a seven-day or all-history backlog. The current iOS export plan writes the current local day through actual execution time, with same-day append cursors for interval append and full-day rewrites for GPX/KML. Preserve exact occurrence identity for retries/deduplication and explain that delayed execution can leave a prior day's aggregate incomplete; historical recovery is a manual export. Source: `IsoMe/Services/DailyExportScheduler.swift` and `IsoMeTests/IntervalScheduledExportTests.swift` as inspected October 27.

Automation integration target, October 28, 2026: the owner delegated client selection; use Automate for the scoped, authenticated Android integration and physical-device walkthrough. The scoped Activity/consent contract is implemented and isolated cross-UID tests pass; the installed Automate walkthrough is not yet accepted.

Approved analytics decision, October 28, 2026: Android offers optional usage analytics only after explicit opt-in. Consent must default off; no analytics collection or upload may occur before that choice. This controls analytics telemetry; the separate on-device support log stays memory-only and is copied only by user action. Pre-consent events, including entries in the local support log, must not be replayed into analytics. The consent UI, event hooks, cancellable transport and WorkManager integration are implemented and locally tested. Production delivery remains disabled pending receiver deployment, event/provider/privacy acceptance and the remaining device matrix.

Build a native Kotlin and Jetpack Compose app alongside the existing iOS app. Match the app's data, workflows, export contracts, and free versus paid capabilities, adapting system integrations to Android. Prove background recording and visit detection on physical devices before committing to the full feature build.

## Product baseline

Use the current working-tree source as the reference, with [FEATURES.md](FEATURES.md) as the inventory. The README has known drift: there are eight export formats, independent watch recording, third-party packages, and release analytics. CoreMotion auto-start is no longer present. Do not recreate removed features based on README claims.

The source also contains newer functionality absent from the inventory: Country Days and manual country stays; system/light/dark themes; richer automation actions for reading and renaming visits and movements; and the myself.md purchase-claim screen. Freeze a source revision and build a traceable parity checklist before implementation. Reconcile this checklist whenever the iOS baseline changes.

### Phone release coverage

| Area | Android release requirement | Existing reference |
| --- | --- | --- |
| Tracking | Start/stop; background route recording; persisted state; 5–200 m distance choices; Never/1/2/4/8/12 h auto-stop; location quality and outlier rules; daily statistics | `LocationManager`, `DailyDistanceTracker` |
| Visits | Automatic arrivals/departures; merge/reconciliation; saved-place matching; manual visits; confirm/correct/undo; original values and provenance; editable times, names, addresses, notes | `Visit`, `SavedPlace`, visit views |
| Places | Search, nearby suggestions, current-place suggestion, searchable saved-place library, radius editing, CSV import and duplicate preview | Place services, `SavedPlaceImportService` |
| Maps | Date ranges, custom dates, visit pins, point/path layers, start/end flags, photo clusters, outliers, road-snapped display, fit/follow controls | `MapView`, `SessionPathMapView` |
| Timeline | Day navigation; exact/inferred outings; configurable inference; details, rename/notes, linked visits/photos, delete, map focus and export | `OutingsView`, `RecordingSession` |
| Replay | Day and outing playback; scrubber; elapsed time, distance and point statistics | Map and outing views |
| Country Days | Country-local unique dates; year/365-day/all filters; detail dates; manual stays; resumable analysis; CSV export | `CountryStay`, `CountryDayResolver`, tests |
| Photos | Optional photo correlation, automatic sync where permission allows, markers/thumbnails, strips, pager, limited-access handling | `PhotoLibraryManager`, `PhotoMoment` |
| Portability | Eight export formats; free preview; all filters and field choices; per-day/per-outing splitting; filename templates/subfolders; default folder/share; JSON/CSV/Markdown import | `ExportService`, import services, ExportKit adapter |
| Automation | Daily and anchored 1–23 h export schedules; rewrite/append; run-now, catch-up, error/retry surfaces; platform equivalents for current read/rename/start/stop/export actions | Schedulers and all `IsoMe*Intents` files |
| Webhooks | All formats; all auth modes; real-time/count/time/manual delivery; retry policy; queue/status/test connection; credential masking | `WebhookManager` |
| Live status | Ongoing tracking notification with distance, elapsed/countdown, points and Stop; optional compact route image | `LiveActivityManager`, widget target |
| Purchases | Free unlimited tracking/import/preview; lifetime export unlock; paid scheduled exports and webhooks; restore/revocation handling | `StoreManager`, paywall |
| Shell and support | Five-step onboarding adapted to Android; appearance, units, settings, clear-data, logs, release notes, review prompt, support/legal links, localization/accessibility | Content/settings views, string catalogs |
| Service integrations | Explicit analytics policy; purchase-only myself.md claim if Android eligibility is approved | Analytics services, `MyselfClaimView` |

No Android home-screen widget is required for parity: the current iOS widget target provides Live Activities only. A home-screen widget is optional future scope. Apple Watch functionality moves to the later Wear OS release.

## Architecture and repository structure

Keep iOS intact and add an `android/` Gradle project. Proposed minimum: Android 10/API 29, to be confirmed against audience requirements. Target API 36 or higher at implementation time and recheck before submission; the published requirement for new phone apps is currently API 36. Pin current compatible stable Kotlin, AGP, Compose, Room and Billing versions in a version catalog after the first build succeeds. [Target API requirements](https://developer.android.com/google/play/requirements/target-sdk)

Use Compose navigation and ViewModels with StateFlow, coroutines, Room for durable records, DataStore for nonsecret preferences, Android Keystore-backed encryption for webhook secrets, and WorkManager for deferrable jobs. Use explicit dependency injection initially; adopt a framework only if the dependency graph warrants it.

Start with these modules, keeping individual features as packages until size justifies more modules:

```text
android/
  app/                  # Compose shell, navigation, onboarding and feature UI
  domain/               # Pure Kotlin rules, models, time math and use cases
  data/                 # Room, migrations, repositories, settings and outbox
  platform/             # Location service, files, photos, notifications, billing
  export/               # Renderers, parsers, planning, merge and filename rules
  gradle/libs.versions.toml
shared-fixtures/        # Platform-neutral inputs and expected results
```

The domain layer must compile without Android dependencies. UI and background components call the same commands and observe persisted state. A location service owns recording; an Activity or composable never owns the recording lifecycle. Durable work survives UI disposal and process death where Android permits recovery.

Reuse concepts, fixtures, assets and protocol definitions. Port Swift logic to Kotlin rather than attempting to reuse SwiftData, SwiftUI or the Swift ExportKit binary. Do not rewrite iOS into a shared framework as part of this project. Keep export compatibility through cross-platform tests.

### Provider choices

Recommended initial distribution is Google Play with Play services fused location behind a `LocationSource` interface. A non-Google build is separate scope; preserve an interface seam without implementing an untested second recording engine. [Fused location API](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)

Use MapLibre Native as the initial map-rendering candidate behind `MapRenderer`. It supplies rendering, not production tiles, geocoding, search or routing. Phase 0 must select a production tile provider, reverse-geocoder, POI/search provider, routing provider, and coordinate-to-time-zone resolver. Evaluate availability, attribution, license/cache rules, network data sent, recurring cost and regional coverage. Do not ship against a demo tile service or assume public OSM infrastructure is a production backend. Prototype the needed layers, clustering and snapshots before confirming this choice. [MapLibre Android documentation](https://maplibre.org/maplibre-native/android/examples/)

Keep coordinate recording functional offline, without a map or place provider. Cache approved lookup results; honor the geocoding opt-out; retain user corrections. Route matching affects visualization only and never changes recorded coordinates. Country Days requires a location's IANA time zone; a country code or the phone's current zone is insufficient.

## Background recording and visit detection

Run user-started recording in a declared `location` foreground service with the appropriate manifest permissions and an ongoing notification. Request foreground location first; explain precise versus approximate access. Request notifications in context. Request background location separately only for the passive detection/recovery paths that require it. While-in-use location can support a properly started recording service; background permission is not a blanket requirement for every recording session. [Location foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types), [background permission flow](https://developer.android.com/develop/sensors-and-location/location/permissions/background)

Persist requested recording state, actual service state, active session ID, auto-stop deadline and last durable fix. Serialize start/stop actions so repeated taps, notification actions and restored processes cannot create duplicate sessions. Commit points and related state transactionally before updating UI, notifications or delivery queues. Preserve timestamps and tolerate batched/out-of-order fixes, clock changes and unavailable altitude/speed.

Define explicit behavior for screen lock, UI dismissal, OS process death, service termination, reboot, force-stop, battery restriction, revoked permission and disabled location. Reconcile on relaunch; resume only through a permitted Android path, otherwise explain that user action is required. Never display 'recording' solely because a stored toggle is true. A boot receiver and sticky service do not guarantee unrestricted restart. [Foreground service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

Android needs a visit detector rather than a direct `CLVisit` translation. Implement a persisted state machine: moving → candidate dwell → open visit → candidate departure → closed visit. Use accuracy-aware spatial clustering, dwell/departure hysteresis, event timestamps and stale-fix rejection. Start experimental calibration with a 100–150 m dwell radius and approximately five minutes of dwell; these are proposed Android parameters, not established iOS equivalents. Backdate confirmed arrivals to the first supporting sample. Never fill a sampling gap with invented presence.

Keep visit detection separate from exact route recording so passive visits need not require continuous high-accuracy GPS. Compare balanced-power updates and dynamically managed geofences for likely/saved places; geofences alone cannot discover arbitrary visited places. Validate latency, power use, background limits and permission requirements in the physical-device spike. If the only reliable approach requires an ongoing service for all visit capture, make its notification and battery cost an explicit product decision before implementation proceeds.

Port the existing merge, correction-protection, reconciliation and outlier rules separately from this detector. Include pedestrian, vehicle, stationary indoor, urban canyon, short-stop, airplane and multi-day fixtures. Measure false visits and missed visits against labeled traces rather than assuming identical behavior from identical constants.

## Storage, compatibility and privacy

Room entities cover visits, points, exact sessions, saved places, photo references and manual country stays. Also persist detector state, schedule occurrences, export results/cursors and webhook outbox entries. Preserve UUIDs, nullable values, original/detected coordinates, confirmation/source fields, country-resolution metadata and inferred-departure flags. Index point timestamps, visit arrival/departure and session ranges. Test migrations from the first schema onward.

Store event instants in UTC and civil country-stay dates as dates, with explicit time-zone identifiers for geographic calculations. Use an injectable clock. Reproduce inferred outing splits, outlier handling and distance calculations through shared fixtures. Separate raw storage from bounded map queries; preserve existing 2,500-point display and 500-photo limits while streaming large exports. Add cancellation and progress for analysis/import/export.

Disable automatic cloud backup of sensitive history, photo references, credentials and queues; explicitly decide device-to-device transfer behavior. Android's default backup behavior must not silently contradict on-device storage promises. Clear-data stops recording, cancels work and clears owned data/caches/outboxes; disclose that files already exported to a user folder remain there.

Maintain an egress inventory: maps, search, geocoding/routing, user-enabled webhooks, billing, optional analytics and purchase claims. Never send raw tracks to analytics or billing. Keep support logs free of secrets and unnecessary coordinates. Analytics exists in the current iOS release source despite README copy. The approved Android policy is explicit opt-in, default off; align store disclosures and documentation with that decision before enabling production delivery.

### Import and export contract

Create synthetic golden fixtures from the current Swift renderers and importers. Cover JSON, CSV, Markdown, GeoJSON, GPX, KML, OwnTracks and Overland; retain points-only semantics and existing fallback behavior where applicable. Only JSON/CSV/Markdown are general history imports today. Saved-place CSV is a separate import contract.

Test iOS export → Android import and Android export → iOS import for supported formats. Check semantic identity and precision; use byte comparisons only for deliberately stable syntax. Cover BOM/quoted/multiline CSV, escaping, nulls, time zones, open visits, corrections, unknown optional fields, split boundaries, filename collisions, Unicode and path traversal. Port country-day CSV separately. Document which fields existing formats cannot represent so migration does not promise a complete database clone. Photos must be relinked on the new device; Apple asset identifiers are not portable.

Use Storage Access Framework directory selection and persisted URI grants for a default export folder, and FileProvider content URIs for sharing. Handle revoked grants, read-only/unavailable providers, low storage and partial writes. Render to an app-private temporary file before destination replacement; a document provider may not support atomic rename or append. Advance success/cursors only after a verified write and preserve the last good file where provider capabilities allow. [Android document storage](https://developer.android.com/training/data-storage/shared/documents-files)

## Scheduled exports, webhooks and automation

Port the existing schedule date math: daily anchor, every 1–23 h, reset at the next anchor, time-zone/DST transitions and stable daily filenames. Schedule unique WorkManager work toward the next occurrence, backed by recovery/catch-up. Evaluate an inexact alarm only if the spike demonstrates a meaningful benefit. WorkManager execution is deferrable; do not promise exact-minute exports or request exact-alarm privileges merely to imitate the iOS scheduler. [Work scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

Use a durable occurrence key and per-destination lock so app-open, run-now and worker triggers cannot write concurrently or duplicate success. Preserve rewrite/append semantics: CSV rows, JSON identity merging, Markdown section merging, GPX/KML rewrite. Match iOS when execution is delayed: select the latest scheduled occurrence on the current local date, skip it when already completed, and export the current local day through actual execution time. Once-daily runs rewrite; interval append starts at the same-day cursor or midnight when the cursor belongs to an earlier day. GPX/KML rewrite the full current day without advancing an append cursor. Persist the occurrence, captured window and retry metadata; advance success/cursors only after verified delivery. Show last success, delay/error and retry action. Do not automatically enumerate missed days; provide manual historical export and explain that a delayed run can leave a prior day's aggregate incomplete.

Use Android scheduling locally initially. The APNs worker has no Android transport; an FCM extension is optional later work, not a prerequisite. A silent FCM message is not an exact scheduling guarantee. Any future extension needs authenticated installation registration, token rotation, deletion and routing-only data; do not duplicate the current worker's unauthenticated registration pattern.

Webhook delivery uses a durable outbox with stable record identities, response-aware retry/backoff, count/time/manual modes and credential masking. HTTP retries can duplicate delivery unless the receiver supports idempotency; describe delivery as at-least-once and send an idempotency key where supported. Port the current supported authentication schemes and warn about secrets in query URLs. Cancellation, permission revocation and clear-data must prevent old queues from sending later. Time-based batches can be delayed when Android suspends background work.

Build one internal command/query layer for tracking, counts/distance/duration, current outing name, date-range visits/movements, rename operations and parameterized exports. Expose launcher shortcuts and notification actions for simple operations. For richer third-party automation, define an opt-in, authenticated Android contract with scoped data access and temporary file URI grants; do not export an unrestricted history ContentProvider. Prove one integration with a chosen Android automation client. Full Siri phrase/entity behavior has no automatic Android equivalent; specify unsupported voice interactions openly. Use one explicit purchase-gating policy across UI and automation instead of accidentally carrying forward ungated export intents.

## Photos, purchases and platform substitutions

Photo moments use MediaStore references and capture times, with EXIF coordinates when available/authorized, then the existing nearest-point and containing-visit fallbacks. Handle selected-photo access, deletions, revoked permission and missing GPS. Photo Picker supports selected assets; it does not itself grant automatic whole-library scanning. Broad automatic scanning needs a justified Play permissions declaration and review. If approval fails, selected-photo correlation remains functional and the whole-library parity gap must be disclosed before launch. [Shared media access](https://developer.android.com/training/data-storage/shared/media), [photo permission policy](https://support.google.com/googleplay/android-developer/answer/14115180?hl=en)

Replace Live Activities and Dynamic Island with an ongoing Android notification, avoiding sensitive lock-screen content by default and respecting notification settings. Stop must work with no Activity open. Treat any newer platform-specific live-status presentation as optional enhancement after the baseline notification works.

Recommend one non-consumable individual lifetime product for the first Android launch. Preserve the free/paid feature split and localized store pricing. Google Play Family Library does not share in-app purchases, so the iOS family plan/upgrade cannot be copied directly. Full family parity requires a separately approved account or invitation entitlement service; phone-first does not resolve this business decision. Do not advertise family or cross-store entitlement support until implemented. [Family Library eligibility](https://support.google.com/googleplay/answer/7007852?hl=en)

Implement pending/canceled/successful purchases, acknowledgement, restore, refund/revocation refresh and cached offline access. Recommend a small purchase-verification service using the Google Play Developer API, with no location data or mandatory tracking account. Define token ownership, retention, offline grace and reconciliation; protect server credentials and prevent token replay. [Billing integration](https://developer.android.com/google/play/billing/integrate), [purchase verification guidance](https://developer.android.com/google/play/billing/security)

The current myself.md claim endpoint receives iOS transaction proof. Android needs an agreed eligibility policy and server verification of Play proof before showing a qualifying claim action. An Apple purchase cannot simply be restored through Google Play. Keep any purchase-claim sign-in optional and separate from recording.

## Delivery milestones

Effort ranges are planning estimates for one experienced Android engineer working full time with access to iOS/product support and physical test devices. They exclude store-review waiting, provider procurement and a custom family entitlement system. Re-estimate after Phase 0.

| Phase | Work and dependencies | Estimate | Exit criterion |
| --- | --- | --- | --- |
| 0 — Platform proof and decisions | Freeze parity baseline; choose providers/SDKs; location service and detector prototype; permission/recovery tests; export-to-folder spike; confirm billing/photo/automation policies | 1–2 weeks | Screen-off route and dwell traces on Pixel and Samsung; explicit recovery behavior; provider cost/privacy review; decisions recorded |
| 1 — Durable recording foundation | Gradle/CI; domain/data modules; Room/migrations; preferences; recording state machine; notification; outliers, sessions and reconciliation; fixture harness | 2–3 weeks | Start → lock phone → record → stop → reopen preserves one correct session and all acknowledged points; process/restart tests pass |
| 2 — Core daily use | Map and timeline; visits/places/manual editing; inferred outings; country days; geocoding/search; onboarding/settings/theme; accessibility foundation | 3–4 weeks | A user records a day, corrects a visit, manages a saved place and reviews map/timeline/country totals offline |
| 3 — Portability and integrations | Eight export renderers; imports; preview; templates/folders; scheduler; webhook outbox; command layer; Play Billing/verification | 3–4 weeks | Cross-platform round trips pass; scheduled output and retry recovery verified; purchase sandbox flows and webhook modes pass |
| 4 — Complete phone parity | Photo moments and permission variants; replay/road display; automation client integration; release notes/logs/review/support; localization; eligible claim flow | 2–3 weeks | Every phone checklist item has evidence or an explicitly approved platform/product deviation |
| 5 — Release hardening | Large-history tests; battery/thermal/Doze/OEM matrix; security/network review; Play declarations/listing; internal/closed testing; rollout and recovery procedures | 2–3 weeks | Signed release candidate passes the acceptance suite and internal field beta; store submission package ready |
| 6 — Wear OS after phone launch | Independent watch recording, durable offline queue, acknowledgements/deduped phone import, snapshots, complications and battery validation | 3–5 additional weeks | Record without the phone, reconnect, import exactly once, and refresh phone/watch stats without data loss |

Phone estimate: 13–19 engineering weeks before external review delays. The first usable recording build arrives in Phase 1; that is not the full-parity release. Implement vertical workflows within phases rather than completing every repository layer before exercising the app.

## Verification and release gates

Use JVM tests for pure algorithms and format contracts, Room instrumentation tests for persistence/migrations, Compose tests for critical UI paths, and physical-device field tests for recording and power. Translate existing `IsoMeTests` cases into shared fixtures where useful. CI runs lint, JVM tests, debug assembly and a selected emulator instrumentation suite; release CI builds a signed AAB using protected signing credentials and produces checksums/mapping artifacts. Add native-library compatibility checks for the chosen map SDK and current Android page-size requirements.

Required evidence before launch:

1. Fresh install → onboarding → precise recording → screen lock → route/visit capture → stop → timeline edit → purchased export → import on iOS. Repeat with denied/approximate location and denied notifications, documenting expected degraded behavior.
2. Permission revocation, GPS off, UI dismissal, process death, reboot, force-stop and restricted battery state produce truthful recording status and no corrupted/open-session duplicates. User force-stop is a documented interruption, not a claimed recoverable recording mode.
3. Labeled field routes cover walking, driving, stationary indoors, short stops and overnight stays. Phase 0 establishes measurable visit precision/recall, arrival/departure delay and battery budgets; Phase 5 must meet them on at least Pixel and Samsung devices, plus an aggressive-power-management OEM if supported in launch regions.
4. Midnight/DST/time-zone travel and non-divisor schedules such as 9 h and 23 h produce correct day files and no duplicate successful occurrence. Revoked folder access, low storage and interruption preserve success bookkeeping and retry safely.
5. Webhook offline/retry/429/server-error flows preserve the queue, mask credentials and stop sending after disable/clear-data. Duplicate delivery is tested against supported receiver behavior.
6. One-million-point synthetic history remains bounded in map memory; export streams without dropping records; imports are cancellable and deduplicate according to the compatibility contract. Establish device-specific latency and memory budgets in Phase 1.
7. Country totals cover borders, missing resolution, DST, open visits, inferred departures, manual overlap and outliers; user corrections survive geocoder updates. Photos behave correctly with selected/full/no access and missing/revoked assets.
8. Billing covers pending/completed/canceled, restored and refunded purchases; every paid entry point follows the chosen policy. Free tracking remains available with no purchase or network connection.
9. TalkBack, large fonts, contrast, dark/light/system appearance, right-to-left layout and translated strings pass key workflows. Convert string catalogs with format/plural review rather than blindly copying Swift placeholders.
10. Play submission includes the background-location and foreground-service declarations, photo-access justification if requested, Data Safety, privacy policy, review demonstration and current target/API/Billing requirements. Prepare internal testing, any account-specific closed-test requirements, staged rollout, crash/ANR review and halt/forward-fix instructions. Database migrations must remain compatible with the supported recovery strategy.

## Decisions to settle in Phase 0

- Confirm proposed native Kotlin/Compose, API 29 minimum, Google Play distribution and Google-services dependency.
- Select and budget map, place, routing and geographic time-zone providers; settle their data disclosures and license obligations.
- Confirm whether passive visits remain active independently of route tracking and accept the validated background/power behavior.
- Individual lifetime launch is approved; decide the price, iOS-owner eligibility and myself.md claim policy.
- Analytics is approved as explicit opt-in, default off; finish production receiver/privacy acceptance. Automate is the selected integration client; finish its installed-client walkthrough when a device is available.
- Schedule catch-up matches iOS as approved above; decide the fallback if whole-library photo access is denied.
- Name physical devices, field-test owners and measurable tracking/battery acceptance targets.

Wear OS remains deferred. Preserve stable UUIDs and a versioned sync payload now so adding a watch later does not require changing phone record identity. Use a durable watch outbox and application-level acknowledgements; immediate messages alone are not durable transport. The Android phone and watch can use the Wear Data Layer, with independent local persistence and explicit overlapping-recording semantics. [Wear OS data synchronization](https://developer.android.com/training/wearables/data/sync)
