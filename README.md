# Whereday

> **Open source, privacy-first iOS location tracking — your data stays on your device.**

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-iOS%2017%2B%20%7C%20watchOS%2010%2B-lightgrey)](#tech-stack)
[![Swift](https://img.shields.io/badge/swift-5-orange)](#tech-stack)

Whereday is a location tracking app for iOS and watchOS. It automatically records the places you visit throughout the day, tracks routes with continuous GPS logging, and **keeps every byte of your data on-device**. No accounts. No cloud sync. No third-party dependencies. No analytics. Just you, your phone, and your history.

**[🌐 whereday.com](https://whereday.com)** · **[📲 Download on the App Store](https://apps.apple.com/app/id6761960794)** · **[🛠 Contribute](CONTRIBUTING.md)** · **[💬 Discussions](https://github.com/CodyBontecou/whereday/discussions)** · **[👥 Discord](https://discord.gg/jNRWSSSz4N)** · **[⭐ Star this repo](https://github.com/CodyBontecou/whereday)**

## Screenshots

| Capture every visit | Keep it on-device | Export your history |
|---|---|---|
| ![Map view with visit pins](screenshots/final/02-capture-visits.jpg) | ![Settings showing on-device tracking](screenshots/final/01-keep-on-device.jpg) | ![Export format picker — JSON, CSV, Markdown](screenshots/final/04-export-history.jpg) |

## Features

### Visit Detection
Automatic background detection of places you arrive at and depart from, powered by `CLLocationManager` visit monitoring. Each visit is reverse-geocoded to show a human-readable address and location name.

### Continuous Tracking
High-accuracy GPS tracking that records your exact path. Configurable distance filter (5m-200m) and auto-off timer (30 min to never). Tracked points include altitude, speed, and accuracy metadata.

### Dark Mode
Follow the device appearance or choose Light or Dark in **Settings → Appearance → Theme**. The shared palette adapts across the map, timeline, settings, export, and onboarding screens.

### Country Days
The free **Timeline → Country Days** screen counts unique local dates present in each country from recorded visits and route points. Filter by this year, the last 365 days, or all time; open a country to review individual dates. Travel days can count in both countries. Add, edit, or delete manual stays to cover missing records; overlapping dates are deduplicated.

New recordings identify countries using the existing network-geocoding preference. **Analyze saved locations** resolves earlier history in resumable batches and respects network opt-out. Dates use the resolved location's time zone. Unknown locations, outliers, and gaps are not assigned invented days; open visits and unverified departure times contribute only the arrival day. Existing history with unverified departures may need manual stays for multi-day totals. Country-day CSV export uses the existing lifetime export purchase and includes the country code, local date, and recorded/manual source.

### Live Activities
Real-time tracking status on the lock screen and Dynamic Island. Shows current location, distance traveled, points recorded, and remaining auto-off time.

### Export & Import
Export visits and location points as JSON, CSV, or Markdown. Choose a date range, time-of-day window, and per-format field toggles, and either condense everything into a single file or split the export into **one file per day** for easier downstream slicing. Customize filenames and dated subfolders with tokens (for example, Markdown can use `{year}/{year}-{month}/Daily Track - {date}.md`). Optionally set a default export folder for one-tap saves and schedule an automatic export — once per day, or **repeated every 1–23 hours** from a start time. Automatic exports always keep one consistent dated file per local day; interval runs can either fully rewrite that day's file or append only new records. Scheduled exports use a routing-only APNs worker to wake the app near each occurrence; your location records, files, folder paths, and filename templates stay on-device. Import previously exported data back into the app. Export is the one paid feature — see [Pricing](#pricing).

Exports drop in cleanly to the [Whereday Maps Obsidian plugin](https://github.com/CodyBontecou/obsidian-iso-me-maps), which renders visits, routes, heatmaps, and outliers as Leaflet maps inline in your notes — point a code block at a single condensed export, a folder of per-day files, or a filename glob.

### Watch App
Companion watchOS app showing today's visit count, distance traveled, and tracking status. Syncs with the main app via shared App Groups.

### Widgets
iOS lock screen widget via Live Activities and watchOS complications for quick tracking status.

## Pricing

Tracking is free and unlimited — visits, continuous routes, watch app, Live Activities, widgets. No subscription, no ads, no usage caps.

The one paid feature is **export**, offered as lifetime purchases with no recurring charge. These are US prices; regional prices reflect purchasing power:

- **Individual Lifetime — $19.99** for one Apple ID
- **Family Lifetime — $39.99** with Apple Family Sharing for up to five family members
- **Family Upgrade — $19.99** for Individual Lifetime owners who later want Family Sharing

Everyone who bought the original `com.bontecou.isome.lifetime` unlock is grandfathered into Family Lifetime automatically. The split is on purpose: tracking is the commodity, but a clean, lossless export of your full history is the part that's worth paying for once. It also keeps the privacy promise free at the door — you never have to pay to keep your data on your device.

## Tech Stack

- **Language:** Swift 5
- **UI:** SwiftUI
- **Data:** SwiftData
- **Minimum iOS:** 17.0
- **Minimum watchOS:** 10.0
- **Dependencies:** None (all native Apple frameworks)

### Frameworks Used

| Framework | Purpose |
|-----------|---------|
| CoreLocation | Visit monitoring, GPS tracking, geocoding |
| CoreMotion | Activity detection (driving, walking, cycling) |
| SwiftData | On-device persistence for visits and location points |
| ActivityKit | Live Activities on lock screen and Dynamic Island |
| WidgetKit | Home screen and watch widgets |
| StoreKit 2 | In-app purchase (lifetime unlock) |
| MapKit | Map visualization and route display |
| Combine | Reactive state management |

## Project Structure

```
IsoMe/
  Models/
    Visit.swift                    # Visit data model (coordinates, times, address)
    LocationPoint.swift            # Continuous tracking point model
    LocationActivityAttributes.swift  # Live Activity state
  Services/
    LocationManager.swift          # Core location tracking logic
    ActivityDetectionManager.swift # CoreMotion activity detection
    LiveActivityManager.swift      # Live Activity lifecycle
    GeocodingService.swift         # Reverse geocoding with caching
    StoreManager.swift             # In-app purchase management
    LogManager.swift               # Crash and debug logging
  ViewModels/
    LocationViewModel.swift        # Data coordination between UI and services
  Views/
    ContentView.swift              # Root view with onboarding and tab navigation
    TrackingView.swift             # Tracking controls
    MapView.swift                  # Map with visit pins
    SessionPathMapView.swift       # Route path visualization
    SettingsView.swift             # Preferences, export, import, data management
    VisitDetailView.swift          # Individual visit details
    PaywallView.swift              # Pro upgrade screen
    LogViewerView.swift            # Debug log viewer
  Utilities/
    ExportService.swift            # JSON/CSV/Markdown export
    ImportService.swift            # Data import parsing
    ExportFolderManager.swift      # Default export folder management
    DistanceFormatter.swift        # Metric/imperial formatting
    TEDesign.swift                 # Design tokens and typography
  IsoMeApp.swift                   # App entry point
  AppDelegate.swift                # Background launch handling

IsoMeWidget/                       # iOS widget and Live Activity extension
IsoMeWatch/                        # watchOS companion app
IsoMeWatchWidget/                  # watchOS widget extension
Shared/                            # Shared code (App Group data sync)
```

## Build Targets

| Target | Bundle ID | Platform |
|--------|-----------|----------|
| IsoMe | `com.bontecou.isome` | iOS |
| IsoMeWidgetExtension | `com.bontecou.isome.Widget` | iOS |
| IsoMeWatch | `com.bontecou.isome.watchkitapp` | watchOS |
| IsoMeWatchWidgetExtension | `com.bontecou.isome.watchkitapp.Widget` | watchOS |

## Setup

1. Open `IsoMe.xcodeproj` in Xcode 15+
2. Select the **IsoMe** scheme
3. Set your development team for all four targets
4. Configure the App Group entitlement (`group.com.bontecou.isome`) for your team
5. Build and run on a physical device (location features require real hardware)

## Testing

Run the app unit tests with:

```bash
xcodebuild test -project IsoMe.xcodeproj -scheme IsoMe -destination 'platform=iOS Simulator,name=iPhone 17'
```

Export round-trip coverage lives in `IsoMeTests/ExportRoundTripTests.swift`. It uses inline representative visit and route-point fixtures, renders JSON, CSV, Markdown, GeoJSON, GPX, OwnTracks, and Overland through `ExportService.render`, then parses each payload back into normalized records while asserting the emitted schema keys. OwnTracks and Overland are points-only protocols, so their cases verify route points and explicitly assert that visits are not emitted.

### Required Permissions

The app requests the following permissions at runtime:

- **Location (Always)** - Background visit detection and tracking
- **Location (When in Use)** - Foreground location display
- **Motion & Fitness** - Activity detection for auto-start feature

### Entitlements

- App Groups (`group.com.bontecou.isome`) - Shared data between app, widgets, and watch
- Background Modes: Location updates
- Live Activities support

## URL Scheme

The app registers the `isome://` URL scheme. Currently supports:

- `isome://stop` - Stops continuous tracking (used by Live Activity)

## Contributing

Contributions are welcome — bug reports, feature ideas, and pull requests. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full setup guide (Xcode, signing, TestFlight) and a list of [good first issues](https://github.com/CodyBontecou/whereday/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22).

If you want to chat about the project, design decisions, or what to build next, jump into [GitHub Discussions](https://github.com/CodyBontecou/whereday/discussions).

## License

Whereday is licensed under the [GNU Affero General Public License v3.0](LICENSE). The AGPL ensures that any modified version of Whereday — including ones run as a hosted service — must also be open source. This protects the privacy-first promise: nobody can take Whereday, bolt on tracking, and ship it as a closed product.
