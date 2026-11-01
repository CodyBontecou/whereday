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
        isCoverDisplay: Bool = false
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
        _locationManager = ObservedObject(wrappedValue: viewModel.locationManager)
    }

    private var access: LocationAccess {
        switch locationManager.authorizationStatus {
        case .authorizedAlways: locationManager.hasReducedAccuracy ? .limited : .always
        case .authorizedWhenInUse: .whileUsing
        case .denied, .restricted: .off
        default: .always
        }
    }

    /// Open beside the companion map: a slimmer list without moves, the map draws the routes.
    private var isCompanion: Bool { onSelectVisit != nil }

    private var home: SavedPlace? {
        viewModel.savedPlaces.first { $0.kind == .home }
    }

    var body: some View {
        TimelineView(.everyMinute) { context in
            let day = TodayDay(
                viewModel: viewModel,
                now: context.date,
                home: home,
                marksUnrecordedGaps: access == .whileUsing || access == .limited
            )
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header(now: context.date)
                    if access != .always {
                        accessCard
                            .padding(.top, 18)
                    }
                    if dynamicTypeSize.isAccessibilitySize {
                        largeTextOverview(day: day)
                            .padding(.top, 18)
                        daySoFar(day, now: context.date)
                            .padding(.top, 24)
                    } else if day.stays.isEmpty && access == .always {
                        blankPage(now: context.date)
                            .padding(.top, 26)
                    } else if isCoverDisplay {
                        coverPage(day: day, now: context.date)
                    } else {
                        if access != .off {
                            overview(day: day, now: context.date)
                                .padding(.top, isCompanion ? 14 : 18)
                                .padding(.bottom, isCompanion ? 6 : 8)
                        }
                        if let current = day.currentStay, !isCompanion {
                            currentPlaceCard(current)
                                .padding(.top, 6)
                        }
                        daySoFar(day, now: context.date)
                            .padding(.top, isCompanion ? 8 : 20)
                    }
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
        .background(TE.surface.ignoresSafeArea())
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
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first { importFileSelection = ImportFileSelection(url: url) }
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
            ImportPreviewView(url: selection.url, viewModel: viewModel) {
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
        switch access {
        case .whileUsing: "THE DAY SO FAR · WHILE OPEN"
        case .limited: "THE DAY SO FAR · APPROXIMATE"
        default: "THE DAY SO FAR"
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
            .padding(.horizontal, isCompanion ? 16 : (dynamicTypeSize.isAccessibilitySize ? 20 : 24))
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
            Text(stay.start.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)))
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
            Line()
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

private enum LocationAccess {
    /// `limited` is Always with Precise Location off.
    case always, whileUsing, limited, off
}

// MARK: - Day dial

/// A 24-hour ring, midnight at the top, with each stay drawn as an arc in its place's ink.
private struct DayDial: View {
    let segments: [TodayDay.Segment]
    let now: Date
    /// Set for a blank day: draws a dashed arc from when recording began and labels the center with it.
    let recordingSince: Date?
    /// Smaller dials drop the captions around the time.
    var captions: Captions = .full

    enum Captions {
        case full, nowOnly, timeOnly
    }

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let lineWidth = size * 0.07
            let ringInset = size * 0.06

            ZStack {
                Circle()
                    .inset(by: ringInset)
                    .stroke(TE.dialTrack, lineWidth: lineWidth)

                ForEach(segments) { segment in
                    Circle()
                        .inset(by: ringInset)
                        .trim(from: segment.start, to: segment.end)
                        .stroke(segment.ink, style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
                        .rotationEffect(.degrees(-90))
                }

                if let recordingSince {
                    Circle()
                        .inset(by: ringInset)
                        .trim(from: TodayDay.dayFraction(of: recordingSince), to: TodayDay.dayFraction(of: now))
                        .stroke(TE.textPrimary.opacity(0.5),
                                style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, dash: [0.1, lineWidth * 1.4]))
                        .rotationEffect(.degrees(-90))
                }

                ForEach(0..<4, id: \.self) { quarter in
                    Rectangle()
                        .fill(TE.dialTick)
                        .frame(width: 1, height: size * 0.03)
                        .offset(y: -size / 2 + size * 0.025)
                        .rotationEffect(.degrees(Double(quarter) * 90))
                }

                nowMarker(size: size, ringInset: ringInset)

                VStack(spacing: 2) {
                    if captions != .timeOnly || recordingSince != nil {
                        Text(recordingSince == nil ? "NOW" : "LISTENING SINCE")
                            .font(TE.mono(.caption2))
                            .tracking(1)
                            .foregroundStyle(TE.textMuted)
                    }
                    // Sized to the ring, not to Dynamic Type; the dial is hidden from VoiceOver and gives way at large sizes.
                    Text((recordingSince ?? now).formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)))
                        .font(.custom(WheredayFont.serifFamily, fixedSize: size * (captions == .timeOnly ? 0.21 : 0.19)))
                        .monospacedDigit()
                    if recordingSince == nil && captions == .full {
                        Text("tap to replay")
                            .font(TE.mono(.caption2))
                            .foregroundStyle(TE.inkRust)
                    }
                }
            }
            .frame(width: size, height: size)
        }
        .accessibilityHidden(true)
    }

    private func nowMarker(size: CGFloat, ringInset: CGFloat) -> some View {
        let fraction = TodayDay.dayFraction(of: now)
        let radius = size / 2 - ringInset
        let angle = Angle.degrees(fraction * 360 - 90)
        let marker = size * 0.09

        return ZStack {
            Circle().fill(TE.surface)
            Circle().strokeBorder(TE.inkRust, lineWidth: 2.5)
            Circle().fill(TE.inkRust).frame(width: marker * 0.38, height: marker * 0.38)
        }
        .frame(width: marker, height: marker)
        .offset(x: cos(angle.radians) * radius, y: sin(angle.radians) * radius)
    }
}

