import SwiftUI
import AVKit
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

    /// Newest first; the first entry is what Settings opens as "What's new".
    static let notes: [ReleaseNotesVersion] = [
        .init(
            version: "1.7.17",
            items: [
                .media(
                    kind: .video,
                    url: releaseNoteVideoURL("manual-visit-shortcuts"),
                    title: "Check in and out with Shortcuts",
                    description: "Start a manual visit at a saved place or coordinates, then finish it with Check Out. Choose the visit when more than one is open."
                ),
                .list(
                    title: "More ways to log your visits",
                    rows: [
                        .init(
                            symbolSystemName: "mappin.and.ellipse",
                            title: "Log a visit from Shortcuts",
                            description: "Use a saved place, enter coordinates, or request your current location when permission allows."
                        ),
                        .init(
                            symbolSystemName: "checkmark.circle",
                            title: "Keep overlapping visits intentional",
                            description: "Shortcuts warns about overlapping visits. Enable Allow Overlap only when you want to keep both records."
                        ),
                        .init(
                            symbolSystemName: "link",
                            title: "Open visit forms with a link",
                            description: "Links can open Check In, Add Past Visit, or a visit's details. Review the place and times before saving."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.7.16",
            items: [
                .list(
                    title: "Watch sync and smoother maps",
                    rows: [
                        .init(
                            symbolSystemName: "applewatch",
                            title: "Sync your Watch visits",
                            description: "Watch recordings now include visited places. Open Whereday on your iPhone and tap Sync Now in the Watch app to retry a transfer."
                        ),
                        .init(
                            symbolSystemName: "arrow.triangle.2.circlepath",
                            title: "Keep completed outings up to date",
                            description: "The iPhone app refreshes when your Watch sends an outing's completion update."
                        ),
                        .init(
                            symbolSystemName: "map",
                            title: "Smoother GPS maps",
                            description: "GPS dots adjust to the visible map area and zoom level to keep large location histories responsive."
                        )
                    ]
                )
            ]
        ),
        .init(
            version: "1.7.15",
            items: [
                .list(
                    title: "Explore your photo map",
                    rows: [
                        .init(
                            symbolSystemName: "circle.grid.3x3.fill",
                            title: "Photos as dots",
                            description: "Turn on Photo dots in the map filters for a clearer view of where you took your photos. Your choice is saved."
                        ),
                        .init(
                            symbolSystemName: "photo.on.rectangle",
                            title: "Browse every photo",
                            description: "Explore all matching photos in each group, or use Browse Photo Places to find locations beyond the markers currently shown on the map."
                        ),
                        .init(
                            symbolSystemName: "hand.tap",
                            title: "Easier photo selection",
                            description: "Open the photo you intended, even when nearby dots or route markers overlap."
                        )
                    ] + (MyselfClaimFeature.isEnabled ? [
                        .init(
                            symbolSystemName: "gift",
                            title: "Claim your myself.md access",
                            description: "Existing purchasers can verify their purchase in Settings and sign in to myself.md to claim complimentary lifetime access."
                        )
                    ] : [])
                )
            ]
        ),
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

    /// The version whose notes open automatically once, or nil when automatic presentation is off.
    static var presentedVersion: String? {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--seed-screenshot-data") { return nil }
        #endif

        return AppInfo.version
    }
}

// MARK: - Model

/// The same shape the Notelet package used, so the notes above read unchanged.
struct ReleaseNotesVersion: Identifiable {
    let version: String
    let items: [ReleaseNoteItem]
    var id: String { version }

    init(version: String, items: [ReleaseNoteItem]) {
        self.version = version
        self.items = items
    }

    var hasMedia: Bool {
        items.contains { if case .media = $0 { return true } else { return false } }
    }

    /// The first media title, or the first list title, leads the page.
    var headline: LocalizedStringResource? {
        for item in items {
            if case .media(_, _, let title, _) = item { return title }
        }
        for item in items {
            if case .list(let title, _) = item { return title }
        }
        return nil
    }
}

enum ReleaseNoteItem {
    enum MediaKind {
        case video
    }

    struct Row {
        let symbolSystemName: String
        let title: LocalizedStringResource
        let description: LocalizedStringResource

        init(symbolSystemName: String, title: LocalizedStringResource, description: LocalizedStringResource) {
            self.symbolSystemName = symbolSystemName
            self.title = title
            self.description = description
        }
    }

    case media(kind: MediaKind, url: URL, title: LocalizedStringResource, description: LocalizedStringResource)
    case list(title: LocalizedStringResource, rows: [Row])
}

// MARK: - Presentation

extension View {
    /// Shows the current version's notes once, then remembers it was seen.
    func isoMeReleaseNotesSheet() -> some View {
        modifier(ReleaseNotesPresenter())
    }
}

private struct ReleaseNotesPresenter: ViewModifier {
    @State private var presented: ReleaseNotesVersion?

    func body(content: Content) -> some View {
        content
            .onAppear {
                guard presented == nil,
                      let version = IsoMeReleaseNotes.presentedVersion,
                      // Keep Notelet's key so versions seen before this sheet existed stay seen.
                      UserDefaults.standard.string(forKey: NoteletStorageKey.latestSeenAppVersion) != version,
                      let release = IsoMeReleaseNotes.notes.first(where: { $0.version == version }),
                      !release.items.isEmpty else { return }
                presented = release
            }
            .sheet(item: $presented, onDismiss: {
                if let version = IsoMeReleaseNotes.presentedVersion {
                    UserDefaults.standard.set(version, forKey: NoteletStorageKey.latestSeenAppVersion)
                }
            }) { release in
                WhatsNewSheet(release: release)
            }
    }
}

/// One release: headline, looping clip, highlights, and a way back to earlier releases.
struct WhatsNewSheet: View {
    let release: ReleaseNotesVersion
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ReleaseNotesPage(release: release, onClose: { dismiss() })
                .toolbar(.hidden, for: .navigationBar)
                .navigationTitle("What's new")
        }
        .presentationDragIndicator(.visible)
        .presentationBackground(TE.surface)
    }
}

private struct ReleaseNotesPage: View {
    let release: ReleaseNotesVersion
    var onClose: (() -> Void)?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("WHAT'S NEW · \(release.version)")
                            .font(TE.mono(.caption2))
                            .tracking(1.5)
                            .foregroundStyle(TE.textMuted)
                        if let headline = release.headline {
                            Text(headline)
                                .font(TE.editorial(.largeTitle))
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityAddTraits(.isHeader)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if let onClose {
                        BackPageCircleButton(systemImage: "xmark", label: "Close", action: onClose)
                    }
                }
                .padding(.leading, 24)
                .padding(.trailing, 20)
                .padding(.top, 24)

                ForEach(Array(release.items.enumerated()), id: \.offset) { index, item in
                    itemView(item, isFirst: index == 0)
                }
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if onClose != nil {
                VStack(spacing: 4) {
                    Button("Continue") { onClose?() }
                        .buttonStyle(BackPagePillButtonStyle(kind: .filled))
                    if IsoMeReleaseNotes.notes.count > 1 {
                        NavigationLink("Earlier releases") {
                            EarlierReleasesView(current: release.version)
                        }
                        .font(.footnote)
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 10)
                .padding(.bottom, 8)
                .background(TE.surface.ignoresSafeArea(edges: .bottom))
            }
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .tint(TE.textPrimary)
    }

    @ViewBuilder
    private func itemView(_ item: ReleaseNoteItem, isFirst: Bool) -> some View {
        switch item {
        case .media(_, let url, let title, let description):
            VStack(alignment: .leading, spacing: 0) {
                if !isFirst {
                    Text(title)
                        .font(TE.editorial(.title2))
                        .padding(.horizontal, 24)
                        .padding(.top, 26)
                }
                ReleaseNoteVideo(url: url, title: title)
                    .padding(.horizontal, 16)
                    .padding(.top, isFirst ? 18 : 12)
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 24)
                    .padding(.top, 14)
            }
        case .list(let title, let rows):
            VStack(alignment: .leading, spacing: 0) {
                if !(isFirst && !release.hasMedia) {
                    Text(title)
                        .font(TE.mono(.caption2))
                        .tracking(1.5)
                        .textCase(.uppercase)
                        .foregroundStyle(TE.textMuted)
                        .padding(.horizontal, 24)
                        .padding(.top, 22)
                        .padding(.bottom, 8)
                        .accessibilityAddTraits(.isHeader)
                }
                BackPageCard {
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                        if index > 0 { BackPageDivider() }
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: row.symbolSystemName)
                                .font(.body)
                                .foregroundStyle(TE.inkRust)
                                .frame(width: 28)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.title)
                                    .font(.subheadline.weight(.semibold))
                                Text(row.description)
                                    .font(.footnote)
                                    .foregroundStyle(TE.textMuted)
                            }
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, isFirst && !release.hasMedia ? 18 : 0)
            }
        }
    }
}

