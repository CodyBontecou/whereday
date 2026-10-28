import SwiftUI
import Notelet

// MARK: - In-app release notes

enum IsoMeReleaseNotes {
    private static func releaseNoteVideoURL(_ resourceName: String) -> URL {
        if let url = Bundle.main.url(
            forResource: resourceName,
            withExtension: "mp4",
            subdirectory: "ReleaseNoteVideos"
        ) {
            return url
        }

        if let url = Bundle.main.url(forResource: resourceName, withExtension: "mp4") {
            return url
        }

        assertionFailure("Missing release note video resource: \(resourceName).mp4")
        return URL(fileURLWithPath: "/dev/null")
    }

    static let notes: [NoteletVersionNotes] = [
        .init(
            version: "1.7.14",
            items: [
                .list(
                    title: "Dark mode and country days",
                    rows: [
                        .init(
                            symbolSystemName: "moon",
                            title: "Choose your appearance",
                            description: "Use System, Light, or Dark in Settings."
                        ),
                        .init(
                            symbolSystemName: "globe.europe.africa",
                            title: "Days in each country",
                            description: "Open Country Days from Timeline. Review recorded local dates and add or edit manual stays without counting overlapping days twice."
                        ),
                        .init(
                            symbolSystemName: "square.and.arrow.up",
                            title: "Export your country days",
                            description: "The tracker is free. Export CSV with your existing lifetime export purchase."
                        ),
                        .init(
                            symbolSystemName: "clock.arrow.circlepath",
                            title: "Include earlier history",
                            description: "Analyze saved locations with saved progress, stopping and resuming, and clearer messages when country lookup is unavailable."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.7.13",
            items: [
                .list(
                    title: "Manage your saved places",
                    rows: [
                        .init(
                            symbolSystemName: "bookmark",
                            title: "Saved Locations in Settings",
                            description: "Add, edit, search, and delete reusable locations. Your past visits stay unchanged."
                        ),
                        .init(
                            symbolSystemName: "square.and.arrow.down",
                            title: "Review CSV imports",
                            description: "Review locations, invalid rows, and duplicates before importing. Keep existing locations or update matches."
                        ),
                        .init(
                            symbolSystemName: "mappin.and.ellipse",
                            title: "Save places missing from Maps",
                            description: "Drop a precise map pin or enter exact coordinates to save any location."
                        )
                    ]
                ),
                .list(
                    title: "Maps and visit fixes",
                    rows: [
                        .init(
                            symbolSystemName: "map",
                            title: "Use your default navigation app",
                            description: "Open in Maps uses your default navigation app where supported, with Apple Maps as a fallback."
                        ),
                        .init(
                            symbolSystemName: "trash",
                            title: "Smoother visit deletion",
                            description: "Deleting a visit now closes its details screen correctly."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.7.4",
            items: [
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("remote-past-visits"),
                    title: "Add past visits from anywhere",
                    description: "Search Apple Maps for a place or address, confirm it on the map, and save the visit without using your current GPS location."
                ),
                .list(
                    title: "Pick the right place",
                    rows: [
                        .init(
                            symbolSystemName: "map",
                            title: "Search or drop a pin",
                            description: "Find a business, landmark, or street address, or place a pin at the exact coordinate."
                        ),
                        .init(
                            symbolSystemName: "bookmark",
                            title: "Reuse saved locations",
                            description: "Choose a saved location for a past visit, or save a newly selected place so it is ready next time."
                        ),
                        .init(
                            symbolSystemName: "location.slash",
                            title: "No current location required",
                            description: "Past visits work without Location permission and never fall back to your current GPS position."
                        ),
                        .init(
                            symbolSystemName: "clock.badge.checkmark",
                            title: "Keep the visit in context",
                            description: "Set arrival and departure times. After you save, the map jumps to the visit and its date range."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.7.3",
            items: [
                .list(
                    title: "More control over movement history",
                    rows: [
                        .init(
                            symbolSystemName: "trash",
                            title: "Delete outings",
                            description: "Open any recorded or inferred outing and delete it from the details screen. Its GPS route points are removed, visits from the same time stay in your history, and deleting a live outing stops tracking first."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.7.2",
            items: [
                .list(
                    title: "More control over familiar places",
                    rows: [
                        .init(
                            symbolSystemName: "house",
                            title: "Save reusable locations",
                            description: "Save places like Home, Work, or a favorite café once, then reuse them when adding visits on future days. Nearby automatic visits can use the saved name before network geocoding."
                        ),
                        .init(
                            symbolSystemName: "square.and.arrow.up",
                            title: "Export settings stick",
                            description: "The Export tab now remembers your selected formats, data type, filters, dates, and one-file-per-day choice the next time you return."
                        ),
                        .init(
                            symbolSystemName: "pencil",
                            title: "Rename outings from Shortcuts",
                            description: "While a route is recording, use the new Rename Current Outing action in Shortcuts or Siri to name the trip without opening Whereday."
                        ),
                        .init(
                            symbolSystemName: "doc.text.magnifyingglass",
                            title: "Clearer export path previews",
                            description: "File paths using {title} or {name} now show a sensible data-type fallback for Visits, Points, and All exports when no outing title exists."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.7.1",
            items: [
                .list(
                    title: "Timeline fixes",
                    rows: [
                        .init(
                            symbolSystemName: "calendar.badge.clock",
                            title: "Find past days faster",
                            description: "When today has no timeline events, Whereday now opens the most recent day with saved visits or movement so your history is easier to review."
                        ),
                        .init(
                            symbolSystemName: "calendar",
                            title: "Date controls stay visible",
                            description: "Empty days now keep the overview and date picker on screen, with a quick jump back to your latest data."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.7",
            items: [
                .list(
                    title: "A clearer view of your days",
                    rows: [
                        .init(
                            symbolSystemName: "calendar",
                            title: "Daily timeline",
                            description: "Review visits and movement sessions together by day, with quick controls for today, yesterday, and any date you choose."
                        ),
                        .init(
                            symbolSystemName: "mappin.and.ellipse",
                            title: "Save and confirm places",
                            description: "Add current or past visits from the map, confirm detected places, or correct them with nearby place suggestions."
                        ),
                        .init(
                            symbolSystemName: "clock.badge.checkmark",
                            title: "Edit visit times",
                            description: "Adjust arrival and departure times, mark a visit as still in progress, and keep the visit history accurate."
                        ),
                        .init(
                            symbolSystemName: "square.and.arrow.up",
                            title: "Smoother exports",
                            description: "Outing exports now plan filenames more accurately, preserve visit correction details, and present sharing more reliably."
                        ),
                        .init(
                            symbolSystemName: "slider.horizontal.3",
                            title: "Map polish",
                            description: "Place prompts stay dismissed around the same area, and add-place controls step out of the way while filters are open."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.6.2",
            items: [
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("apple-watch-tracking"),
                    title: "Track from Apple Watch",
                    description: "Start location tracking from your wrist, watch the live timer, and review today’s visits, distance, points, and current location."
                ),
                .list(
                    title: "New Apple Watch app",
                    rows: [
                        .init(
                            symbolSystemName: "applewatch",
                            title: "Track from your wrist",
                            description: "Start or stop Whereday tracking directly on Apple Watch without opening your iPhone."
                        ),
                        .init(
                            symbolSystemName: "figure.walk",
                            title: "Today’s stats at a glance",
                            description: "See visits, distance, location points, current location, and remaining session time right on the watch."
                        ),
                        .init(
                            symbolSystemName: "rectangle.on.rectangle",
                            title: "Watch face complications",
                            description: "Add Whereday to supported watch faces for quick tracking status and today’s totals."
                        )
                    ]
                ),
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("multi-format-export"),
                    title: "Export multiple formats",
                    description: "Tap several export formats at once — JSON, CSV, Markdown, GPX, KML, GeoJSON, OwnTracks, and Overland — and Whereday prepares a separate file for each."
                ),
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("outings-export"),
                    title: "Export every outing",
                    description: "The Export tab can now write one file per outing, with previews that show the exact format, count, and filenames before you share or save."
                ),
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("outing-detail-export"),
                    title: "Share a single outing",
                    description: "Open any outing and export it directly in JSON, CSV, Markdown, OwnTracks, Overland, GPX, KML, or GeoJSON."
                ),
                .list(
                    title: "More control over outing exports",
                    rows: [
                        .init(
                            symbolSystemName: "doc.text.magnifyingglass",
                            title: "Markdown pages for your log",
                            description: "Markdown outing exports include YAML front matter, notes, visits, and route points so they drop cleanly into a journal or PKM system."
                        ),
                        .init(
                            symbolSystemName: "tag",
                            title: "Filenames can use outing names",
                            description: "Use {title} or {name} in export paths, and Whereday keeps one-file-per-outing exports distinct automatically."
                        ),
                        .init(
                            symbolSystemName: "point.topleft.down.curvedto.point.bottomright.up",
                            title: "Routes work in every format",
                            description: "JSON and CSV summarize each outing, while GPX, KML, GeoJSON, OwnTracks, and Overland export the route fixes."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.6.1",
            items: [
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("outings-dashboard"),
                    title: "Outings for every trip",
                    description: "Recorded sessions now live in a dedicated Outings tab, with totals, live/current status, inferred badges, and quick access to every route."
                ),
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("route-replay"),
                    title: "Replay your route",
                    description: "Open an outing to watch the path play back with a moving marker, scrubber, elapsed time, duration, and distance stats."
                ),
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("map-focus"),
                    title: "Jump from outing to map",
                    description: "Show an outing on the main map to focus the exact route, start and finish markers, and cleaned-up current visit state."
                ),
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("visit-renaming"),
                    title: "Name visits faster",
                    description: "Rename stops from nearby business suggestions, filter the list, and reset back to the detected location whenever you need."
                ),
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("inferred-rules"),
                    title: "Sort and filter outings",
                    description: "Sort your outing history and show or hide inferred historical outings so the timeline matches how you want to review trips."
                )
            ]
        ),
        .init(
            version: "1.5.0",
            items: [
                .list(
                    title: "What’s new in Whereday",
                    rows: [
                        .init(
                            symbolSystemName: "eye",
                            title: "Preview before exporting",
                            description: "Check the destination, file names, counts, warnings, and contents before writing or sharing your location history."
                        ),
                        .init(
                            symbolSystemName: "bell.badge",
                            title: "More dependable daily exports",
                            description: "Scheduled exports now use iOS background wakes, server nudges, and a tap-to-retry fallback notification to run closer to your chosen time."
                        ),
                        .init(
                            symbolSystemName: "folder",
                            title: "Cleaner export organization",
                            description: "File path templates now support folders, more date tokens, and a dated-folder preset for tidy daily archives."
                        ),
                        .init(
                            symbolSystemName: "calendar",
                            title: "Quickly focus on yesterday",
                            description: "New Yesterday filters make it easier to review or export the previous day’s visits and tracks."
                        ),
                        .init(
                            symbolSystemName: "sparkles",
                            title: "A fresh coat of polish",
                            description: "Whereday has a refreshed app icon and updated release polish across the app experience."
                        )
                    ]
                )
            ]
        )
    ]

    static var presentedVersion: NoteletPresentedVersion? {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--seed-screenshot-data") { return nil }
        #endif

        return .current
    }

    static let configuration = NoteletConfiguration(
        nextButtonLabel: "Next",
        doneButtonLabel: "Done",
        accentColor: TE.accent
    )
}

extension View {
    func isoMeReleaseNotesSheet() -> some View {
        noteletSheet(
            notes: IsoMeReleaseNotes.notes,
            version: IsoMeReleaseNotes.presentedVersion,
            configuration: IsoMeReleaseNotes.configuration
        )
    }
}
