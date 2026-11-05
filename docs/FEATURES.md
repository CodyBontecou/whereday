# Whereday Feature Baseline

> Candidate update: custom in-app analytics, collectors, queues and install identifiers have been removed. ANL-1 and analytics keys below are historical inventory only; the upgrade removes those keys. Essential location recording, scheduling and purchases remain.

> **Status: canonical feature inventory — generated from a full source audit (all Swift/TS sources, 4 app targets + worker), Aug 2026.**
> **Purpose:** single source of truth for every feature the app supports, used to structure and manage documentation. Every entry cites implementation files so docs can be traced back to code.

## Current paid-access policy

The feature-by-feature free/paid labels below are the August 2026 baseline. They do not define the subscription-enabled release. New users need an eligible store-backed trial, weekly/yearly subscription, or Lifetime purchase before recording visits/routes or manual check-ins. Existing free users retain tracking and their previous export purchase requirement. New-cohort users retain viewing/export of existing records after expiration. Family Sharing shares access, not journals. See [paid tracking rollout](paid-tracking-rollout.md) for current product IDs and migration rules. Lifetime prices remain on hold; no new price or release is authorized by this inventory.

**Conventions**
- Feature IDs are stable (`DOMAIN-NUMBER`). Reference them from docs, release notes, and issues.
- "Paid gate" = requires any lifetime export entitlement (Individual, Family, or Family Upgrade via StoreKit 2). The original `com.bontecou.isome.lifetime` product is the grandfathered Family plan. `isPurchased` is forced to a Family entitlement in DEBUG unless `--use-storekit` is passed.
- Defaults are exactly as coded. "Hidden" = surface with no Settings UI (debug args, deep links, background machinery).
- File refs are repo-relative. Line numbers drift; treat file + symbol as authoritative.

---

## 1. Platform & Target Matrix

| Target | Bundle ID | Platform | Role |
|---|---|---|---|
| IsoMe | `com.bontecou.isome` | iOS 17+ | Main app (all UI, tracking engine, export) |
| IsoMeWidgetExtension | `com.bontecou.isome.Widget` | iOS | Live Activity only (no home-screen widget) |
| IsoMeWatch | `com.bontecou.isome.watchkitapp` | watchOS 10+ | Independent watch tracking app (`WKRunsIndependentlyOfCompanionApp=true`) |
| IsoMeWatchWidgetExtension | `com.bontecou.isome.watchkitapp.Widget` | watchOS | Watch complications (4 accessory families) |
| worker/scheduled-notifications | `isome-scheduled-notifications.costream.workers.dev` | Cloudflare | APNs scheduler for scheduled exports (routing-only) |

**External dependencies** (contradicts README "Dependencies: None"):
- `ExportKit` + `ExportAutomationKit` (github.com/CodyBontecou/ExportKit) — export engine: file planning, path safety, writing, merging, preview, automation scheduling.
- `Notelet` (github.com/mykolaharmash/notelet) — in-app release-notes sheet UI (iOS target only).

---

## 2. Feature Catalog

### A. Tracking & Data Capture (TRK)

**TRK-1 Continuous GPS tracking** — free · iOS
- Start attaches **all three** CoreLocation APIs: `startMonitoringVisits()` + `startMonitoringSignificantLocationChanges()` + `startUpdatingLocation()`; `kCLLocationAccuracyBest`, background updates allowed, auto-pause disabled (`LocationManager.swift`).
- Persists across launches (`isTrackingEnabled`); auto-resumes on relaunch if permission held; re-attaches when permission arrives mid-session.
- Stop ends the active outing, ends Live Activity, syncs watch snapshot.
- Options: distance filter **5/10/25/50/100/200 m (default 5)**; auto-stop **Never/1h/2h/4h/8h/12h (default Never)** — safety-net timer stops tracking at deadline.
- Background launch from location events supported (`AppDelegate`, `UIBackgroundModes: location`).

**TRK-2 Visit detection (CLVisit)** — free · iOS
- Arrival closes older open visits and creates a `Visit`; departure matched to an open visit by scored distance/arrival-time (match window **150 m / 15 min**, checked against current + detected + original coordinates).
- Duplicate merging: visits within **100 m** and (arrival ≤15 min apart or gap ≤30 min) are merged (earliest arrival, latest departure, notes concatenated).
- Startup reconciliation keeps only the newest open visit; stale ones get inferred departures (next arrival or now).

