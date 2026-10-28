import Foundation
import SwiftData

@Model
final class RecordingSession {
    var id: UUID
    var startedAt: Date
    var endedAt: Date?
    var customName: String?
    var notes: String?

    init(
        id: UUID = UUID(),
        startedAt: Date,
        endedAt: Date? = nil,
        customName: String? = nil,
        notes: String? = nil
    ) {
        self.id = id
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.customName = customName
        self.notes = notes
    }

    var isActive: Bool {
        endedAt == nil
    }

    var normalizedCustomName: String? {
        guard let trimmed = customName?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    func displayName(defaultName: String) -> String {
        normalizedCustomName ?? defaultName
    }
}

struct RecordingSessionSummary: Identifiable {
    let id: String
    let storedSession: RecordingSession?
    let sequenceNumber: Int
    let startedAt: Date
    let endedAt: Date?
    let points: [LocationPoint]
    let isInferred: Bool
    let isActive: Bool
    let now: Date

    var title: String {
        storedSession?.displayName(defaultName: defaultTitle) ?? defaultTitle
    }

    var defaultTitle: String {
        String(localized: "Outing \(sequenceNumber)")
    }

    var effectiveEndDate: Date {
        let fallback = points.last?.timestamp ?? now
        return max(startedAt, endedAt ?? fallback)
    }

    var dateRange: ClosedRange<Date> {
        startedAt...effectiveEndDate
    }

    var duration: TimeInterval {
        max(0, effectiveEndDate.timeIntervalSince(startedAt))
    }

    var distanceMeters: Double {
        RecordingSessionBuilder.totalDistance(in: distancePoints)
    }

    var averageSpeedMetersPerSecond: Double? {
        guard duration > 0, distanceMeters > 0 else { return nil }
        return distanceMeters / duration
    }

    var pointCount: Int {
        points.count
    }

    var outlierCount: Int {
        points.filter(\.isOutlier).count
    }

    var distancePoints: [LocationPoint] {
        let filtered = points.filter { !$0.isOutlier }
        return filtered.isEmpty ? points : filtered
    }

    var startPoint: LocationPoint? {
        points.first
    }

    var endPoint: LocationPoint? {
        points.last
    }

    var formattedTimeRange: String {
        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .medium
        dateFormatter.timeStyle = .none

        let timeFormatter = DateFormatter()
        timeFormatter.dateStyle = .none
        timeFormatter.timeStyle = .short

        let date = dateFormatter.string(from: startedAt)
        let start = timeFormatter.string(from: startedAt)

        if isActive {
            return String(localized: "\(date) • \(start) – now")
        }

        let end = timeFormatter.string(from: effectiveEndDate)
        if Calendar.current.isDate(startedAt, inSameDayAs: effectiveEndDate) {
            return "\(date) • \(start) – \(end)"
        }

        return "\(date) • \(start) – \(effectiveEndDate.formatted(date: .abbreviated, time: .shortened))"
    }

    var accessibilityValue: String {
        var parts = [
            formattedTimeRange,
            String(localized: "Duration \(RecordingSessionFormatter.duration(duration))"),
            String(localized: "Distance \(DistanceFormatter.format(meters: distanceMeters, usesMetric: true))"),
            pointCount == 1
                ? String(localized: "1 point")
                : String(localized: "\(pointCount) points")
        ]

        if isActive {
            parts.append(String(localized: "Currently recording"))
        }

        if isInferred {
            parts.append(String(localized: "Inferred from a gap in location points"))
        }

        return parts.joined(separator: ". ")
    }
}

enum RecordingSessionSort: String, CaseIterable, Identifiable {
    case newest
    case oldest
    case longest
    case distance
    case points

    var id: String { rawValue }

    var label: String {
        switch self {
        case .newest: return String(localized: "Newest")
        case .oldest: return String(localized: "Oldest")
        case .longest: return String(localized: "Longest")
        case .distance: return String(localized: "Distance")
        case .points: return String(localized: "Points")
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .newest: return String(localized: "Newest first")
        case .oldest: return String(localized: "Oldest first")
        case .longest: return String(localized: "Longest duration first")
        case .distance: return String(localized: "Longest distance first")
        case .points: return String(localized: "Most points first")
        }
    }

