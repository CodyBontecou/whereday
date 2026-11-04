import SwiftUI

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

    @ObservedObject private var locationManager: LocationManager
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.openURL) private var openURL
    @Environment(ManualVisitRouter.self) private var manualVisitRouter
    @State private var inspectedVisit: Visit?

    init(
        viewModel: LocationViewModel,
        onReplay: @escaping () -> Void = {},
        onPickDay: @escaping () -> Void = {},
        onOpenPlaces: @escaping () -> Void = {},
        onOpenArchive: @escaping () -> Void = {},
        onSelectVisit: ((Visit) -> Void)? = nil,
        showsCoverHint: Bool = false
    ) {
        self.viewModel = viewModel
        self.onReplay = onReplay
        self.onPickDay = onPickDay
        self.onOpenPlaces = onOpenPlaces
        self.onOpenArchive = onOpenArchive
        self.onSelectVisit = onSelectVisit
        self.showsCoverHint = showsCoverHint
        _locationManager = ObservedObject(wrappedValue: viewModel.locationManager)
    }

    private var access: LocationAccess {
        switch locationManager.authorizationStatus {
        case .authorizedAlways: .always
        case .authorizedWhenInUse: .whileUsing
        case .denied, .restricted: .off
        default: .always
        }
    }

    var body: some View {
        TimelineView(.everyMinute) { context in
            let day = TodayDay(viewModel: viewModel, now: context.date)
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
                        daySoFar(day)
                            .padding(.top, 24)
                    } else if day.stays.isEmpty && access == .always {
                        blankPage(now: context.date)
                            .padding(.top, 26)
                    } else {
                        if access != .off {
                            overview(day: day, now: context.date)
                                .padding(.top, 18)
                                .padding(.bottom, 8)
                        }
                        if let current = day.currentStay {
                            currentPlaceCard(current)
                                .padding(.top, 6)
                        }
                        daySoFar(day)
                            .padding(.top, 20)
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
    }

    private func select(_ visit: Visit) {
        if let onSelectVisit {
            onSelectVisit(visit)
        } else {
            inspectedVisit = visit
        }
    }

    private func logStopByHand() {
        manualVisitRouter.request = .init(route: .pastVisit(name: nil, latitude: nil, longitude: nil, arrival: nil, departure: nil))
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
                    .font(TE.editorial(.largeTitle))
                    .accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 12)
            if showsCoverHint {
                Text("Open for the map")
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .padding(.bottom, 14)
            }
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
        .padding(.horizontal, 24)
        .padding(.top, 12)
    }

    private func dayCaption(for date: Date) -> String {
        let weekday = date.formatted(.dateTime.weekday(.abbreviated))
        let monthDay = date.formatted(.dateTime.month(.abbreviated).day())
        guard !dynamicTypeSize.isAccessibilitySize else {
            return String(localized: "\(weekday) · \(monthDay)").uppercased()
        }
        let dayOfYear = Calendar.current.ordinality(of: .day, in: .year, for: date) ?? 0
        return String(localized: "\(weekday) · \(monthDay) · DAY \(dayOfYear)").uppercased()
    }

    // MARK: - Dial and numbers

    private func overview(day: TodayDay, now: Date) -> some View {
        HStack(alignment: .center, spacing: 18) {
            Button(action: onReplay) {
                DayDial(segments: day.segments, now: now, recordingSince: nil)
                    .frame(width: 176, height: 176)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(day.dialAccessibilityLabel)
            .accessibilityHint("Shows today's route on the map")

            VStack(alignment: .leading, spacing: 14) {
                stat(DistanceFormatter.format(meters: viewModel.todayDistanceTraveled, usesMetric: usesMetricDistanceUnits),
                     label: "MOVED")
                stat(day.awayDuration.map(TodayDay.formatDuration) ?? "—", label: "AWAY FROM PLACES")
                stat(viewModel.todayLocationPoints.count.formatted(), label: "POINTS KEPT")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 24)
    }

    /// At accessibility sizes the dial gives way to a plain button and stacked numbers.
    private func largeTextOverview(day: TodayDay) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            if let current = day.currentStay {
                currentPlaceCard(current)
                    .padding(.horizontal, -8)
            }
            VStack(alignment: .leading, spacing: 14) {
                stat(DistanceFormatter.format(meters: viewModel.todayDistanceTraveled, usesMetric: usesMetricDistanceUnits),
                     label: "moved", tracked: false)
                stat(day.awayDuration.map(TodayDay.formatDuration) ?? "—", label: "away from places", tracked: false)
            }
            Button(action: onReplay) {
                HStack(spacing: 12) {
                    Text("Replay the day")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
                .frame(minHeight: 60)
                .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
                .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 24)
    }

    private func stat(_ value: String, label: LocalizedStringKey, tracked: Bool = true) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(TE.editorial(.title))
            Text(label)
                .font(tracked ? TE.mono(.caption2) : .body)
                .tracking(tracked ? 1 : 0)
                .foregroundStyle(TE.textMuted)
        }
        .accessibilityElement(children: .combine)
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
                startLink("Save Home so days start there", systemImage: "house", action: onOpenPlaces)
                Rectangle().fill(TE.dialTrack).frame(height: 1)
                startLink("Bring in your Google Timeline", systemImage: "square.and.arrow.down", action: onOpenArchive)
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

    private var accessCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label {
                Text(access == .off ? "LOCATION ACCESS IS OFF" : "ONLY WHILE USING THE APP")
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.inkRust)
            } icon: {
                Image(systemName: "exclamationmark.shield")
                    .foregroundStyle(TE.placeInks[1])
            }
            Text(access == .off ? "Whereday can’t record today." : "Today has gaps when Whereday is closed.")
                .font(TE.editorial(.title2))
            Text(access == .off
                 ? "Nothing new is being kept. Your past days, places and exports are all still here."
                 : "Allow Always so the day records around the clock, even in your pocket.")
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

    private func currentPlaceCard(_ stay: TodayDay.Stay) -> some View {
        HStack(spacing: 14) {
            Circle()
                .fill(TE.liveDot)
                .frame(width: 10, height: 10)
                .background(Circle().fill(TE.liveDot.opacity(0.25)).padding(-5))
                .padding(5)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("YOU'RE AT · SINCE \(stay.visit.arrivedAt.formatted(date: .omitted, time: .shortened))")
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.onInkCardMuted)
                Text(stay.visit.displayName)
                    .font(TE.editorial(.title2))
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            Button("Rename") { inspectedVisit = stay.visit }
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .overlay(Capsule().strokeBorder(TE.onInkCard.opacity(0.35), lineWidth: 1))
                .buttonStyle(.plain)
        }
        .foregroundStyle(TE.onInkCard)
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
        .padding(.horizontal, 16)
    }

    // MARK: - The day so far

    private func daySoFar(_ day: TodayDay) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(access == .whileUsing ? "THE DAY SO FAR · WHILE OPEN" : "THE DAY SO FAR")
                .font(TE.mono(.caption2))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
                .padding(.bottom, 10)
                .accessibilityAddTraits(.isHeader)

            if day.entries.isEmpty {
                Text("No places yet today. Stops show up here once you've stayed somewhere for a few minutes.")
                    .font(TE.editorial(.body))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            ForEach(day.entries) { entry in
                switch entry.kind {
                case .stay(let stay):
                    Button { select(stay.visit) } label: {
                        stayRow(stay)
                    }
                    .buttonStyle(.plain)
                case .move(let label):
                    moveRow(label)
                }
            }

            if access != .always {
                Button("Log a stop by hand", action: logStopByHand)
                    .font(.subheadline)
                    .foregroundStyle(TE.inkRust)
                    .frame(minHeight: 44)
                    .padding(.top, 8)
            }
        }
        .padding(.horizontal, 24)
    }

    @ViewBuilder
    private func stayRow(_ stay: TodayDay.Stay) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Circle()
                    .fill(stay.ink)
                    .frame(width: 16, height: 16)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(stay.visit.displayName)
                        .font(TE.editorial(.title3))
                    Text(stayMeta(stay))
                        .font(.body)
                        .foregroundStyle(TE.textMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
            .accessibilityElement(children: .combine)
        } else {
            compactStayRow(stay)
        }
    }

    private func stayMeta(_ stay: TodayDay.Stay) -> String {
        let time = stay.start.formatted(date: .omitted, time: .shortened)
        let duration = stay.isCurrent ? String(localized: "now") : TodayDay.formatDuration(stay.end.timeIntervalSince(stay.start))
        return "\(time) · \(duration)"
    }

    private func compactStayRow(_ stay: TodayDay.Stay) -> some View {
        HStack(spacing: 10) {
            Text(stay.start.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)))
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
                .frame(minWidth: 44, alignment: .leading)
            Circle()
                .fill(stay.ink)
                .frame(width: 12, height: 12)
                .frame(width: 14)
                .accessibilityHidden(true)
            Text(stay.visit.displayName)
                .font(TE.editorial(.title3))
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(stay.isCurrent ? String(localized: "now") : TodayDay.formatDuration(stay.end.timeIntervalSince(stay.start)))
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
        }
        .frame(minHeight: 46)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
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
}

