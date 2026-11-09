import Foundation
import UIKit
import ExportKit

enum ExportFormat: CaseIterable, Hashable, Codable {
    case json
    case csv
    case markdown
    case owntracks
    case overland
    case gpx
    case kml
    case geojson

    var fileExtension: String {
        switch self {
        case .json, .owntracks, .overland: return "json"
        case .csv: return "csv"
        case .markdown: return "md"
        case .gpx: return "gpx"
        case .kml: return "kml"
        case .geojson: return "geojson"
        }
    }

    var mimeType: String {
        switch self {
        case .json, .owntracks, .overland: return "application/json"
        case .csv: return "text/csv"
        case .markdown: return "text/markdown"
        case .gpx: return "application/gpx+xml"
        case .kml: return "application/vnd.google-earth.kml+xml"
        case .geojson: return "application/geo+json"
        }
    }

    var utiIdentifier: String {
        switch self {
        case .kml: return "com.google.earth.kml"
        case .json, .owntracks, .overland: return "public.json"
        case .csv: return "public.comma-separated-values-text"
        case .markdown: return "net.daringfireball.markdown"
        case .gpx: return "com.topografix.gpx"
        case .geojson: return "public.geojson"
        }
    }

    /// Stable identifier for the `{format}` filename token and persisted prefs.
    /// Distinct from `fileExtension` so two `.json`-extension formats don't collide.
    var token: String {
        switch self {
        case .json: return "json"
        case .csv: return "csv"
        case .markdown: return "md"
        case .owntracks: return "owntracks"
        case .overland: return "overland"
        case .gpx: return "gpx"
        case .kml: return "kml"
        case .geojson: return "geojson"
        }
    }

    /// Tracking-protocol formats only model continuous GPS fixes, not visits/stays.
    var isPointsOnly: Bool {
        switch self {
        case .owntracks, .overland: return true
        case .json, .csv, .markdown, .gpx, .kml, .geojson: return false
        }
    }
}

/// Lossless audit payload shared by the human-readable and map export formats.
/// Values are already filtered by the user's coordinate/name/address export options.
struct VisitAuditMetadata: Codable {
    let source: String?
    let confirmationStatus: String?
    let confirmedAt: String?
    let updatedAt: String?
    let originalLatitude: Double?
    let originalLongitude: Double?
    let originalLocationName: String?
    let originalAddress: String?
    let detectedLatitude: Double?
    let detectedLongitude: Double?
    let detectedLocationName: String?
    let detectedAddress: String?
    let placeSource: String?
    let placeDistanceMeters: Double?

    func json() -> String {
        "{" + fields().map { Self.quote($0.0) + ":" + Self.quote($0.1) }.joined(separator: ",") + "}"
    }

    private static func quote(_ value: String) -> String {
        let escaped = value.unicodeScalars.map { scalar -> String in
            switch scalar.value {
            case 34: return "\\\""
            case 92: return "\\\\"
            case 60: return "\\u003c"
            case 0...31: return String(format: "\\u%04x", scalar.value)
            default: return String(scalar)
            }
        }.joined()
        return "\"" + escaped + "\""
    }

    func fields() -> [(String, String)] {
        var result: [(String, String)] = []
        if let source { result.append(("source", String(describing: source))) }
        if let confirmationStatus { result.append(("confirmationStatus", String(describing: confirmationStatus))) }
        if let confirmedAt { result.append(("confirmedAt", String(describing: confirmedAt))) }
        if let updatedAt { result.append(("updatedAt", String(describing: updatedAt))) }
        if let originalLatitude { result.append(("originalLatitude", String(describing: originalLatitude))) }
        if let originalLongitude { result.append(("originalLongitude", String(describing: originalLongitude))) }
        if let originalLocationName { result.append(("originalLocationName", String(describing: originalLocationName))) }
        if let originalAddress { result.append(("originalAddress", String(describing: originalAddress))) }
        if let detectedLatitude { result.append(("detectedLatitude", String(describing: detectedLatitude))) }
        if let detectedLongitude { result.append(("detectedLongitude", String(describing: detectedLongitude))) }
        if let detectedLocationName { result.append(("detectedLocationName", String(describing: detectedLocationName))) }
        if let detectedAddress { result.append(("detectedAddress", String(describing: detectedAddress))) }
        if let placeSource { result.append(("placeSource", String(describing: placeSource))) }
        if let placeDistanceMeters { result.append(("placeDistanceMeters", String(describing: placeDistanceMeters))) }

        return result
    }
}

struct ExportService {
    private static let iso8601Formatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    // MARK: - JSON Export

    struct ExportableVisit: Codable {
        let latitude: Double?
        let longitude: Double?
        let arrivedAt: String
        let departedAt: String?
        let durationMinutes: Double?
        let locationName: String?
        let address: String?
        let notes: String?
        let source: String?
        let confirmationStatus: String?
        let confirmedAt: String?
        let updatedAt: String?
        let originalLatitude: Double?
        let originalLongitude: Double?
        let originalLocationName: String?
        let originalAddress: String?
        let detectedLatitude: Double?
        let detectedLongitude: Double?
        let detectedLocationName: String?
        let detectedAddress: String?
        let placeSource: String?
        let placeDistanceMeters: Double?
    }

    struct ExportData: Codable {
        let exportDate: String
        let visits: [ExportableVisit]
    }

    private static func exportableVisit(_ visit: Visit, options: ExportOptions) -> ExportableVisit {
        ExportableVisit(
            latitude: options.includeVisitCoordinates ? visit.latitude : nil,
            longitude: options.includeVisitCoordinates ? visit.longitude : nil,
            arrivedAt: iso8601Formatter.string(from: visit.arrivedAt),
            departedAt: visit.departedAt.map { iso8601Formatter.string(from: $0) },
            durationMinutes: options.includeVisitDuration ? visit.durationMinutes : nil,
            locationName: options.includeVisitLocationName ? visit.exportLocationName : nil,
            address: options.includeVisitAddress ? visit.address : nil,
            notes: options.includeVisitNotes ? visit.notes : nil,
            source: visit.source.rawValue,
            confirmationStatus: visit.confirmationStatus.rawValue,
            confirmedAt: visit.confirmedAt.map { iso8601Formatter.string(from: $0) },
            updatedAt: visit.updatedAt.map { iso8601Formatter.string(from: $0) },
            originalLatitude: options.includeVisitCoordinates ? visit.originalLatitude : nil,
            originalLongitude: options.includeVisitCoordinates ? visit.originalLongitude : nil,
            originalLocationName: options.includeVisitLocationName ? visit.originalLocationName : nil,
            originalAddress: options.includeVisitAddress ? visit.originalAddress : nil,
            detectedLatitude: options.includeVisitCoordinates ? visit.detectedLatitude : nil,
            detectedLongitude: options.includeVisitCoordinates ? visit.detectedLongitude : nil,
            detectedLocationName: options.includeVisitLocationName ? visit.detectedLocationName : nil,
            detectedAddress: options.includeVisitAddress ? visit.detectedAddress : nil,
            placeSource: visit.placeSource?.rawValue,
            placeDistanceMeters: visit.placeDistanceMeters
        )
    }

    private static func auditMetadata(_ visit: Visit, options: ExportOptions) -> VisitAuditMetadata {
        let record = exportableVisit(visit, options: options)
        return VisitAuditMetadata(
            source: record.source,
            confirmationStatus: record.confirmationStatus,
            confirmedAt: record.confirmedAt,
            updatedAt: record.updatedAt,
            originalLatitude: record.originalLatitude,
            originalLongitude: record.originalLongitude,
            originalLocationName: record.originalLocationName,
            originalAddress: record.originalAddress,
            detectedLatitude: record.detectedLatitude,
            detectedLongitude: record.detectedLongitude,
            detectedLocationName: record.detectedLocationName,
            detectedAddress: record.detectedAddress,
            placeSource: record.placeSource,
            placeDistanceMeters: record.placeDistanceMeters
        )
    }

    static func exportToJSON(visits: [Visit], options: ExportOptions = ExportOptions()) throws -> Data {
        let exportableVisits = visits.map { exportableVisit($0, options: options) }

        let exportData = ExportData(
            exportDate: iso8601Formatter.string(from: Date()),
            visits: exportableVisits
        )

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]