    func sorted(_ sessions: [RecordingSessionSummary]) -> [RecordingSessionSummary] {
        switch self {
        case .newest:
            return sessions.sorted { lhs, rhs in
                if lhs.startedAt == rhs.startedAt { return lhs.sequenceNumber > rhs.sequenceNumber }
                return lhs.startedAt > rhs.startedAt
            }
        case .oldest:
            return sessions.sorted { lhs, rhs in
                if lhs.startedAt == rhs.startedAt { return lhs.sequenceNumber < rhs.sequenceNumber }
                return lhs.startedAt < rhs.startedAt
            }
        case .longest:
            return sessions.sorted { lhs, rhs in
                if lhs.duration == rhs.duration { return lhs.startedAt > rhs.startedAt }
                return lhs.duration > rhs.duration
            }
        case .distance:
            return sessions.sorted { lhs, rhs in
                if lhs.distanceMeters == rhs.distanceMeters { return lhs.startedAt > rhs.startedAt }
                return lhs.distanceMeters > rhs.distanceMeters
            }
        case .points:
            return sessions.sorted { lhs, rhs in
                if lhs.pointCount == rhs.pointCount { return lhs.startedAt > rhs.startedAt }
                return lhs.pointCount > rhs.pointCount
            }
        }
    }
}

enum RecordingSessionGapPreset: String, CaseIterable, Identifiable {
    case fifteenMinutes
    case thirtyMinutes
    case oneHour
    case twoHours
    case fourHours

    var id: String { rawValue }

    var seconds: TimeInterval {
        switch self {
        case .fifteenMinutes: return 15 * 60
        case .thirtyMinutes: return 30 * 60
        case .oneHour: return 60 * 60
        case .twoHours: return 2 * 60 * 60
        case .fourHours: return 4 * 60 * 60
        }
    }

    var label: String {
        switch self {
        case .fifteenMinutes: return String(localized: "15m gaps")
        case .thirtyMinutes: return String(localized: "30m gaps")
        case .oneHour: return String(localized: "1h gaps")
        case .twoHours: return String(localized: "2h gaps")
        case .fourHours: return String(localized: "4h gaps")
        }
    }

    var settingsLabel: String {
        switch self {
        case .fifteenMinutes: return String(localized: "15 minutes")
        case .thirtyMinutes: return String(localized: "30 minutes")
        case .oneHour: return String(localized: "1 hour")
        case .twoHours: return String(localized: "2 hours")
        case .fourHours: return String(localized: "4 hours")
        }
    }
}

enum RecordingSessionMinimumDurationPreset: String, CaseIterable, Identifiable {
    case none
    case fiveMinutes
    case fifteenMinutes
    case thirtyMinutes
    case oneHour

    var id: String { rawValue }

    var seconds: TimeInterval {
        switch self {
        case .none: return 0
        case .fiveMinutes: return 5 * 60
        case .fifteenMinutes: return 15 * 60
        case .thirtyMinutes: return 30 * 60
        case .oneHour: return 60 * 60
        }
    }

    var label: String {
        switch self {
        case .none: return String(localized: "No minimum")
        case .fiveMinutes: return String(localized: "5 minutes")
        case .fifteenMinutes: return String(localized: "15 minutes")
        case .thirtyMinutes: return String(localized: "30 minutes")
        case .oneHour: return String(localized: "1 hour")
        }
    }
}

enum RecordingSessionMinimumPointCountPreset: Int, CaseIterable, Identifiable {
    case one = 1
    case two = 2
    case three = 3
    case five = 5
    case ten = 10

    var id: Int { rawValue }

    var label: String {
        rawValue == 1 ? String(localized: "1+ point") : String(localized: "\(rawValue)+ points")
    }
}

struct RecordingSessionInferenceConfiguration: Equatable {
    static let includesInferredSessionsKey = "inferredOutings.includeInferredSessions"
    static let gapPresetKey = "inferredOutings.gapPreset"
    static let minimumDurationPresetKey = "inferredOutings.minimumDurationPreset"
    static let minimumPointCountKey = "inferredOutings.minimumPointCount"

    static let defaultIncludesInferredSessions = true
    static let defaultGapPreset = RecordingSessionGapPreset.thirtyMinutes
    static let defaultMinimumDurationPreset = RecordingSessionMinimumDurationPreset.none
    static let defaultMinimumPointCountPreset = RecordingSessionMinimumPointCountPreset.one

    static let `default` = RecordingSessionInferenceConfiguration(
        includesInferredSessions: defaultIncludesInferredSessions,
        gapThreshold: defaultGapPreset.seconds,
        minimumDuration: defaultMinimumDurationPreset.seconds,
        minimumPointCount: defaultMinimumPointCountPreset.rawValue
    )

    let includesInferredSessions: Bool
    let gapThreshold: TimeInterval
    let minimumDuration: TimeInterval
    let minimumPointCount: Int

    init(
        includesInferredSessions: Bool = defaultIncludesInferredSessions,
        gapThreshold: TimeInterval = defaultGapPreset.seconds,
        minimumDuration: TimeInterval = defaultMinimumDurationPreset.seconds,
        minimumPointCount: Int = defaultMinimumPointCountPreset.rawValue
    ) {
        self.includesInferredSessions = includesInferredSessions
        self.gapThreshold = max(1, gapThreshold)
        self.minimumDuration = max(0, minimumDuration)
        self.minimumPointCount = max(1, minimumPointCount)
    }