private struct Line: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.midX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        }
    }
}

// MARK: - Day model

/// Today's stays clipped to midnight and now, with the moves between them.
@MainActor
private struct TodayDay {
    struct Move {
        let distance: Double
        let duration: TimeInterval
        let mode: TravelMode?

        /// "Bike · 3.4 km · 28 min"
        func label(usesMetric: Bool) -> String {
            var parts: [String] = []
            if let mode { parts.append(mode.label) }
            if distance > 0 { parts.append(DistanceFormatter.format(meters: distance, usesMetric: usesMetric)) }
            parts.append(TodayDay.formatMoveDuration(duration))
            return parts.joined(separator: " · ")
        }

        /// "after a 3.4 km ride", folded into the next stay's line at large text sizes.
        func arrivalPhrase(usesMetric: Bool) -> String? {
            guard distance > 0 else { return nil }
            let distance = DistanceFormatter.format(meters: distance, usesMetric: usesMetric)
            switch mode {
            case .walk: return String(localized: "after a \(distance) walk")
            case .run: return String(localized: "after a \(distance) run")
            case .bike: return String(localized: "after a \(distance) ride")
            case .drive: return String(localized: "after a \(distance) drive")
            case .transit: return String(localized: "after a \(distance) train ride")
            case .fly: return String(localized: "after a \(distance) flight")
            case nil: return String(localized: "after \(distance) on the move")
            }
        }
    }

    struct Stay {
        /// Nil for the stretch at Home before the first recorded stop, which no visit covers.
        let visit: Visit?
        /// What the row and dial call it: the saved Home's name inside Home, otherwise the visit's name.
        let name: String
        let start: Date
        let end: Date
        let isCurrent: Bool
        let ink: Color
        /// The move that ended here, when it was recorded.
        var arrival: Move?

        var id: String { visit.map { $0.id.uuidString } ?? "home-start" }
        /// "Since" on the current-place card: when the visit began, even before midnight.
        var since: Date { visit?.arrivedAt ?? start }
    }

    struct Segment: Identifiable {
        let id: String
        let start: Double
        let end: Double
        let ink: Color
    }

    struct Entry: Identifiable {
        enum Kind {
            case stay(Stay)
            case move(Move)
            /// No location points at all for this long, while Whereday could not listen in the background.
            case unrecorded(TimeInterval)
        }

        let id: String
        let kind: Kind
    }

    /// Stretches without a single point at least this long read as "Not recorded" rather than a move.
    static let unrecordedGapThreshold: TimeInterval = 30 * 60

    let stays: [Stay]
    let entries: [Entry]
    let segments: [Segment]
    let awayDuration: TimeInterval?

    var currentStay: Stay? { stays.last(where: \.isCurrent) }