**TRK-3 GPS point capture + outlier detection** — free · iOS
- Only fixes with `0 ≤ horizontalAccuracy ≤ 100 m` are saved (lat/lon/time/altitude/speed/accuracy/outlier flag).
- Outlier heuristics: **implied speed > 40 m/s** (teleport) and **out-and-back spike** (prev→last >100 m, last→new >100 m, prev→new <30 m — retroactively flags the middle point).

**TRK-4 Daily distance stats** — free · iOS + shared
- Every delivered location (even when not tracking) accumulates distance per calendar day; moves <200 m ignored, >100 km treated as glitch; 7-day rolling window (`DailyDistanceTracker`). Feeds widgets/watch/Live Activity. (`isAboveAverage()` is dead code from a removed auto-start feature.)

**TRK-5 Recording sessions ("exact" outings)** — free · iOS
- A `RecordingSession` is created on tracking start (ID persisted in `activeRecordingSessionID`) and closed on stop; reconciliation repairs stale/duplicate open sessions on launch.
- User-editable name + notes; deletion removes the session and its time-window points (keeps visits; stops tracking first if active).

**TRK-6 Inferred outings** — free · iOS
- Point-only history (legacy/watch-imported) is split into outings on quiet gaps. Config (Settings → INFERRED OUTINGS): include toggle (**default on**), split-after gap **15m/30m/1h/2h/4h (default 30m)**, minimum duration **none/5m/15m/30m/1h (default none)**, minimum points **1/2/3/5/10 (default 1)**. The chunk containing the live tracking start stays "active".

**TRK-7 Map point downsampling** — free · iOS
- Map renders ≤2,500 points (photo moments ≤500); ranges >10,000 raw points are fetched in 500-point batches then uniformly downsampled; full history stays lazy until export. Live batches append incrementally. (`LocationViewModel`)

### B. Places & Visit Editing (VIS)

**VIS-1 Reverse geocoding** — free · iOS · network (Apple CLGeocoder, toggleable)
- Visits geocoded to name + address (placename → area-of-interest → neighborhood fallback); in-memory cache (~11 m precision), request coalescing, 0.5 s rate limit. Gated by `allowNetworkGeocoding` (**default on**). Failures mark complete (no retry storm); manual retry exists.
- Live Activity geocoding throttled to 50 m of movement.

**VIS-2 Saved places** — free · iOS
- `SavedPlace` (name/coords/address/radius, **default 150 m**, min 25 m). Creating a place with an identical name (case-insensitive) within radius updates instead of duplicating.
- Auto-stamp: new visits inside a saved place's radius get its name/address, marked confirmed (`placeSource = userEntered`, distance recorded).
- Settings → Saved Locations: searchable library with standalone Add/Edit/Delete, name, optional address, map pin or direct coordinates, and match radius (25–10,000 m). Edits and deletion keep historical visits unchanged.
- Confirming or correcting a visit remembers a new place at 150 m; an existing matching place retains its managed pin, address, and radius.
- Saved-location CSV import previews valid/invalid rows and new/duplicate locations. Required columns: `name,latitude,longitude`; optional `address,radius_meters` (default 150). Supports quoted/multiline UTF-8 CSV and BOM, up to 5 MB / 10,000 rows. Invalid rows are skipped; users explicitly choose Keep existing or Update matching locations. Matching uses the same case-insensitive name within either radius, choosing the nearest match deterministically.

**VIS-3 Visit confirmation & correction** — free · iOS
- Status machine: unconfirmed → confirmed / corrected, with provenance (`VisitSource` automatic/manual/imported; `VisitPlaceSource` coreLocationGeocode/appleMaps/userEntered/import).
- Correcting preserves originals (lat/lon/name/address) for **Undo Correction**; automatic geocoding never overwrites user-touched visits.
- Editing: custom name (reset to detected), address, notes (autosave), arrived/departed times + "still here" toggle with validation.
- Open in Maps: iOS 18.4+ tries the system default navigation app with a location URL, falling back to Apple Maps if unavailable; earlier iOS uses Apple Maps. System default navigation selection is region-dependent.

