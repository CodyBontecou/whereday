import SwiftUI
import CoreLocation

/// The order in which a day's places take the place inks: Home first when one is saved, then by first arrival.
/// Today's dial, the map's markers and the map's route legs all colour a place with the same ink.
struct PlaceInkOrder {
    private(set) var indexByKey: [String: Int] = [:]

    init(leading: String? = nil) {
        if let leading { _ = index(for: leading) }
    }

    /// The ink slot for a place, assigning the next one on first sight.
    mutating func index(for key: String) -> Int {
        if let index = indexByKey[key] { return index }
        let index = indexByKey.count
        indexByKey[key] = index
        return index
    }

    static func ink(at index: Int) -> Color {
        TE.placeInks[index % TE.placeInks.count]
    }

    /// Inside a saved Home, stays read as Home unless renamed by hand.
    @MainActor
    static func placeName(for visit: Visit, home: SavedPlace?) -> String {
        if let home, !visit.hasCustomName, home.contains(visit.coordinate) { return home.name }
        return visit.displayName
    }
}

// MARK: - Day dial

/// One stay on a 24-hour dial: fractions of the day from midnight, in its place's ink.
struct DialArc: Identifiable {
    let id: String
    let start: Double
    let end: Double
    let ink: Color
}

/// A 24-hour ring, midnight at the top, with each stay drawn as an arc in its place's ink.
/// Shared by Today, the map's day sheet and the map's mini dial.
struct DayDial: View {
    let segments: [DialArc]
    let now: Date
    /// Set for a blank day: draws a dashed arc from when recording began and labels the center with it.
    let recordingSince: Date?
    /// Smaller dials drop the captions around the time.
    var captions: Captions = .full
    /// A past day has no "now" on its ring.
    var showsNowMarker = true

    enum Captions {
        /// `none` is the map's 56-point mini dial: just the ring and the now marker.
        case full, nowOnly, timeOnly, none
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

                if showsNowMarker {
                    nowMarker(size: size, ringInset: ringInset)
                }

                if captions != .none || recordingSince != nil {
                VStack(spacing: 2) {
                    if captions != .timeOnly || recordingSince != nil {
                        Text(recordingSince == nil ? "NOW" : "LISTENING SINCE")
                            .font(TE.mono(.caption2))
                            .tracking(1)
                            .foregroundStyle(TE.textMuted)
                    }
                    // Sized to the ring, not to Dynamic Type; the dial is hidden from VoiceOver and gives way at large sizes.
                    Text((recordingSince ?? now).formatted(.verbatim("\(hour: .twoDigits(clock: .twentyFourHour, hourCycle: .zeroBased)):\(minute: .twoDigits)", timeZone: .current, calendar: .current)))
                        .font(.custom(WheredayFont.serifFamily, fixedSize: size * (captions == .timeOnly ? 0.21 : 0.19)))
                        .monospacedDigit()
                    if recordingSince == nil && captions == .full {
                        Text("tap to replay")
                            .font(TE.mono(.caption2))
                            .foregroundStyle(TE.inkRust)
                    }
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

struct DottedMoveLine: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.midX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        }
    }
}

// MARK: - Day model

/// Today's stays clipped to midnight and now, with the moves between them.
/// Drives Today, the map's day sheet and the map's mini dial.
@MainActor
struct TodayDay {
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

    typealias Segment = DialArc

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

    /// Distinct places stayed at today, as the map's peek card and day sheet count them.
    var placeCount: Int { Set(stays.map(\.name)).count }

    init(viewModel: LocationViewModel, now: Date, home: SavedPlace? = nil, marksUnrecordedGaps: Bool = false) {
        let startOfDay = Calendar.current.startOfDay(for: now)
        self.init(
            visits: viewModel.visitsOverlappingDateRange(startOfDay...now, referenceDate: now),
            points: viewModel.todayLocationPoints.filter { !$0.isOutlier },
            startOfDay: startOfDay,
            now: now,
            home: home,
            marksUnrecordedGaps: marksUnrecordedGaps,
            isCurrentVisit: viewModel.isCurrentVisit
        )
    }

    /// A finished day, as the map's day sheet shows Yesterday or a picked day: it ends with its last record.
    init(pastDay day: Date, viewModel: LocationViewModel, points: [LocationPoint], home: SavedPlace? = nil) {
        let calendar = Calendar.current
        let startOfDay = calendar.startOfDay(for: day)
        let endOfDay = calendar.date(byAdding: .day, value: 1, to: startOfDay) ?? startOfDay.addingTimeInterval(86_400)
        let visits = viewModel.visitsOverlappingDateRange(startOfDay...endOfDay, referenceDate: endOfDay)
        let dayPoints = points.filter { !$0.isOutlier && $0.timestamp >= startOfDay && $0.timestamp < endOfDay }
        let lastRecord = ([dayPoints.last?.timestamp] + visits.map { $0.departedAt ?? endOfDay }).compactMap { $0 }.max()
        self.init(
            visits: visits,
            points: dayPoints,
            startOfDay: startOfDay,
            now: min(lastRecord ?? endOfDay, endOfDay),
            home: home,
            isCurrentVisit: { _ in false }
        )
    }

    init(
        visits: [Visit],
        points: [LocationPoint],
        startOfDay: Date,
        now: Date,
        home: SavedPlace? = nil,
        marksUnrecordedGaps: Bool = false,
        isCurrentVisit: (Visit) -> Bool
    ) {

        // Inside a saved Home, stays read as Home unless renamed by hand.
        func name(for visit: Visit) -> String {
            PlaceInkOrder.placeName(for: visit, home: home)
        }

        // The map colours its markers and route legs with the same order.
        var inkOrder = PlaceInkOrder(leading: home?.name)
        func ink(for name: String) -> Color {
            PlaceInkOrder.ink(at: inkOrder.index(for: name))
        }

        var stays: [Stay] = visits.compactMap { visit in
            let start = max(visit.arrivedAt, startOfDay)
            let end = min(visit.departedAt ?? now, now)
            guard end > start else { return nil }
            let placeName = name(for: visit)
            return Stay(visit: visit, name: placeName, start: start, end: end,
                        isCurrent: isCurrentVisit(visit), ink: ink(for: placeName))
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

        var inkOrder = PlaceInkOrder()
        let stays: [Stay] = visits.compactMap { visit in
            let end = min(visit.departedAt ?? now, now)
            guard end > visit.arrivedAt else { return nil }
            let ink = PlaceInkOrder.ink(at: inkOrder.index(for: visit.displayName))
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