        return try encoder.encode(exportData)
    }

    // MARK: - CSV Export

    static func exportToCSV(visits: [Visit], options: ExportOptions = ExportOptions()) -> Data {
        var headers = ["arrived_at", "departed_at"]
        if options.includeVisitDuration { headers.append("duration_minutes") }
        if options.includeVisitCoordinates {
            headers.append("latitude")
            headers.append("longitude")
        }
        if options.includeVisitLocationName { headers.append("location_name") }
        if options.includeVisitAddress { headers.append("address") }
        if options.includeVisitNotes { headers.append("notes") }
        headers.append("source")
        headers.append("confirmation_status")
        if options.includeVisitLocationName { headers.append("original_location_name") }
        if options.includeVisitAddress { headers.append("original_address") }
        headers.append("visit_metadata")

        var csvString = headers.joined(separator: ",") + "\n"

        for visit in visits {
            var fields: [String] = []
            fields.append(iso8601Formatter.string(from: visit.arrivedAt))
            fields.append(visit.departedAt.map { iso8601Formatter.string(from: $0) } ?? "")
            if options.includeVisitDuration {
                fields.append(visit.durationMinutes.map { String(format: "%.1f", $0) } ?? "")
            }
            if options.includeVisitCoordinates {
                fields.append(String(visit.latitude))
                fields.append(String(visit.longitude))
            }
            if options.includeVisitLocationName {
                fields.append(escapeCSVField(visit.exportLocationName ?? ""))
            }
            if options.includeVisitAddress {
                fields.append(escapeCSVField(visit.address ?? ""))
            }
            if options.includeVisitNotes {
                fields.append(escapeCSVField(visit.notes ?? ""))
            }
            fields.append(visit.source.rawValue)
            fields.append(visit.confirmationStatus.rawValue)
            if options.includeVisitLocationName {
                fields.append(escapeCSVField(visit.originalLocationName ?? ""))
            }
            if options.includeVisitAddress {
                fields.append(escapeCSVField(visit.originalAddress ?? ""))
            }
            fields.append(escapeCSVField(auditMetadata(visit, options: options).json()))
            csvString.append(fields.joined(separator: ",") + "\n")
        }

        return csvString.data(using: .utf8) ?? Data()
    }

    private static func escapeCSVField(_ field: String) -> String {
        if field.contains(",") || field.contains("\"") || field.contains("\n") {
            let escaped = field.replacingOccurrences(of: "\"", with: "\"\"")
            return "\"\(escaped)\""
        }
        return field
    }

    // MARK: - Markdown Export

    static func exportToMarkdown(visits: [Visit], options: ExportOptions = ExportOptions()) -> Data {
        var md = "# Whereday Export\n\n"
        md += "**Export Date:** \(formattedDateReadable())\n\n"
        md += "**Total Visits:** \(visits.count)\n\n"
        md += "---\n\n"

        // Group visits by date
        let grouped = Dictionary(grouping: visits) { visit in
            Calendar.current.startOfDay(for: visit.arrivedAt)
        }

        let sortedDates = grouped.keys.sorted(by: >)

        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .full
        dateFormatter.timeStyle = .none

        let timeFormatter = DateFormatter()
        timeFormatter.dateStyle = .none
        timeFormatter.timeStyle = .short

        var headerCols: [String] = ["Arrived", "Departed"]
        if options.includeVisitDuration { headerCols.append("Duration") }
        if options.includeVisitCoordinates {
            headerCols.append("Lat")
            headerCols.append("Lon")
        }
        if options.includeVisitLocationName { headerCols.append("Location") }
        if options.includeVisitAddress { headerCols.append("Address") }
        if options.includeVisitNotes { headerCols.append("Notes") }
        headerCols.append(contentsOf: ["Source", "Status", "Visit Metadata"])

        for date in sortedDates {
            guard let dayVisits = grouped[date] else { continue }
            let sortedDayVisits = dayVisits.sorted { $0.arrivedAt < $1.arrivedAt }

            md += "## \(dateFormatter.string(from: date))\n\n"
            md += "| " + headerCols.joined(separator: " | ") + " |\n"
            md += "|" + headerCols.map { _ in "------" }.joined(separator: "|") + "|\n"

            for visit in sortedDayVisits {
                var cells: [String] = []
                cells.append(timeFormatter.string(from: visit.arrivedAt))
                cells.append(visit.departedAt.map { timeFormatter.string(from: $0) } ?? "-")

                if options.includeVisitDuration {
                    if let duration = visit.durationMinutes {
                        let hours = Int(duration) / 60
                        let minutes = Int(duration) % 60
                        cells.append(hours > 0 ? "\(hours)h \(minutes)m" : "\(minutes)m")
                    } else {
                        cells.append("-")
                    }
                }
                if options.includeVisitCoordinates {
                    cells.append(String(format: "%.6f", visit.latitude))
                    cells.append(String(format: "%.6f", visit.longitude))
                }
                if options.includeVisitLocationName {
                    cells.append(escapeMarkdownTableCell(visit.exportLocationName))
                }
                if options.includeVisitAddress {
                    cells.append(escapeMarkdownTableCell(visit.address))
                }
                if options.includeVisitNotes {
                    cells.append(escapeMarkdownTableCell(visit.notes))
                }
                cells.append(visit.source.rawValue)
                cells.append(visit.confirmationStatus.rawValue)
                cells.append(escapeMarkdownTableCell(auditMetadata(visit, options: options).json()))
                md += "| " + cells.joined(separator: " | ") + " |\n"
            }

            md += "\n"
        }

        return md.data(using: .utf8) ?? Data()
    }

    private static func escapeMarkdownTableCell(_ value: String?) -> String {
        guard let value = value, !value.isEmpty else { return "-" }
        return value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "|", with: "\\|")
            .replacingOccurrences(of: "\r\n", with: " ")
            .replacingOccurrences(of: "\n", with: "<br>")
            .replacingOccurrences(of: "\r", with: " ")
    }

    private static func formattedDateReadable() -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .long
        formatter.timeStyle = .short
        return formatter.string(from: Date())
    }

    // MARK: - Share/Save

    static func createTemporaryFile(data: Data, format: ExportFormat) throws -> URL {
        let fileName = "isome_export_\(formattedDate()).\(format.fileExtension)"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        try data.write(to: tempURL)
        return tempURL
    }

    private static func formattedDate() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HHmmss"
        return formatter.string(from: Date())
    }

    private static func activityItems(for fileURLs: [URL], format: ExportFormat) -> [Any] {
        guard format == .kml else { return fileURLs }
        return fileURLs.map { KMLActivityItemSource(fileURL: $0) }
    }

    private static func activityItems(for plannedFiles: [PlannedExportFile], fileURLs: [URL]) -> [Any] {
        zip(plannedFiles, fileURLs).map { plannedFile, fileURL in
            if case .aggregate(let formatID) = plannedFile.role,
               ExportFormat(exportKitFormatID: formatID) == .kml {
                return KMLActivityItemSource(fileURL: fileURL) as Any
            }
            return fileURL as Any
        }
    }

    @MainActor
    private static func presenter(from viewController: UIViewController?) -> UIViewController? {
        if let viewController {
            return topMostViewController(startingAt: viewController)
        }

        return UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive || $0.activationState == .foregroundInactive }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
            .flatMap { $0.rootViewController }
            .map { topMostViewController(startingAt: $0) }
    }

    @MainActor
    private static func topMostViewController(startingAt viewController: UIViewController) -> UIViewController {
        if let presented = viewController.presentedViewController {
            return topMostViewController(startingAt: presented)
        }

        if let navigationController = viewController as? UINavigationController,
           let visibleViewController = navigationController.visibleViewController {
            return topMostViewController(startingAt: visibleViewController)
        }

        if let tabBarController = viewController as? UITabBarController,
           let selectedViewController = tabBarController.selectedViewController {
            return topMostViewController(startingAt: selectedViewController)
        }

        return viewController
    }

    @MainActor
    static func presentShareSheet(
        _ activityVC: UIActivityViewController,
        from viewController: UIViewController?
    ) {
        guard let presenter = presenter(from: viewController), presenter.presentedViewController == nil else {
            return
        }

        if let popover = activityVC.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }

        presenter.present(activityVC, animated: true)
    }

    @MainActor
    static func share(
        visits: [Visit],
        format: ExportFormat,
        from viewController: UIViewController? = nil,
        completion: ((Bool) -> Void)? = nil
    ) throws {
        let data: Data
        switch format {
        case .json:
            data = try exportToJSON(visits: visits)
        case .csv:
            data = exportToCSV(visits: visits)
        case .markdown:
            data = exportToMarkdown(visits: visits)
        case .owntracks, .overland:
            // Tracking protocols can't represent visits; emit standard JSON instead.
            data = try exportToJSON(visits: visits)
        case .gpx:
            data = exportVisitsToGPX(visits: visits)
        case .kml:
            data = exportVisitsToKML(visits: visits)
        case .geojson:
            data = try exportVisitsToGeoJSON(visits: visits)
        }

        let fileURL = try createTemporaryFile(data: data, format: format)

        let activityVC = UIActivityViewController(
            activityItems: activityItems(for: [fileURL], format: format),
            applicationActivities: nil
        )
        activityVC.completionWithItemsHandler = { _, completed, _, _ in
            completion?(completed)
        }

        presentShareSheet(activityVC, from: viewController)
    }
    
    // MARK: - Direct Export to Default Folder
    
    /// Export visits directly to the default export folder
    /// - Returns: The URL where the file was saved
    @MainActor
    static func exportToDefaultFolder(visits: [Visit], format: ExportFormat) throws -> URL {
        let data: Data
        switch format {
        case .json:
            data = try exportToJSON(visits: visits)
        case .csv:
            data = exportToCSV(visits: visits)
        case .markdown:
            data = exportToMarkdown(visits: visits)
        case .owntracks, .overland:
            data = try exportToJSON(visits: visits)
        case .gpx:
            data = exportVisitsToGPX(visits: visits)
        case .kml:
            data = exportVisitsToKML(visits: visits)
        case .geojson:
            data = try exportVisitsToGeoJSON(visits: visits)
        }

        let fileName = "isome_visits_\(formattedDate()).\(format.fileExtension)"
        
        guard let savedURL = try ExportFolderManager.shared.saveToDefaultFolder(data: data, fileName: fileName) else {
            throw ExportFolderError.noDefaultFolder
        }
        
        return savedURL
    }
}

private final class KMLActivityItemSource: NSObject, UIActivityItemSource {
    let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
    }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any {
        fileURL
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        itemForActivityType activityType: UIActivity.ActivityType?
    ) -> Any? {
        fileURL
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        dataTypeIdentifierForActivityType activityType: UIActivity.ActivityType?
    ) -> String {
        ExportFormat.kml.utiIdentifier
    }
}

// MARK: - Location Points Export (for continuous tracking data)

