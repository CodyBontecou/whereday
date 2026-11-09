import Foundation
import SwiftData
import CoreLocation

enum ImportSourceFormat: String, Sendable {
    case isoMeJSON, isoMeCSV, isoMeMarkdown, googleRaw, googleSemantic, googleDevice, wheredayBackup

    var displayName: String {
        switch self {
        case .isoMeJSON: return "Whereday JSON"
        case .wheredayBackup: return String(localized: "Whereday backup")
        case .isoMeCSV: return "Whereday CSV"
        case .isoMeMarkdown: return "Whereday Markdown"
        case .googleRaw: return String(localized: "Google Location History")
        case .googleSemantic: return String(localized: "Google Semantic Timeline")
        case .googleDevice: return String(localized: "Google Device Timeline")
        }
    }
}

enum TimelineImportError: LocalizedError {
    case fileTooLarge, tooManyRecords, invalidGoogleArray, noRecords, nothingSelected

    var errorDescription: String? {
        switch self {
        case .fileTooLarge: return String(localized: "Choose a file smaller than 50 MB. Larger exports must be split before importing.")
        case .tooManyRecords: return String(localized: "This file contains too many records. Import files with up to 250,000 visits and points.")
        case .invalidGoogleArray: return String(localized: "The Google export does not contain a supported record array.")
        case .noRecords: return String(localized: "No supported visits or points were found in this file.")
        case .nothingSelected: return String(localized: "Select visits or points to import.")
        }
    }
}

struct ImportPayload: Sendable {
    static let maximumFileSize = 50 * 1_024 * 1_024
    static let maximumRecordCount = 250_000
    var format: ImportSourceFormat
    var backup: WheredayBackup?
    var visits: [ImportedVisit] = []
    var points: [ImportedLocationPoint] = []
    var invalidRecordCount = 0
    var unsupportedRecordCount = 0

    var dateRange: ClosedRange<Date>? {
        var first: Date?, last: Date?
        for visit in visits {
            first = min(first ?? visit.arrivedAt, visit.arrivedAt)
            last = max(last ?? visit.arrivedAt, visit.departedAt ?? visit.arrivedAt)
        }
        for point in points {
            first = min(first ?? point.timestamp, point.timestamp)
            last = max(last ?? point.timestamp, point.timestamp)
        }
        guard let first, let last else { return nil }
        return first...last
    }
}

struct ImportOptions: Sendable {
    var includeVisits = true
    var includePoints = true
}

struct ImportPreview: Identifiable, Sendable {
    let id = UUID()
    let fileName: String
    let payload: ImportPayload
    let newVisitCount: Int
    let newPointCount: Int
    let duplicateVisitCount: Int
    let duplicatePointCount: Int
    var newAdditionalRecordCount = 0
    var duplicateAdditionalRecordCount = 0
}

struct ImportCommitResult: Sendable {
    let visitCount: Int
    let pointCount: Int
    let duplicateVisitCount: Int
    let duplicatePointCount: Int

    var additionalRecordCount = 0
    var duplicateAdditionalRecordCount = 0

    var summary: String {
        if additionalRecordCount > 0 {
            return String(localized: "Restored \((visitCount + pointCount + additionalRecordCount).formatted()) records from backup.")
        }
        return ImportResult(visitCount: visitCount, pointCount: pointCount).summary + " " + String(
            format: String(localized: "Duplicates skipped: visits %d, points %d."),
            duplicateVisitCount, duplicatePointCount
        )
    }
}

/// Live progress of the import being saved, for the review sheet's "29,880 OF 48,210 POINTS" line.
/// The import worker posts counts from its own actor; the sheet observes them on the main actor.
@MainActor
final class ImportProgress: ObservableObject {
    static let shared = ImportProgress()

    @Published private(set) var completed = 0
    @Published private(set) var total = 0
    private var generation = 0

    func reset() {
        generation += 1
        completed = 0
        total = 0
    }

    nonisolated static func post(completed: Int, total: Int) {
        Task { @MainActor in shared.update(completed: completed, total: total) }
    }