**VIS-4 Manual visit entry** — free · iOS
- Map "+" menu: Save current place / Add past visit / Add place manually. Sheet offers: name, address/note, saved-location picker, arrival/departure (past-only, validated), "still here", location via Apple Maps search or map pin or current location, optional "save as reusable location" with radius picker 50/100/150/250 m. Past-visit mode never falls back to current location.

**VIS-5 Place search & nearby suggestions** — free · iOS · network (Apple Maps)
- `PlaceSearchService`: MKLocalSearch (addresses + POI), ≥2 chars, 300 ms debounce, stale-result guard, region bias.
- `NearbyPlaceSearchService`: business-POI categories within 1 km (fallback: any POI, then natural-language queries) for visit-name correction suggestions; filtered, deduped, distance-sorted.

**VIS-6 Current-place suggestion card** — free · iOS · off by default
- When `showVisitSuggestions` on and a fresh fix exists (≤5 min old, ≤100 m accuracy): suggests nearest saved place (within radius) or top Apple Maps nearby hit; Save / Not here / dismiss (suppressed per-place + 500 m radius).

### C. Photos (PHT)

**PHT-1 Photo moments correlation** — free · iOS · permission: Photos (read/limited)
- Fetches image assets by capture-date range; resolves coordinates by priority: photo GPS → nearest route point (±15 min) → containing visit (±15 min grace); skipped if none. Stored as `PhotoMoment` (asset local ID, coordinate source, lastSyncedAt). Missing assets cleaned up (full-access only).
- Auto-sync opt-in (`automaticPhotoSyncEnabled`, **default off**), 10-min cooldown, re-syncs on library-change notification and app foreground.

**PHT-2 Photo UI** — free · iOS
- Map photo markers (opt-in `showPhotoMarkers`, default off; thumbnails toggleable), 35 m clustering with stacked previews and "99+" badges; outing detail photo strips; quick-view sheets; full-screen pager with swipe.

### D. Map & Timeline UI (MAP / TIM)

**MAP-1 Map view** — free · iOS
- MapKit map: travel-path polyline with **road snapping** (MKDirections on sparse gaps; 120 m–25 km segments, ≤2 h gaps, ≤55 m/s implied speed, ≤40 requests/build, walking-aware), start/end flags, point markers (≥50 m spacing) with tooltips, visit pins + quick-view sheet, photo markers, session path toggle, user-location controls.
- Layer quick-filter bar (presets + toggles: travel path, points, start/end, session path, visits, photos, outliers, road snap, straight-line segments) with long-press help.
- Date filtering: Today (default)/Yesterday/7D/30D/All + custom range sheet; fit-to-content menu.

**MAP-2 Route replay** — free · iOS
- Playhead scrubber replaying the day's path (or a single outing) with elapsed/duration/distance/point counters; ~180 ticks, 0.25 s cadence.

**MAP-3 Tracking control pill** — free · iOS
- Floating status pill: pulsing state, live duration, expandable stats (distance + point count), play/stop; auto-off countdown pill when timer armed.

**TIM-1 Timeline (Outings) view** — free · iOS
- Day-scoped timeline merging visits + outings (exact & inferred), day overview card (moves/visits/distance), prev/next/jump-to-latest navigation, empty states.
- Outing detail: path mini-map (road-snapped), stats (duration/distance/points/glitches/avg speed), editable name+notes (exact only; inferred get explainer), linked visits, photo strip, **Show on Map**, **Export** (paid), **Delete**.

### E. Export & Portability (EXP)

**EXP-1 Formats (8)** — paid (export action) · preview free
- JSON, CSV, Markdown, **GeoJSON (RFC 7946)**, **GPX 1.1** (custom isome namespace; track segments split at 600 s gaps), **KML 2.2** (gx:Track + ExtendedData), **OwnTracks** (points-only; vel in km/h, tid `IM`), **Overland** (points-only; GeoJSON-ish `locations` payload).
- Combined visits+points variants for all; OwnTracks/Overland visits requests silently degrade to visits JSON.
- Outings export: per-outing files; Markdown single-outing = YAML front-matter page; inferred outings tagged `source: inferred`.

**EXP-2 Export options** — paid
- Data kind: visits/points/outings/all (default visits; outings forces split-by-day-per-outing).
- Date presets: All (default)/Today/Yesterday/7D/30D/This month/Custom; time-of-day window (off by default, 09:00–17:00 defaults, midnight wraparound).
- Filters: only completed visits (off), min visit duration (none/5/15/30/60 m, default none), exclude outliers (off), accuracy cap (any/≤10/25/50/100 m, default any).
- Field toggles: visit name/address/duration/coords/notes (all on); point altitude/speed/accuracy/outlier (all on).
- All options + selected formats persisted as `exportView.preferences.v1` snapshot.