    init(viewModel: LocationViewModel, now: Date, home: SavedPlace? = nil, marksUnrecordedGaps: Bool = false) {
        let calendar = Calendar.current
        let startOfDay = calendar.startOfDay(for: now)
        let visits = viewModel.visitsOverlappingDateRange(startOfDay...now, referenceDate: now)
        let points = viewModel.todayLocationPoints.filter { !$0.isOutlier }

        // Inside a saved Home, stays read as Home unless renamed by hand.
        func name(for visit: Visit) -> String {
            if let home, !visit.hasCustomName, home.contains(visit.coordinate) { return home.name }
            return visit.displayName
        }

        var inkByPlace: [String: Color] = [:]
        func ink(for name: String) -> Color {
            if let ink = inkByPlace[name] { return ink }
            let ink = TE.placeInks[inkByPlace.count % TE.placeInks.count]
            inkByPlace[name] = ink
            return ink
        }
        if let home { _ = ink(for: home.name) }

        var stays: [Stay] = visits.compactMap { visit in
            let start = max(visit.arrivedAt, startOfDay)
            let end = min(visit.departedAt ?? now, now)
            guard end > start else { return nil }
            let placeName = name(for: visit)
            return Stay(visit: visit, name: placeName, start: start, end: end,
                        isCurrent: viewModel.isCurrentVisit(visit), ink: ink(for: placeName))
        }

        // With a saved Home, the day starts there when that is where the phone was: the first stretch
        // reads "Home until 8:12" instead of starting at the first recorded stop.
        if let home, let homeStretch = Self.homeStretch(
            home: home,
            firstStay: stays.first,
            points: points,
            startOfDay: startOfDay,
            now: now
        ) {
            if let first = stays.first, homeStretch.end >= first.start,
               first.visit.map({ home.contains($0.coordinate) }) == true {
                // At Home until the first stay, which is Home too: it runs from midnight.
                stays[0] = Stay(visit: first.visit, name: first.name, start: startOfDay, end: first.end,
                                isCurrent: first.isCurrent, ink: first.ink)
            } else {
                stays.insert(Stay(visit: nil, name: home.name, start: startOfDay, end: homeStretch.end,
                                  isCurrent: homeStretch.isCurrent, ink: ink(for: home.name)), at: 0)
            }
        }

        var entries: [Entry] = []
        for index in stays.indices {
            if index > 0 {
                let previous = stays[index - 1]
                let gapStart = previous.end
                let gapEnd = stays[index].start
                let gap = gapEnd.timeIntervalSince(gapStart)
                if gap >= 60 {
                    let window = points.filter { $0.timestamp >= gapStart && $0.timestamp <= gapEnd }
                    if marksUnrecordedGaps && window.isEmpty && gap >= Self.unrecordedGapThreshold {
                        entries.append(Entry(id: "gap-\(stays[index].id)", kind: .unrecorded(gap)))
                    } else {
                        let distance = Self.pathDistance(window)
                        let move = Move(
                            distance: distance,
                            duration: gap,
                            mode: distance > 0 ? TravelMode.infer(meters: distance, seconds: gap) : nil
                        )
                        stays[index].arrival = move
                        entries.append(Entry(id: "move-\(stays[index].id)", kind: .move(move)))
                    }
                }
            }
            entries.append(Entry(id: "stay-\(stays[index].id)", kind: .stay(stays[index])))
        }

        // Since the last stay ended, nothing at all was recorded for a long stretch.
        if marksUnrecordedGaps, let last = stays.last, !last.isCurrent {
            let lastPoint = points.filter { $0.timestamp >= last.end }.map(\.timestamp).max()
            let silence = now.timeIntervalSince(lastPoint ?? last.end)
            if lastPoint == nil && silence >= Self.unrecordedGapThreshold {
                entries.append(Entry(id: "gap-end", kind: .unrecorded(silence)))
            }
        }

        self.stays = stays
        self.entries = entries

        segments = stays.map { stay in
            Segment(
                id: stay.id,
                start: Self.dayFraction(of: stay.start, startOfDay: startOfDay),
                end: Self.dayFraction(of: stay.end, startOfDay: startOfDay),
                ink: stay.ink
            )
        }

        // Away is measured from the first thing recorded today. With a saved Home it is time anywhere but
        // Home; without one, it is time between saved stays.
        let firstRecorded = [stays.first?.start, points.map(\.timestamp).min()].compactMap { $0 }.min()
        if let firstRecorded {
            let span = max(0, now.timeIntervalSince(firstRecorded))
            let counted = stays.filter { stay in
                guard let home else { return true }
                return stay.visit.map { home.contains($0.coordinate) } ?? true
            }
            let present = counted.reduce(0) { $0 + $1.end.timeIntervalSince($1.start) }
            awayDuration = max(0, span - present)
        } else {
            awayDuration = nil
        }
    }