extension ExportService {
    struct ExportableLocationPoint: Codable {
        let latitude: Double
        let longitude: Double
        let timestamp: String
        let timestampUnix: Double
        let altitude: Double?
        let speed: Double?
        let course: Double?
        let horizontalAccuracy: Double?
        let verticalAccuracy: Double?
        // True when the app's GPS-glitch detector flagged this point as an outlier.
        let isOutlier: Bool?
    }

    struct LocationPointsExportData: Codable {
        let exportDate: String
        let totalPoints: Int
        let dateRange: DateRangeInfo?
        let points: [ExportableLocationPoint]
        
        struct DateRangeInfo: Codable {
            let earliest: String
            let latest: String
            let durationSeconds: Double
        }
    }

    static func exportLocationPointsToJSON(points: [LocationPoint], options: ExportOptions = ExportOptions()) throws -> Data {
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }

        let exportablePoints = sortedPoints.map { point in
            ExportableLocationPoint(
                latitude: point.latitude,
                longitude: point.longitude,
                timestamp: iso8601Formatter.string(from: point.timestamp),
                timestampUnix: point.timestamp.timeIntervalSince1970,
                altitude: options.includePointAltitude ? point.altitude : nil,
                speed: options.includePointSpeed ? point.speed : nil,
                course: nil,
                horizontalAccuracy: options.includePointAccuracy ? point.horizontalAccuracy : nil,
                verticalAccuracy: nil,
                isOutlier: options.includePointOutlierFlag ? point.isOutlier : nil
            )
        }

        var dateRangeInfo: LocationPointsExportData.DateRangeInfo? = nil
        if let first = sortedPoints.first, let last = sortedPoints.last {
            dateRangeInfo = LocationPointsExportData.DateRangeInfo(
                earliest: iso8601Formatter.string(from: first.timestamp),
                latest: iso8601Formatter.string(from: last.timestamp),
                durationSeconds: last.timestamp.timeIntervalSince(first.timestamp)
            )
        }

        let exportData = LocationPointsExportData(
            exportDate: iso8601Formatter.string(from: Date()),
            totalPoints: exportablePoints.count,
            dateRange: dateRangeInfo,
            points: exportablePoints
        )

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]

        return try encoder.encode(exportData)
    }
    
    static func exportLocationPointsToCSV(points: [LocationPoint], options: ExportOptions = ExportOptions()) -> Data {
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }

        var headers = ["timestamp", "timestamp_unix", "latitude", "longitude"]
        if options.includePointAltitude { headers.append("altitude") }
        if options.includePointSpeed { headers.append("speed") }
        if options.includePointAccuracy { headers.append("horizontal_accuracy") }
        if options.includePointOutlierFlag { headers.append("is_outlier") }

        var csvString = headers.joined(separator: ",") + "\n"

        for point in sortedPoints {
            var fields: [String] = []
            fields.append(iso8601Formatter.string(from: point.timestamp))
            fields.append(String(format: "%.3f", point.timestamp.timeIntervalSince1970))
            fields.append(String(point.latitude))
            fields.append(String(point.longitude))
            if options.includePointAltitude {
                fields.append(point.altitude.map { String(format: "%.2f", $0) } ?? "")
            }
            if options.includePointSpeed {
                fields.append(point.speed.map { String(format: "%.2f", $0) } ?? "")
            }
            if options.includePointAccuracy {
                fields.append(String(point.horizontalAccuracy))
            }
            if options.includePointOutlierFlag {
                fields.append(point.isOutlier ? "true" : "false")
            }
            csvString.append(fields.joined(separator: ",") + "\n")
        }

        return csvString.data(using: .utf8) ?? Data()
    }
    
    static func exportLocationPointsToMarkdown(points: [LocationPoint], options: ExportOptions = ExportOptions()) -> Data {
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }

        var md = "# Whereday Location Points Export\n\n"
        md += "**Export Date:** \(formattedDateReadable())\n\n"
        md += "**Total Points:** \(sortedPoints.count)\n\n"

        if let first = sortedPoints.first, let last = sortedPoints.last {
            let duration = last.timestamp.timeIntervalSince(first.timestamp)
            let hours = Int(duration) / 3600
            let minutes = (Int(duration) % 3600) / 60

            let dateFormatter = DateFormatter()
            dateFormatter.dateStyle = .medium
            dateFormatter.timeStyle = .medium

            md += "**Time Range:** \(dateFormatter.string(from: first.timestamp)) → \(dateFormatter.string(from: last.timestamp))\n\n"
            md += "**Duration:** \(hours)h \(minutes)m\n\n"
        }

        md += "---\n\n"

        // Group by date
        let grouped = Dictionary(grouping: sortedPoints) { point in
            Calendar.current.startOfDay(for: point.timestamp)
        }

        let sortedDates = grouped.keys.sorted()

        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .full
        dateFormatter.timeStyle = .none

        let timeFormatter = DateFormatter()
        timeFormatter.dateStyle = .none
        timeFormatter.timeStyle = .medium

        var headerCols: [String] = ["Time", "Lat", "Lon"]
        if options.includePointSpeed { headerCols.append("Speed") }
        if options.includePointAltitude { headerCols.append("Altitude") }
        if options.includePointAccuracy { headerCols.append("Accuracy") }
        if options.includePointOutlierFlag { headerCols.append("Outlier") }

        for date in sortedDates {
            guard let dayPoints = grouped[date] else { continue }
            let sortedDayPoints = dayPoints.sorted { $0.timestamp < $1.timestamp }

            md += "## \(dateFormatter.string(from: date))\n\n"
            md += "| " + headerCols.joined(separator: " | ") + " |\n"
            md += "|" + headerCols.map { _ in "------" }.joined(separator: "|") + "|\n"

            for point in sortedDayPoints {
                var cells: [String] = []
                cells.append(timeFormatter.string(from: point.timestamp))
                cells.append(String(format: "%.6f", point.latitude))
                cells.append(String(format: "%.6f", point.longitude))
                if options.includePointSpeed {
                    cells.append(point.speed.map { String(format: "%.1f m/s", $0) } ?? "-")
                }
                if options.includePointAltitude {
                    cells.append(point.altitude.map { String(format: "%.1f m", $0) } ?? "-")
                }
                if options.includePointAccuracy {
                    cells.append(String(format: "%.1f m", point.horizontalAccuracy))
                }
                if options.includePointOutlierFlag {
                    cells.append(point.isOutlier ? "yes" : "-")
                }
                md += "| " + cells.joined(separator: " | ") + " |\n"
            }

            md += "\n"
        }

        return md.data(using: .utf8) ?? Data()
    }
    
    @MainActor
    static func shareLocationPoints(
        points: [LocationPoint],
        format: ExportFormat,
        from viewController: UIViewController? = nil,
        completion: ((Bool) -> Void)? = nil
    ) throws {
        let data: Data
        switch format {
        case .json:
            data = try exportLocationPointsToJSON(points: points)
        case .csv:
            data = exportLocationPointsToCSV(points: points)
        case .markdown:
            data = exportLocationPointsToMarkdown(points: points)
        case .owntracks:
            data = try exportLocationPointsToOwnTracks(points: points)
        case .overland:
            data = try exportLocationPointsToOverland(points: points)
        case .gpx:
            data = exportLocationPointsToGPX(points: points)
        case .kml:
            data = exportLocationPointsToKML(points: points)
        case .geojson:
            data = try exportLocationPointsToGeoJSON(points: points)
        }

        let fileName = "isome_location_points_export_\(formattedDate()).\(format.fileExtension)"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try data.write(to: tempURL)
        
        let activityVC = UIActivityViewController(
            activityItems: activityItems(for: [tempURL], format: format),
            applicationActivities: nil
        )
        activityVC.completionWithItemsHandler = { _, completed, _, _ in
            completion?(completed)
        }

        presentShareSheet(activityVC, from: viewController)
    }
    
    // MARK: - OwnTracks (https://owntracks.org/booklet/tech/json/)

    /// One OwnTracks `_type:"location"` message. Field names and units match
    /// the spec verbatim — short keys, integer units (`vel` km/h, `acc`/`alt`/`vac` m).
    private struct OwnTracksLocation: Codable {
        let _type: String
        let lat: Double
        let lon: Double
        let tst: Int
        let acc: Int?
        let alt: Int?
        let vel: Int?
        let cog: Int?
        let vac: Int?
        let tid: String?
    }

    static func exportLocationPointsToOwnTracks(points: [LocationPoint], options: ExportOptions = ExportOptions()) throws -> Data {
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }

        let messages = sortedPoints.map { p -> OwnTracksLocation in
            OwnTracksLocation(
                _type: "location",
                lat: p.latitude,
                lon: p.longitude,
                tst: Int(p.timestamp.timeIntervalSince1970),
                acc: options.includePointAccuracy ? Int(p.horizontalAccuracy.rounded()) : nil,
                alt: (options.includePointAltitude ? p.altitude : nil).map { Int($0.rounded()) },
                // OwnTracks `vel` is km/h; LocationPoint.speed is m/s.
                vel: (options.includePointSpeed ? p.speed : nil).map { Int(($0 * 3.6).rounded()) },
                cog: nil,
                vac: nil,
                tid: "IM"
            )
        }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        return try encoder.encode(messages)
    }

    // MARK: - Overland (https://github.com/aaronpk/Overland-iOS)

    private struct OverlandFeature: Codable {
        let type: String
        let geometry: Geometry
        let properties: Properties

        struct Geometry: Codable {
            let type: String
            let coordinates: [Double]  // [lon, lat]
        }
        struct Properties: Codable {
            let timestamp: String
            let altitude: Double?
            let speed: Double?
            let horizontalAccuracy: Double?
            let deviceId: String?
        }
    }

    private struct OverlandPayload: Codable {
        let locations: [OverlandFeature]
        let current: OverlandFeature?
    }

    static func exportLocationPointsToOverland(points: [LocationPoint], options: ExportOptions = ExportOptions()) throws -> Data {
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }

        let features = sortedPoints.map { p -> OverlandFeature in
            OverlandFeature(
                type: "Feature",
                geometry: .init(type: "Point", coordinates: [p.longitude, p.latitude]),
                properties: .init(
                    timestamp: iso8601Formatter.string(from: p.timestamp),
                    altitude: options.includePointAltitude ? p.altitude : nil,
                    // Overland `speed` is m/s — same as LocationPoint, no conversion.
                    speed: options.includePointSpeed ? p.speed : nil,
                    horizontalAccuracy: options.includePointAccuracy ? p.horizontalAccuracy : nil,
                    deviceId: "isome"
                )
            )
        }

        let payload = OverlandPayload(locations: features, current: features.last)

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        encoder.keyEncodingStrategy = .convertToSnakeCase
        return try encoder.encode(payload)
    }

    /// Export location points directly to the default export folder
    /// - Returns: The URL where the file was saved
    @MainActor
    static func exportLocationPointsToDefaultFolder(points: [LocationPoint], format: ExportFormat) throws -> URL {
        let data: Data
        switch format {
        case .json:
            data = try exportLocationPointsToJSON(points: points)
        case .csv:
            data = exportLocationPointsToCSV(points: points)
        case .markdown:
            data = exportLocationPointsToMarkdown(points: points)
        case .owntracks:
            data = try exportLocationPointsToOwnTracks(points: points)
        case .overland:
            data = try exportLocationPointsToOverland(points: points)
        case .gpx:
            data = exportLocationPointsToGPX(points: points)
        case .kml:
            data = exportLocationPointsToKML(points: points)
        case .geojson:
            data = try exportLocationPointsToGeoJSON(points: points)
        }

        let fileName = "isome_location_points_export_\(formattedDate()).\(format.fileExtension)"

        guard let savedURL = try ExportFolderManager.shared.saveToDefaultFolder(data: data, fileName: fileName) else {
            throw ExportFolderError.noDefaultFolder
        }

        return savedURL
    }
}