    static func legacy(gapThreshold: TimeInterval) -> RecordingSessionInferenceConfiguration {
        RecordingSessionInferenceConfiguration(
            includesInferredSessions: true,
            gapThreshold: gapThreshold,
            minimumDuration: 0,
            minimumPointCount: 1
        )
    }

    static func stored(in defaults: UserDefaults = .standard) -> RecordingSessionInferenceConfiguration {
        let includesInferredSessions: Bool
        if defaults.object(forKey: includesInferredSessionsKey) == nil {
            includesInferredSessions = defaultIncludesInferredSessions
        } else {
            includesInferredSessions = defaults.bool(forKey: includesInferredSessionsKey)
        }

        let gapPreset = RecordingSessionGapPreset(
            rawValue: defaults.string(forKey: gapPresetKey) ?? defaultGapPreset.rawValue
        ) ?? defaultGapPreset
        let minimumDurationPreset = RecordingSessionMinimumDurationPreset(
            rawValue: defaults.string(forKey: minimumDurationPresetKey) ?? defaultMinimumDurationPreset.rawValue
        ) ?? defaultMinimumDurationPreset
        let minimumPointCount: Int
        if defaults.object(forKey: minimumPointCountKey) == nil {
            minimumPointCount = defaultMinimumPointCountPreset.rawValue
        } else {
            minimumPointCount = max(1, defaults.integer(forKey: minimumPointCountKey))
        }

        return RecordingSessionInferenceConfiguration(
            includesInferredSessions: includesInferredSessions,
            gapThreshold: gapPreset.seconds,
            minimumDuration: minimumDurationPreset.seconds,
            minimumPointCount: minimumPointCount
        )
    }

    func shouldIncludeInferredChunk(_ chunk: [LocationPoint], isActive: Bool) -> Bool {
        guard includesInferredSessions, !chunk.isEmpty else { return false }
        if isActive { return true }
        guard chunk.count >= minimumPointCount else { return false }
        guard minimumDuration > 0,
              let first = chunk.first,
              let last = chunk.last else {
            return true
        }
        return last.timestamp.timeIntervalSince(first.timestamp) >= minimumDuration
    }
}

enum RecordingSessionFormatter {
    static func duration(_ interval: TimeInterval) -> String {
        let totalSeconds = max(0, Int(interval.rounded()))
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            if minutes == 0 { return String(localized: "\(hours)h") }
            return String(localized: "\(hours)h \(minutes)m")
        }

        if minutes > 0 {
            return String(localized: "\(minutes)m")
        }

        return String(localized: "\(seconds)s")
    }

    static func clockDuration(_ interval: TimeInterval) -> String {
        let totalSeconds = max(0, Int(interval.rounded()))
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }

        return String(format: "%d:%02d", minutes, seconds)
    }
}

enum RecordingSessionBuilder {
    static let defaultGapThreshold: TimeInterval = 30 * 60