private enum LocationAccess {
    case always, whileUsing, off
}

// MARK: - Day dial

/// A 24-hour ring, midnight at the top, with each stay drawn as an arc in its place's ink.
private struct DayDial: View {
    let segments: [TodayDay.Segment]
    let now: Date
    /// Set for a blank day: draws a dashed arc from when recording began and labels the center with it.
    let recordingSince: Date?

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
                    Text(recordingSince == nil ? "NOW" : "LISTENING SINCE")
                        .font(TE.mono(.caption2))
                        .tracking(1)
                        .foregroundStyle(TE.textMuted)
                    Text((recordingSince ?? now).formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)))
                        .font(.system(size: size * 0.19, design: .serif))
                        .monospacedDigit()
                    if recordingSince == nil {
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
    struct Stay {
        let visit: Visit
        let start: Date
        let end: Date
        let isCurrent: Bool
        let ink: Color
    }

    struct Segment: Identifiable {
        let id: UUID
        let start: Double
        let end: Double
        let ink: Color
    }

    struct Entry: Identifiable {
        enum Kind {
            case stay(Stay)
            case move(String)
        }

        let id: String
        let kind: Kind
    }

    let stays: [Stay]
    let entries: [Entry]
    let segments: [Segment]
    let awayDuration: TimeInterval?

    var currentStay: Stay? { stays.last(where: \.isCurrent) }

    init(viewModel: LocationViewModel, now: Date) {
        let calendar = Calendar.current
        let startOfDay = calendar.startOfDay(for: now)
        let visits = viewModel.visitsOverlappingDateRange(startOfDay...now, referenceDate: now)

        var inkByPlace: [String: Color] = [:]
        let stays: [Stay] = visits.compactMap { visit in
            let start = max(visit.arrivedAt, startOfDay)
            let end = min(visit.departedAt ?? now, now)
            guard end > start else { return nil }
            let ink = inkByPlace[visit.displayName] ?? TE.placeInks[inkByPlace.count % TE.placeInks.count]
            inkByPlace[visit.displayName] = ink
            return Stay(visit: visit, start: start, end: end, isCurrent: viewModel.isCurrentVisit(visit), ink: ink)
        }
        self.stays = stays

        segments = stays.map { stay in
            Segment(
                id: stay.visit.id,
                start: Self.dayFraction(of: stay.start, startOfDay: startOfDay),
                end: Self.dayFraction(of: stay.end, startOfDay: startOfDay),
                ink: stay.ink
            )
        }

        let points = viewModel.todayLocationPoints.filter { !$0.isOutlier }
        let usesMetric = UserDefaults.standard.object(forKey: "usesMetricDistanceUnits") as? Bool ?? true
        var entries: [Entry] = []
        for (index, stay) in stays.enumerated() {
            if index > 0 {
                let previous = stays[index - 1]
                let gap = stay.start.timeIntervalSince(previous.end)
                if gap >= 60 {
                    let distance = Self.pathDistance(points, from: previous.end, to: stay.start)
                    var parts: [String] = []
                    if distance > 0 {
                        parts.append(DistanceFormatter.format(meters: distance, usesMetric: usesMetric))
                    }
                    parts.append(Self.formatDuration(gap))
                    entries.append(Entry(id: "move-\(stay.visit.id)", kind: .move(parts.joined(separator: " · "))))
                }
            }
            entries.append(Entry(id: "stay-\(stay.visit.id)", kind: .stay(stay)))
        }
        self.entries = entries

        if let first = stays.first {
            let atPlaces = stays.reduce(0) { $0 + $1.end.timeIntervalSince($1.start) }
            let span = now.timeIntervalSince(first.start)
            awayDuration = max(0, span - atPlaces)
        } else {
            awayDuration = nil
        }
    }

    var dialAccessibilityLabel: String {
        guard !stays.isEmpty else {
            return String(localized: "24-hour dial, no places yet today")
        }
        let places = stays.map { stay in
            let time = stay.start.formatted(date: .omitted, time: .shortened)
            return String(localized: "\(stay.visit.displayName) from \(time)")
        }
        return String(localized: "24-hour dial: \(places.formatted(.list(type: .and)))")
    }

    static func dayFraction(of date: Date, startOfDay: Date? = nil) -> Double {
        let start = startOfDay ?? Calendar.current.startOfDay(for: date)
        return min(max(date.timeIntervalSince(start) / 86_400, 0), 1)
    }

    static func pathDistance(_ points: [LocationPoint], from start: Date, to end: Date) -> Double {
        let window = points.filter { $0.timestamp >= start && $0.timestamp <= end }
        return zip(window, window.dropFirst()).reduce(0) { $0 + $1.0.distance(to: $1.1) }
    }

    static func formatDuration(_ interval: TimeInterval) -> String {
        let minutes = max(0, Int(interval / 60))
        if minutes < 60 {
            return String(localized: "\(minutes)m")
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

    var summary: LocalizedStringKey {
        switch self {
        case .places: "Where you stop, with a coarse path. Lightest on battery."
        case .routes: "Your path while moving, quiet when still."
        case .everyStep: "A point every 5 m for hikes and rides. Stops on its own after 2 h."
        }
    }

    @MainActor
    func apply(to locationManager: LocationManager) {
        locationManager.setDistanceFilter(distanceFilter)
        locationManager.setStopAfterHours(stopAfterHours)
    }
}