// MARK: - Combined Export (visits + location points in a single file)

extension ExportService {
    struct CombinedExportData: Codable {
        let exportDate: String
        let totalVisits: Int
        let totalPoints: Int
        let dateRange: LocationPointsExportData.DateRangeInfo?
        let visits: [ExportableVisit]
        let points: [ExportableLocationPoint]
    }

    static func exportCombinedToJSON(visits: [Visit], points: [LocationPoint], options: ExportOptions = ExportOptions()) throws -> Data {
        let exportableVisits = visits.map { exportableVisit($0, options: options) }

        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }
        let exportablePoints = sortedPoints.map { point in
            ExportableLocationPoint(
                latitude: point.latitude,
                longitude: point.longitude,
                timestamp: iso8601Formatter.string(from: point.timestamp),
                timestampUnix: point.timestamp.timeIntervalSince1970,
                altitude: options.includePointAltitude ? point.altitude : nil,
                speed: options.includePointSpeed ? point.speed : nil,
                course: nil,
                horizontalAccuracy: options.includePointAccuracy ? point.horizontalAccuracy : nil,
                verticalAccuracy: nil,
                isOutlier: options.includePointOutlierFlag ? point.isOutlier : nil
            )
        }

        var dateRangeInfo: LocationPointsExportData.DateRangeInfo? = nil
        if let first = sortedPoints.first, let last = sortedPoints.last {
            dateRangeInfo = LocationPointsExportData.DateRangeInfo(
                earliest: iso8601Formatter.string(from: first.timestamp),
                latest: iso8601Formatter.string(from: last.timestamp),
                durationSeconds: last.timestamp.timeIntervalSince(first.timestamp)
            )
        }

        let exportData = CombinedExportData(
            exportDate: iso8601Formatter.string(from: Date()),
            totalVisits: exportableVisits.count,
            totalPoints: exportablePoints.count,
            dateRange: dateRangeInfo,
            visits: exportableVisits,
            points: exportablePoints
        )

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]

        return try encoder.encode(exportData)
    }

    static func exportCombinedToCSV(visits: [Visit], points: [LocationPoint], options: ExportOptions = ExportOptions()) -> Data {
        var csvString = "# Whereday Combined Export\n"
        csvString.append("# Generated: \(iso8601Formatter.string(from: Date()))\n\n")

        csvString.append("# VISITS (\(visits.count))\n")
        let visitsCSV = String(data: exportToCSV(visits: visits, options: options), encoding: .utf8) ?? ""
        csvString.append(visitsCSV)

        csvString.append("\n# LOCATION POINTS (\(points.count))\n")
        let pointsCSV = String(data: exportLocationPointsToCSV(points: points, options: options), encoding: .utf8) ?? ""
        csvString.append(pointsCSV)

        return csvString.data(using: .utf8) ?? Data()
    }

    static func exportCombinedToMarkdown(visits: [Visit], points: [LocationPoint], options: ExportOptions = ExportOptions()) -> Data {
        var md = "# Whereday Complete Export\n\n"
        md += "**Export Date:** \(formattedDateReadable())\n\n"
        md += "**Total Visits:** \(visits.count)\n\n"
        md += "**Total Location Points:** \(points.count)\n\n"
        md += "---\n\n"

        md += String(data: exportToMarkdown(visits: visits, options: options), encoding: .utf8)?
            .replacingOccurrences(of: "# Whereday Export\n\n", with: "# Visits\n\n") ?? ""

        md += "\n---\n\n"

        md += String(data: exportLocationPointsToMarkdown(points: points, options: options), encoding: .utf8)?
            .replacingOccurrences(of: "# Whereday Location Points Export\n\n", with: "# Location Points\n\n") ?? ""

        return md.data(using: .utf8) ?? Data()
    }

    @MainActor
    static func shareCombined(
        visits: [Visit],
        points: [LocationPoint],
        format: ExportFormat,
        from viewController: UIViewController? = nil,
        completion: ((Bool) -> Void)? = nil
    ) throws {
        let data = try combinedData(visits: visits, points: points, format: format)

        let fileName = "isome_complete_export_\(formattedDate()).\(format.fileExtension)"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try data.write(to: tempURL)

        let activityVC = UIActivityViewController(
            activityItems: activityItems(for: [tempURL], format: format),
            applicationActivities: nil
        )
        activityVC.completionWithItemsHandler = { _, completed, _, _ in
            completion?(completed)
        }

        presentShareSheet(activityVC, from: viewController)
    }

    @MainActor
    static func exportCombinedToDefaultFolder(visits: [Visit], points: [LocationPoint], format: ExportFormat) throws -> URL {
        let data = try combinedData(visits: visits, points: points, format: format)
        let fileName = "isome_complete_export_\(formattedDate()).\(format.fileExtension)"

        guard let savedURL = try ExportFolderManager.shared.saveToDefaultFolder(data: data, fileName: fileName) else {
            throw ExportFolderError.noDefaultFolder
        }

        return savedURL
    }

    static func combinedData(visits: [Visit], points: [LocationPoint], format: ExportFormat, options: ExportOptions = ExportOptions()) throws -> Data {
        switch format {
        case .json: return try exportCombinedToJSON(visits: visits, points: points, options: options)
        case .csv: return exportCombinedToCSV(visits: visits, points: points, options: options)
        case .markdown: return exportCombinedToMarkdown(visits: visits, points: points, options: options)
        case .owntracks: return try exportLocationPointsToOwnTracks(points: points, options: options)
        case .overland: return try exportLocationPointsToOverland(points: points, options: options)
        case .gpx: return exportCombinedToGPX(visits: visits, points: points, options: options)
        case .kml: return exportCombinedToKML(visits: visits, points: points, options: options)
        case .geojson: return try exportCombinedToGeoJSON(visits: visits, points: points, options: options)
        }
    }
}

// MARK: - Outings Export

extension ExportService {
    struct ExportableOuting: Codable {
        let id: String
        let title: String
        let sequenceNumber: Int
        let startedAt: String
        let endedAt: String?
        let effectiveEndAt: String
        let durationSeconds: Double
        let distanceMeters: Double
        let averageSpeedMetersPerSecond: Double?
        let pointCount: Int
        let outlierCount: Int
        let visitCount: Int
        let isInferred: Bool
        let isActive: Bool
        let notes: String?
        let startLatitude: Double?
        let startLongitude: Double?
        let endLatitude: Double?
        let endLongitude: Double?
    }

    struct OutingsExportData: Codable {
        let exportDate: String
        let totalOutings: Int
        let outings: [ExportableOuting]
    }

    static func outingsData(
        outings: [RecordingSessionSummary],
        visits: [Visit],
        format: ExportFormat,
        options: ExportOptions = ExportOptions()
    ) throws -> Data {
        switch format {
        case .json:
            return try exportOutingsToJSON(outings: outings, visits: visits)
        case .csv:
            return exportOutingsToCSV(outings: outings, visits: visits)
        case .markdown:
            return exportOutingsToMarkdown(outings: outings, visits: visits, options: options)
        case .owntracks:
            return try exportLocationPointsToOwnTracks(points: uniqueOutingPoints(outings), options: options)
        case .overland:
            return try exportLocationPointsToOverland(points: uniqueOutingPoints(outings), options: options)
        case .gpx:
            return exportLocationPointsToGPX(points: uniqueOutingPoints(outings), options: options)
        case .kml:
            return exportLocationPointsToKML(points: uniqueOutingPoints(outings), options: options)
        case .geojson:
            return try exportLocationPointsToGeoJSON(points: uniqueOutingPoints(outings), options: options)
        }
    }

