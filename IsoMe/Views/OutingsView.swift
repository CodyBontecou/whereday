import SwiftUI
import SwiftData
import MapKit
import Combine

struct OutingsView: View {
    @Bindable var viewModel: LocationViewModel
    let onShowOnMap: () -> Void

    @State private var calendarDraft: CalendarEventDraft?
    @State private var selectedDate = Date()
    @State private var hasLoadedSessions = false
    @State private var hasAutoSelectedInitialTimelineDate = false

    @AppStorage(RecordingSessionInferenceConfiguration.includesInferredSessionsKey)
    private var includesInferredSessions = RecordingSessionInferenceConfiguration.defaultIncludesInferredSessions
    @AppStorage(RecordingSessionInferenceConfiguration.gapPresetKey)
    private var inferenceGapPresetRawValue = RecordingSessionInferenceConfiguration.defaultGapPreset.rawValue
    @AppStorage(RecordingSessionInferenceConfiguration.minimumDurationPresetKey)
    private var inferenceMinimumDurationPresetRawValue = RecordingSessionInferenceConfiguration.defaultMinimumDurationPreset.rawValue
    @AppStorage(RecordingSessionInferenceConfiguration.minimumPointCountKey)
    private var inferenceMinimumPointCount = RecordingSessionInferenceConfiguration.defaultMinimumPointCountPreset.rawValue