    /// When the day began at Home and when that stretch ended, if the record shows it: the first points of
    /// the day are inside Home. The stretch ends at the last point inside Home before leaving, at the first
    /// stop, or runs to now.
    static func homeStretch(
        home: SavedPlace,
        firstStay: Stay?,
        points: [LocationPoint],
        startOfDay: Date,
        now: Date
    ) -> (end: Date, isCurrent: Bool)? {
        if let firstStay, firstStay.start <= startOfDay.addingTimeInterval(60) { return nil }
        let limit = firstStay?.start ?? now
        let early = points.filter { $0.timestamp >= startOfDay && $0.timestamp < limit }.sorted { $0.timestamp < $1.timestamp }

        if let first = early.first {
            guard home.contains(first.coordinate) else { return nil }
            if let leftAt = early.firstIndex(where: { !home.contains($0.coordinate) }) {
                let end = early[max(0, leftAt - 1)].timestamp
                return end > startOfDay.addingTimeInterval(60) ? (end, false) : nil
            }
            return firstStay == nil ? (now, true) : (limit, false)
        }

        // Nothing recorded before the first stop. A Home visit running past midnight already starts the day
        // there; without one, there is no sign the night was spent at Home.
        return nil
    }

    /// The most recent earlier day with any stays, newest stay first.
    static func lastRecordedDay(viewModel: LocationViewModel, before startOfToday: Date, now: Date) -> (day: Date, stays: [Stay])? {
        let calendar = Calendar.current
        let earlier = viewModel.allVisits.filter { $0.arrivedAt < startOfToday }
        guard let latest = earlier.max(by: { $0.arrivedAt < $1.arrivedAt }) else { return nil }
        let day = calendar.startOfDay(for: latest.arrivedAt)
        let visits = earlier
            .filter { calendar.isDate($0.arrivedAt, inSameDayAs: day) }
            .sorted { $0.arrivedAt < $1.arrivedAt }

        var inkByPlace: [String: Color] = [:]
        let stays: [Stay] = visits.compactMap { visit in
            let end = min(visit.departedAt ?? now, now)
            guard end > visit.arrivedAt else { return nil }
            let ink = inkByPlace[visit.displayName] ?? TE.placeInks[inkByPlace.count % TE.placeInks.count]
            inkByPlace[visit.displayName] = ink
            return Stay(visit: visit, name: visit.displayName, start: visit.arrivedAt, end: end, isCurrent: false, ink: ink)
        }
        guard !stays.isEmpty else { return nil }
        return (day, Array(stays.reversed()))
    }

    var dialAccessibilityLabel: String {
        guard !stays.isEmpty else {
            return String(localized: "24-hour dial, no places yet today")
        }
        let places = stays.map { stay in
            if stay.visit == nil, !stay.isCurrent {
                let until = stay.end.formatted(date: .omitted, time: .shortened)
                return String(localized: "\(stay.name) until \(until)")
            }
            let time = stay.start.formatted(date: .omitted, time: .shortened)
            return String(localized: "\(stay.name) from \(time)")
        }
        return String(localized: "24-hour dial: \(places.formatted(.list(type: .and)))")
    }

    static func dayFraction(of date: Date, startOfDay: Date? = nil) -> Double {
        let start = startOfDay ?? Calendar.current.startOfDay(for: date)
        return min(max(date.timeIntervalSince(start) / 86_400, 0), 1)
    }

    static func pathDistance(_ window: [LocationPoint]) -> Double {
        zip(window, window.dropFirst()).reduce(0) { $0 + $1.0.distance(to: $1.1) }
    }

    static func formatDuration(_ interval: TimeInterval) -> String {
        let minutes = max(0, Int(interval / 60))
        if minutes < 60 {
            return String(localized: "\(minutes)m")
        }
        return String(format: "%dh %02dm", minutes / 60, minutes % 60)
    }

    /// Moves read as "28 min", stays as "45m".
    nonisolated static func formatMoveDuration(_ interval: TimeInterval) -> String {
        let minutes = max(0, Int(interval / 60))
        if minutes < 60 {
            return String(localized: "\(minutes) min")
        }
        return String(format: "%dh %02dm", minutes / 60, minutes % 60)
    }
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
        locationManager.setDistanceFilter(distanceFilter)
        locationManager.setStopAfterHours(stopAfterHours)
    }
}