    static func exportOutingsToJSON(outings: [RecordingSessionSummary], visits: [Visit]) throws -> Data {
        let exportData = OutingsExportData(
            exportDate: iso8601Formatter.string(from: Date()),
            totalOutings: outings.count,
            outings: outings.sorted { $0.startedAt < $1.startedAt }.map { outing in
                exportableOuting(outing, visitCount: outingVisits(outing, visits: visits).count)
            }
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(exportData)
    }

    static func exportOutingsToCSV(outings: [RecordingSessionSummary], visits: [Visit]) -> Data {
        let headers = [
            "id", "title", "sequence_number", "started_at", "ended_at", "effective_end_at",
            "duration_seconds", "distance_meters", "average_speed_mps", "point_count", "outlier_count",
            "visit_count", "is_inferred", "is_active", "notes", "start_latitude", "start_longitude",
            "end_latitude", "end_longitude"
        ]
        var csv = headers.joined(separator: ",") + "\n"

        for outing in outings.sorted(by: { $0.startedAt < $1.startedAt }) {
            let exportable = exportableOuting(outing, visitCount: outingVisits(outing, visits: visits).count)
            let fields: [String] = [
                escapeCSVField(exportable.id),
                escapeCSVField(exportable.title),
                String(exportable.sequenceNumber),
                exportable.startedAt,
                exportable.endedAt ?? "",
                exportable.effectiveEndAt,
                String(format: "%.0f", exportable.durationSeconds),
                String(format: "%.2f", exportable.distanceMeters),
                exportable.averageSpeedMetersPerSecond.map { String(format: "%.3f", $0) } ?? "",
                String(exportable.pointCount),
                String(exportable.outlierCount),
                String(exportable.visitCount),
                exportable.isInferred ? "true" : "false",
                exportable.isActive ? "true" : "false",
                escapeCSVField(exportable.notes ?? ""),
                exportable.startLatitude.map { String($0) } ?? "",
                exportable.startLongitude.map { String($0) } ?? "",
                exportable.endLatitude.map { String($0) } ?? "",
                exportable.endLongitude.map { String($0) } ?? ""
            ]
            csv.append(fields.joined(separator: ",") + "\n")
        }

        return csv.data(using: .utf8) ?? Data()
    }

    static func exportOutingsToMarkdown(
        outings: [RecordingSessionSummary],
        visits: [Visit],
        options: ExportOptions = ExportOptions()
    ) -> Data {
        let sortedOutings = outings.sorted { $0.startedAt < $1.startedAt }

        if sortedOutings.count == 1, let outing = sortedOutings.first {
            return outingMarkdownPage(
                outing,
                visits: outingVisits(outing, visits: visits),
                options: options
            ).data(using: .utf8) ?? Data()
        }

        var md = "# Whereday Outings Export\n\n"
        md += "**Export Date:** \(formattedDateReadable())\n\n"
        md += "**Total Outings:** \(sortedOutings.count)\n\n"
        md += "---\n\n"
        md += "| Start | End | Title | Duration | Distance | Points | Visits | Notes |\n"
        md += "|------|-----|-------|----------|----------|--------|--------|-------|\n"

        for outing in sortedOutings {
            let visitsForOuting = outingVisits(outing, visits: visits)
            let notes = outingNotes(outing)
            let cells = [
                formattedDateTime(outing.startedAt),
                outing.endedAt.map(formattedDateTime) ?? (outing.isActive ? "now" : formattedDateTime(outing.effectiveEndDate)),
                escapeMarkdownTableCell(outing.title),
                RecordingSessionFormatter.duration(outing.duration),
                String(format: "%.0f m", outing.distanceMeters),
                String(outing.pointCount),
                String(visitsForOuting.count),
                escapeMarkdownTableCell(notes)
            ]
            md += "| " + cells.joined(separator: " | ") + " |\n"
        }

        return md.data(using: .utf8) ?? Data()
    }

    private static func exportableOuting(_ outing: RecordingSessionSummary, visitCount: Int) -> ExportableOuting {
        ExportableOuting(
            id: outing.id,
            title: outing.title,
            sequenceNumber: outing.sequenceNumber,
            startedAt: iso8601Formatter.string(from: outing.startedAt),
            endedAt: outing.endedAt.map { iso8601Formatter.string(from: $0) },
            effectiveEndAt: iso8601Formatter.string(from: outing.effectiveEndDate),
            durationSeconds: outing.duration,
            distanceMeters: outing.distanceMeters,
            averageSpeedMetersPerSecond: outing.averageSpeedMetersPerSecond,
            pointCount: outing.pointCount,
            outlierCount: outing.outlierCount,
            visitCount: visitCount,
            isInferred: outing.isInferred,
            isActive: outing.isActive,
            notes: outingNotes(outing),
            startLatitude: outing.startPoint?.latitude,
            startLongitude: outing.startPoint?.longitude,
            endLatitude: outing.endPoint?.latitude,
            endLongitude: outing.endPoint?.longitude
        )
    }

    private static func outingMarkdownPage(
        _ outing: RecordingSessionSummary,
        visits: [Visit],
        options: ExportOptions
    ) -> String {
        var md = "---\n"
        md += yamlString("type", "outing")
        md += yamlString("app", "Whereday")
        md += yamlString("id", outing.id)
        md += yamlString("title", outing.title)
        md += "sequence: \(outing.sequenceNumber)\n"
        md += yamlString("start", iso8601Formatter.string(from: outing.startedAt))
        md += yamlString("end", outing.endedAt.map { iso8601Formatter.string(from: $0) })
        md += yamlString("effective_end", iso8601Formatter.string(from: outing.effectiveEndDate))
        md += yamlNumber("duration_seconds", outing.duration, decimals: 0)
        md += yamlNumber("distance_meters", outing.distanceMeters, decimals: 2)
        md += yamlOptionalNumber("average_speed_mps", outing.averageSpeedMetersPerSecond, decimals: 3)
        md += "point_count: \(outing.pointCount)\n"
        md += "outlier_count: \(outing.outlierCount)\n"
        md += "visit_count: \(visits.count)\n"
        md += "is_inferred: \(outing.isInferred ? "true" : "false")\n"
        md += "is_active: \(outing.isActive ? "true" : "false")\n"
        md += yamlString("source", outing.isInferred ? "inferred" : "recorded")
        md += yamlString("notes", outingNotes(outing))
        md += yamlOptionalNumber("start_latitude", outing.startPoint?.latitude, decimals: 6)
        md += yamlOptionalNumber("start_longitude", outing.startPoint?.longitude, decimals: 6)
        md += yamlOptionalNumber("end_latitude", outing.endPoint?.latitude, decimals: 6)
        md += yamlOptionalNumber("end_longitude", outing.endPoint?.longitude, decimals: 6)
        md += yamlStringArray("visits", visits.map(\.displayName))
        md += "---\n\n"

        md += "# \(outing.title)\n\n"
        md += "## Summary\n\n"
        md += "| Field | Value |\n"
        md += "|------|-------|\n"
        md += "| Start | \(escapeMarkdownTableCell(formattedDateTime(outing.startedAt))) |\n"
        md += "| End | \(escapeMarkdownTableCell(outing.endedAt.map(formattedDateTime) ?? (outing.isActive ? "Now" : formattedDateTime(outing.effectiveEndDate)))) |\n"
        md += "| Duration | \(escapeMarkdownTableCell(RecordingSessionFormatter.duration(outing.duration))) |\n"
        md += "| Distance | \(escapeMarkdownTableCell(String(format: "%.0f m", outing.distanceMeters))) |\n"
        md += "| GPS points | \(outing.pointCount) |\n"
        md += "| Visits | \(visits.count) |\n"
        md += "| Source | \(outing.isInferred ? "Inferred" : "Recorded") |\n\n"

        if let notes = outingNotes(outing) {
            md += "## Notes\n\n\(notes)\n\n"
        }

        if !visits.isEmpty {
            md += outingVisitsMarkdown(visits, options: options)
        }

        if !outing.points.isEmpty {
            md += outingPointsMarkdown(outing.points, options: options)
        }

        return md
    }

    private static func outingVisits(_ outing: RecordingSessionSummary, visits: [Visit]) -> [Visit] {
        visits
            .filter { outing.dateRange.contains($0.arrivedAt) }
            .sorted { $0.arrivedAt < $1.arrivedAt }
    }

    private static func outingNotes(_ outing: RecordingSessionSummary) -> String? {
        guard let notes = outing.storedSession?.notes?.trimmingCharacters(in: .whitespacesAndNewlines), !notes.isEmpty else {
            return nil
        }
        return notes
    }

    private static func uniqueOutingPoints(_ outings: [RecordingSessionSummary]) -> [LocationPoint] {
        var seenIDs = Set<UUID>()
        return outings
            .flatMap(\.points)
            .sorted { $0.timestamp < $1.timestamp }
            .filter { seenIDs.insert($0.id).inserted }
    }

    private static func outingVisitsMarkdown(_ visits: [Visit], options: ExportOptions) -> String {
        var headerCols: [String] = ["Arrived", "Departed"]
        if options.includeVisitDuration { headerCols.append("Duration") }
        if options.includeVisitCoordinates {
            headerCols.append("Lat")
            headerCols.append("Lon")
        }
        if options.includeVisitLocationName { headerCols.append("Location") }
        if options.includeVisitAddress { headerCols.append("Address") }
        if options.includeVisitNotes { headerCols.append("Notes") }

        headerCols.append("Visit Metadata")
        var md = "## Visits\n\n"
        md += "| " + headerCols.joined(separator: " | ") + " |\n"
        md += "|" + headerCols.map { _ in "------" }.joined(separator: "|") + "|\n"

        for visit in visits {
            var cells: [String] = []
            cells.append(formattedDateTime(visit.arrivedAt))
            cells.append(visit.departedAt.map(formattedDateTime) ?? "-")
            if options.includeVisitDuration {
                cells.append(visit.durationMinutes.map { String(format: "%.1f min", $0) } ?? "-")
            }
            if options.includeVisitCoordinates {
                cells.append(String(format: "%.6f", visit.latitude))
                cells.append(String(format: "%.6f", visit.longitude))
            }
            if options.includeVisitLocationName { cells.append(escapeMarkdownTableCell(visit.exportLocationName)) }
            if options.includeVisitAddress { cells.append(escapeMarkdownTableCell(visit.address)) }
            if options.includeVisitNotes { cells.append(escapeMarkdownTableCell(visit.notes)) }
            cells.append(escapeMarkdownTableCell(auditMetadata(visit, options: options).json()))
            md += "| " + cells.joined(separator: " | ") + " |\n"
        }

        return md + "\n"
    }

    private static func outingPointsMarkdown(_ points: [LocationPoint], options: ExportOptions) -> String {
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }
        var headerCols: [String] = ["Time", "Lat", "Lon"]
        if options.includePointSpeed { headerCols.append("Speed") }
        if options.includePointAltitude { headerCols.append("Altitude") }
        if options.includePointAccuracy { headerCols.append("Accuracy") }
        if options.includePointOutlierFlag { headerCols.append("Outlier") }

        var md = "## Route Points\n\n"
        md += "| " + headerCols.joined(separator: " | ") + " |\n"
        md += "|" + headerCols.map { _ in "------" }.joined(separator: "|") + "|\n"

        for point in sortedPoints {
            var cells: [String] = []
            cells.append(formattedDateTime(point.timestamp))
            cells.append(String(format: "%.6f", point.latitude))
            cells.append(String(format: "%.6f", point.longitude))
            if options.includePointSpeed { cells.append(point.speed.map { String(format: "%.1f m/s", $0) } ?? "-") }
            if options.includePointAltitude { cells.append(point.altitude.map { String(format: "%.1f m", $0) } ?? "-") }
            if options.includePointAccuracy { cells.append(String(format: "%.1f m", point.horizontalAccuracy)) }
            if options.includePointOutlierFlag { cells.append(point.isOutlier ? "yes" : "-") }
            md += "| " + cells.joined(separator: " | ") + " |\n"
        }

        return md + "\n"
    }

