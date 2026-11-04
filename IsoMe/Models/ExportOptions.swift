import Foundation

struct ExportOptions: Codable, Equatable {
    enum DataKind: String, CaseIterable, Identifiable, Hashable, Codable {
        case visits, points, outings, all
        var id: String { rawValue }
    }

    enum DateRangePreset: String, CaseIterable, Identifiable, Codable {
        case allTime
        case today
        case yesterday
        case last7Days
        case last30Days
        case thisMonth
        /// A whole named month ("October"), anchored by `presetAnchor`.
        case month
        /// A whole calendar year ("all of 2026"), anchored by `presetAnchor`.
        case year
        case custom

        var id: String { rawValue }

        /// The fixed presets listed one per row; `month` and `year` are picked from their own menus.
        static let simplePresets: [DateRangePreset] = [.allTime, .today, .yesterday, .last7Days, .last30Days, .thisMonth]

        var label: String {
            switch self {
            case .allTime: return String(localized: "ALL TIME")
            case .today: return String(localized: "TODAY")
            case .yesterday: return String(localized: "YESTERDAY")
            case .last7Days: return String(localized: "LAST 7 DAYS")
            case .last30Days: return String(localized: "LAST 30 DAYS")
            case .thisMonth: return String(localized: "THIS MONTH")
            case .month: return String(localized: "MONTH")
            case .year: return String(localized: "YEAR")
            case .custom: return String(localized: "CUSTOM")
            }
        }
    }

    // Selection
    var dataKind: DataKind = .visits
    var format: ExportFormat = .json

    // Date range
    var datePreset: DateRangePreset = .allTime
    var customStart: Date = Calendar.current.startOfDay(for: Date().addingTimeInterval(-7 * 86_400))
    var customEnd: Date = Date()
    /// Any date inside the month or year chosen by the `month` / `year` presets.
    /// Optional so preferences and recipes saved before these presets still decode.
    var presetAnchor: Date?

    // Time-of-day window (only hour/minute components used)
    var timeOfDayEnabled: Bool = false
    var timeOfDayStart: Date = ExportOptions.defaultTimeOfDayStart
    var timeOfDayEnd: Date = ExportOptions.defaultTimeOfDayEnd

    // Visit field toggles
    var includeVisitCoordinates: Bool = true
    var includeVisitDuration: Bool = true
    var includeVisitLocationName: Bool = true
    var includeVisitAddress: Bool = true
    var includeVisitNotes: Bool = true

    // Point field toggles
    var includePointAltitude: Bool = true
    var includePointSpeed: Bool = true
    var includePointAccuracy: Bool = true
    var includePointOutlierFlag: Bool = true

    // Filters
    var excludeOutliers: Bool = false
    var onlyCompletedVisits: Bool = false
    var minVisitDurationMinutes: Double = 0
    var maxAccuracyMeters: Double = 0  // 0 means no cap

    // Output shape
    var splitByDay: Bool = false

    // MARK: - Defaults

    private static let defaultTimeOfDayStart: Date = {
        var comps = DateComponents()
        comps.hour = 9
        comps.minute = 0
        return Calendar.current.date(from: comps) ?? Date()
    }()

    private static let defaultTimeOfDayEnd: Date = {
        var comps = DateComponents()
        comps.hour = 17
        comps.minute = 0
        return Calendar.current.date(from: comps) ?? Date()
    }()

    // MARK: - Resolved Range

    func resolvedDateRange(now: Date = Date()) -> ClosedRange<Date>? {
        let cal = Calendar.current
        switch datePreset {
        case .allTime:
            return nil
        case .today:
            let start = cal.startOfDay(for: now)
            return start...now
        case .yesterday:
            let startOfToday = cal.startOfDay(for: now)
            let start = cal.date(byAdding: .day, value: -1, to: startOfToday) ?? now
            let end = Date(timeIntervalSinceReferenceDate: startOfToday.timeIntervalSinceReferenceDate.nextDown)
            return start...end
        case .last7Days:
            let start = cal.date(byAdding: .day, value: -7, to: cal.startOfDay(for: now)) ?? now
            return start...now
        case .last30Days:
            let start = cal.date(byAdding: .day, value: -30, to: cal.startOfDay(for: now)) ?? now
            return start...now
        case .thisMonth:
            let comps = cal.dateComponents([.year, .month], from: now)
            let start = cal.date(from: comps) ?? cal.startOfDay(for: now)
            return start...now
        case .month, .year:
            let component: Calendar.Component = datePreset == .month ? .month : .year
            guard let interval = cal.dateInterval(of: component, for: presetAnchor ?? now) else { return nil }
            let end = Date(timeIntervalSinceReferenceDate: interval.end.timeIntervalSinceReferenceDate.nextDown)
            // A current month or year runs to now; a future one is an empty range at its start.
            return interval.start...max(interval.start, min(end, now))
        case .custom:
            let lo = min(customStart, customEnd)
            let hi = max(customStart, customEnd)
            return lo...hi
        }
    }