    private func update(completed: Int, total: Int) {
        // Posts can land out of order; counts only move forward within one import.
        if total != self.total {
            self.total = total
            self.completed = completed
        } else {
            self.completed = max(self.completed, completed)
        }
    }
}

/// This worker owns its contexts. File IO, JSON parsing, duplicate indexing and
/// insertions do not run in the SwiftUI action that presents the review sheet.
actor TimelineImportService {
    private let container: ModelContainer

    init(container: ModelContainer) { self.container = container }

    func preview(url: URL) throws -> ImportPreview {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        if let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > ImportPayload.maximumFileSize {
            throw TimelineImportError.fileTooLarge
        }
        try Task.checkCancellation()
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        return try preview(data: data, fileName: url.lastPathComponent)
    }

    func preview(data: Data, fileName: String) throws -> ImportPreview {
        let payload = try Self.parse(data: data, fileName: fileName)
        if let backup = payload.backup {
            let plan = try WheredayBackupService.pending(backup, context: ModelContext(container))
            return ImportPreview(fileName: fileName, payload: payload,
                                 newVisitCount: plan.visits.count, newPointCount: plan.points.count,
                                 duplicateVisitCount: backup.visits.count - plan.visits.count,
                                 duplicatePointCount: backup.points.count - plan.points.count,
                                 newAdditionalRecordCount: plan.additionalRecordCount,
                                 duplicateAdditionalRecordCount: backup.additionalRecordCount - plan.additionalRecordCount)
        }
        let plan = try makePlan(payload: payload, options: ImportOptions(), context: ModelContext(container))
        return ImportPreview(fileName: fileName, payload: payload,
                             newVisitCount: plan.visits.count, newPointCount: plan.points.count,
                             duplicateVisitCount: plan.duplicateVisitCount, duplicatePointCount: plan.duplicatePointCount)
    }

    static func parse(data: Data, fileName: String) throws -> ImportPayload {
        guard data.count <= ImportPayload.maximumFileSize else { throw TimelineImportError.fileTooLarge }
        try Task.checkCancellation()
        let pathExtension = URL(fileURLWithPath: fileName).pathExtension.lowercased()
        var payload: ImportPayload
        switch pathExtension {
        case "json":
            let root = try JSONSerialization.jsonObject(with: data)
            let object = root as? [String: Any]
            if object?["backupFormat"] != nil {
                return ImportPayload(format: .wheredayBackup, backup: try WheredayBackupService.decode(data))
            }
            let format: ImportSourceFormat
            if object?["locations"] != nil { format = .googleRaw }
            else if object?["timelineObjects"] != nil { format = .googleSemantic }
            else if object?["semanticSegments"] != nil || root is [Any] { format = .googleDevice }
            else if object?["visits"] != nil || object?["points"] != nil { format = .isoMeJSON }
            else { throw ImportError.unsupportedFormat }
            if format == .isoMeJSON {
                let records = try ImportService.importFile(data: data, format: .json)
                payload = ImportPayload(format: format, visits: records.visits, points: records.points)
            } else { payload = try GoogleTimelineImportService.parse(root: root, format: format) }
        case "csv", "md", "markdown":
            let format: ExportFormat = pathExtension == "csv" ? .csv : .markdown
            let records = try ImportService.importFile(data: data, format: format)
            payload = ImportPayload(format: pathExtension == "csv" ? .isoMeCSV : .isoMeMarkdown,
                                    visits: records.visits, points: records.points)
        default: throw ImportError.unsupportedFormat
        }
        guard payload.visits.count + payload.points.count <= ImportPayload.maximumRecordCount else {
            throw TimelineImportError.tooManyRecords
        }
        func validCoordinate(_ latitude: Double, _ longitude: Double) -> Bool {
            latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude)
        }
        let visits = payload.visits.filter { visit in
            validCoordinate(visit.latitude, visit.longitude) && GoogleTimelineImportService.validDate(visit.arrivedAt)
                && (visit.departedAt.map { GoogleTimelineImportService.validDate($0) && $0 >= visit.arrivedAt } ?? true)
        }
        payload.invalidRecordCount += payload.visits.count - visits.count
        payload.visits = visits.sorted { $0.arrivedAt < $1.arrivedAt }
        let points = payload.points.filter {
            validCoordinate($0.latitude, $0.longitude) && GoogleTimelineImportService.validDate($0.timestamp)
                && $0.horizontalAccuracy.isFinite && $0.horizontalAccuracy >= -1
                && ($0.altitude?.isFinite ?? true) && ($0.speed?.isFinite ?? true)
        }
        payload.invalidRecordCount += payload.points.count - points.count
        payload.points = points.sorted { $0.timestamp < $1.timestamp }
        guard !payload.visits.isEmpty || !payload.points.isEmpty else { throw TimelineImportError.noRecords }
        return payload
    }

    func commit(_ preview: ImportPreview, options: ImportOptions) throws -> ImportCommitResult {
        if let backup = preview.payload.backup {
            return try WheredayBackupService.restore(backup, context: ModelContext(container))
        }
        guard options.includeVisits || options.includePoints else { throw TimelineImportError.nothingSelected }
        let context = ModelContext(container)
        context.autosaveEnabled = false
        // Recheck against fresh storage: the preview is advisory, not a reservation.
        let plan = try makePlan(payload: preview.payload, options: options, context: context)
        let total = plan.visits.count + plan.points.count
        ImportProgress.post(completed: 0, total: total)
        do {
            for (index, imported) in plan.visits.enumerated() {
                if index.isMultiple(of: 500) {
                    try Task.checkCancellation()
                    ImportProgress.post(completed: index, total: total)
                }
                context.insert(Visit(
                    latitude: imported.latitude, longitude: imported.longitude,
                    arrivedAt: imported.arrivedAt, departedAt: imported.departedAt,
                    customName: imported.customName, locationName: imported.locationName,
                    address: imported.address, notes: imported.notes, geocodingCompleted: true,
                    source: imported.sourceRaw.flatMap(VisitSource.init(rawValue:)) ?? .imported,
                    confirmationStatus: imported.confirmationStatusRaw.flatMap(VisitConfirmationStatus.init(rawValue:)) ?? .unconfirmed,
                    confirmedAt: imported.confirmedAt, updatedAt: imported.updatedAt,
                    originalLatitude: imported.originalLatitude, originalLongitude: imported.originalLongitude,
                    originalLocationName: imported.originalLocationName, originalAddress: imported.originalAddress,
                    detectedLatitude: imported.detectedLatitude, detectedLongitude: imported.detectedLongitude,
                    detectedLocationName: imported.detectedLocationName, detectedAddress: imported.detectedAddress,
                    placeSource: imported.placeSourceRaw.flatMap(VisitPlaceSource.init(rawValue:)) ?? .import,
                    placeDistanceMeters: imported.placeDistanceMeters
                ))
            }
            for (index, imported) in plan.points.enumerated() {
                if index.isMultiple(of: 500) {
                    try Task.checkCancellation()
                    ImportProgress.post(completed: plan.visits.count + index, total: total)
                }
                context.insert(LocationPoint(latitude: imported.latitude, longitude: imported.longitude,
                                             timestamp: imported.timestamp, altitude: imported.altitude, speed: imported.speed,
                                             horizontalAccuracy: imported.horizontalAccuracy, isOutlier: imported.isOutlier))
            }
            ImportProgress.post(completed: total, total: total)
            try Task.checkCancellation()
            // One save is intentional: an error/cancellation cannot leave half an import.
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
        return ImportCommitResult(visitCount: plan.visits.count, pointCount: plan.points.count,
                                  duplicateVisitCount: plan.duplicateVisitCount, duplicatePointCount: plan.duplicatePointCount)
    }

    private struct Plan {
        var visits: [ImportedVisit] = []
        var points: [ImportedLocationPoint] = []
        var duplicateVisitCount = 0
        var duplicatePointCount = 0
    }

    private func makePlan(payload: ImportPayload, options: ImportOptions, context: ModelContext) throws -> Plan {
        var plan = Plan()
        var visitsByMinute: [Int64: [VisitMatch]] = [:]
        var pointsBySecond: [Int64: [PointMatch]] = [:]
        let incomingVisits = options.includeVisits ? payload.visits : []
        let incomingPoints = options.includePoints ? payload.points : []
        if let first = incomingVisits.first, let last = incomingVisits.last {
            let start = first.arrivedAt.addingTimeInterval(-60), end = last.arrivedAt.addingTimeInterval(60)
            let descriptor = FetchDescriptor<Visit>(predicate: #Predicate { $0.arrivedAt >= start && $0.arrivedAt <= end })
            for visit in try context.fetch(descriptor) {
                let match = VisitMatch(latitude: visit.latitude, longitude: visit.longitude, arrival: visit.arrivedAt, departure: visit.departedAt)
                visitsByMinute[match.bucket, default: []].append(match)
            }
        }
        if let first = incomingPoints.first, let last = incomingPoints.last {
            let start = first.timestamp.addingTimeInterval(-1), end = last.timestamp.addingTimeInterval(1)
            let descriptor = FetchDescriptor<LocationPoint>(predicate: #Predicate { $0.timestamp >= start && $0.timestamp <= end })
            for point in try context.fetch(descriptor) {
                let match = PointMatch(latitude: point.latitude, longitude: point.longitude, time: point.timestamp)
                pointsBySecond[match.bucket, default: []].append(match)
            }
        }
        for (index, visit) in incomingVisits.enumerated() {
            if index.isMultiple(of: 500) { try Task.checkCancellation() }
            let match = VisitMatch(latitude: visit.latitude, longitude: visit.longitude, arrival: visit.arrivedAt, departure: visit.departedAt)
            let duplicate = ((match.bucket - 1)...(match.bucket + 1)).contains { bucket in
                (visitsByMinute[bucket] ?? []).contains { $0.matches(match) }
            }
            if duplicate { plan.duplicateVisitCount += 1 }
            else { plan.visits.append(visit); visitsByMinute[match.bucket, default: []].append(match) }
        }
        for (index, point) in incomingPoints.enumerated() {
            if index.isMultiple(of: 500) { try Task.checkCancellation() }
            let match = PointMatch(latitude: point.latitude, longitude: point.longitude, time: point.timestamp)
            if (pointsBySecond[match.bucket] ?? []).contains(where: { $0.matches(match) }) { plan.duplicatePointCount += 1 }
            else { plan.points.append(point); pointsBySecond[match.bucket, default: []].append(match) }
        }
        return plan
    }
}

private struct VisitMatch {
    let latitude: Double, longitude: Double
    let arrival: Date, departure: Date?
    var bucket: Int64 { Int64(floor(arrival.timeIntervalSince1970 / 60)) }
    func matches(_ other: Self) -> Bool {
        guard abs(arrival.timeIntervalSince(other.arrival)) <= 60,
              distance(latitude, longitude, other.latitude, other.longitude) <= 100 else { return false }
        switch (departure, other.departure) {
        case (nil, nil): return true
        case let (lhs?, rhs?): return abs(lhs.timeIntervalSince(rhs)) <= 300
        default: return false
        }
    }
}

private struct PointMatch {
    let latitude: Double, longitude: Double
    let time: Date
    var bucket: Int64 { Int64(time.timeIntervalSince1970.rounded()) }
    func matches(_ other: Self) -> Bool { distance(latitude, longitude, other.latitude, other.longitude) <= 10 }
}

private func distance(_ latitude: Double, _ longitude: Double, _ otherLatitude: Double, _ otherLongitude: Double) -> Double {
    CLLocation(latitude: latitude, longitude: longitude).distance(from: CLLocation(latitude: otherLatitude, longitude: otherLongitude))
}