    private static func formattedDateTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    private static func yamlString(_ key: String, _ value: String?) -> String {
        guard let value, !value.isEmpty else { return "\(key): null\n" }
        let normalized = value
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        if normalized.contains("\n") {
            let lines = normalized.split(separator: "\n", omittingEmptySubsequences: false)
            return "\(key): |-\n" + lines.map { "  \($0)" }.joined(separator: "\n") + "\n"
        }
        return "\(key): \(yamlQuoted(normalized))\n"
    }

    private static func yamlStringArray(_ key: String, _ values: [String]) -> String {
        guard !values.isEmpty else { return "\(key): []\n" }
        return "\(key):\n" + values.map { "  - \(yamlQuoted($0))" }.joined(separator: "\n") + "\n"
    }

    private static func yamlNumber(_ key: String, _ value: Double, decimals: Int) -> String {
        "\(key): \(String(format: "%.\(decimals)f", value))\n"
    }

    private static func yamlOptionalNumber(_ key: String, _ value: Double?, decimals: Int) -> String {
        guard let value else { return "\(key): null\n" }
        return yamlNumber(key, value, decimals: decimals)
    }

    private static func yamlQuoted(_ value: String) -> String {
        let escaped = value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
        return "\"\(escaped)\""
    }
}

// MARK: - GPX Export (https://www.topografix.com/GPX/1/1/)

extension ExportService {
    /// New <trkseg> when consecutive points are more than this many seconds apart.
    fileprivate static let gpxSegmentGapSeconds: TimeInterval = 600

    fileprivate static let gpxNamespace = "https://isome.isolated.tech/gpx/1.0"