    // MARK: - Time-of-day check

    private func minutesOfDay(from date: Date) -> Int {
        let comps = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
    }

    private func matchesTimeOfDay(_ date: Date) -> Bool {
        guard timeOfDayEnabled else { return true }
        let m = minutesOfDay(from: date)
        let lo = minutesOfDay(from: timeOfDayStart)
        let hi = minutesOfDay(from: timeOfDayEnd)
        if lo == hi { return true }
        if lo < hi {
            return m >= lo && m <= hi
        }
        // Wraps midnight (e.g. 22:00–06:00)
        return m >= lo || m <= hi
    }

    // MARK: - Filtering

    func filterVisits(_ visits: [Visit]) -> [Visit] {
        let range = resolvedDateRange()
        return visits.filter { visit in
            if let range = range, !range.contains(visit.arrivedAt) { return false }
            if !matchesTimeOfDay(visit.arrivedAt) { return false }
            if onlyCompletedVisits && visit.departedAt == nil { return false }
            if minVisitDurationMinutes > 0 {
                let mins = visit.durationMinutes ?? 0
                if mins < minVisitDurationMinutes { return false }
            }
            return true
        }
    }

    func filterPoints(_ points: [LocationPoint]) -> [LocationPoint] {
        let range = resolvedDateRange()
        return points.filter { point in
            if let range = range, !range.contains(point.timestamp) { return false }
            if !matchesTimeOfDay(point.timestamp) { return false }
            if excludeOutliers && point.isOutlier { return false }
            if maxAccuracyMeters > 0 && point.horizontalAccuracy > maxAccuracyMeters { return false }
            return true
        }
    }

    func filterOutings(_ outings: [RecordingSessionSummary]) -> [RecordingSessionSummary] {
        let range = resolvedDateRange()
        return outings.filter { outing in
            if let range = range, !range.contains(outing.startedAt) { return false }
            if !matchesTimeOfDay(outing.startedAt) { return false }
            return true
        }
    }

    // MARK: - Per-day grouping

    struct DayGroup {
        let day: Date
        let visits: [Visit]
        let points: [LocationPoint]
    }

    /// Groups already-filtered visits and points by calendar day. Days with no
    /// data for the active dataKind are omitted.
    func groupByDay(visits: [Visit], points: [LocationPoint]) -> [DayGroup] {
        let cal = Calendar.current
        let visitsByDay = Dictionary(grouping: visits) { cal.startOfDay(for: $0.arrivedAt) }
        let pointsByDay = Dictionary(grouping: points) { cal.startOfDay(for: $0.timestamp) }

        let relevantKeys: Set<Date> = {
            switch dataKind {
            case .visits: return Set(visitsByDay.keys)
            case .points: return Set(pointsByDay.keys)
            case .outings: return []
            case .all: return Set(visitsByDay.keys).union(pointsByDay.keys)
            }
        }()

        return relevantKeys.sorted().map { day in
            DayGroup(
                day: day,
                visits: visitsByDay[day] ?? [],
                points: pointsByDay[day] ?? []
            )
        }
    }
}

/// Persists the interactive Export tab settings so users don't lose their
/// preferred formats, filters, and output shape when they leave the page.
enum ExportPreferencesStore {
    private struct Snapshot: Codable {
        var options: ExportOptions
        var selectedFormatTokens: [String]
    }

    private static let key = "exportView.preferences.v1"

    static func load(defaults: UserDefaults = .standard) -> (options: ExportOptions, selectedFormats: Set<ExportFormat>) {
        guard let data = defaults.data(forKey: key),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data) else {
            let options = ExportOptions()
            return (options, [options.format])
        }

        var options = snapshot.options
        let selectedFormats = normalizedFormats(snapshot.selectedFormatTokens, fallback: options.format)
        options.format = selectedFormatsList(from: selectedFormats).first ?? options.format
        return (options, selectedFormats)
    }

    static func save(
        options: ExportOptions,
        selectedFormats: Set<ExportFormat>,
        defaults: UserDefaults = .standard
    ) {
        let formats = selectedFormatsList(from: selectedFormats)
        let selectedFormatTokens = formats.map(\.token)
        var normalizedOptions = options
        if let primaryFormat = formats.first {
            normalizedOptions.format = primaryFormat
        }

        let snapshot = Snapshot(options: normalizedOptions, selectedFormatTokens: selectedFormatTokens)
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        defaults.set(data, forKey: key)
    }

    private static func normalizedFormats(_ tokens: [String], fallback: ExportFormat) -> Set<ExportFormat> {
        let formats = tokens.compactMap { token in
            ExportFormat.allCases.first { $0.token == token }
        }
        if formats.isEmpty { return [fallback] }
        return Set(formats)
    }

    private static func selectedFormatsList(from selectedFormats: Set<ExportFormat>) -> [ExportFormat] {
        let selected = ExportFormat.allCases.filter { selectedFormats.contains($0) }
        return selected.isEmpty ? [.json] : selected
    }
}