private struct EarlierReleasesView: View {
    let current: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackPageHeader(caption: "RELEASE NOTES", title: "Earlier releases")
                    .padding(.horizontal, 24)
                    .padding(.top, 8)
                BackPageCard {
                    let releases = IsoMeReleaseNotes.notes.filter { $0.version != current }
                    ForEach(Array(releases.enumerated()), id: \.element.id) { index, release in
                        if index > 0 { BackPageDivider() }
                        NavigationLink {
                            ReleaseNotesPage(release: release)
                        } label: {
                            BackPageRowLabel(
                                title: release.headline.map { Text($0) } ?? Text(verbatim: release.version),
                                value: Text(verbatim: release.version).font(TE.mono(.caption))
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 18)
            }
            .padding(.bottom, 32)
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
    }
}

/// A muted, looping clip on the ink card. Paused under Reduce Motion; tap to play or pause.
private struct ReleaseNoteVideo: View {
    let url: URL
    let title: LocalizedStringResource
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var player: AVQueuePlayer?
    @State private var looper: AVPlayerLooper?
    @State private var isPlaying = false

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RoundedRectangle(cornerRadius: 20).fill(TE.inkCard)
            ReleaseNotePlayerLayer(player: player)
            Label(isPlaying ? "Pause" : "Play", systemImage: isPlaying ? "pause.fill" : "play.fill")
                .labelStyle(.iconOnly)
                .font(.caption.weight(.semibold))
                .foregroundStyle(TE.onInkCard)
                .frame(width: 32, height: 32)
                .background(Circle().fill(TE.inkCard.opacity(0.7)))
                .padding(12)
                .accessibilityHidden(true)
        }
        .frame(height: 236)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .contentShape(RoundedRectangle(cornerRadius: 20))
        .onTapGesture { togglePlayback() }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Video: \(Text(title))"))
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(isPlaying ? "Pauses the video" : "Plays the video")
        .task(id: url) {
            let item = AVPlayerItem(url: url)
            let queue = AVQueuePlayer(playerItem: item)
            queue.isMuted = true
            looper = AVPlayerLooper(player: queue, templateItem: item)
            player = queue
            if !reduceMotion {
                queue.play()
                isPlaying = true
            }
        }
        .onDisappear {
            player?.pause()
            isPlaying = false
        }
    }

    private func togglePlayback() {
        guard let player else { return }
        if isPlaying { player.pause() } else { player.play() }
        isPlaying.toggle()
    }
}

private struct ReleaseNotePlayerLayer: UIViewRepresentable {
    final class PlayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    let player: AVPlayer?

    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.playerLayer.videoGravity = .resizeAspectFill
        view.clipsToBounds = true
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: PlayerView, context: Context) {
        uiView.playerLayer.player = player
    }
}