    fileprivate static func escapeXML(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count)
        for c in s {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            default: out.append(c)
            }
        }
        return out
    }

    fileprivate static func gpxCoord(_ d: Double) -> String {
        String(format: "%.7f", d)
    }

    fileprivate static func gpxNumber(_ d: Double, decimals: Int = 2) -> String {
        String(format: "%.\(decimals)f", d)
    }

    private static func gpxHeader() -> String {
        let now = iso8601Formatter.string(from: Date())
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Whereday" xmlns="http://www.topografix.com/GPX/1/1" xmlns:isome="\(gpxNamespace)">
          <metadata>
            <name>Whereday export</name>
            <time>\(now)</time>
          </metadata>
        """
    }

    private static func gpxFooter() -> String { "</gpx>\n" }

    private static func gpxWaypoint(_ visit: Visit, options: ExportOptions) -> String? {
        // <wpt> requires lat/lon; if the user opted out of coordinates, skip.
        guard options.includeVisitCoordinates else { return nil }

        var xml = "  <wpt lat=\"\(gpxCoord(visit.latitude))\" lon=\"\(gpxCoord(visit.longitude))\">\n"
        xml += "    <time>\(iso8601Formatter.string(from: visit.arrivedAt))</time>\n"

        if options.includeVisitLocationName, let name = visit.exportLocationName, !name.isEmpty {
            xml += "    <name>\(escapeXML(name))</name>\n"
        } else {
            xml += "    <name>Visit</name>\n"
        }

        var descParts: [String] = []
        if options.includeVisitAddress, let address = visit.address, !address.isEmpty {
            descParts.append(address)
        }
        if options.includeVisitNotes, let notes = visit.notes, !notes.isEmpty {
            descParts.append(notes)
        }
        if !descParts.isEmpty {
            xml += "    <desc>\(escapeXML(descParts.joined(separator: " — ")))</desc>\n"
        }

        var ext = ""
        if let departed = visit.departedAt {
            ext += "      <isome:departedAt>\(iso8601Formatter.string(from: departed))</isome:departedAt>\n"
        }
        if options.includeVisitDuration, let mins = visit.durationMinutes {
            ext += "      <isome:durationMinutes>\(gpxNumber(mins, decimals: 2))</isome:durationMinutes>\n"
        }
        for (key, value) in auditMetadata(visit, options: options).fields() {
            ext += "      <isome:\(key)>\(escapeXML(value))</isome:\(key)>\n"
        }
        if !ext.isEmpty {
            xml += "    <extensions>\n\(ext)    </extensions>\n"
        }

        xml += "  </wpt>\n"
        return xml
    }

    private static func gpxTrack(_ points: [LocationPoint], options: ExportOptions) -> String {
        guard !points.isEmpty else { return "" }
        let sorted = points.sorted { $0.timestamp < $1.timestamp }

        var xml = "  <trk>\n    <name>Whereday track</name>\n"
        xml += "    <trkseg>\n"

        var previous: LocationPoint?
        for p in sorted {
            if let prev = previous,
               p.timestamp.timeIntervalSince(prev.timestamp) > gpxSegmentGapSeconds {
                xml += "    </trkseg>\n    <trkseg>\n"
            }
            xml += gpxTrackpoint(p, options: options)
            previous = p
        }

        xml += "    </trkseg>\n  </trk>\n"
        return xml
    }

    private static func gpxTrackpoint(_ p: LocationPoint, options: ExportOptions) -> String {
        var xml = "      <trkpt lat=\"\(gpxCoord(p.latitude))\" lon=\"\(gpxCoord(p.longitude))\">\n"
        if options.includePointAltitude, let alt = p.altitude {
            xml += "        <ele>\(gpxNumber(alt, decimals: 2))</ele>\n"
        }
        xml += "        <time>\(iso8601Formatter.string(from: p.timestamp))</time>\n"

        var ext = ""
        if options.includePointSpeed, let speed = p.speed {
            ext += "          <isome:speed>\(gpxNumber(speed, decimals: 2))</isome:speed>\n"
        }
        if options.includePointAccuracy {
            ext += "          <isome:horizontalAccuracy>\(gpxNumber(p.horizontalAccuracy, decimals: 2))</isome:horizontalAccuracy>\n"
        }
        if options.includePointOutlierFlag, p.isOutlier {
            ext += "          <isome:isOutlier>true</isome:isOutlier>\n"
        }
        if !ext.isEmpty {
            xml += "        <extensions>\n\(ext)        </extensions>\n"
        }

        xml += "      </trkpt>\n"
        return xml
    }

    static func exportVisitsToGPX(visits: [Visit], options: ExportOptions = ExportOptions()) -> Data {
        var xml = gpxHeader() + "\n"
        for v in visits {
            if let wpt = gpxWaypoint(v, options: options) { xml += wpt }
        }
        xml += gpxFooter()
        return xml.data(using: .utf8) ?? Data()
    }

    static func exportLocationPointsToGPX(points: [LocationPoint], options: ExportOptions = ExportOptions()) -> Data {
        var xml = gpxHeader() + "\n"
        xml += gpxTrack(points, options: options)
        xml += gpxFooter()
        return xml.data(using: .utf8) ?? Data()
    }

    static func exportCombinedToGPX(visits: [Visit], points: [LocationPoint], options: ExportOptions = ExportOptions()) -> Data {
        var xml = gpxHeader() + "\n"
        for v in visits {
            if let wpt = gpxWaypoint(v, options: options) { xml += wpt }
        }
        xml += gpxTrack(points, options: options)
        xml += gpxFooter()
        return xml.data(using: .utf8) ?? Data()
    }
}

// MARK: - KML Export (https://developers.google.com/kml/documentation/kmlreference)

extension ExportService {
    static func kmlString(visits: [Visit], points: [LocationPoint], options: ExportOptions = ExportOptions()) -> String {
        var xml = kmlHeader() + "\n"

        let sortedVisits = visits.sorted { $0.arrivedAt < $1.arrivedAt }
        for visit in sortedVisits {
            if let placemark = kmlVisitPlacemark(visit, options: options) {
                xml += placemark
            }
        }

        let sessions = kmlPointSessions(points.sorted { $0.timestamp < $1.timestamp })
        for (index, session) in sessions.enumerated() {
            xml += kmlSessionPlacemark(session, index: index + 1, options: options)
        }

        xml += kmlFooter()
        return xml
    }

    private static func kmlHeader() -> String {
        let now = iso8601Formatter.string(from: Date())
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2" xmlns:isome="\(gpxNamespace)">
          <Document>
            <name>Whereday export</name>
            <open>1</open>
            <ExtendedData>
              <Data name="generator"><value>Whereday</value></Data>
              <Data name="exportDate"><value>\(now)</value></Data>
            </ExtendedData>
        """
    }

    private static func kmlFooter() -> String {
        "  </Document>\n</kml>\n"
    }

    private static func kmlCoordinate(lon: Double, lat: Double, altitude: Double? = nil) -> String {
        if let altitude {
            return "\(gpxCoord(lon)),\(gpxCoord(lat)),\(gpxNumber(altitude, decimals: 2))"
        }
        return "\(gpxCoord(lon)),\(gpxCoord(lat))"
    }

    private static func kmlGXCoordinate(_ point: LocationPoint, options: ExportOptions) -> String {
        let altitude = (options.includePointAltitude ? point.altitude : nil) ?? 0
        return "\(gpxCoord(point.longitude)) \(gpxCoord(point.latitude)) \(gpxNumber(altitude, decimals: 2))"
    }

    private static func kmlVisitPlacemark(_ visit: Visit, options: ExportOptions) -> String? {
        guard options.includeVisitCoordinates else { return nil }

        let name = {
            if options.includeVisitLocationName, let locationName = visit.exportLocationName, !locationName.isEmpty {
                return locationName
            }
            return "Visit"
        }()

        var xml = "    <Placemark>\n"
        xml += "      <name>\(escapeXML(name))</name>\n"
        xml += "      <TimeStamp><when>\(iso8601Formatter.string(from: visit.arrivedAt))</when></TimeStamp>\n"

        var descParts: [String] = []
        if options.includeVisitAddress, let address = visit.address, !address.isEmpty {
            descParts.append(address)
        }
        if options.includeVisitNotes, let notes = visit.notes, !notes.isEmpty {
            descParts.append(notes)
        }
        if !descParts.isEmpty {
            xml += "      <description>\(escapeXML(descParts.joined(separator: "\n\n")))</description>\n"
        }

        var extended = ""
        extended += "        <Data name=\"kind\"><value>visit</value></Data>\n"
        extended += "        <Data name=\"arrivedAt\"><value>\(iso8601Formatter.string(from: visit.arrivedAt))</value></Data>\n"
        if let departedAt = visit.departedAt {
            extended += "        <Data name=\"departedAt\"><value>\(iso8601Formatter.string(from: departedAt))</value></Data>\n"
        }
        if options.includeVisitDuration, let durationMinutes = visit.durationMinutes {
            extended += "        <Data name=\"durationMinutes\"><value>\(gpxNumber(durationMinutes, decimals: 2))</value></Data>\n"
        }
        if options.includeVisitAddress, let address = visit.address, !address.isEmpty {
            extended += "        <Data name=\"address\"><value>\(escapeXML(address))</value></Data>\n"
        }
        if options.includeVisitNotes, let notes = visit.notes, !notes.isEmpty {
            extended += "        <Data name=\"notes\"><value>\(escapeXML(notes))</value></Data>\n"
        }
        for (key, value) in auditMetadata(visit, options: options).fields() {
            extended += "        <Data name=\"\(key)\"><value>\(escapeXML(value))</value></Data>\n"
        }
        xml += "      <ExtendedData>\n\(extended)      </ExtendedData>\n"
        xml += "      <Point><coordinates>\(kmlCoordinate(lon: visit.longitude, lat: visit.latitude))</coordinates></Point>\n"
        xml += "    </Placemark>\n"
        return xml
    }

    private static func kmlPointSessions(_ sortedPoints: [LocationPoint]) -> [[LocationPoint]] {
        guard !sortedPoints.isEmpty else { return [] }
        var sessions: [[LocationPoint]] = []
        var current: [LocationPoint] = []
        var previous: LocationPoint?

        for point in sortedPoints {
            if let previous,
               point.timestamp.timeIntervalSince(previous.timestamp) > gpxSegmentGapSeconds,
               !current.isEmpty {
                sessions.append(current)
                current = []
            }
            current.append(point)
            previous = point
        }

        if !current.isEmpty {
            sessions.append(current)
        }
        return sessions
    }

    private static func kmlSessionPlacemark(_ points: [LocationPoint], index: Int, options: ExportOptions) -> String {
        guard let first = points.first else { return "" }
        let last = points.last ?? first

        var xml = "    <Placemark>\n"
        xml += "      <name>Whereday session \(index)</name>\n"
        xml += "      <TimeSpan><begin>\(iso8601Formatter.string(from: first.timestamp))</begin><end>\(iso8601Formatter.string(from: last.timestamp))</end></TimeSpan>\n"
        xml += "      <ExtendedData>\n"
        xml += "        <Data name=\"kind\"><value>trackingSession</value></Data>\n"
        xml += "        <Data name=\"pointCount\"><value>\(points.count)</value></Data>\n"
        xml += "      </ExtendedData>\n"
        xml += "      <MultiGeometry>\n"
        if points.count > 1 {
            xml += "        <LineString>\n"
            xml += "          <tessellate>1</tessellate>\n"
            xml += "          <altitudeMode>clampToGround</altitudeMode>\n"
            xml += "          <coordinates>\n"
            for point in points {
                let altitude = options.includePointAltitude ? point.altitude : nil
                xml += "            \(kmlCoordinate(lon: point.longitude, lat: point.latitude, altitude: altitude))\n"
            }
            xml += "          </coordinates>\n"
            xml += "        </LineString>\n"
        } else {
            let altitude = options.includePointAltitude ? first.altitude : nil
            xml += "        <Point><coordinates>\(kmlCoordinate(lon: first.longitude, lat: first.latitude, altitude: altitude))</coordinates></Point>\n"
        }
        xml += "        <gx:Track>\n"
        xml += "          <altitudeMode>clampToGround</altitudeMode>\n"
        for point in points {
            xml += "          <when>\(iso8601Formatter.string(from: point.timestamp))</when>\n"
        }
        for point in points {
            xml += "          <gx:coord>\(kmlGXCoordinate(point, options: options))</gx:coord>\n"
        }
        xml += "        </gx:Track>\n"
        xml += "      </MultiGeometry>\n"
        xml += "    </Placemark>\n"
        return xml
    }

    static func exportVisitsToKML(visits: [Visit], options: ExportOptions = ExportOptions()) -> Data {
        kmlString(visits: visits, points: [], options: options).data(using: .utf8) ?? Data()
    }

    static func exportLocationPointsToKML(points: [LocationPoint], options: ExportOptions = ExportOptions()) -> Data {
        kmlString(visits: [], points: points, options: options).data(using: .utf8) ?? Data()
    }

    static func exportCombinedToKML(visits: [Visit], points: [LocationPoint], options: ExportOptions = ExportOptions()) -> Data {
        kmlString(visits: visits, points: points, options: options).data(using: .utf8) ?? Data()
    }
}

// MARK: - GeoJSON (https://datatracker.ietf.org/doc/html/rfc7946)

extension ExportService {
    private struct GeoJSONFeatureCollection: Encodable {
        let type = "FeatureCollection"
        let features: [GeoJSONFeature]
        let generator: String
        let exportDate: String
    }

    private struct GeoJSONFeature: Encodable {
        let type = "Feature"
        let geometry: GeoJSONGeometry
        let properties: GeoJSONProperties
    }

    /// RFC 7946 only defines `Point`/`LineString`/etc. with a coordinates array
    /// of `[lon, lat]` or `[lon, lat, altitude]`. Order matters: longitude first.
    private struct GeoJSONGeometry: Encodable {
        let type: String
        let coordinates: [Double]
    }

    /// Wraps either visit or point property bags so a single `FeatureCollection`
    /// can hold both kinds in the combined export.
    private enum GeoJSONProperties: Encodable {
        case visit(VisitProperties)
        case point(PointProperties)

        func encode(to encoder: Encoder) throws {
            var container = encoder.singleValueContainer()
            switch self {
            case .visit(let p): try container.encode(p)
            case .point(let p): try container.encode(p)
            }
        }
    }

    private struct VisitProperties: Encodable {
        let kind = "visit"
        let arrivedAt: String
        let departedAt: String?
        let durationMinutes: Double?
        let locationName: String?
        let address: String?
        let notes: String?
        let audit: VisitAuditMetadata