**EXP-3 Filename templates** — paid
- 14 tokens: `{date} {year} {month} {dayNumber} {weekday} {monthName} {quarter} {datetime} {time} {day} {type} {title} {name} {format}`; `/` builds subfolders; extension auto-added; sanitization + path-traversal rejection.
- Presets: READABLE `Whereday - {day} {date} - {type}` (default), COMPACT, DATED `{year}/{year}-{month}/Daily Track - {date}`.
- Day-stable normalization for scheduled exports guarantees one filename per local day.

**EXP-4 Output modes** — paid
- Single file vs **one file per day** (default single); outings = one file per outing. Collision suffixing (`_2`, format-token suffixes).

**EXP-5 Export destinations** — paid
- Default folder via document picker + security-scoped bookmark (auto-save default ON) → silent save + toast; else share sheet. Free **preview** sheet (per-file contents, truncation notices).
- Per-outing export from outing detail (format picker, remembered).

**EXP-6 Import** — free
- JSON / CSV / Markdown only (full RFC-4180 CSV parser; Markdown table + legacy list formats; per-row errors). KML/GeoJSON detected but rejected with "one-way map files" message; GPX/OwnTracks/Overland not importable.

### F. Automation & Integration (SCH / WHK / INT)

**SCH-1 Scheduled exports ("AUTO EXPORT")** — paid (section hidden until purchase + folder set) · iOS + worker
- Daily at chosen time (default **21:00**) or **every 1–23 h**; exports current local day to the default folder; one day-stable file per day.
- Update mode (interval only): REWRITE (default, full day snapshot) or APPEND (delta since last success via cursor; merge strategies: CSV row-append, JSON array merge with identity dedup, Markdown section merge; **GPX/KML always rewrite**).
- Reliability layering: BGAppRefreshTask (`com.bontecou.isome.dailyexport`) + APNs silent push at fire time + local fallback notification at +60 s + foreground catch-up on launch/activate; tap-to-retry notifications; failure notifications with reason. RUN NOW button.

**SCH-2 Push registration & remote schedule sync** — free · iOS + worker
- Stable install UUID in Keychain; APNs token + schedule (timezone, hour/minute[/interval]) synced to worker `/devices/register` + `/schedules/upsert` (3× retry); disable sync deletes server row (after first successful sync).

**SCH-3 Cloudflare scheduled-notifications worker** — infrastructure
- D1-backed; every-minute cron; silent pushes `{"aps":{"content-available":1},"type":"scheduled-export","fireAt":…}` (routing-only — no location data); ES256 JWT APNs auth; dead-token cleanup. Weekly frequency and macOS platform accepted but unused by the app. **No API authentication** (userId opacity + bundleId pinning only).

**WHK-1 Webhook delivery** — paid · opt-in, off by default
- POSTs location data to user endpoint in any of the 8 export formats (Dawarich/OwnTracks Recorder/Traccar compatible).
- Auth: none / API-key-query / Bearer / Basic / custom header — credentials in **Keychain** (legacy plaintext migrated+deleted).
- Send modes: real-time (default; arming cursor prevents history resend) / batch-by-count (5–100, default 10) / batch-by-time (1–60 min, default 5) / manual (**Send Now** = all data). Outliers filtered unless `showOutliers`.
- 3× exponential backoff (URLError, 408/429/5xx only); test connection; queued-count + flush UI; error text credential-masked; prominent "data leaves your device" warning.

**INT-1 App Intents & Siri shortcuts** — free (⚠ export intents are **not** purchase-gated — see Drift D-6)
- 9 intents, all background-runnable: Start/Stop Tracking, Rename Current Outing, Today's Distance / Tracking Duration / Visit Count, Export Today's Data / Outings / Yesterday's Data (format parameter, all 8 formats, returns IntentFile).
- 9 App Shortcuts with Siri phrases (`IsoMeAppShortcuts`); no donations.
- Stop-without-app-runs path closes persisted sessions + flips the tracking flag.

### G. watchOS (WCH)

