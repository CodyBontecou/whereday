import SwiftUI
import SwiftData
import UniformTypeIdentifiers
import CoreLocation

/// The day at a glance: a 24-hour dial of today's places, headline numbers, and the day so far.
struct TodayView: View {
    let viewModel: LocationViewModel
    var onReplay: () -> Void
    var onPickDay: () -> Void
    var onOpenPlaces: () -> Void
    var onOpenArchive: () -> Void
    /// Set when a companion pane shows the map: stays focus there instead of opening a sheet.
    var onSelectVisit: ((Visit) -> Void)?
    /// On a foldable's cover display, a hint that opening it reveals the map.
    var showsCoverHint: Bool
    /// The stay focused on the companion map, highlighted in the list.
    var selectedVisitID: UUID?
    /// A foldable's closed cover display: the current place and the two latest stays only.
    var isCoverDisplay: Bool
    /// Where Today is drawn: its own page, or the map's day sheet (the map's header names the day).
    var style: Style
    /// A finished day shown in the map's day sheet (Yesterday, or a picked day); nil for today.
    var pastDay: Date?

    enum Style {
        case page, mapSheet
    }

    @ObservedObject private var locationManager: LocationManager
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.openURL) private var openURL
    @Environment(\.modelContext) private var modelContext
    @Environment(ManualVisitRouter.self) private var manualVisitRouter
    @State private var inspectedVisit: Visit?
    @State private var renamingVisit: Visit?
    @State private var renameText = ""
    @FocusState private var renameFieldFocused: Bool
    @State private var reopensImportPicker = false
    @State private var showingImportPicker = false
    @State private var importFileSelection: ImportFileSelection?
    @State private var importErrorMessage: String?
    @State private var newHomeDraft: SavedPlaceDraft?
    @State private var showsMoments = false

    init(
        viewModel: LocationViewModel,
        onReplay: @escaping () -> Void = {},
        onPickDay: @escaping () -> Void = {},
        onOpenPlaces: @escaping () -> Void = {},
        onOpenArchive: @escaping () -> Void = {},
        onSelectVisit: ((Visit) -> Void)? = nil,
        showsCoverHint: Bool = false,
        selectedVisitID: UUID? = nil,
        isCoverDisplay: Bool = false,
        style: Style = .page,
        pastDay: Date? = nil
    ) {
        self.viewModel = viewModel
        self.onReplay = onReplay
        self.onPickDay = onPickDay
        self.onOpenPlaces = onOpenPlaces
        self.onOpenArchive = onOpenArchive
        self.onSelectVisit = onSelectVisit
        self.showsCoverHint = showsCoverHint
        self.selectedVisitID = selectedVisitID
        self.isCoverDisplay = isCoverDisplay
        self.style = style
        self.pastDay = pastDay
        _locationManager = ObservedObject(wrappedValue: viewModel.locationManager)
    }

    private var access: LocationAccess {
        // Access only shapes how today is recorded.
        if pastDay != nil { return .always }
        switch locationManager.authorizationStatus {
        case .authorizedAlways: return locationManager.hasReducedAccuracy ? .limited : .always
        case .authorizedWhenInUse: return .whileUsing
        case .denied, .restricted: return .off
        default: return .always
        }
    }

    /// Open beside the companion map: a slimmer list without moves, the map draws the routes.
    private var isCompanion: Bool { onSelectVisit != nil && !isMapSheet }

    /// In the map's day sheet the map's header names the day and the map is right above.
    private var isMapSheet: Bool { style == .mapSheet }

    private var home: SavedPlace? {
        viewModel.savedPlaces.first { $0.kind == .home }
    }

    var body: some View {
        TimelineView(.everyMinute) { context in
            let day = pastDay.map { TodayDay(pastDay: $0, viewModel: viewModel, points: viewModel.mapLocationPoints, home: home) }
                ?? TodayDay(
                    viewModel: viewModel,
                    now: context.date,
                    home: home,
                    marksUnrecordedGaps: access == .whileUsing || access == .limited
                )
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if !isMapSheet {
                        header(now: context.date)
                    }
                    if access != .always {
                        accessCard
                            .padding(.top, 18)
                    }
                    if dynamicTypeSize.isAccessibilitySize {
                        largeTextOverview(day: day)
                            .padding(.top, 18)
                        daySoFar(day, now: context.date)
                            .padding(.top, 24)
                    } else if day.stays.isEmpty && pastDay != nil {
                        Text("Nothing was recorded this day.")
                            .font(TE.editorial(.body))
                            .foregroundStyle(TE.textMuted)
                            .padding(.horizontal, 20)
                            .padding(.top, 12)
                    } else if day.stays.isEmpty && access == .always {
                        blankPage(now: context.date)
                            .padding(.top, 26)
                    } else if isCoverDisplay {
                        coverPage(day: day, now: context.date)
                    } else {
                        if access != .off {
                            if isMapSheet {
                                mapSheetOverview(day: day, now: context.date)
                                    .padding(.top, 4)
                            } else {
                                overview(day: day, now: context.date)
                                    .padding(.top, isCompanion ? 14 : 18)
                                    .padding(.bottom, isCompanion ? 6 : 8)
                            }
                        }
                        // The map's peek card already says where you are; a tapped stay renames it there.
                        if let current = day.currentStay, !isCompanion, !isMapSheet {
                            currentPlaceCard(current)
                                .padding(.top, 6)
                        }
                        daySoFar(day, now: context.date)
                            .padding(.top, isCompanion ? 8 : (isMapSheet ? 14 : 20))
                    }
                    if isMapSheet {
                        mapSheetLinks
                            .padding(.top, 14)
                    }
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
        .background(isMapSheet ? Color.clear : TE.surface)
        .background { if !isMapSheet { TE.surface.ignoresSafeArea() } }
        .foregroundStyle(TE.textPrimary)
        .sheet(item: $inspectedVisit) { visit in
            NavigationStack {
                VisitDetailView(visit: visit, viewModel: viewModel)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") { inspectedVisit = nil }
                        }
                    }
            }
        }
        // Bring in your Google Timeline: the same picker and review as Settings → Import.
        .fileImporter(
            isPresented: $showingImportPicker,
            allowedContentTypes: [.json, .commaSeparatedText, UTType(filenameExtension: "md") ?? .plainText],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls):
                if !urls.isEmpty { importFileSelection = ImportFileSelection(urls: urls) }
            case .failure(let error):
                importErrorMessage = error.localizedDescription
            }
        }
        .sheet(item: $importFileSelection, onDismiss: {
            // "Choose another file" after a failed read: back to the picker, as in Settings.
            if reopensImportPicker {
                reopensImportPicker = false
                showingImportPicker = true
            }
        }) { selection in
            ImportPreviewView(urls: selection.urls, viewModel: viewModel) {
                reopensImportPicker = true
            }
        }
        .alert("Import Failed", isPresented: Binding(
            get: { importErrorMessage != nil },
            set: { if !$0 { importErrorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { importErrorMessage = nil }
        } message: {
            Text(importErrorMessage ?? "")
        }
        .sheet(item: $newHomeDraft) { draft in
            SavedPlaceEditorView(viewModel: viewModel, draft: draft, kind: .home)
        }
        .sheet(isPresented: $showsMoments) {
            PhotoMomentPlacesView()
        }
    }

    private func select(_ visit: Visit?) {
        guard let visit else { return }
        if let onSelectVisit {
            onSelectVisit(visit)
        } else {
            inspectedVisit = visit
        }
    }

    private func beginRename(_ visit: Visit, currentName: String) {
        renameText = currentName
        renamingVisit = visit
    }

    private func commitRename() {
        if let renamingVisit {
            viewModel.updateVisitName(renamingVisit, customName: renameText)
        }
        cancelRename()
    }

    private func cancelRename() {
        renameFieldFocused = false
        renamingVisit = nil
        renameText = ""
    }

    private func logStopByHand() {
        manualVisitRouter.request = .init(route: .pastVisit(name: nil, latitude: nil, longitude: nil, arrival: nil, departure: nil))
    }

    // MARK: - Save Home

    private func saveHome() {
        let coordinate = locationManager.currentLocation?.coordinate
            ?? viewModel.todayLocationPoints.last.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
        let draft = SavedPlaceDraft(
            name: String(localized: "Home"),
            latitude: coordinate?.latitude,
            longitude: coordinate?.longitude
        )
        newHomeDraft = draft
    }

    // MARK: - Header

    private func header(now: Date) -> some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                Text(dayCaption(for: now))
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                Text("Today")
                    .font(TE.editorial(isCompanion || isCoverDisplay ? .title : .largeTitle))
                    .accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 12)
            if showsCoverHint {
                Text("Open for the map")
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .padding(.bottom, 6)
            } else {
                HStack(spacing: 8) {
                    Button { showsMoments = true } label: {
                        Image(systemName: "photo.on.rectangle")
                            .font(.system(size: 17, weight: .light))
                            .frame(width: 44, height: 44)
                            .background(Circle().fill(TE.card))
                            .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Moments")
                    .accessibilityHint("Photos matched to today's places")

                    Button(action: onPickDay) {
                        Image(systemName: "calendar")
                            .font(.system(size: 18, weight: .light))
                            .frame(width: 44, height: 44)
                            .background(Circle().fill(TE.card))
                            .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Pick a day")
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
    }

    private func dayCaption(for date: Date) -> String {
        let weekday = date.formatted(.dateTime.weekday(.abbreviated))
        let monthDay = date.formatted(.dateTime.month(.abbreviated).day())
        guard !dynamicTypeSize.isAccessibilitySize else {
            return String(localized: "\(weekday) · \(monthDay)").uppercased()
        }
        // Day N counts from the first recorded day; a journal with nothing in it yet is on Day 1.
        let dayNumber = locationManager.recordedDayNumber(on: date) ?? 1
        return String(localized: "\(weekday) · \(monthDay) · DAY \(dayNumber)").uppercased()
    }

    // MARK: - Dial and numbers

    private var awayLabel: LocalizedStringKey {
        home != nil ? "AWAY FROM HOME" : "AWAY FROM PLACES"
    }

    private func overview(day: TodayDay, now: Date, dialSize: CGFloat? = nil, showsPoints: Bool? = nil) -> some View {
        let size = dialSize ?? (isCompanion ? 112 : 176)
        let points = showsPoints ?? !isCompanion
        return HStack(alignment: .center, spacing: isCompanion ? 14 : 18) {
            Button(action: onReplay) {
                DayDial(segments: day.segments, now: now, recordingSince: nil, captions: isCompanion ? .timeOnly : (size < 170 ? .nowOnly : .full))
                    .frame(width: size, height: size)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(day.dialAccessibilityLabel)
            .accessibilityHint("Shows today's route on the map")

            VStack(alignment: .leading, spacing: points ? 14 : 10) {
                stat(DistanceFormatter.format(meters: viewModel.todayDistanceTraveled, usesMetric: usesMetricDistanceUnits),
                     label: "MOVED")
                stat(day.awayDuration.map(TodayDay.formatDuration) ?? "—", label: awayLabel)
                if points {
                    stat(viewModel.todayLocationPoints.count.formatted(), label: "POINTS KEPT")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, isCompanion ? 16 : 24)
    }

    /// The map's day sheet: a 72-point dial (tap to replay) beside three numbers.
    private func mapSheetOverview(day: TodayDay, now: Date) -> some View {
        HStack(alignment: .center, spacing: 14) {
            Button(action: onReplay) {
                DayDial(segments: day.segments, now: now, recordingSince: nil, captions: .none, showsNowMarker: pastDay == nil)
                    .frame(width: 72, height: 72)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(day.dialAccessibilityLabel)
            .accessibilityHint("Replays the day's route on the map")

            HStack(alignment: .top, spacing: 8) {
                sheetStat(DistanceFormatter.format(meters: movedMeters, usesMetric: usesMetricDistanceUnits),
                          label: "MOVED")
                sheetStat(day.awayDuration.map(TodayDay.formatDuration) ?? "—", label: "AWAY")
                sheetStat(day.placeCount.formatted(), label: "PLACES")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 20)
    }

    private var movedMeters: Double {
        guard let pastDay else { return viewModel.todayDistanceTraveled }
        let calendar = Calendar.current
        let points = viewModel.mapLocationPoints.filter { !$0.isOutlier && calendar.isDate($0.timestamp, inSameDayAs: pastDay) }
        return TodayDay.pathDistance(points)
    }

    private func sheetStat(_ value: String, label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(WheredayFont.serif(size: 21, relativeTo: .title2))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(WheredayFont.mono(size: 9, weight: .medium, relativeTo: .caption2))
                .tracking(1)
                .foregroundStyle(TE.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// What Today's header offered: photos matched to today's places, and every other day.
    private var mapSheetLinks: some View {
        HStack(spacing: 8) {
            Button { showsMoments = true } label: {
                Label("Moments", systemImage: "photo.on.rectangle")
                    .sheetLinkLabel()
            }
            .accessibilityHint("Photos matched to today's places")
            Button(action: onPickDay) {
                Label("Pick a day", systemImage: "calendar")
                    .sheetLinkLabel()
            }
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 20)
    }

    /// At accessibility sizes the dial gives way to a plain button and stacked numbers.
    private func largeTextOverview(day: TodayDay) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            if let current = day.currentStay {
                largeTextCurrentPlaceCard(current)
            }
            VStack(alignment: .leading, spacing: 10) {
                largeTextStat(DistanceFormatter.format(meters: viewModel.todayDistanceTraveled, usesMetric: usesMetricDistanceUnits),
                              label: "moved")
                largeTextStat(day.awayDuration.map(TodayDay.formatDuration) ?? "—",
                              label: home != nil ? "away from home" : "away from places")
            }
            Button(action: onReplay) {
                HStack(spacing: 12) {
                    Text("Replay the day")
                        .font(.body.weight(.medium))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
                .frame(minHeight: 64)
                .overlay(Capsule().strokeBorder(TE.textPrimary, lineWidth: 1.5))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
    }

    private func largeTextStat(_ value: String, label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(TE.editorial(.largeTitle))
            Text(label)
                .font(.body)
                .foregroundStyle(TE.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private func stat(_ value: String, label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(TE.editorial(isCompanion ? .title2 : .title))
            Text(label)
                .font(TE.mono(.caption2))
                .tracking(1)
                .foregroundStyle(TE.textMuted)
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: - Cover display

    /// Closed, a foldable's cover keeps the numbers, where you are, and the two latest stays, newest first.
    private func coverPage(day: TodayDay, now: Date) -> some View {
        let recent = Array(day.stays.filter { !$0.isCurrent }.suffix(2).reversed())
        return VStack(alignment: .leading, spacing: 0) {
            if access != .off {
                overview(day: day, now: now, dialSize: 150, showsPoints: false)
                    .padding(.top, 16)
            }
            if let current = day.currentStay {
                currentPlaceCard(current)
                    .padding(.top, 16)
            }
            VStack(alignment: .leading, spacing: 0) {
                ForEach(recent, id: \.id) { stay in
                    Button { select(stay.visit) } label: {
                        compactStayRow(stay, minHeight: 42)
                    }
                    .buttonStyle(.plain)
                    .accessibilityRemoveTraits(stay.visit == nil ? .isButton : [])
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 14)
        }
    }

    // MARK: - Blank page

    private func blankPage(now: Date) -> some View {
        let recordingSince = locationManager.trackingStartTime.map { max($0, Calendar.current.startOfDay(for: now)) }
        return VStack(spacing: 24) {
            VStack(spacing: 18) {
                DayDial(segments: [], now: now, recordingSince: recordingSince)
                    .frame(width: 220, height: 220)
                    .accessibilityElement()
                    .accessibilityLabel(recordingSince.map {
                        String(localized: "Empty 24-hour dial; recording started at \($0.formatted(date: .omitted, time: .shortened))")
                    } ?? String(localized: "Empty 24-hour dial"))
                VStack(spacing: 8) {
                    Text("The page is blank for now.")
                        .font(TE.editorial(.title))
                    Text("Stay somewhere for a few minutes and it becomes your first stop. Moves between stops fill the ring as you go.")
                        .font(.subheadline)
                        .foregroundStyle(TE.textMuted)
                }
                .multilineTextAlignment(.center)
                .frame(maxWidth: 300)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)

            VStack(spacing: 0) {
                if home == nil {
                    // With Home saved, each day opens on the stretch at Home ("Home until 8:12") and counts time away.
                    startLink("Save Home so days start there", systemImage: "house", action: saveHome)
                    Rectangle().fill(TE.dialTrack).frame(height: 1)
                }
                startLink("Bring in your Google Timeline", systemImage: "square.and.arrow.down") {
                    showingImportPicker = true
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
            .padding(.horizontal, 16)
        }
    }

    private func startLink(_ title: LocalizedStringKey, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .foregroundStyle(TE.placeInks[1])
                    .frame(width: 22)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Location access

    private var accessKicker: LocalizedStringKey {
        switch access {
        case .off: "LOCATION ACCESS IS OFF"
        case .limited: "APPROXIMATE LOCATION ONLY"
        default: "ONLY WHILE USING THE APP"
        }
    }

    private var accessHeadline: LocalizedStringKey {
        switch access {
        case .off: "Whereday can’t record today."
        case .limited: "Today’s places are approximate."
        default: "Today has gaps when Whereday is closed."
        }
    }

    private var accessBody: LocalizedStringKey {
        switch access {
        case .off: "Nothing new is being kept. Your past days, places and exports are all still here."
        case .limited: "Precise Location is off, so stops and routes come from rough positions and some stretches go unrecorded."
        default: "Allow Always so the day records around the clock, even in your pocket."
        }
    }

    private var accessCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label {
                Text(accessKicker)
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.inkRust)
            } icon: {
                Image(systemName: "exclamationmark.shield")
                    .foregroundStyle(TE.placeInks[1])
            }
            Text(accessHeadline)
                .font(TE.editorial(.title2))
            Text(accessBody)
                .font(.subheadline)
                .foregroundStyle(TE.textMuted)
            VStack(alignment: .leading, spacing: 2) {
                Text("1. Settings → Whereday → Location")
                Text("2. Choose **Always**")
                Text("3. Keep **Precise Location** on")
            }
            .font(TE.mono(.caption))
            .padding(.top, 4)
            Button("Open iPhone Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            .buttonStyle(TEActionButtonStyle(emphasis: .primary))
            .padding(.top, 4)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.placeInks[1], lineWidth: 1.5))
        .padding(.horizontal, 16)
        .accessibilityElement(children: .contain)
    }

    // MARK: - Current place

    private func isRenaming(_ stay: TodayDay.Stay) -> Bool {
        guard let renamingVisit, let visit = stay.visit else { return false }
        return renamingVisit.id == visit.id
    }

    private func currentPlaceCard(_ stay: TodayDay.Stay) -> some View {
        let renaming = isRenaming(stay)
        return HStack(spacing: 14) {
            Circle()
                .fill(TE.liveDot)
                .frame(width: 10, height: 10)
                .background(Circle().fill(TE.liveDot.opacity(0.25)).padding(-5))
                .padding(5)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("YOU'RE AT · SINCE \(stay.since.formatted(date: .omitted, time: .shortened))")
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.onInkCardMuted)
                if renaming {
                    renameField(for: stay, font: TE.editorial(.title2))
                } else {
                    Text(stay.name)
                        .font(TE.editorial(.title2))
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: renaming ? .contain : .combine)
            if renaming {
                HStack(spacing: 8) {
                    renameIconButton("xmark", label: "Cancel", filled: false, action: cancelRename)
                    renameIconButton("checkmark", label: "Save", filled: true, action: commitRename)
                }
            } else if let visit = stay.visit {
                Button("Rename") { beginRename(visit, currentName: stay.name) }
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 14)
                    .frame(minHeight: 44)
                    .overlay(Capsule().strokeBorder(TE.onInkCard.opacity(0.35), lineWidth: 1))
                    .buttonStyle(.plain)
            }
        }
        .foregroundStyle(TE.onInkCard)
        .padding(.horizontal, 18)
        .padding(.vertical, isCoverDisplay ? 14 : 16)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
        .padding(.horizontal, 16)
        .animation(.easeOut(duration: 0.15), value: renaming)
    }

    private func largeTextCurrentPlaceCard(_ stay: TodayDay.Stay) -> some View {
        let renaming = isRenaming(stay)
        return VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 8) {
                Text("You're at, since \(stay.since.formatted(date: .omitted, time: .shortened))")
                    .font(TE.mono(.body))
                    .foregroundStyle(TE.onInkCardMuted)
                if renaming {
                    renameField(for: stay, font: TE.editorial(.largeTitle))
                } else {
                    Text(stay.name)
                        .font(TE.editorial(.largeTitle))
                }
            }
            .accessibilityElement(children: renaming ? .contain : .combine)
            if renaming {
                HStack(spacing: 10) {
                    largeTextCardButton("Save", filled: true, action: commitRename)
                    largeTextCardButton("Cancel", filled: false, action: cancelRename)
                }
            } else if let visit = stay.visit {
                largeTextCardButton("Rename", filled: false) { beginRename(visit, currentName: stay.name) }
            }
        }
        .foregroundStyle(TE.onInkCard)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(RoundedRectangle(cornerRadius: 20).fill(TE.inkCard))
    }

    /// The place name, edited where it stands. Return saves; an empty name goes back to the detected place.
    private func renameField(for stay: TodayDay.Stay, font: Font) -> some View {
        TextField(
            "Name",
            text: $renameText,
            prompt: Text(stay.visit?.automaticDisplayName ?? stay.name).foregroundStyle(TE.onInkCardMuted)
        )
        .font(font)
        .foregroundStyle(TE.onInkCard)
        .tint(TE.onInkCard)
        .textInputAutocapitalization(.words)
        .autocorrectionDisabled()
        .submitLabel(.done)
        .focused($renameFieldFocused)
        .onSubmit(commitRename)
        .padding(.vertical, 2)
        .overlay(alignment: .bottom) {
            Rectangle().fill(TE.onInkCard.opacity(0.5)).frame(height: 1)
        }
        .accessibilityLabel("Place name")
        .accessibilityHint("Leave it empty to use the detected place.")
        .onAppear { renameFieldFocused = true }
    }

    private func renameIconButton(_ systemImage: String, label: LocalizedStringKey, filled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(filled ? TE.inkCard : TE.onInkCard)
                .frame(width: 44, height: 44)
                .background(Circle().fill(filled ? TE.onInkCard : Color.clear))
                .overlay(Circle().strokeBorder(TE.onInkCard.opacity(filled ? 0 : 0.35), lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func largeTextCardButton(_ title: LocalizedStringKey, filled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.medium))
                .foregroundStyle(filled ? TE.inkCard : TE.onInkCard)
                .padding(.horizontal, 22)
                .frame(minHeight: 56)
                .background(Capsule().fill(filled ? TE.onInkCard : Color.clear))
                .overlay(Capsule().strokeBorder(TE.onInkCard.opacity(filled ? 0 : 0.5), lineWidth: 1.5))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - The day so far

    private var daySoFarLabel: LocalizedStringKey {
        if pastDay != nil { return "THE DAY" }
        switch access {
        case .whileUsing: return "THE DAY SO FAR · WHILE OPEN"
        case .limited: return "THE DAY SO FAR · APPROXIMATE"
        default: return "THE DAY SO FAR"
        }
    }

    @ViewBuilder
    private func daySoFar(_ day: TodayDay, now: Date) -> some View {
        if access == .off && day.entries.isEmpty {
            lastRecordedDay(now: now)
        } else {
            VStack(alignment: .leading, spacing: isCompanion ? 2 : 0) {
                if !isCompanion {
                    sectionLabel(dynamicTypeSize.isAccessibilitySize ? "The day so far" : daySoFarLabel)
                }

                if day.entries.isEmpty {
                    Text("No places yet today. Stops show up here once you've stayed somewhere for a few minutes.")
                        .font(TE.editorial(.body))
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }

                ForEach(day.entries) { entry in
                    switch entry.kind {
                    case .stay(let stay):
                        // The stretch at Home before the first stop has no visit to open.
                        Button { select(stay.visit) } label: {
                            stayRow(stay)
                        }
                        .buttonStyle(.plain)
                        .accessibilityRemoveTraits(stay.visit == nil ? .isButton : [])
                    case .move(let move):
                        // Large text folds each move into the next stay's line; the companion map draws them instead.
                        if !dynamicTypeSize.isAccessibilitySize && !isCompanion {
                            moveRow(move.label(usesMetric: usesMetricDistanceUnits))
                        }
                    case .unrecorded(let duration):
                        unrecordedRow(duration)
                    }
                }

                if access != .always && !isCompanion {
                    logStopButton
                }
            }
            .padding(.horizontal, isCompanion ? 16 : (dynamicTypeSize.isAccessibilitySize || isMapSheet ? 20 : 24))
        }
    }

    private func sectionLabel(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(dynamicTypeSize.isAccessibilitySize ? TE.mono(.body) : TE.mono(.caption2))
            .tracking(dynamicTypeSize.isAccessibilitySize ? 0.5 : 1.5)
            .foregroundStyle(TE.textMuted)
            .padding(.bottom, 10)
            .accessibilityAddTraits(.isHeader)
    }

    private var logStopButton: some View {
        Button("Log a stop by hand", action: logStopByHand)
            .font(.subheadline)
            .foregroundStyle(TE.inkRust)
            .frame(minHeight: 44)
            .padding(.top, 8)
    }

    /// With location off and nothing today, the last day that was recorded stands in, newest stay first.
    private func lastRecordedDay(now: Date) -> some View {
        let last = TodayDay.lastRecordedDay(viewModel: viewModel, before: Calendar.current.startOfDay(for: now), now: now)
        return VStack(alignment: .leading, spacing: 0) {
            if let last {
                sectionLabel(lastRecordedLabel(for: last.day))
                ForEach(Array(last.stays.enumerated()), id: \.element.id) { index, stay in
                    Button { select(stay.visit) } label: {
                        stayRow(stay)
                    }
                    .buttonStyle(.plain)
                    .opacity(index == 0 ? 1 : 0.6)
                }
            } else {
                sectionLabel(daySoFarLabel)
                Text("Nothing has been recorded yet. Turn location on to start your first day.")
                    .font(TE.editorial(.body))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            logStopButton
        }
        .padding(.horizontal, dynamicTypeSize.isAccessibilitySize ? 20 : 24)
    }

    private func lastRecordedLabel(for day: Date) -> LocalizedStringKey {
        if Calendar.current.isDateInYesterday(day) {
            return "LAST RECORDED · YESTERDAY"
        }
        let date = day.formatted(.dateTime.month(.abbreviated).day()).uppercased()
        return "LAST RECORDED · \(date)"
    }

    @ViewBuilder
    private func stayRow(_ stay: TodayDay.Stay) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Circle()
                        .fill(stay.ink)
                        .frame(width: 16, height: 16)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(stay.name)
                            .font(TE.editorial(.title))
                        Text(stayMeta(stay))
                            .font(TE.mono(.body))
                            .foregroundStyle(TE.textMuted)
                    }
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.vertical, 12)
                Rectangle().fill(TE.dialTrack).frame(height: 1)
            }
            .contentShape(Rectangle())
            .accessibilityElement(children: .combine)
        } else {
            compactStayRow(stay)
        }
    }

    private func stayMeta(_ stay: TodayDay.Stay) -> String {
        let time = stay.start.formatted(date: .omitted, time: .shortened)
        let duration = stay.isCurrent ? String(localized: "now") : TodayDay.formatDuration(stay.end.timeIntervalSince(stay.start))
        var parts = [time, duration]
        if let arrival = stay.arrival?.arrivalPhrase(usesMetric: usesMetricDistanceUnits) {
            parts.append(arrival)
        }
        return parts.joined(separator: " · ")
    }

    private func compactStayRow(_ stay: TodayDay.Stay, minHeight: CGFloat = 46) -> some View {
        let isSelected = isCompanion && stay.visit != nil && stay.visit?.id == selectedVisitID
        return HStack(spacing: 10) {
            Text(ClockTimeFormatter.string(from: stay.start))
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
                .frame(minWidth: 44, alignment: .leading)
            Circle()
                .fill(stay.ink)
                .frame(width: 12, height: 12)
                .frame(width: 14)
                .accessibilityHidden(true)
            Text(stay.name)
                .font(TE.editorial(isCompanion ? .headline : .title3))
                .lineLimit(isCompanion ? 1 : nil)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(stay.isCurrent ? String(localized: "now") : TodayDay.formatDuration(stay.end.timeIntervalSince(stay.start)))
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
        }
        .frame(minHeight: isCompanion ? 48 : minHeight)
        .padding(.horizontal, isCompanion ? 10 : 0)
        .background {
            if isCompanion {
                RoundedRectangle(cornerRadius: 14)
                    .fill(isSelected ? TE.card : Color.clear)
                    .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(isSelected ? TE.placeInks[1] : Color.clear, lineWidth: 1.5))
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func moveRow(_ label: String) -> some View {
        HStack(spacing: 10) {
            Color.clear.frame(width: 44, height: 1)
            DottedMoveLine()
                .stroke(TE.dialTick, style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [0.1, 4]))
                .frame(width: 14, height: 24)
                .accessibilityHidden(true)
            Text(label)
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
        }
        .frame(minHeight: 26)
    }

    /// A stretch with no location points while Whereday could only listen while open, or only roughly.
    @ViewBuilder
    private func unrecordedRow(_ duration: TimeInterval) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Circle()
                        .fill(TE.dialTrack)
                        .frame(width: 16, height: 16)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Not recorded")
                            .font(TE.editorial(.title))
                        Text(TodayDay.formatDuration(duration))
                            .font(TE.mono(.body))
                            .foregroundStyle(TE.textMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.vertical, 12)
                Rectangle().fill(TE.dialTrack).frame(height: 1)
            }
            .opacity(0.6)
            .accessibilityElement(children: .combine)
        } else {
            HStack(spacing: 10) {
                Text(verbatim: "—")
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.textMuted)
                    .frame(minWidth: 44, alignment: .leading)
                    .accessibilityHidden(true)
                Circle()
                    .fill(TE.dialTrack)
                    .frame(width: 12, height: 12)
                    .frame(width: 14)
                    .accessibilityHidden(true)
                Text("Not recorded")
                    .font(TE.editorial(isCompanion ? .headline : .title3))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(TodayDay.formatDuration(duration))
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.textMuted)
            }
            .frame(minHeight: isCompanion ? 48 : 46)
            .padding(.horizontal, isCompanion ? 10 : 0)
            .opacity(0.6)
            .accessibilityElement(children: .combine)
        }
    }
}

private extension Label where Title == Text, Icon == Image {
    func sheetLinkLabel() -> some View {
        self
            .font(.subheadline.weight(.medium))
            .foregroundStyle(TE.textPrimary)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(Capsule().fill(TE.card))
            .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
            .contentShape(Capsule())
    }
}

private enum LocationAccess {
    /// `limited` is Always with Precise Location off.
    case always, whileUsing, limited, off
}

// MARK: - Recording detail

/// First-run and "How Whereday listens" presets over the distance filter and auto-stop settings.
enum RecordingDetail: CaseIterable, Identifiable {
    case places, routes, everyStep

    var id: Self { self }

    init(distanceFilter: Double) {
        switch distanceFilter {
        case 100...: self = .places
        case 25...: self = .routes
        default: self = .everyStep
        }
    }

    var distanceFilter: Double {
        switch self {
        case .places: 100
        case .routes: 25
        case .everyStep: 5
        }
    }

    var stopAfterHours: Double { self == .everyStep ? 2 : 0 }

    var title: LocalizedStringKey {
        switch self {
        case .places: "Places only"
        case .routes: "Places + routes"
        case .everyStep: "Every step"
        }
    }

    /// First run, step 3: the short version.
    var summary: LocalizedStringKey {
        switch self {
        case .places: "Where you stop. Lightest on battery."
        case .routes: "Your path while moving, quiet when still."
        case .everyStep: "High-detail tracks for hikes and rides."
        }
    }

    /// "How Whereday listens" names the routes mode in full.
    var listeningTitle: LocalizedStringKey {
        switch self {
        case .routes: "Places + routes while moving"
        default: title
        }
    }

    /// "How Whereday listens": what each mode keeps, and what it costs.
    var listeningSummary: LocalizedStringKey {
        switch self {
        case .places: "Notes where you stop. No path between. Lightest on battery."
        case .routes: "Draws your path when you're out, sleeps when you're still."
        case .everyStep: "A point every 5 m, always. For hikes and rides. Stops on its own after 2 h."
        }
    }

    @MainActor
    func apply(to locationManager: LocationManager) {
        // Presets are distance-based; choosing one leaves any time-based sampling.
        locationManager.setRouteSamplingMode(.distance)
        locationManager.setDistanceFilter(distanceFilter)
        locationManager.setStopAfterHours(stopAfterHours)
    }
}