        private enum CodingKeys: String, CodingKey {
            case kind, arrivedAt, departedAt, durationMinutes, locationName, address, notes
        }

        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(kind, forKey: .kind)
            try container.encode(arrivedAt, forKey: .arrivedAt)
            try container.encodeIfPresent(departedAt, forKey: .departedAt)
            try container.encodeIfPresent(durationMinutes, forKey: .durationMinutes)
            try container.encodeIfPresent(locationName, forKey: .locationName)
            try container.encodeIfPresent(address, forKey: .address)
            try container.encodeIfPresent(notes, forKey: .notes)
            try audit.encode(to: encoder)
        }
    }

    private struct PointProperties: Encodable {
        let kind = "point"
        let timestamp: String
        let timestampUnix: Double
        let altitude: Double?
        let speed: Double?
        let horizontalAccuracy: Double?
        let isOutlier: Bool?
    }

    private static func geoJSONVisitFeature(_ visit: Visit, options: ExportOptions) -> GeoJSONFeature? {
        // GeoJSON geometry needs valid coordinates; if the user opted out, skip.
        guard options.includeVisitCoordinates else { return nil }

        let props = VisitProperties(
            arrivedAt: iso8601Formatter.string(from: visit.arrivedAt),
            departedAt: visit.departedAt.map { iso8601Formatter.string(from: $0) },
            durationMinutes: options.includeVisitDuration ? visit.durationMinutes : nil,
            locationName: options.includeVisitLocationName ? visit.exportLocationName : nil,
            address: options.includeVisitAddress ? visit.address : nil,
            notes: options.includeVisitNotes ? visit.notes : nil,
            audit: auditMetadata(visit, options: options)
        )

        return GeoJSONFeature(
            geometry: GeoJSONGeometry(type: "Point", coordinates: [visit.longitude, visit.latitude]),
            properties: .visit(props)
        )
    }

    private static func geoJSONPointFeature(_ point: LocationPoint, options: ExportOptions) -> GeoJSONFeature {
        var coords: [Double] = [point.longitude, point.latitude]
        if options.includePointAltitude, let alt = point.altitude {
            coords.append(alt)
        }

        let props = PointProperties(
            timestamp: iso8601Formatter.string(from: point.timestamp),
            timestampUnix: point.timestamp.timeIntervalSince1970,
            altitude: options.includePointAltitude ? point.altitude : nil,
            speed: options.includePointSpeed ? point.speed : nil,
            horizontalAccuracy: options.includePointAccuracy ? point.horizontalAccuracy : nil,
            isOutlier: options.includePointOutlierFlag ? point.isOutlier : nil
        )

        return GeoJSONFeature(
            geometry: GeoJSONGeometry(type: "Point", coordinates: coords),
            properties: .point(props)
        )
    }

    private static func encodeGeoJSON(_ features: [GeoJSONFeature]) throws -> Data {
        let collection = GeoJSONFeatureCollection(
            features: features,
            generator: "Whereday",
            exportDate: iso8601Formatter.string(from: Date())
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        return try encoder.encode(collection)
    }

    static func exportVisitsToGeoJSON(visits: [Visit], options: ExportOptions = ExportOptions()) throws -> Data {
        let features = visits.compactMap { geoJSONVisitFeature($0, options: options) }
        return try encodeGeoJSON(features)
    }

    static func exportLocationPointsToGeoJSON(points: [LocationPoint], options: ExportOptions = ExportOptions()) throws -> Data {
        let sorted = points.sorted { $0.timestamp < $1.timestamp }
        let features = sorted.map { geoJSONPointFeature($0, options: options) }
        return try encodeGeoJSON(features)
    }

    static func exportCombinedToGeoJSON(visits: [Visit], points: [LocationPoint], options: ExportOptions = ExportOptions()) throws -> Data {
        var features = visits.compactMap { geoJSONVisitFeature($0, options: options) }
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }
        features.append(contentsOf: sortedPoints.map { geoJSONPointFeature($0, options: options) })
        return try encodeGeoJSON(features)
    }
}

// MARK: - Options-Driven Entrypoints

extension ExportService {
    /// Resolves the data, applies filters, and returns the encoded payload + suggested filename.
    static func render(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        filenamePattern: String = FilenameTemplate.defaultPattern
    ) throws -> (data: Data, fileName: String) {
        try IsoMeExportKitAdapter.render(
            visits: visits,
            points: points,
            recordingSessions: recordingSessions,
            activeTrackingStart: activeTrackingStart,
            options: options,
            filenamePattern: filenamePattern
        )
    }

    /// Renders one file per calendar day. Each day's file is produced by running
    /// the standard `render` pipeline against just that day's data. The day's
    /// `startOfDay` is threaded through the path planner so date tokens reflect
    /// the day represented by the file.
    static func renderPerDay(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        filenamePattern: String = FilenameTemplate.defaultPattern
    ) throws -> [(data: Data, fileName: String)] {
        try IsoMeExportKitAdapter.renderPerDay(
            visits: visits,
            points: points,
            recordingSessions: recordingSessions,
            activeTrackingStart: activeTrackingStart,
            options: options,
            filenamePattern: filenamePattern
        )
    }

    /// Ensures per-day filenames don't collide if the user's pattern omits
    /// date/day tokens. Falls back to prefixing the ISO date.
    private static func uniqueFilename(
        _ baseName: String,
        in used: inout Set<String>,
        day: Date,
        format: ExportFormat
    ) -> String {
        if !used.contains(baseName) {
            used.insert(baseName)
            return baseName
        }

        let dateFmt = DateFormatter()
        dateFmt.locale = Locale(identifier: "en_US_POSIX")
        dateFmt.dateFormat = "yyyy-MM-dd"
        let isoDay = dateFmt.string(from: day)

        let ext = ".\(format.fileExtension)"
        let stem: String
        if baseName.hasSuffix(ext) {
            stem = String(baseName.dropLast(ext.count))
        } else {
            stem = baseName
        }

        var candidate = "\(isoDay)_\(stem)\(ext)"
        var counter = 2
        while used.contains(candidate) {
            candidate = "\(isoDay)_\(stem)_\(counter)\(ext)"
            counter += 1
        }
        used.insert(candidate)
        return candidate
    }

    @MainActor
    static func share(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        selectedFormats: [ExportFormat]? = nil,
        filenamePattern: String = FilenameTemplate.defaultPattern,
        from viewController: UIViewController? = nil,
        completion: ((Bool) -> Void)? = nil
    ) throws {
        let plannedFiles = try IsoMeExportKitAdapter.plannedFiles(
            visits: visits,
            points: points,
            recordingSessions: recordingSessions,
            activeTrackingStart: activeTrackingStart,
            options: options,
            selectedFormats: selectedFormats,
            filenamePattern: filenamePattern
        )
        let fileURLs = try IsoMeExportKitAdapter.writeTemporaryFiles(plannedFiles)

        guard !fileURLs.isEmpty else { return }

        let activityVC = UIActivityViewController(
            activityItems: activityItems(for: plannedFiles, fileURLs: fileURLs),
            applicationActivities: nil
        )
        activityVC.completionWithItemsHandler = { _, completed, _, _ in
            completion?(completed)
        }

        presentShareSheet(activityVC, from: viewController)
    }

    @MainActor
    static func saveToDefaultFolder(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        selectedFormats: [ExportFormat]? = nil,
        filenamePattern: String = FilenameTemplate.defaultPattern,
        filenameDate: Date? = nil,
        writeMode: ExportWriteMode = .overwrite,
        mergeStrategy: (any ExportMergeStrategy)? = nil
    ) throws -> [URL] {
        let files = try IsoMeExportKitAdapter.plannedFiles(
            visits: visits,
            points: points,
            recordingSessions: recordingSessions,
            activeTrackingStart: activeTrackingStart,
            options: options,
            selectedFormats: selectedFormats,
            filenamePattern: filenamePattern,
            aggregateFilenameDate: filenameDate
        )
        // Merge strategies are keyed by the planned file's format id.
        let mergeStrategies: [String: any ExportMergeStrategy] = mergeStrategy.map {
            [options.format.exportKitFormatID: $0]
        } ?? [:]
        guard let urls = try ExportFolderManager.shared.savePlannedFilesToDefaultFolder(
            files,
            mode: writeMode,
            mergeStrategies: mergeStrategies
        ) else {
            throw ExportFolderError.noDefaultFolder
        }
        return urls
    }

    @MainActor
    static func shareOuting(
        _ outing: RecordingSessionSummary,
        visits: [Visit],
        options: ExportOptions,
        filenamePattern: String = FilenameTemplate.defaultPattern,
        from viewController: UIViewController? = nil,
        completion: ((Bool) -> Void)? = nil
    ) throws {
        let files = try IsoMeExportKitAdapter.plannedOutingFiles(
            outings: [outing],
            visits: visits,
            options: options,
            filenamePattern: filenamePattern
        )
        let fileURLs = try IsoMeExportKitAdapter.writeTemporaryFiles(files)
        guard !fileURLs.isEmpty else { return }

        let activityVC = UIActivityViewController(
            activityItems: activityItems(for: fileURLs, format: options.format),
            applicationActivities: nil
        )
        activityVC.completionWithItemsHandler = { _, completed, _, _ in
            completion?(completed)
        }

        presentShareSheet(activityVC, from: viewController)
    }

    @MainActor
    static func saveOutingToDefaultFolder(
        _ outing: RecordingSessionSummary,
        visits: [Visit],
        options: ExportOptions,
        filenamePattern: String = FilenameTemplate.defaultPattern
    ) throws -> [URL] {
        let files = try IsoMeExportKitAdapter.plannedOutingFiles(
            outings: [outing],
            visits: visits,
            options: options,
            filenamePattern: filenamePattern
        )
        guard let urls = try ExportFolderManager.shared.savePlannedFilesToDefaultFolder(files) else {
            throw ExportFolderError.noDefaultFolder
        }
        return urls
    }
}