    static func summaries(
        storedSessions: [RecordingSession],
        points: [LocationPoint],
        activeTrackingStart: Date?,
        gapThreshold: TimeInterval = defaultGapThreshold,
        inferenceConfiguration: RecordingSessionInferenceConfiguration? = nil,
        now: Date = Date()
    ) -> [RecordingSessionSummary] {
        let resolvedInferenceConfiguration = inferenceConfiguration ?? .legacy(gapThreshold: gapThreshold)
        let orderedPoints = points.sorted { $0.timestamp < $1.timestamp }
        let orderedStoredSessions = storedSessions.sorted { lhs, rhs in
            if lhs.startedAt == rhs.startedAt { return lhs.id.uuidString < rhs.id.uuidString }
            return lhs.startedAt < rhs.startedAt
        }

        var consumedPointIDs = Set<UUID>()
        var pending: [PendingSessionSummary] = []

        for storedSession in orderedStoredSessions {
            let endDate = storedSession.endedAt ?? now
            let startIndex = firstPointIndex(in: orderedPoints, notBefore: storedSession.startedAt)
            let endIndex = firstPointIndex(in: orderedPoints, after: endDate)
            let sessionPoints = startIndex < endIndex ? Array(orderedPoints[startIndex..<endIndex]) : []
            sessionPoints.forEach { consumedPointIDs.insert($0.id) }

            pending.append(PendingSessionSummary(
                id: "stored-\(storedSession.id.uuidString)",
                storedSession: storedSession,
                startedAt: storedSession.startedAt,
                endedAt: storedSession.endedAt,
                points: sessionPoints,
                isInferred: false,
                isActive: storedSession.endedAt == nil
            ))
        }

        if resolvedInferenceConfiguration.includesInferredSessions {
            let remainingPoints = consumedPointIDs.isEmpty
                ? orderedPoints
                : orderedPoints.filter { !consumedPointIDs.contains($0.id) }
            let inferredChunks = inferredPointChunks(
                from: remainingPoints,
                activeTrackingStart: activeTrackingStart,
                gapThreshold: resolvedInferenceConfiguration.gapThreshold
            )

            for chunk in inferredChunks {
                guard let first = chunk.first, let last = chunk.last else { continue }
                let containsActiveTrackingStart = activeTrackingStart.map { activeStart in
                    first.timestamp >= activeStart || (first.timestamp < activeStart && last.timestamp >= activeStart)
                } ?? false
                guard resolvedInferenceConfiguration.shouldIncludeInferredChunk(
                    chunk,
                    isActive: containsActiveTrackingStart
                ) else { continue }

                pending.append(PendingSessionSummary(
                    id: "inferred-\(first.id.uuidString)-\(last.id.uuidString)-\(chunk.count)",
                    storedSession: nil,
                    startedAt: first.timestamp,
                    endedAt: containsActiveTrackingStart ? nil : last.timestamp,
                    points: chunk,
                    isInferred: true,
                    isActive: containsActiveTrackingStart
                ))
            }
        }

        let chronological = pending.sorted { lhs, rhs in
            if lhs.startedAt == rhs.startedAt { return lhs.id < rhs.id }
            return lhs.startedAt < rhs.startedAt
        }

        return chronological.enumerated().map { offset, item in
            RecordingSessionSummary(
                id: item.id,
                storedSession: item.storedSession,
                sequenceNumber: offset + 1,
                startedAt: item.startedAt,
                endedAt: item.endedAt,
                points: item.points,
                isInferred: item.isInferred,
                isActive: item.isActive,
                now: now
            )
        }
    }

    static func inferredPointChunks(
        from points: [LocationPoint],
        activeTrackingStart: Date? = nil,
        gapThreshold: TimeInterval = defaultGapThreshold
    ) -> [[LocationPoint]] {
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }
        guard !sortedPoints.isEmpty else { return [] }

        var chunks: [[LocationPoint]] = []
        var currentChunk: [LocationPoint] = []
        currentChunk.reserveCapacity(sortedPoints.count)

        for point in sortedPoints {
            if let previous = currentChunk.last,
               shouldStartNewChunk(
                   previous: previous,
                   current: point,
                   activeTrackingStart: activeTrackingStart,
                   gapThreshold: gapThreshold
               ) {
                chunks.append(currentChunk)
                currentChunk = [point]
            } else {
                currentChunk.append(point)
            }
        }

        if !currentChunk.isEmpty {
            chunks.append(currentChunk)
        }

        return chunks
    }

    static func totalDistance(in points: [LocationPoint]) -> Double {
        guard points.count > 1 else { return 0 }
        var total: Double = 0
        for index in 1..<points.count {
            total += points[index - 1].distance(to: points[index])
        }
        return total
    }

    private static func shouldStartNewChunk(
        previous: LocationPoint,
        current: LocationPoint,
        activeTrackingStart: Date?,
        gapThreshold: TimeInterval
    ) -> Bool {
        if let activeTrackingStart,
           previous.timestamp < activeTrackingStart,
           current.timestamp >= activeTrackingStart {
            return true
        }

        return current.timestamp.timeIntervalSince(previous.timestamp) > gapThreshold
    }

    private static func firstPointIndex(in points: [LocationPoint], notBefore date: Date) -> Int {
        var lowerBound = 0
        var upperBound = points.count
        while lowerBound < upperBound {
            let midpoint = (lowerBound + upperBound) / 2
            if points[midpoint].timestamp < date {
                lowerBound = midpoint + 1
            } else {
                upperBound = midpoint
            }
        }
        return lowerBound
    }

    private static func firstPointIndex(in points: [LocationPoint], after date: Date) -> Int {
        var lowerBound = 0
        var upperBound = points.count
        while lowerBound < upperBound {
            let midpoint = (lowerBound + upperBound) / 2
            if points[midpoint].timestamp <= date {
                lowerBound = midpoint + 1
            } else {
                upperBound = midpoint
            }
        }
        return lowerBound
    }

    private struct PendingSessionSummary {
        let id: String
        let storedSession: RecordingSession?
        let startedAt: Date
        let endedAt: Date?
        let points: [LocationPoint]
        let isInferred: Bool
        let isActive: Bool
    }
}

extension RecordingSession {
    static var preview: RecordingSession {
        RecordingSession(
            startedAt: Date().addingTimeInterval(-2 * 3600),
            endedAt: Date().addingTimeInterval(-20 * 60),
            customName: "Morning Errands"
        )
    }
}