    private var selectedDayRange: ClosedRange<Date> {
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: selectedDate)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(24 * 60 * 60)
        return start...end
    }

    private var selectedDayTitle: String {
        if Calendar.current.isDateInToday(selectedDate) {
            return String(localized: "Today")
        }

        return selectedDate.formatted(date: .complete, time: .omitted)
    }

    private var selectedDaySubtitle: String {
        selectedDayRange.lowerBound.formatted(.dateTime.weekday(.wide).month(.abbreviated).day().year())
    }

    private var dayResetButtonTitle: String {
        let calendar = Calendar.current
        guard !calendar.isDateInToday(selectedDate) else {
            return String(localized: "TODAY")
        }

        let selectedDay = calendar.startOfDay(for: selectedDate)
        let today = calendar.startOfDay(for: Date())
        let dayOffset = calendar.dateComponents([.day], from: selectedDay, to: today).day ?? 0
        guard dayOffset != 0 else { return String(localized: "TODAY") }

        var formatter = RelativeDateTimeFormatter()
        formatter.dateTimeStyle = .numeric
        formatter.unitsStyle = .full
        return formatter.localizedString(from: DateComponents(day: -dayOffset))
    }

    private var allSessions: [RecordingSessionSummary] {
        viewModel.recordingSessionSummaries(inferenceConfiguration: inferenceConfiguration)
    }

    private var sessions: [RecordingSessionSummary] {
        allSessions.filter { rangesOverlap($0.dateRange, selectedDayRange) }
    }

    private var dayVisits: [Visit] {
        viewModel.visitsOverlappingDateRange(selectedDayRange)
    }

    private var timelineEvents: [TimelineEvent] {
        let events = sessions.map(TimelineEvent.session) + dayVisits.map(TimelineEvent.visit)
        return events.sorted { lhs, rhs in
            if lhs.startDate == rhs.startDate {
                return lhs.sortPriority < rhs.sortPriority
            }
            return lhs.startDate < rhs.startDate
        }
    }

    private var inferenceConfiguration: RecordingSessionInferenceConfiguration {
        RecordingSessionInferenceConfiguration(
            includesInferredSessions: includesInferredSessions,
            gapThreshold: inferenceGapPreset.seconds,
            minimumDuration: inferenceMinimumDurationPreset.seconds,
            minimumPointCount: inferenceMinimumPointCount
        )
    }

    private var inferenceGapPreset: RecordingSessionGapPreset {
        RecordingSessionGapPreset(rawValue: inferenceGapPresetRawValue) ?? RecordingSessionInferenceConfiguration.defaultGapPreset
    }

    private var inferenceMinimumDurationPreset: RecordingSessionMinimumDurationPreset {
        RecordingSessionMinimumDurationPreset(rawValue: inferenceMinimumDurationPresetRawValue) ?? RecordingSessionInferenceConfiguration.defaultMinimumDurationPreset
    }

    private var totalDistance: Double {
        sessions.reduce(0) { $0 + $1.distanceMeters }
    }

    private var latestActivityDate: Date? {
        let sessionDates = viewModel.allRecordingSessions.flatMap { session -> [Date] in
            var dates = [session.startedAt]
            if let endedAt = session.endedAt {
                dates.append(endedAt)
            }
            return dates
        }
        let visitDates = viewModel.allVisits.map(\.arrivedAt)
        let pointDates = viewModel.locationPoints.last.map { [$0.timestamp] } ?? []
        return (sessionDates + visitDates + pointDates).max()
    }

    private var hasAnyTimelineData: Bool {
        viewModel.totalLocationPointCount > 0 || !viewModel.allVisits.isEmpty || !viewModel.allRecordingSessions.isEmpty
    }

    var body: some View {
        NavigationStack {
            ZStack {
                TE.surface.ignoresSafeArea()

                if !hasLoadedSessions && hasAnyTimelineData {
                    ProgressView("Building timeline…")
                        .font(TE.mono(.caption, weight: .medium))
                        .foregroundStyle(TE.textMuted)
                } else {
                    ScrollView {
                        VStack(spacing: 0) {
                            overviewSection
                            countryDaysSection
                            dayControlsSection

                            if timelineEvents.isEmpty {
                                emptyTimelineSection
                            } else {
                                timelineSection
                            }
                        }
                        .padding(.bottom, 28)
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("TIMELINE")
                        .font(TE.mono(.caption, weight: .bold))
                        .tracking(3)
                        .foregroundStyle(TE.textMuted)
                }
            }
            .sheet(item: $calendarDraft) { draft in
                CalendarEventEditor(draft: draft)
            }
            .task {
                refreshTimelineData()
                selectInitialTimelineDateIfNeeded()
            }
            .onReceive(NotificationCenter.default.publisher(for: .appDidBecomeActive)) { _ in
                refreshTimelineData()
                selectInitialTimelineDateIfNeeded()
            }
        }
    }

    private var countryDaysSection: some View {
        TECard {
            NavigationLink {
                CountryDaysView()
            } label: {
                TERow(showDivider: false) {
                    HStack(spacing: 12) {
                        Image(systemName: "globe.europe.africa")
                            .foregroundStyle(TE.accent)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Country Days")
                                .font(TE.mono(.subheadline, weight: .semibold))
                                .foregroundStyle(TE.textPrimary)
                            Text("Review days present in each country.")
                                .font(.caption)
                                .foregroundStyle(TE.textMuted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(TE.textMuted)
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
    }

    private var overviewSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "DAY")

            TECard {
                VStack(spacing: 0) {
                    TERow {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(selectedDayTitle.uppercased())
                                .font(TE.mono(.headline, weight: .bold))
                                .tracking(1.6)
                                .foregroundStyle(TE.textPrimary)

                            Text(selectedDaySubtitle)
                                .font(TE.mono(.caption2, weight: .medium))
                                .foregroundStyle(TE.textMuted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    TERow(showDivider: false) {
                        HStack(spacing: 12) {
                            OutingOverviewMetric(
                                label: "MOVES",
                                value: "\(sessions.count)",
                                systemImage: "figure.walk.motion"
                            )

                            OutingOverviewMetric(
                                label: "VISITS",
                                value: "\(dayVisits.count)",
                                systemImage: "mappin.and.ellipse"
                            )

                            OutingOverviewMetric(
                                label: "DISTANCE",
                                value: DistanceFormatter.format(meters: totalDistance, usesMetric: usesMetricDistanceUnits),
                                systemImage: "point.topleft.down.to.point.bottomright.curvepath"
                            )
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Timeline combines visits and movement sessions for the selected day. Movement comes from exact recordings plus optional inferred GPS outings.")
        }
    }

    private var dayControlsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "CONTROLS")

            TECard {
                VStack(spacing: 0) {
                    TERow {
                        HStack(spacing: 10) {
                            Button {
                                moveSelectedDay(by: -1)
                            } label: {
                                Image(systemName: "chevron.left")
                                    .font(.caption.weight(.bold))
                                    .frame(width: 34, height: 34)
                                    .background(TE.textMuted.opacity(0.10), in: RoundedRectangle(cornerRadius: 4))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Previous day")

                            Button {
                                selectedDate = Date()
                            } label: {
                                Text(dayResetButtonTitle)
                                    .font(TE.mono(.caption2, weight: .bold))
                                    .tracking(1.2)
                                    .foregroundStyle(TE.accent)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.75)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 34)
                                    .background(TE.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 4))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(dayResetButtonTitle)
                            .accessibilityHint(
                                Calendar.current.isDateInToday(selectedDate)
                                    ? ""
                                    : String(localized: "JUMP TO TODAY")
                            )

                            Button {
                                moveSelectedDay(by: 1)
                            } label: {
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.bold))
                                    .frame(width: 34, height: 34)
                                    .background(TE.textMuted.opacity(0.10), in: RoundedRectangle(cornerRadius: 4))
                            }
                            .buttonStyle(.plain)
                            .disabled(isSelectedDayTodayOrFuture)
                            .opacity(isSelectedDayTodayOrFuture ? 0.35 : 1)
                            .accessibilityLabel("Next day")
                        }
                        .foregroundStyle(TE.textPrimary)
                    }

                    TERow {
                        DatePicker("DATE", selection: $selectedDate, displayedComponents: .date)
                            .font(TE.mono(.caption, weight: .medium))
                            .foregroundStyle(TE.textPrimary)
                            .tint(TE.accent)
                    }

                    TERow(showDivider: includesInferredSessions) {
                        Toggle(isOn: $includesInferredSessions) {
                            Text("INFERRED MOVEMENT")
                                .font(TE.mono(.caption, weight: .medium))
                                .tracking(1)
                                .foregroundStyle(TE.textPrimary)
                        }
                        .toggleStyle(TEToggleStyle())
                    }

                    if includesInferredSessions {
                        TERow(showDivider: false) {
                            HStack(spacing: 12) {
                                Text("SPLIT AFTER")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)

                                Spacer()

                                Menu {
                                    ForEach(RecordingSessionGapPreset.allCases) { option in
                                        Button {
                                            inferenceGapPresetRawValue = option.rawValue
                                        } label: {
                                            if inferenceGapPreset == option {
                                                Label(option.settingsLabel, systemImage: "checkmark")
                                            } else {
                                                Text(option.settingsLabel)
                                            }
                                        }
                                    }
                                } label: {
                                    HStack(spacing: 6) {
                                        Text(inferenceGapPreset.label.uppercased())
                                        Image(systemName: "chevron.up.chevron.down")
                                            .font(.caption2.weight(.bold))
                                    }
                                    .font(TE.mono(.caption2, weight: .semibold))
                                    .tracking(1)
                                    .foregroundStyle(TE.accent)
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Use inferred movement to fill older days from GPS points that were saved before exact start/stop sessions existed.")
        }
    }

    private var timelineSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "EVENTS")

            LazyVStack(spacing: 10) {
                ForEach(timelineEvents) { event in
                    switch event {
                    case .session(let session):
                        NavigationLink {
                            RecordingSessionDetailView(
                                session: session,
                                viewModel: viewModel,
                                onShowOnMap: onShowOnMap
                            )
                        } label: {
                            TimelineMovementCard(
                                session: session,
                                usesMetricDistanceUnits: usesMetricDistanceUnits
                            )
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("Add Event to Calendar", systemImage: "calendar.badge.plus") {
                                calendarDraft = CalendarEventDraft(session: session)
                            }
                        }

                    case .visit(let visit):
                        NavigationLink {
                            VisitDetailView(visit: visit, viewModel: viewModel)
                        } label: {
                            TimelineVisitCard(visit: visit, isCurrent: viewModel.isCurrentVisit(visit))
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("Add Event to Calendar", systemImage: "calendar.badge.plus") {
                                calendarDraft = CalendarEventDraft(visit: visit)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var emptyTimelineSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "EVENTS")

            TECard {
                emptyState
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 16)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 18) {
            Image(systemName: "calendar")
                .font(.largeTitle.weight(.light))
                .foregroundStyle(TE.textMuted)

            Text("NO TIMELINE EVENTS")
                .font(TE.mono(.caption, weight: .bold))
                .tracking(2)
                .foregroundStyle(TE.textPrimary)

            Text(emptyStateMessage)
                .font(TE.mono(.caption2, weight: .medium))
                .foregroundStyle(TE.textMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, 24)

            if let emptyStateActionLabel {
                Button {
                    performEmptyStateAction()
                } label: {
                    Text(emptyStateActionLabel)
                        .font(TE.mono(.caption2, weight: .bold))
                        .tracking(1.4)
                        .foregroundStyle(TE.accent)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 34)
    }

    private var emptyStateActionLabel: String? {
        if let latestActivityDate,
           !Calendar.current.isDate(latestActivityDate, inSameDayAs: selectedDate) {
            return String(localized: "JUMP TO LATEST DATA")
        }

        if !Calendar.current.isDateInToday(selectedDate) {
            return String(localized: "JUMP TO TODAY")
        }

        return nil
    }

    private func performEmptyStateAction() {
        if let latestActivityDate,
           !Calendar.current.isDate(latestActivityDate, inSameDayAs: selectedDate) {
            selectedDate = latestActivityDate
            return
        }

        selectedDate = Date()
    }

    private var emptyStateMessage: String {
        if viewModel.locationManager.isTrackingEnabled, Calendar.current.isDateInToday(selectedDate) {
            return String(localized: "Tracking is active. New movement will appear here after Whereday saves the first location point.")
        }

        if hasAnyTimelineData {
            return String(localized: "No visits or movement sessions were found for \(selectedDaySubtitle). Pick another day or enable inferred movement for older GPS history.")
        }

        return String(localized: "Start tracking from the Map tab or your Shortcuts automation. Visits and movement sessions will appear here by day.")
    }

    private var usesMetricDistanceUnits: Bool {
        let key = "usesMetricDistanceUnits"
        if UserDefaults.standard.object(forKey: key) == nil { return true }
        return UserDefaults.standard.bool(forKey: key)
    }

    private var isSelectedDayTodayOrFuture: Bool {
        let calendar = Calendar.current
        return calendar.startOfDay(for: selectedDate) >= calendar.startOfDay(for: Date())
    }

    private func refreshTimelineData() {
        viewModel.loadAllVisits()
        viewModel.loadRecordingSessions()
        viewModel.ensureAllLocationPointsLoaded()
        hasLoadedSessions = true
    }

    private func selectInitialTimelineDateIfNeeded() {
        guard !hasAutoSelectedInitialTimelineDate else { return }
        hasAutoSelectedInitialTimelineDate = true

        guard timelineEvents.isEmpty,
              let latestActivityDate,
              !Calendar.current.isDate(latestActivityDate, inSameDayAs: selectedDate) else {
            return
        }

        selectedDate = latestActivityDate
    }

    private func moveSelectedDay(by value: Int) {
        selectedDate = Calendar.current.date(byAdding: .day, value: value, to: selectedDate) ?? selectedDate
    }

    private func rangesOverlap(_ lhs: ClosedRange<Date>, _ rhs: ClosedRange<Date>) -> Bool {
        lhs.lowerBound <= rhs.upperBound && rhs.lowerBound <= lhs.upperBound
    }
}

private enum TimelineEvent: Identifiable {
    case session(RecordingSessionSummary)
    case visit(Visit)

    var id: String {
        switch self {
        case .session(let session): return "session-\(session.id)"
        case .visit(let visit): return "visit-\(visit.id.uuidString)"
        }
    }

    var startDate: Date {
        switch self {
        case .session(let session): return session.startedAt
        case .visit(let visit): return visit.arrivedAt
        }
    }

    var sortPriority: Int {
        switch self {
        case .visit: return 0
        case .session: return 1
        }
    }
}

private struct OutingOverviewMetric: View {
    let label: LocalizedStringKey
    let value: String
    let systemImage: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 5) {
                Image(systemName: systemImage)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(TE.accent)
                    .accessibilityHidden(true)

                Text(label)
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(1)
                    .foregroundStyle(TE.textMuted)
            }

            Text(value)
                .font(TE.mono(.caption, weight: .bold))
                .foregroundStyle(TE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct TimelineMovementCard: View {
    let session: RecordingSessionSummary
    let usesMetricDistanceUnits: Bool

    var body: some View {
        TECard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: session.isInferred ? "wand.and.stars" : "figure.walk.motion")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(session.isInferred ? TE.warning : TE.accent)
                        .frame(width: 28, height: 28)
                        .background((session.isInferred ? TE.warning : TE.accent).opacity(0.12), in: Circle())
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text("MOVEMENT")
                                .font(TE.mono(.caption2, weight: .bold))
                                .tracking(1.4)
                                .foregroundStyle(TE.textMuted)

                            if session.isActive {
                                OutingBadge(text: String(localized: "LIVE"), color: TE.success)
                            } else if session.isInferred {
                                OutingBadge(text: String(localized: "INFERRED"), color: TE.warning)
                            }
                        }

                        Text(session.title.uppercased())
                            .font(TE.mono(.caption, weight: .bold))
                            .tracking(1.1)
                            .foregroundStyle(TE.textPrimary)
                            .lineLimit(1)

                        Text(session.formattedTimeRange)
                            .font(TE.mono(.caption2, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                            .lineLimit(2)
                    }

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(TE.textMuted.opacity(0.55))
                        .padding(.top, 2)
                }

                HStack(spacing: 8) {
                    OutingStatChip(
                        label: "TIME",
                        value: RecordingSessionFormatter.duration(session.duration),
                        systemImage: "clock"
                    )
                    OutingStatChip(
                        label: "DISTANCE",
                        value: DistanceFormatter.format(meters: session.distanceMeters, usesMetric: usesMetricDistanceUnits),
                        systemImage: "ruler"
                    )
                    OutingStatChip(
                        label: "POINTS",
                        value: "\(session.pointCount)",
                        systemImage: "smallcircle.filled.circle"
                    )
                }
            }
            .padding(14)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Movement, \(session.title)")
        .accessibilityValue(session.accessibilityValue)
        .accessibilityHint("Opens movement details.")
    }
}

private struct TimelineVisitCard: View {
    let visit: Visit
    let isCurrent: Bool

    var body: some View {
        TECard {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: isCurrent ? "mappin.circle.fill" : "mappin.and.ellipse")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(isCurrent ? TE.accent : TE.danger)
                    .frame(width: 28, height: 28)
                    .background((isCurrent ? TE.accent : TE.danger).opacity(0.12), in: Circle())
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text("VISIT")
                            .font(TE.mono(.caption2, weight: .bold))
                            .tracking(1.4)
                            .foregroundStyle(TE.textMuted)

                        if isCurrent {
                            OutingBadge(text: String(localized: "NOW"), color: TE.success)
                        }

                        OutingBadge(text: visit.confirmationStatus.displayName.uppercased(), color: statusColor)

                        if visit.source == .manual {
                            OutingBadge(text: String(localized: "MANUAL"), color: TE.accent)
                        }
                    }

                    Text(visit.displayName.uppercased())
                        .font(TE.mono(.caption, weight: .bold))
                        .tracking(1.1)
                        .foregroundStyle(TE.textPrimary)
                        .lineLimit(1)

                    Text("\(visit.formattedTimeRange) • \(visit.formattedDuration)")
                        .font(TE.mono(.caption2, weight: .medium))
                        .foregroundStyle(TE.textMuted)
                        .lineLimit(2)

                    if let address = visit.address, !address.isEmpty, address != visit.displayName {
                        Text(address)
                            .font(TE.mono(.caption2, weight: .medium))
                            .foregroundStyle(TE.textMuted.opacity(0.82))
                            .lineLimit(1)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(TE.textMuted.opacity(0.55))
                    .padding(.top, 2)
            }
            .padding(14)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(visit.accessibilityLabel)
        .accessibilityValue("\(visit.accessibilityValue). Status \(visit.confirmationStatus.displayName). Source \(visit.source.displayName).")
        .accessibilityHint("Opens visit details.")
    }

    private var statusColor: Color {
        switch visit.confirmationStatus {
        case .unconfirmed: return TE.warning
        case .confirmed: return TE.success
        case .corrected: return TE.accent
        }
    }
}

private struct RecordingSessionCard: View {
    let session: RecordingSessionSummary
    let usesMetricDistanceUnits: Bool

    var body: some View {
        TECard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(session.title.uppercased())
                                .font(TE.mono(.caption, weight: .bold))
                                .tracking(1.4)
                                .foregroundStyle(TE.textPrimary)
                                .lineLimit(1)

                            if session.isActive {
                                OutingBadge(text: String(localized: "LIVE"), color: TE.success)
                            } else if session.isInferred {
                                OutingBadge(text: String(localized: "INFERRED"), color: TE.warning)
                            }
                        }

                        Text(session.formattedTimeRange)
                            .font(TE.mono(.caption2, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                            .lineLimit(2)
                    }

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(TE.textMuted.opacity(0.55))
                        .padding(.top, 2)
                }

                HStack(spacing: 8) {
                    OutingStatChip(
                        label: "DURATION",
                        value: RecordingSessionFormatter.duration(session.duration),
                        systemImage: "clock"
                    )
                    OutingStatChip(
                        label: "DISTANCE",
                        value: DistanceFormatter.format(meters: session.distanceMeters, usesMetric: usesMetricDistanceUnits),
                        systemImage: "ruler"
                    )
                    OutingStatChip(
                        label: "POINTS",
                        value: "\(session.pointCount)",
                        systemImage: "smallcircle.filled.circle"
                    )
                }
            }
            .padding(14)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(session.title)
        .accessibilityValue(session.accessibilityValue)
        .accessibilityHint("Opens outing details.")
    }
}

private struct RecordingSessionDetailView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    let session: RecordingSessionSummary
    @Bindable var viewModel: LocationViewModel
    let onShowOnMap: () -> Void

    @StateObject private var exportFolderManager = ExportFolderManager.shared
    @ObservedObject private var storeManager = StoreManager.shared
    @State private var nameText = ""
    @State private var notesText = ""
    @State private var isRouteReplayEnabled = false
    @State private var isRouteReplayPlaying = false
    @State private var routeReplayProgress: Double = 1.0
    @State private var roadSnappedRoute: RoadSnappedRoute?
    @State private var showingPaywall = false
    @State private var showingDeleteConfirmation = false
    @State private var isSyncingPhotos = false
    @State private var selectedPhotoMoment: PhotoMoment?
    @FocusState private var isNameFieldFocused: Bool
    @FocusState private var isNotesFieldFocused: Bool

    @AppStorage("useDefaultExportFolder") private var useDefaultExportFolder = true
    @AppStorage("exportFilenamePattern") private var filenamePattern = FilenameTemplate.defaultPattern
    @AppStorage("outingDetailExportFormat") private var outingDetailExportFormatToken = ExportFormat.markdown.token
    @AppStorage("snapTravelPathToRoads") private var snapTravelPathToRoads = true
    @AppStorage("showStraightLinePathSegments") private var showStraightLinePathSegments = false
    @AppStorage(LocationViewModel.showPhotoMarkersKey) private var showPhotoMarkersOnMap = false

    private let routeReplayTimer = Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()

    private var visits: [Visit] {
        viewModel.visitsOverlappingDateRange(session.dateRange)
    }

    private var photoMoments: [PhotoMoment] {
        viewModel.photosInDateRange(session.dateRange)
    }

    private var routeReplayPoints: [LocationPoint] {
        session.distancePoints
    }

    private var canReplayRoute: Bool {
        routeReplayPoints.count >= 2
    }

    private var routeReplaySnapshot: RouteReplaySnapshot? {
        RouteReplayCalculator.snapshot(points: routeReplayPoints, progress: routeReplayProgress)
    }

    private var roadSnappingSourceFingerprint: Int {
        RoadSnappedRouteBuilder.fingerprint(for: routeReplayPoints)
    }

    private var roadSnappingTaskKey: Int {
        RoadSnappedRouteBuilder.taskFingerprint(
            for: routeReplayPoints,
            isEnabled: snapTravelPathToRoads
        )
    }

    private var preparedRoadSnappedRoute: RoadSnappedRoute? {
        guard snapTravelPathToRoads,
              let roadSnappedRoute,
              roadSnappedRoute.sourceFingerprint == roadSnappingSourceFingerprint,
              roadSnappedRoute.sourcePointCount == routeReplayPoints.count else {
            return nil
        }

        return roadSnappedRoute
    }

    private var activeRoadSnappedRoute: RoadSnappedRoute? {
        guard let preparedRoadSnappedRoute,
              preparedRoadSnappedRoute.hasSnappedSegments else {
            return nil
        }

        return preparedRoadSnappedRoute
    }

    private var isPreparingRoadRoute: Bool {
        snapTravelPathToRoads && canReplayRoute && preparedRoadSnappedRoute == nil && !showStraightLinePathSegments
    }

    private var shouldDrawStraightLinePath: Bool {
        showStraightLinePathSegments || !snapTravelPathToRoads
    }

    private var usesMetricDistanceUnits: Bool {
        let key = "usesMetricDistanceUnits"
        if UserDefaults.standard.object(forKey: key) == nil { return true }
        return UserDefaults.standard.bool(forKey: key)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                headerSection

                if session.points.isEmpty {
                    noPointsSection
                } else {
                    mapSection
                }

                statsSection

                if let storedSession = session.storedSession {
                    editableDetailsSection(storedSession)
                } else {
                    inferredSection
                }

                visitsSection

                photoMomentsSection

                actionSection
            }
            .padding(.bottom, 28)
        }
        .background(TE.surface.ignoresSafeArea())
        .navigationTitle(session.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            nameText = session.storedSession?.displayName(defaultName: session.defaultTitle) ?? session.title
            notesText = session.storedSession?.notes ?? ""
            validateRouteReplayState()
            syncPhotoMomentsIfAllowed()
        }
        .onDisappear {
            isRouteReplayPlaying = false
        }
        .onReceive(routeReplayTimer) { _ in
            advanceRouteReplayIfNeeded()
        }
        .onReceive(NotificationCenter.default.publisher(for: .appDidBecomeActive)) { _ in
            viewModel.refreshPhotoLibraryAuthorizationState()
            syncPhotoMomentsIfAllowed()
        }
        .task(id: roadSnappingTaskKey) {
            await refreshRoadSnappedRoute()
        }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") {
                    isNameFieldFocused = false
                    isNotesFieldFocused = false
                    saveEditableDetails()
                }
            }
        }
        .sheet(isPresented: $showingPaywall) {
            PaywallView(storeManager: storeManager, context: .export)
        }
        .alert("Delete Outing?", isPresented: $showingDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                viewModel.deleteRecordingSession(session)
                dismiss()
            }
        } message: {
            Text(deleteConfirmationMessage)
        }
        .sheet(item: $selectedPhotoMoment) { photo in
            PhotoMomentQuickView(photo: photo)
                .presentationDetents([.medium, .large])
        }
    }

    private var headerSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "OUTING")

            TECard {
                TERow(showDivider: false) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 6) {
                            Text(session.title.uppercased())
                                .font(TE.mono(.headline, weight: .bold))
                                .tracking(1.4)
                                .foregroundStyle(TE.textPrimary)
                                .lineLimit(2)

                            if session.isActive {
                                OutingBadge(text: String(localized: "LIVE"), color: TE.success)
                            } else if session.isInferred {
                                OutingBadge(text: String(localized: "INFERRED"), color: TE.warning)
                            }
                        }

                        Text(session.formattedTimeRange)
                            .font(TE.mono(.caption, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var mapSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "PATH")

            SessionPathMapView(
                points: routeReplayPoints,
                replaySnapshot: isRouteReplayEnabled ? routeReplaySnapshot : nil,
                roadSnappedRoute: activeRoadSnappedRoute,
                photoMoments: photoMoments,
                showsStraightLineSegments: showStraightLinePathSegments,
                showsRawPathFallback: shouldDrawStraightLinePath
            )
            .frame(height: 260)
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .overlay {
                RoundedRectangle(cornerRadius: 4)
                    .strokeBorder(TE.border, lineWidth: 1)
            }
            .padding(.horizontal, 16)

            if isPreparingRoadRoute {
                routeSnappingStatus
                    .padding(.horizontal, 16)
                    .padding(.top, 10)
            }

            if canReplayRoute {
                routeReplayControls
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
            }
        }
    }

    private var routeSnappingStatus: some View {
        HStack(spacing: 8) {
            ProgressView()
                .controlSize(.mini)
                .tint(TE.accent)

            Text("MATCHING PATH TO ROADS…")
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)

            Spacer()
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .background(TE.textMuted.opacity(0.08), in: RoundedRectangle(cornerRadius: 4))
    }

    @ViewBuilder
    private var routeReplayControls: some View {
        if isRouteReplayEnabled, let routeReplaySnapshot {
            RouteReplayControl(
                snapshot: routeReplaySnapshot,
                pointCount: routeReplayPoints.count,
                progress: Binding(
                    get: { routeReplayProgress },
                    set: { routeReplayProgress = RouteReplayCalculator.clampedProgress($0) }
                ),
                isPlaying: isRouteReplayPlaying,
                onPlayPause: toggleRouteReplayPlayback,
                onScrub: { isRouteReplayPlaying = false },
                onClose: disableRouteReplay
            )
        } else {
            Button(action: startRouteReplay) {
                HStack(spacing: 8) {
                    Image(systemName: "play.fill")
                        .font(.caption.weight(.bold))

                    Text("PLAY OUTING")
                        .font(TE.mono(.caption, weight: .bold))
                        .tracking(2)
                }
                .foregroundStyle(TE.accent)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(TE.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 4))
                .overlay {
                    RoundedRectangle(cornerRadius: 4)
                        .strokeBorder(TE.accent.opacity(0.35), lineWidth: 1)
                }
            }
            .buttonStyle(.plain)
            .disabled(isPreparingRoadRoute)
            .opacity(isPreparingRoadRoute ? 0.55 : 1)
            .accessibilityHint(isPreparingRoadRoute
                ? "Wait for the outing path to be matched to roads."
                : "Opens route replay controls for this outing.")
        }
    }

    private var noPointsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "PATH")

            TECard {
                TERow(showDivider: false) {
                    HStack(spacing: 10) {
                        Image(systemName: "location.slash")
                            .foregroundStyle(TE.textMuted)
                        Text("No GPS points were saved for this outing yet.")
                            .font(TE.mono(.caption, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                        Spacer()
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var statsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "STATS")

            TECard {
                VStack(spacing: 0) {
                    detailRow(label: "START", value: session.startedAt.formatted(date: .abbreviated, time: .shortened))
                    detailRow(label: session.isActive ? "LATEST" : "END", value: session.effectiveEndDate.formatted(date: .abbreviated, time: .shortened))
                    detailRow(label: "DURATION", value: RecordingSessionFormatter.duration(session.duration))
                    detailRow(label: "DISTANCE", value: DistanceFormatter.format(meters: session.distanceMeters, usesMetric: usesMetricDistanceUnits))
                    detailRow(label: "POINTS", value: "\(session.pointCount)")
                    if session.outlierCount > 0 {
                        detailRow(label: "GPS GLITCHES", value: "\(session.outlierCount)")
                    }
                    if let averageSpeed = session.averageSpeedMetersPerSecond {
                        detailRow(label: "AVG SPEED", value: formattedSpeed(averageSpeed), showDivider: false)
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func editableDetailsSection(_ storedSession: RecordingSession) -> some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "DETAILS")

            TECard {
                VStack(spacing: 0) {
                    TERow {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("NAME")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)

                            TextField("Outing name", text: $nameText)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                                .textFieldStyle(.plain)
                                .submitLabel(.done)
                                .focused($isNameFieldFocused)
                                .onSubmit(saveEditableDetails)
                                .onChange(of: isNameFieldFocused) { _, focused in
                                    if !focused { saveEditableDetails() }
                                }
                        }
                    }

                    TERow(showDivider: false) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("NOTES")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)

                            TextField("Add notes", text: $notesText, axis: .vertical)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                                .textFieldStyle(.plain)
                                .lineLimit(2...5)
                                .focused($isNotesFieldFocused)
                                .onChange(of: isNotesFieldFocused) { _, focused in
                                    if !focused { saveEditableDetails() }
                                }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: storedSession.isActive
                ? LocalizedStringKey("This outing is currently being recorded.")
                : LocalizedStringKey("Names and notes stay on-device with the recording session."))
        }
    }

    private var inferredSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "DETAILS")

            TECard {
                TERow(showDivider: false) {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "wand.and.stars")
                            .foregroundStyle(TE.warning)
                            .accessibilityHidden(true)

                        Text("This outing was auto-inferred from your GPS history using your inferred outing settings. New Shortcut start/stop recordings are saved exactly and can be named.")
                            .font(TE.mono(.caption2, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var visitsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "VISITS")

            TECard {
                if visits.isEmpty {
                    TERow(showDivider: false) {
                        Text("No visits were detected during this outing.")
                            .font(TE.mono(.caption, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(visits.enumerated()), id: \.element.id) { index, visit in
                            TERow(showDivider: index != visits.count - 1) {
                                NavigationLink {
                                    VisitDetailView(visit: visit, viewModel: viewModel)
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: "mappin.circle.fill")
                                            .foregroundStyle(viewModel.isCurrentVisit(visit) ? TE.accent : TE.danger)
                                            .accessibilityHidden(true)

                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(visit.displayName.uppercased())
                                                .font(TE.mono(.caption, weight: .semibold))
                                                .tracking(0.8)
                                                .foregroundStyle(TE.textPrimary)
                                                .lineLimit(1)

                                            Text("\(visit.formattedTimeRange) • \(visit.formattedDuration)")
                                                .font(TE.mono(.caption2, weight: .medium))
                                                .foregroundStyle(TE.textMuted)
                                        }

                                        Spacer()

                                        Image(systemName: "chevron.right")
                                            .font(.caption2.weight(.bold))
                                            .foregroundStyle(TE.textMuted.opacity(0.55))
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var photoMomentsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "PHOTOS")

            TECard {
                VStack(spacing: 0) {
                    if !viewModel.photoLibraryAccessState.canRead {
                        photoAccessPromptRow
                    } else if photoMoments.isEmpty {
                        emptyPhotosRow
                    } else {
                        photoStripRow
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: photoSectionFooterText)
        }
    }

    private var photoAccessPromptRow: some View {
        TERow(showDivider: false) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "camera.fill")
                        .foregroundStyle(TE.accent)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("CONNECT PHOTOS")
                            .font(TE.mono(.caption, weight: .bold))
                            .tracking(1.2)
                            .foregroundStyle(TE.textPrimary)

                        Text(viewModel.photoLibraryAccessState.explanation)
                            .font(TE.mono(.caption2, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                Button(action: requestPhotoAccessForOuting) {
                    HStack(spacing: 8) {
                        if isSyncingPhotos {
                            ProgressView()
                                .controlSize(.mini)
                                .tint(.white)
                        } else {
                            Image(systemName: "photo.on.rectangle")
                                .font(.caption.weight(.bold))
                        }

                        Text(isSyncingPhotos ? "SYNCING PHOTOS" : "CONNECT PHOTOS")
                            .font(TE.mono(.caption, weight: .bold))
                            .tracking(2)
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 42)
                    .background(TE.accent)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                }
                .buttonStyle(.plain)
                .disabled(isSyncingPhotos)
            }
        }
    }

    private var emptyPhotosRow: some View {
        TERow(showDivider: false) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: isSyncingPhotos ? "arrow.triangle.2.circlepath" : "photo")
                    .foregroundStyle(TE.textMuted)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 6) {
                    Text(isSyncingPhotos ? "SYNCING PHOTOS…" : "NO MATCHED PHOTOS")
                        .font(TE.mono(.caption, weight: .bold))
                        .tracking(1.2)
                        .foregroundStyle(TE.textPrimary)

                    Text(isSyncingPhotos ? "Checking your Photos library for pictures taken during this outing." : "No photos with GPS metadata or a nearby Whereday route/visit match were found during this outing.")
                        .font(TE.mono(.caption2, weight: .medium))
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)

                    if !isSyncingPhotos {
                        Button("REFRESH PHOTOS") {
                            syncPhotoMomentsIfAllowed()
                        }
                        .font(TE.mono(.caption2, weight: .bold))
                        .tracking(1.2)
                        .foregroundStyle(TE.accent)
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private var photoStripRow: some View {
        TERow(showDivider: false) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    Image(systemName: "camera.fill")
                        .foregroundStyle(TE.accent)
                        .accessibilityHidden(true)

                    Text("\(photoMoments.count) MATCHED \(photoMoments.count == 1 ? "PHOTO" : "PHOTOS")")
                        .font(TE.mono(.caption, weight: .bold))
                        .tracking(1.2)
                        .foregroundStyle(TE.textPrimary)

                    Spacer()

                    Button("REFRESH") {
                        syncPhotoMomentsIfAllowed()
                    }
                    .font(TE.mono(.caption2, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(TE.accent)
                    .buttonStyle(.plain)
                    .disabled(isSyncingPhotos)
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(photoMoments) { photo in
                            Button {
                                selectedPhotoMoment = photo
                            } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    PhotoThumbnailView(
                                        assetLocalIdentifier: photo.assetLocalIdentifier,
                                        targetPointSize: CGSize(width: 88, height: 88),
                                        cornerRadius: 5
                                    )

                                    Text(photo.takenAt.formatted(date: .omitted, time: .shortened))
                                        .font(TE.mono(.caption2, weight: .medium))
                                        .foregroundStyle(TE.textMuted)
                                        .monospacedDigit()
                                        .lineLimit(1)
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(photo.accessibilityLabel)
                            .accessibilityValue(photo.accessibilityValue)
                        }
                    }
                    .padding(.trailing, 8)
                }
            }
        }
    }

    private var photoSectionFooterText: LocalizedStringKey {
        if viewModel.photoLibraryAccessState == .limited {
            return "Limited Photos access is enabled, so only selected photos can appear."
        }
        if viewModel.photoLibraryAccessState.canRead {
            return "Photos are matched by timestamp. Whereday uses photo GPS when available, otherwise the nearest route point or visit. Photo files stay in Photos."
        }
        return "Connecting Photos lets Whereday show pictures taken during this outing."
    }

    private var actionSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "ACTIONS")

            TECard {
                TERow(showDivider: false) {
                    HStack(spacing: 12) {
                        Text("FORMAT")
                            .font(TE.mono(.caption, weight: .medium))
                            .tracking(1)
                            .foregroundStyle(TE.textPrimary)

                        Spacer()

                        Menu {
                            ForEach(ExportFormat.allCases, id: \.token) { format in
                                Button {
                                    outingDetailExportFormatToken = format.token
                                } label: {
                                    if outingExportFormat == format {
                                        Label(format.displayName, systemImage: "checkmark")
                                    } else {
                                        Text(format.displayName)
                                    }
                                }
                            }
                        } label: {
                            HStack(spacing: 6) {
                                Text(outingExportFormat.displayName.uppercased())
                                Image(systemName: "chevron.up.chevron.down")
                                    .font(.caption2.weight(.bold))
                            }
                            .font(TE.mono(.caption2, weight: .semibold))
                            .tracking(1)
                            .foregroundStyle(TE.accent)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            VStack(spacing: 10) {
                AddEventToCalendarButton { CalendarEventDraft(session: session) }
                    .tint(TE.accent)

                Button {
                    showPhotoMarkersOnMap = true
                    viewModel.focusMap(on: session)
                    onShowOnMap()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "map.fill")
                            .font(.caption.weight(.bold))
                        Text("SHOW ON MAP")
                            .font(TE.mono(.caption, weight: .bold))
                            .tracking(2)
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(TE.accent)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                }
                .buttonStyle(.plain)

                Button {
                    exportOuting()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: exportFolderManager.hasDefaultFolder && useDefaultExportFolder ? "square.and.arrow.down" : "square.and.arrow.up")
                            .font(.caption.weight(.bold))
                        Text(exportButtonTitle)
                            .font(TE.mono(.caption, weight: .bold))
                            .tracking(2)
                    }
                    .foregroundStyle(storeManager.isPurchased ? TE.accent : TE.textMuted)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(storeManager.isPurchased ? TE.accent.opacity(0.08) : TE.textMuted.opacity(0.08))
                    .overlay {
                        RoundedRectangle(cornerRadius: 4)
                            .strokeBorder(storeManager.isPurchased ? TE.accent.opacity(0.5) : TE.textMuted.opacity(0.25), lineWidth: 1)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                }
                .buttonStyle(.plain)
                .accessibilityHint("Exports this outing in the selected format.")

                Button(role: .destructive) {
                    showingDeleteConfirmation = true
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "trash")
                            .font(.caption.weight(.bold))
                        Text("DELETE OUTING")
                            .font(TE.mono(.caption, weight: .bold))
                            .tracking(2)
                    }
                    .foregroundStyle(TE.danger)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(TE.danger.opacity(0.08))
                    .overlay {
                        RoundedRectangle(cornerRadius: 4)
                            .strokeBorder(TE.danger.opacity(0.5), lineWidth: 1)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                }
                .buttonStyle(.plain)
                .accessibilityHint("Deletes this outing and its GPS points after confirmation. Visits are kept.")
            }
            .padding(.horizontal, 16)
            .padding(.top, 2)

            TESectionFooter(text: outingExportFooterText)
        }
    }

    private var deleteConfirmationMessage: String {
        let deletionMessage = String(localized: "This outing and its GPS points will be permanently deleted. Visits during this time will be kept.")
        if session.isActive {
            return String(localized: "Tracking will stop. \(deletionMessage)")
        }
        return deletionMessage
    }

    private var outingExportFormat: ExportFormat {
        ExportFormat(exportKitFormatID: outingDetailExportFormatToken) ?? .markdown
    }

    private var exportButtonTitle: String {
        if !storeManager.isPurchased { return String(localized: "UNLOCK EXPORT") }
        let formatName = outingExportFormat.displayName.uppercased()
        if exportFolderManager.hasDefaultFolder && useDefaultExportFolder {
            return String(localized: "EXPORT \(formatName)")
        }
        return String(localized: "SHARE \(formatName)")
    }

    private var outingExportFooterText: LocalizedStringKey {
        switch outingExportFormat {
        case .markdown:
            return "Markdown exports this outing as a page with YAML front matter, visits, and route points."
        case .json, .csv:
            return "This format exports an outing summary with timing, distance, visit count, notes, and coordinates."
        case .gpx, .kml, .geojson, .owntracks, .overland:
            return "This route format exports the outing's GPS route points. Notes and visit rows are not represented."
        }
    }

    private func detailRow(label: LocalizedStringKey, value: String, showDivider: Bool = true) -> some View {
        TERow(showDivider: showDivider) {
            HStack(spacing: 12) {
                Text(label)
                    .font(TE.mono(.caption, weight: .medium))
                    .tracking(1)
                    .foregroundStyle(TE.textMuted)

                Spacer()

                Text(value)
                    .font(TE.mono(.caption, weight: .semibold))
                    .foregroundStyle(TE.textPrimary)
                    .multilineTextAlignment(.trailing)
                    .monospacedDigit()
            }
        }
    }

    private func saveEditableDetails() {
        guard let storedSession = session.storedSession else { return }
        viewModel.updateRecordingSession(
            storedSession,
            customName: nameText,
            notes: notesText
        )
        nameText = storedSession.displayName(defaultName: session.defaultTitle)
        notesText = storedSession.notes ?? ""
    }

    private func requestPhotoAccessForOuting() {
        guard !isSyncingPhotos else { return }
        Task {
            isSyncingPhotos = true
            await viewModel.requestPhotoLibraryAccessAndSync(in: session.dateRange)
            isSyncingPhotos = false
        }
    }

    private func syncPhotoMomentsIfAllowed() {
        guard !isSyncingPhotos else { return }
        Task {
            viewModel.refreshPhotoLibraryAuthorizationState()
            guard viewModel.photoLibraryAccessState.canRead else { return }
            isSyncingPhotos = true
            await viewModel.syncPhotoMomentsIfAuthorized(in: session.dateRange)
            isSyncingPhotos = false
        }
    }

    private func exportOuting() {
        guard storeManager.isPurchased else {
            showingPaywall = true
            return
        }

        saveEditableDetails()

        var options = ExportOptions()
        options.dataKind = .outings
        options.format = outingExportFormat
        options.splitByDay = true

        do {
            if exportFolderManager.hasDefaultFolder && useDefaultExportFolder {
                let urls = try ExportService.saveOutingToDefaultFolder(
                    session,
                    visits: visits,
                    options: options,
                    filenamePattern: filenamePattern
                )
                ExportToastCenter.shared.show(.success(savedURLs: urls))
                AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
            } else {
                try ExportService.shareOuting(
                    session,
                    visits: visits,
                    options: options,
                    filenamePattern: filenamePattern,
                    completion: { completed in
                        guard completed else { return }
                        Task { @MainActor in
                            AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
                        }
                    }
                )
                ExportToastCenter.shared.show(.success(message: String(localized: "Share sheet opened")))
            }
        } catch {
            viewModel.exportError = error.localizedDescription
            ExportToastCenter.shared.show(.failure(message: error.localizedDescription))
        }
    }

    private func startRouteReplay() {
        guard canReplayRoute else { return }
        if isPreparingRoadRoute { return }
        routeReplayProgress = 0
        withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82)) {
            isRouteReplayEnabled = true
        }
        isRouteReplayPlaying = true
    }

    private func disableRouteReplay() {
        isRouteReplayPlaying = false
        routeReplayProgress = 1
        withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82)) {
            isRouteReplayEnabled = false
        }
    }

    private func toggleRouteReplayPlayback() {
        guard canReplayRoute else {
            disableRouteReplay()
            return
        }

        if !isRouteReplayEnabled {
            startRouteReplay()
            return
        }

        if !isRouteReplayPlaying,
           let currentIndex = RouteReplayCalculator.index(for: routeReplayProgress, pointCount: routeReplayPoints.count),
           currentIndex >= routeReplayPoints.count - 1 {
            routeReplayProgress = 0
        }

        isRouteReplayPlaying.toggle()
    }

    private func advanceRouteReplayIfNeeded() {
        guard isRouteReplayEnabled, isRouteReplayPlaying else { return }
        guard canReplayRoute,
              let currentIndex = RouteReplayCalculator.index(for: routeReplayProgress, pointCount: routeReplayPoints.count) else {
            disableRouteReplay()
            return
        }

        let lastIndex = routeReplayPoints.count - 1
        let nextIndex = min(currentIndex + RouteReplayCalculator.playbackStepSize(pointCount: routeReplayPoints.count), lastIndex)
        routeReplayProgress = RouteReplayCalculator.progress(forIndex: nextIndex, pointCount: routeReplayPoints.count)

        if nextIndex >= lastIndex {
            isRouteReplayPlaying = false
        }
    }

    private func validateRouteReplayState() {
        routeReplayProgress = RouteReplayCalculator.clampedProgress(routeReplayProgress)

        guard canReplayRoute else {
            isRouteReplayPlaying = false
            isRouteReplayEnabled = false
            return
        }

        if isRouteReplayEnabled, routeReplaySnapshot == nil {
            routeReplayProgress = 0
        }
    }

    @MainActor
    private func refreshRoadSnappedRoute() async {
        guard snapTravelPathToRoads else {
            roadSnappedRoute = nil
            return
        }

        let sourcePoints = routeReplayPoints
        guard sourcePoints.count >= 2 else {
            roadSnappedRoute = nil
            return
        }

        let fingerprint = RoadSnappedRouteBuilder.fingerprint(for: sourcePoints)
        if roadSnappedRoute?.sourceFingerprint == fingerprint {
            return
        }

        let snapPoints = sourcePoints.map { RoadSnappingPoint(point: $0) }
        let route = await RoadSnappedRouteBuilder.buildRoute(
            for: snapPoints,
            sourceFingerprint: fingerprint
        )

        guard !Task.isCancelled else { return }
        roadSnappedRoute = route
    }

    private func formattedSpeed(_ metersPerSecond: Double) -> String {
        if usesMetricDistanceUnits {
            return String(format: "%.1f km/h", locale: .current, metersPerSecond * 3.6)
        }
        return String(format: "%.1f mph", locale: .current, metersPerSecond * 2.23693629)
    }
}

private struct OutingBadge: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(TE.mono(.caption2, weight: .bold))
            .tracking(0.8)
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(color.opacity(0.12), in: Capsule())
            .overlay {
                Capsule()
                    .strokeBorder(color.opacity(0.35), lineWidth: 1)
            }
    }
}

private struct OutingStatChip: View {
    let label: LocalizedStringKey
    let value: String
    let systemImage: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(TE.accent)
                    .accessibilityHidden(true)

                Text(label)
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(0.7)
                    .foregroundStyle(TE.textMuted)
            }

            Text(value)
                .font(TE.mono(.caption2, weight: .bold))
                .foregroundStyle(TE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .background(TE.textMuted.opacity(0.08), in: RoundedRectangle(cornerRadius: 4))
    }
}

#Preview {
    OutingsView(
        viewModel: LocationViewModel(
            modelContext: try! ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self).mainContext,
            locationManager: LocationManager()
        ),
        onShowOnMap: {}
    )
}