**WCH-1 Independent watch tracking** — free · watchOS
- Watch runs its own CLLocationManager (`activityType .fitness`, 10 m filter, background allowed, whenInUse permission): START/STOP button, live status card (elapsed timer, remaining auto-off time), TODAY stats (visits/distance/points), coordinate readout, error card, RESET TODAY.
- Heuristics: accuracy ≤100 m gate, ≥10 m movement for distance, "visit" proxy = ≥100 m displacement counter; local-day rollover.

**WCH-2 Watch → iPhone sync** — free · watchOS + iOS
- Durable offline queue (App Group `state.json`); batches ≤500 points via `transferUserInfo` + live `sendMessageData` when reachable; transfer every 25 points / session bounds / reachability (throttled 60 s); versioned payload + acks; idempotent import on iPhone (UUID-deduped points, time-range-widened sessions/visits); imported visits geocode later on phone.

**WCH-3 Snapshot bridge** — free · shared
- `SharedLocationData` App Group snapshot (tracking flag, current place name/address, last coords, today counts/distance, tracking start, auto-stop hours, unit pref) powers watch app + all widgets; no staleness check.

### H. Widgets & Live Activities (WID)

**WID-1 Live Activity (iOS)** — free · default on
- Lock Screen: place name, distance, elapsed timer or auto-off countdown, **Stop button (`isome://stop` deep link)**, 160×140 live **map snapshot** (dark-mode MKMapSnapshotter with path polyline + position dot, regenerated every 5th point, App Group PNG).
- Dynamic Island: compact (icon + point count), expanded (name, points, distance, since-time, countdown), minimal.
- No staleDate; no push type.

**WID-2 Watch complications** — free · watchOS
- 4 families (circular = status + visit count; rectangular = status/place/visits/distance/auto-off-left; inline = one-line summary; corner = icon + count). Timeline: 5 min while tracking, 15 min idle; authoritative refresh on every data sync.

**WID-3 (absent) iOS home-screen widget** — none exists; the iOS widget extension is Live-Activity-only.

### I. Onboarding, Settings & Shell (ONB / SET)

**ONB-1 Onboarding wizard** — free · 5 pages
- Welcome → Core features → Permission setup (When-In-Use then Always, denied-recovery to Settings) → Connect Photos (optional auto-sync) → All set (optional "start tracking immediately"). Completing sets `hasCompletedOnboarding`; existing users auto-migrated past it. Replay available in Settings.

**SET-1 Settings** — free unless noted
- Tracking: master toggle, permission status + open-settings, distance filter, stop-after, location names (network geocoding toggle).
- Inferred outings: include/gap/min-duration/min-points (see TRK-6).
- Live Activity toggle (default on). Units: metric/US (default metric; watch derives from locale). Map display: visit suggestions (off), show GPS glitches (off).
- Data: counts, **Clear All Data** (no range deletion UI), Import file (JSON/CSV/MD; see EXP-6).
- Purchase section: status/unlock/restore (paid). Support: logs, Discord. About: version, on-device storage note, feedback email (mailto with diagnostics footer), privacy/terms links.

### J. Platform & Business (PLT / MON / ANL)

**PLT-1 Crash capture & recovery** — free: uncaught-exception handler persists crash log to UserDefaults; next launch shows "Previous Crash Detected" alert with copy-to-clipboard.

**PLT-2 Review prompt** — free: `requestReview` after ≥2 distinct use days + a successful file export, once ever, 1 s delayed.

**PLT-3 In-app release notes** — free: Notelet-powered "What's new" sheet, versions 1.5.0→1.7.4, video + list items.

**PLT-4 Log viewer** — free: 500-entry in-memory ring buffer (INFO/WARN/ERROR, mirrored to OSLog) with copy/clear UI.

**PLT-5 URL scheme** — `isome://stop` only (any other host ignored).

**MON-1 Lifetime export plans** — Individual Lifetime $10 (`com.bontecou.isome.lifetime.individual`), Family Lifetime $20 (`com.bontecou.isome.lifetime`), and a $10 Individual → Family upgrade (`com.bontecou.isome.lifetime.family.upgrade`). Family products use Apple Family Sharing. Owners of the original lifetime product are grandfathered as Family. Entitlements remain Apple server-side, survive reinstall, reconcile refunds/revocations across all products, and support restore. Paywall contexts: export/settings/webhook. Debug builds default to Family; pass `--use-storekit` for the local StoreKit catalog.

**ANL-1 Onboarding/purchase analytics** — default-on in release (DEBUG requires env opt-in); whitelisted funnel events + coarse properties only (no location/addresses/user text); 50-event offline queue; persistent pseudonymous install UUID; ships to Cloudflare worker `iso-me-onboarding-analytics.costream.workers.dev` (endpoint/token overridable via env).

---

## 3. Persisted Settings Inventory (complete)

| Key | Type | Default | Owner |
|---|---|---|---|
| `hasCompletedOnboarding` | Bool | false | Onboarding |
| `isTrackingEnabled` | Bool | false | Tracking |
| `stopAfterHours` | Double | 0 | Auto-stop |
| `distanceFilter` | Double | 5.0 | Point cadence |
| `isLiveActivityEnabled` | Bool | true | Live Activity |
| `allowNetworkGeocoding` | Bool | true | Visit geocoding |
| `activeRecordingSessionID` | UUID-string | — | Active outing |
| `usesMetricDistanceUnits` | Bool | true | Units |
| `inferredOutings.includeInferredSessions` | Bool | true | Inferred outings |
| `inferredOutings.gapPreset` | String | thirtyMinutes | Inferred outings |
| `inferredOutings.minimumDurationPreset` | String | none | Inferred outings |
| `inferredOutings.minimumPointCount` | Int | 1 | Inferred outings |
| `automaticPhotoSyncEnabled` | Bool | false | Photo sync |
| `showPhotoMarkers` | Bool | false | Map photos |
| `showPhotoMarkerImages` | Bool | true | Map photos |
| `snapTravelPathToRoads` | Bool | true | Road snapping |
| `showStraightLinePathSegments` | Bool | false | Map |
| `showOutliers` | Bool | false | Map/webhooks |
| `showVisitSuggestions` | Bool | false | Place card |
| `discordPromoDismissed` | Bool | false | Promo banner |
| `exportView.preferences.v1` | Data (JSON) | defaults + [.json] | Export options |
| `exportFilenamePattern` | String | readable preset | Filenames |
| `useDefaultExportFolder` | Bool | true | Export save |
| `defaultExportFolderBookmark` | Data | — | Export folder |
| `outingDetailExportFormat` | String | markdown | Outing export |
| `dailyExport.enabled` | Bool | false | Scheduled export |
| `dailyExport.hour` / `.minute` | Int | 21 / 0 | Scheduled export |
| `dailyExport.format` / `.dataKind` | String | json / all | Scheduled export |
| `dailyExport.intervalHours` | Int | 0 (once daily) | Scheduled export |
| `dailyExport.fileMode` | String | rewrite | Scheduled export |
| `dailyExport.appendCursorDate` | Date | nil | Append mode |
| `dailyExport.lastRunAt` / `.lastError` | Date/String | nil | Scheduler |
| `dailyExport.pendingNotificationIdentifier` | String | nil | Fallback notif |
| `dailyExport.remoteScheduleHasSynced` | Bool | false | Worker sync |
| `webhook.enabled/.url/.format/.authType/.sendMode/.batchCount/.batchTimeMinutes/.lastSentAt/.lastError` | mixed | off/""/json/none/realtime/10/5/nil/nil | Webhooks |
| `webhook.privacyWarningDismissed` | Bool | false | Webhooks |
| `onboarding.analytics.queue.v1` | Data | — | Analytics queue |
| `onboarding.analytics.install_id.v1` | UUID | generated | Analytics ID |
| `reviewPrompt.usedDayIDs/.completedFileExport/.requestedSecondDayExportReview` | mixed | []/false/false | Review prompt |
| `lastCrashLog` | String | — (deleted after show) | Crash capture |
| `dailyDistanceHistory` / `distanceTrackerLastCheckpoint` | dict/array | — | Daily distance |
| **Keychain**: `webhook.authKey/.authValue/.authUsername`, `pushRegistrationUserId` | String | — | Credentials/installs |
| **App Group** (`group.com.bontecou.isome`): `sharedLocationData`, `watchLocationTrackingState`, file `WatchLocationSync/state.json`, file `map_snapshot.png` | — | — | Watch/widget bridge |

## 4. Permissions & Capabilities

- **Location** Always+WhenInUse (iOS), WhenInUse (watch) — background updates, visit monitoring, SLC.
- **Photos** read/limited (opt-in photo moments).
- **Notifications** — alert/sound requested only when enabling scheduled exports; silent pushes registered regardless.
- **Background modes**: location, fetch, remote-notification; BGTask IDs `refresh` (unused by code — legacy), `dailyexport`.
- **App Group** `group.com.bontecou.isome` — all targets.
- **Network egress**: Apple CLGeocoder/MKLocalSearch/MKDirections/MKMapSnapshotter (toggleable only for geocoding), user webhooks, Cloudflare analytics + APNs-scheduler workers.
- **Keychain** — webhook credentials + push install ID.
- No motion usage description despite README mentioning CoreMotion activity detection (feature removed).

## 5. Paid-Gate Matrix

| Surface | Gated | Where |
|---|---|---|
| Export tab action (all 8 formats) | ✅ | `ExportView.runExport` |
| Export preview | ❌ free | explicit |
| Outing-detail export | ✅ | `OutingsView:1595` |
| Scheduled export UI | ✅ (section hidden) | `ExportView` (`hasDefaultFolder && isPurchased`) |
| Webhook settings | ✅ | `WebhookSettingsView:31,55` |
| App Intent exports | ⚠️ **NOT gated** | `IsoMeIntents.ExportRunner` |
| Import, tracking, watch, widgets, everything else | ❌ free | — |

## 6. Hidden / Debug Surfaces

- Launch args (DEBUG): `--seed-screenshot-data` (demo data + fake suggestions + skips release notes), `--demo-open-map-filters`, `--demo-road-layer-help` (opens with the Layers sheet's road-matching help), `--demo-export-filters`, `--demo-webhook-settings`, `--default-tab=<0-6>` (0 Map, 1 Archive, 2 Settings behind Archive, 3 timeline over the map, 4 map with the day sheet open, 5 Atlas, 6 Places; see `MainTabLaunch`), `--onboarding-page=<0-4>`.
- Env: `ONBOARDING_ANALYTICS_ENABLED/ENDPOINT_URL/INGEST_TOKEN`, `UITEST_ANALYTICS_TRANSPORT=offline`; worker `APNS_HOST` sandbox.
- Deep link: `isome://stop`. App Intents (see INT-1). Live Activity stop button.

## 7. Documentation Drift Register (README vs code)

| # | README claim | Reality | Action |
|---|---|---|---|
| D-1 | "No analytics" | ANL-1 default-on in release, install UUID + funnel events to Cloudflare | Update README/privacy docs |
| D-2 | "No third-party dependencies" | ExportKit + Notelet SPM packages | Update README |
| D-3 | "No cloud sync" | APNs scheduler + analytics workers exist (routing-only, no location data) | Qualify claim |
| D-4 | "Auto-off timer 30 min to never" | Never/1h/2h/4h/8h/12h | Fix README |
| D-5 | Historical release docs describe the former single-product price | All formats, webhook delivery, and scheduled exports use $10 Individual / $20 Family lifetime plans | Preserve release history; current copy updated |
| D-6 | — | Export **intents** bypass the paywall | Product decision: gate or accept |
| D-7 | Frameworks table lists CoreMotion activity detection | No CoreMotion code remains | Remove |
| D-8 | README watch description ("syncs via App Groups") | Watch records independently + WCSession route sync pipeline | Expand |
| D-9 | Discord link differs from in-app URL | `discord.gg/RaQYS4t6gn` (code) vs `jNRWSSSz4N` (README) | Unify |
| D-10 | — | Photos, saved places, inferred outings, route replay, road snapping, webhooks, Shortcuts, import, review prompt, crash capture all undocumented | Add docs |

## 8. Known Gaps / Dead Code (documentation-relevant)

- `DailyDistanceTracker.isAboveAverage()` unused (removed auto-start feature).
- Watch `WatchLocationSyncVisit` type never populated (forward-looking schema).
- Worker `weekly` frequency + `macos` platform accepted but unused.
- `ExportKit` bookmark store, plugin system, portable snapshots, most of ExportAutomationKit unconsumed by the app.
- BGTask ID `com.bontecou.isome.refresh` registered but no handler.
- `DateRangeChip`, `RecordingSessionCard`, `OnboardingSignalColumn` views unreferenced.
- No unit tests for any View layer; watch target untested.
