import Foundation

enum ImportError: LocalizedError {
    case unsupportedFormat
    case invalidData(String)
    case parsingFailed(String)

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat:
            return String(localized: "Unsupported file format. Import supports Google Timeline JSON and Whereday JSON, CSV, or Markdown files. KML, GPX, and GeoJSON exports are one-way map files.")
        case .invalidData(let detail):
            return String(format: String(localized: "Invalid data: %@"), detail)
        case .parsingFailed(let detail):
            return String(format: String(localized: "Parsing failed: %@"), detail)
        }
    }
}

enum ImportDataType {
    case visits
    case locationPoints
}

struct ImportedVisit: Sendable {
    let latitude: Double
    let longitude: Double
    let arrivedAt: Date
    let departedAt: Date?
    let customName: String?
    let locationName: String?
    let address: String?
    let notes: String?
    let sourceRaw: String?
    let confirmationStatusRaw: String?
    let confirmedAt: Date?
    let updatedAt: Date?
    let originalLatitude: Double?
    let originalLongitude: Double?
    let originalLocationName: String?
    let originalAddress: String?
    let detectedLatitude: Double?
    let detectedLongitude: Double?
    let detectedLocationName: String?
    let detectedAddress: String?
    let placeSourceRaw: String?
    let placeDistanceMeters: Double?

    init(
        latitude: Double,
        longitude: Double,
        arrivedAt: Date,
        departedAt: Date?,
        customName: String? = nil,
        locationName: String?,
        address: String?,
        notes: String?,
        sourceRaw: String? = nil,
        confirmationStatusRaw: String? = nil,
        confirmedAt: Date? = nil,
        updatedAt: Date? = nil,
        originalLatitude: Double? = nil,
        originalLongitude: Double? = nil,
        originalLocationName: String? = nil,
        originalAddress: String? = nil,
        detectedLatitude: Double? = nil,
        detectedLongitude: Double? = nil,
        detectedLocationName: String? = nil,
        detectedAddress: String? = nil,
        placeSourceRaw: String? = nil,
        placeDistanceMeters: Double? = nil
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.arrivedAt = arrivedAt
        self.departedAt = departedAt
        self.customName = customName
        self.locationName = locationName
        self.address = address
        self.notes = notes
        self.sourceRaw = sourceRaw
        self.confirmationStatusRaw = confirmationStatusRaw
        self.confirmedAt = confirmedAt
        self.updatedAt = updatedAt
        self.originalLatitude = originalLatitude
        self.originalLongitude = originalLongitude
        self.originalLocationName = originalLocationName
        self.originalAddress = originalAddress
        self.detectedLatitude = detectedLatitude
        self.detectedLongitude = detectedLongitude
        self.detectedLocationName = detectedLocationName
        self.detectedAddress = detectedAddress
        self.placeSourceRaw = placeSourceRaw
        self.placeDistanceMeters = placeDistanceMeters
    }
}

extension ImportedVisit {
    func applyingAuditJSON(_ json: String?) throws -> ImportedVisit {
        guard let json, !json.isEmpty, json != "-" else { return self }
        let metadata = try JSONDecoder().decode([String: String].self, from: Data(json.utf8))
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return ImportedVisit(
            latitude: latitude, longitude: longitude, arrivedAt: arrivedAt, departedAt: departedAt,
            customName: customName, locationName: locationName, address: address, notes: notes,
            sourceRaw: metadata["source"] ?? sourceRaw,
            confirmationStatusRaw: metadata["confirmationStatus"] ?? confirmationStatusRaw,
            confirmedAt: metadata["confirmedAt"].flatMap { formatter.date(from: $0) },
            updatedAt: metadata["updatedAt"].flatMap { formatter.date(from: $0) },
            originalLatitude: metadata["originalLatitude"].flatMap(Double.init),
            originalLongitude: metadata["originalLongitude"].flatMap(Double.init),
            originalLocationName: metadata["originalLocationName"],
            originalAddress: metadata["originalAddress"],
            detectedLatitude: metadata["detectedLatitude"].flatMap(Double.init),
            detectedLongitude: metadata["detectedLongitude"].flatMap(Double.init),
            detectedLocationName: metadata["detectedLocationName"],
            detectedAddress: metadata["detectedAddress"],
            placeSourceRaw: metadata["placeSource"],
            placeDistanceMeters: metadata["placeDistanceMeters"].flatMap(Double.init)
        )
    }
}

struct ImportedLocationPoint: Sendable {
    let latitude: Double
    let longitude: Double
    let timestamp: Date
    let altitude: Double?
    let speed: Double?
    let horizontalAccuracy: Double
    let isOutlier: Bool
}

struct ImportResult {
    let visitCount: Int
    let pointCount: Int

    var summary: String {
        switch (visitCount, pointCount) {
        case (0, 0):
            return String(localized: "No data imported.")
        case (1, 0):
            return String(localized: "Imported 1 visit.")
        case (let visits, 0):
            return String(format: String(localized: "Imported %d visits."), visits)
        case (0, 1):
            return String(localized: "Imported 1 point.")
        case (0, let points):
            return String(format: String(localized: "Imported %d points."), points)
        case (1, 1):
            return String(localized: "Imported 1 visit and 1 point.")
        case (1, let points):
            return String(format: String(localized: "Imported 1 visit and %d points."), points)
        case (let visits, 1):
            return String(format: String(localized: "Imported %d visits and 1 point."), visits)
        case (let visits, let points):
            return String(format: String(localized: "Imported %d visits and %d points."), visits, points)
        }
    }
}

struct ImportService {
    private static let iso8601Formatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let iso8601FallbackFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    // MARK: - Format Detection

    static func detectFormat(url: URL) -> ExportFormat? {
        switch url.pathExtension.lowercased() {
        case "json": return .json
        case "csv": return .csv
        case "md", "markdown": return .markdown
        case "kml": return .kml
        case "geojson": return .geojson
        default: return nil
        }
    }

    static func detectDataType(from data: Data, format: ExportFormat) -> ImportDataType {
        switch format {
        case .json:
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if json["visits"] != nil { return .visits }
                if json["points"] != nil { return .locationPoints }
            }
        case .csv:
            if let header = String(data: data, encoding: .utf8)?.components(separatedBy: "\n").first {
                if header.contains("arrived_at") { return .visits }
                if header.contains("timestamp") { return .locationPoints }
            }
        case .markdown:
            if let text = String(data: data, encoding: .utf8) {
                let headers = markdownHeaders(text)
                if !headers.visits && headers.points { return .locationPoints }
                if (text.contains("# Whereday Location Points Export") || text.contains("# iso.me Location Points Export")) { return .locationPoints }
                if (text.contains("# Whereday Export") || text.contains("# iso.me Export")) { return .visits }
            }
        case .owntracks, .overland, .gpx, .kml, .geojson:
            return .locationPoints
        }
        return .visits
    }

    // MARK: - Unified Import

    static func importFile(data: Data, format: ExportFormat) throws -> (visits: [ImportedVisit], points: [ImportedLocationPoint]) {
        if format == .json, let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
           root["visits"] != nil, root["points"] != nil {
            return (visits: try importVisits(data: data, format: format),
                    points: try importLocationPoints(data: data, format: format))
        }
        if format == .markdown, let text = String(data: data, encoding: .utf8) {
            let headers = markdownHeaders(text)
            if headers.visits && headers.points {
                return (headers.visitRows == 0 ? [] : try importVisits(data: data, format: format),
                        headers.pointRows == 0 ? [] : try importLocationPoints(data: data, format: format))
            }
        }
        if format == .csv, let text = String(data: data, encoding: .utf8),
           let sections = combinedCSVSections(text) {
            return (sections.visitRows == 0 ? [] : try importVisits(data: sections.visits, format: format),
                    sections.pointRows == 0 ? [] : try importLocationPoints(data: sections.points, format: format))
        }
        let dataType = detectDataType(from: data, format: format)

        switch dataType {
        case .visits:
            let visits = try importVisits(data: data, format: format)
            return (visits: visits, points: [])
        case .locationPoints:
            let points = try importLocationPoints(data: data, format: format)
            return (visits: [], points: points)
        }
    }

    private static func markdownHeaders(_ text: String) -> (visits: Bool, points: Bool, visitRows: Int, pointRows: Int) {
        var visits = false
        var points = false
        var visitRows = 0
        var pointRows = 0
        var subject: ImportDataType?
        for line in text.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("## ") { subject = nil; continue }
            guard trimmed.hasPrefix("|") else { continue }
            let cells = parseMarkdownTableCells(trimmed)
            if isMarkdownSeparator(cells) { continue }
            if cells.first == "Arrived" { visits = true; subject = .visits }
            else if cells.first == "Time" { points = true; subject = .locationPoints }
            else {
                switch subject {
                case .visits: visitRows += 1
                case .locationPoints: pointRows += 1
                case nil: break
                }
            }
        }
        return (visits, points, visitRows, pointRows)
    }

    // Split logical CSV rows, so a quoted multiline note cannot create a false section boundary.
    private static func combinedCSVSections(_ text: String) -> (visits: Data, points: Data, visitRows: Int, pointRows: Int)? {
        let rows = parseCSVRows(text)
        guard rows.contains(where: { $0.first == "arrived_at" }), rows.contains(where: { $0.first == "timestamp" }) else { return nil }
        var visits: [[String]] = []
        var points: [[String]] = []
        var subject: ImportDataType?
        for row in rows {
            if row.first == "arrived_at" { subject = .visits }
            else if row.first == "timestamp" { subject = .locationPoints }
            else if row.allSatisfy({ $0.isEmpty }) || (row.count == 1 && row[0].hasPrefix("#")) { continue }
            switch subject {
            case .visits: visits.append(row)
            case .locationPoints: points.append(row)
            case nil: break
            }
        }
        func encode(_ rows: [[String]]) -> Data {
            Data((rows.map { row in row.map { "\"" + $0.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }.joined(separator: ",") }.joined(separator: "\n") + "\n").utf8)
        }
        return (encode(visits), encode(points), max(0, visits.count - 1), max(0, points.count - 1))
    }

    // MARK: - Visit Import

    static func importVisits(data: Data, format: ExportFormat) throws -> [ImportedVisit] {
        switch format {
        case .json: return try importVisitsFromJSON(data: data)
        case .csv: return try importVisitsFromCSV(data: data)
        case .markdown: return try importVisitsFromMarkdown(data: data)
        case .owntracks, .overland, .gpx, .kml, .geojson: throw ImportError.unsupportedFormat
        }
    }

    // MARK: - Location Point Import

    static func importLocationPoints(data: Data, format: ExportFormat) throws -> [ImportedLocationPoint] {
        switch format {
        case .json: return try importLocationPointsFromJSON(data: data)
        case .csv: return try importLocationPointsFromCSV(data: data)
        case .markdown: return try importLocationPointsFromMarkdown(data: data)
        case .owntracks, .overland, .gpx, .kml, .geojson: throw ImportError.unsupportedFormat
        }
    }

    // MARK: - JSON Visit Import

    private static func importVisitsFromJSON(data: Data) throws -> [ImportedVisit] {
        let json: [String: Any]
        do {
            guard let parsed = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw ImportError.invalidData(String(localized: "Expected JSON object at root"))
            }
            json = parsed
        } catch let error as ImportError {
            throw error
        } catch {
            throw ImportError.parsingFailed(String(localized: "Invalid JSON: \(error.localizedDescription)"))
        }

        guard let visitsArray = json["visits"] as? [[String: Any]] else {
            throw ImportError.invalidData(String(localized: "Missing 'visits' array"))
        }

        return try visitsArray.enumerated().map { index, dict in
            guard let latitude = dict["latitude"] as? Double,
                  let longitude = dict["longitude"] as? Double,
                  let arrivedAtStr = dict["arrivedAt"] as? String,
                  let arrivedAt = parseISO8601Date(arrivedAtStr) else {
                throw ImportError.invalidData(String(localized: "Visit at index \(index) missing required fields (latitude, longitude, arrivedAt)"))
            }

            let departedAt: Date? = (dict["departedAt"] as? String).flatMap { parseISO8601Date($0) }

            return ImportedVisit(
                latitude: latitude,
                longitude: longitude,
                arrivedAt: arrivedAt,
                departedAt: departedAt,
                customName: dict["customName"] as? String,
                locationName: dict["locationName"] as? String,
                address: dict["address"] as? String,
                notes: dict["notes"] as? String,
                sourceRaw: dict["source"] as? String,
                confirmationStatusRaw: dict["confirmationStatus"] as? String,
                confirmedAt: (dict["confirmedAt"] as? String).flatMap { parseISO8601Date($0) },
                updatedAt: (dict["updatedAt"] as? String).flatMap { parseISO8601Date($0) },
                originalLatitude: dict["originalLatitude"] as? Double,
                originalLongitude: dict["originalLongitude"] as? Double,
                originalLocationName: dict["originalLocationName"] as? String,
                originalAddress: dict["originalAddress"] as? String,
                detectedLatitude: dict["detectedLatitude"] as? Double,
                detectedLongitude: dict["detectedLongitude"] as? Double,
                detectedLocationName: dict["detectedLocationName"] as? String,
                detectedAddress: dict["detectedAddress"] as? String,
                placeSourceRaw: dict["placeSource"] as? String,
                placeDistanceMeters: dict["placeDistanceMeters"] as? Double
            )
        }
    }

    // MARK: - JSON Location Point Import

    private static func importLocationPointsFromJSON(data: Data) throws -> [ImportedLocationPoint] {
        let json: [String: Any]
        do {
            guard let parsed = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw ImportError.invalidData(String(localized: "Expected JSON object at root"))
            }
            json = parsed
        } catch let error as ImportError {
            throw error
        } catch {
            throw ImportError.parsingFailed(String(localized: "Invalid JSON: \(error.localizedDescription)"))
        }

        guard let pointsArray = json["points"] as? [[String: Any]] else {
            throw ImportError.invalidData(String(localized: "Missing 'points' array"))
        }

        return try pointsArray.enumerated().map { index, dict in
            guard let latitude = dict["latitude"] as? Double,
                  let longitude = dict["longitude"] as? Double else {
                throw ImportError.invalidData(String(localized: "Point at index \(index) missing latitude/longitude"))
            }

            let timestamp: Date
            if let tsStr = dict["timestamp"] as? String, let ts = parseISO8601Date(tsStr) {
                timestamp = ts
            } else if let tsUnix = dict["timestampUnix"] as? Double {
                timestamp = Date(timeIntervalSince1970: tsUnix)
            } else {
                throw ImportError.invalidData(String(localized: "Point at index \(index) missing timestamp"))
            }

            return ImportedLocationPoint(
                latitude: latitude,
                longitude: longitude,
                timestamp: timestamp,
                altitude: dict["altitude"] as? Double,
                speed: dict["speed"] as? Double,
                horizontalAccuracy: dict["horizontalAccuracy"] as? Double ?? 0,
                isOutlier: dict["isOutlier"] as? Bool ?? false
            )
        }
    }

    // MARK: - CSV Visit Import

    private static func importVisitsFromCSV(data: Data) throws -> [ImportedVisit] {
        guard let content = String(data: data, encoding: .utf8) else {
            throw ImportError.invalidData(String(localized: "Could not read file as UTF-8 text"))
        }

        let rows = parseCSVRows(content)
        guard rows.count > 1 else {
            throw ImportError.invalidData(String(localized: "CSV file has no data rows"))
        }

        let header = rows[0]
        guard header.contains("arrived_at") && header.contains("latitude") else {
            throw ImportError.invalidData(String(localized: "CSV header doesn't match expected visit format"))
        }

        let colIndex = Dictionary(uniqueKeysWithValues: header.enumerated().map { ($1, $0) })

        return try rows.dropFirst().enumerated().compactMap { rowIndex, fields in
            guard fields.count >= header.count else {
                throw ImportError.invalidData(String(localized: "Row \(rowIndex + 2): expected \(header.count) columns, found \(fields.count)"))
            }

            guard let arrivedAtCol = colIndex["arrived_at"],
                  let latCol = colIndex["latitude"],
                  let lonCol = colIndex["longitude"] else {
                throw ImportError.invalidData(String(localized: "Missing required columns"))
            }

            guard let arrivedAt = parseISO8601Date(fields[arrivedAtCol]) else {
                throw ImportError.invalidData(String(localized: "Row \(rowIndex + 2): invalid arrived_at date"))
            }

            guard let latitude = Double(fields[latCol]) else {
                throw ImportError.invalidData(String(localized: "Row \(rowIndex + 2): invalid latitude"))
            }
            guard let longitude = Double(fields[lonCol]) else {
                throw ImportError.invalidData(String(localized: "Row \(rowIndex + 2): invalid longitude"))
            }

            let departedAt: Date? = colIndex["departed_at"].flatMap { col in
                let val = fields[col]
                return val.isEmpty ? nil : parseISO8601Date(val)
            }

            let locationName: String? = colIndex["location_name"].flatMap { col in
                let val = fields[col]
                return val.isEmpty ? nil : val
            }

            let address: String? = colIndex["address"].flatMap { col in
                let val = fields[col]
                return val.isEmpty ? nil : val
            }

            let notes: String? = colIndex["notes"].flatMap { col in
                let val = fields[col]
                return val.isEmpty ? nil : val
            }

            let sourceRaw = colIndex["source"].flatMap { optionalCSVField(fields[$0]) }
            let confirmationStatusRaw = colIndex["confirmation_status"].flatMap { optionalCSVField(fields[$0]) }
            let originalLocationName = colIndex["original_location_name"].flatMap { optionalCSVField(fields[$0]) }
            let originalAddress = colIndex["original_address"].flatMap { optionalCSVField(fields[$0]) }

            return try ImportedVisit(
                latitude: latitude,
                longitude: longitude,
                arrivedAt: arrivedAt,
                departedAt: departedAt,
                locationName: locationName,
                address: address,
                notes: notes,
                sourceRaw: sourceRaw,
                confirmationStatusRaw: confirmationStatusRaw,
                originalLocationName: originalLocationName,
                originalAddress: originalAddress
            ).applyingAuditJSON(colIndex["visit_metadata"].flatMap { optionalCSVField(fields[$0]) })
        }
    }

    // MARK: - CSV Location Point Import

    private static func importLocationPointsFromCSV(data: Data) throws -> [ImportedLocationPoint] {
        guard let content = String(data: data, encoding: .utf8) else {
            throw ImportError.invalidData(String(localized: "Could not read file as UTF-8 text"))
        }

        let rows = parseCSVRows(content)
        guard rows.count > 1 else {
            throw ImportError.invalidData(String(localized: "CSV file has no data rows"))
        }

        let header = rows[0]
        guard header.contains("timestamp") && header.contains("latitude") else {
            throw ImportError.invalidData(String(localized: "CSV header doesn't match expected location point format"))
        }

        let colIndex = Dictionary(uniqueKeysWithValues: header.enumerated().map { ($1, $0) })

        return try rows.dropFirst().enumerated().compactMap { rowIndex, fields in
            guard fields.count >= header.count else {
                throw ImportError.invalidData(String(localized: "Row \(rowIndex + 2): expected \(header.count) columns, found \(fields.count)"))
            }

            guard let tsCol = colIndex["timestamp"],
                  let latCol = colIndex["latitude"],
                  let lonCol = colIndex["longitude"] else {
                throw ImportError.invalidData(String(localized: "Missing required columns"))
            }

            let timestamp: Date
            if let ts = parseISO8601Date(fields[tsCol]) {
                timestamp = ts
            } else if let unixCol = colIndex["timestamp_unix"], let unix = Double(fields[unixCol]) {
                timestamp = Date(timeIntervalSince1970: unix)
            } else {
                throw ImportError.invalidData(String(localized: "Row \(rowIndex + 2): invalid timestamp"))
            }

            guard let latitude = Double(fields[latCol]) else {
                throw ImportError.invalidData(String(localized: "Row \(rowIndex + 2): invalid latitude"))
            }
            guard let longitude = Double(fields[lonCol]) else {
                throw ImportError.invalidData(String(localized: "Row \(rowIndex + 2): invalid longitude"))
            }

            let altitude: Double? = colIndex["altitude"].flatMap { col in
                let val = fields[col]
                return val.isEmpty ? nil : Double(val)
            }

            let speed: Double? = colIndex["speed"].flatMap { col in
                let val = fields[col]
                return val.isEmpty ? nil : Double(val)
            }

            let horizontalAccuracy: Double = colIndex["horizontal_accuracy"].flatMap { col in
                Double(fields[col])
            } ?? 0

            let isOutlier: Bool = colIndex["is_outlier"].map { col in
                fields[col].lowercased() == "true"
            } ?? false

            return ImportedLocationPoint(
                latitude: latitude,
                longitude: longitude,
                timestamp: timestamp,
                altitude: altitude,
                speed: speed,
                horizontalAccuracy: horizontalAccuracy,
                isOutlier: isOutlier
            )
        }
    }

    // MARK: - Markdown Visit Import

    private static func importVisitsFromMarkdown(data: Data) throws -> [ImportedVisit] {
        guard let content = String(data: data, encoding: .utf8) else {
            throw ImportError.invalidData(String(localized: "Could not read file as UTF-8 text"))
        }

        if content.components(separatedBy: "\n").contains(where: { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            return trimmed.hasPrefix("|") && parseMarkdownTableCells(trimmed).first == "Arrived"
        }) {
            return try importVisitTableFromMarkdown(content)
        }

        var visits: [ImportedVisit] = []
        let lines = content.components(separatedBy: "\n")

        var currentDate: Date?
        var currentLocationName: String?
        var arrivedAt: Date?
        var departedAt: Date?
        var address: String?
        var latitude: Double?
        var longitude: Double?
        var notes: String?

        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .full
        dateFormatter.timeStyle = .none

        let timeFormatter = DateFormatter()
        timeFormatter.dateStyle = .none
        timeFormatter.timeStyle = .short

        func flushVisit() {
            if let lat = latitude, let lon = longitude, let arrived = arrivedAt {
                visits.append(ImportedVisit(
                    latitude: lat,
                    longitude: lon,
                    arrivedAt: arrived,
                    departedAt: departedAt,
                    locationName: currentLocationName,
                    address: address,
                    notes: notes
                ))
            }
            arrivedAt = nil
            departedAt = nil
            address = nil
            latitude = nil
            longitude = nil
            notes = nil
            currentLocationName = nil
        }

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)

            // Date header: ## Thursday, April 3, 2026
            if trimmed.hasPrefix("## ") && !trimmed.hasPrefix("### ") {
                let dateStr = String(trimmed.dropFirst(3))
                if let date = dateFormatter.date(from: dateStr) {
                    currentDate = date
                }
                continue
            }

            // Location name: ### Blue Bottle Coffee
            if trimmed.hasPrefix("### ") {
                flushVisit()
                currentLocationName = String(trimmed.dropFirst(4))
                continue
            }

            // Fields
            if trimmed.hasPrefix("- **Arrived:**") {
                let timeStr = trimmed.replacingOccurrences(of: "- **Arrived:** ", with: "")
                if let date = currentDate, let time = timeFormatter.date(from: timeStr) {
                    arrivedAt = combineDateAndTime(date: date, time: time)
                }
            } else if trimmed.hasPrefix("- **Departed:**") {
                let timeStr = trimmed.replacingOccurrences(of: "- **Departed:** ", with: "")
                if let date = currentDate, let time = timeFormatter.date(from: timeStr) {
                    departedAt = combineDateAndTime(date: date, time: time)
                }
            } else if trimmed.hasPrefix("- **Address:**") {
                address = trimmed.replacingOccurrences(of: "- **Address:** ", with: "")
            } else if trimmed.hasPrefix("- **Coordinates:**") {
                let coordStr = trimmed.replacingOccurrences(of: "- **Coordinates:** ", with: "")
                let parts = coordStr.components(separatedBy: ", ")
                if parts.count == 2 {
                    latitude = Double(parts[0].trimmingCharacters(in: .whitespaces))
                    longitude = Double(parts[1].trimmingCharacters(in: .whitespaces))
                }
            } else if trimmed.hasPrefix("> ") {
                let noteText = String(trimmed.dropFirst(2))
                if !noteText.isEmpty {
                    notes = noteText
                }
            }
        }

        flushVisit()

        if visits.isEmpty {
            throw ImportError.invalidData(String(localized: "No visits found in Markdown file"))
        }

        return visits
    }

    private static func importVisitTableFromMarkdown(_ content: String) throws -> [ImportedVisit] {
        var visits: [ImportedVisit] = []
        var currentDate: Date?
        var header: [String] = []
        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .full
        dateFormatter.timeStyle = .none
        let timeFormatter = DateFormatter()
        timeFormatter.dateStyle = .none
        timeFormatter.timeStyle = .short

        for line in content.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("## ") {
                currentDate = dateFormatter.date(from: String(trimmed.dropFirst(3)))
                header = []
                continue
            }
            guard trimmed.hasPrefix("|") else { continue }
            let cells = parseMarkdownTableCells(trimmed)
            if isMarkdownSeparator(cells) { continue }
            if cells.first == "Arrived" {
                header = cells
                continue
            }
            // A combined file may contain point tables; never treat them as visits.
            if cells.first == "Time" { header = []; continue }
            guard !header.isEmpty, cells.count == header.count else { continue }
            let columns = try markdownColumns(header)
            guard let arrivedCol = columns["Arrived"], let latCol = columns["Lat"], let lonCol = columns["Lon"] else {
                throw ImportError.invalidData(String(localized: "Markdown visit table is missing required columns"))
            }
            guard let arrivedAt = markdownTimestamp(cells[arrivedCol], date: currentDate, formatter: timeFormatter) else {
                throw ImportError.invalidData(String(localized: "Invalid Markdown visit arrival time"))
            }
            guard let latitude = Double(cells[latCol]), let longitude = Double(cells[lonCol]) else {
                throw ImportError.invalidData(String(localized: "Invalid Markdown visit coordinates"))
            }
            let departedAt: Date?
            if let value = columns["Departed"].flatMap({ optionalMarkdownCell(cells[$0]) }) {
                guard let departure = markdownTimestamp(value, date: currentDate, formatter: timeFormatter) else {
                    throw ImportError.invalidData(String(localized: "Invalid Markdown visit departure time"))
                }
                departedAt = departure
            } else {
                departedAt = nil
            }
            visits.append(try ImportedVisit(
                latitude: latitude, longitude: longitude, arrivedAt: arrivedAt, departedAt: departedAt,
                locationName: optionalMarkdownCell(columns["Location"].map { cells[$0] }),
                address: optionalMarkdownCell(columns["Address"].map { cells[$0] }),
                notes: optionalMarkdownCell(columns["Notes"].map { cells[$0] }),
                sourceRaw: optionalMarkdownCell(columns["Source"].map { cells[$0] })?.lowercased(),
                confirmationStatusRaw: optionalMarkdownCell(columns["Status"].map { cells[$0] })?.lowercased()
            ).applyingAuditJSON(optionalMarkdownCell(columns["Visit Metadata"].map { cells[$0] })))
        }
        guard !visits.isEmpty else {
            throw ImportError.invalidData(String(localized: "No visits found in Markdown file"))
        }
        return visits
    }

    // MARK: - Markdown Location Point Import

    private static func importLocationPointsFromMarkdown(data: Data) throws -> [ImportedLocationPoint] {
        guard let content = String(data: data, encoding: .utf8) else {
            throw ImportError.invalidData(String(localized: "Could not read file as UTF-8 text"))
        }
        var points: [ImportedLocationPoint] = []
        var currentDate: Date?
        var header: [String]?
        var visitingTable = false
        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .full
        dateFormatter.timeStyle = .none
        let timeFormatter = DateFormatter()
        timeFormatter.dateStyle = .none
        timeFormatter.timeStyle = .medium
        let legacyColumns = ["Time", "Lat", "Lon", "Speed", "Altitude", "Accuracy", "Outlier"]

        for line in content.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("## ") {
                currentDate = dateFormatter.date(from: String(trimmed.dropFirst(3)))
                header = nil
                visitingTable = false
                continue
            }
            guard trimmed.hasPrefix("|") else { continue }
            let cells = parseMarkdownTableCells(trimmed)
            if isMarkdownSeparator(cells) { continue }
            if cells.first == "Arrived" { visitingTable = true; header = nil; continue }
            if cells.first == "Time" { header = cells; visitingTable = false; continue }
            guard !visitingTable, cells.count >= 3 else { continue }
            let columns = try markdownColumns(header ?? Array(legacyColumns.prefix(cells.count)))
            if let header, cells.count != header.count { continue }
            guard let timeCol = columns["Time"], let latCol = columns["Lat"], let lonCol = columns["Lon"],
                  let timestamp = markdownTimestamp(cells[timeCol], date: currentDate, formatter: timeFormatter),
                  let latitude = Double(cells[latCol]), let longitude = Double(cells[lonCol]) else { continue }
            func number(_ key: String, suffix: String) -> Double? {
                guard let index = columns[key], let value = optionalMarkdownCell(cells[index]) else { return nil }
                let raw = value.hasSuffix(suffix) ? String(value.dropLast(suffix.count)) : value
                return Double(raw)
            }
            points.append(ImportedLocationPoint(
                latitude: latitude, longitude: longitude, timestamp: timestamp,
                altitude: number("Altitude", suffix: " m"), speed: number("Speed", suffix: " m/s"),
                horizontalAccuracy: number("Accuracy", suffix: " m") ?? 0,
                isOutlier: columns["Outlier"].map { cells[$0].lowercased() == "yes" } ?? false
            ))
        }
        guard !points.isEmpty else {
            throw ImportError.invalidData(String(localized: "No location points found in Markdown file"))
        }
        return points
    }

    // ISO table timestamps are absolute; legacy clock cells still use the original device context.
    private static func markdownTimestamp(_ raw: String, date: Date?, formatter: DateFormatter) -> Date? {
        if let instant = parseISO8601Date(raw) { return instant }
        guard let date, let time = formatter.date(from: raw) else { return nil }
        return combineDateAndTime(date: date, time: time)
    }

    private static func markdownColumns(_ header: [String]) throws -> [String: Int] {
        var columns: [String: Int] = [:]
        for (index, name) in header.enumerated() {
            let key = name == "Latitude" ? "Lat" : name == "Longitude" ? "Lon" : name
            guard columns.updateValue(index, forKey: key) == nil else {
                throw ImportError.invalidData(String(localized: "Duplicate Markdown columns"))
            }
        }
        return columns
    }

    private static func isMarkdownSeparator(_ cells: [String]) -> Bool {
        !cells.isEmpty && cells.allSatisfy { !$0.isEmpty && $0.allSatisfy { $0 == "-" || $0 == ":" || $0 == " " } }
    }

    // MARK: - Helpers

    private static func parseISO8601Date(_ string: String) -> Date? {
        iso8601Formatter.date(from: string) ?? iso8601FallbackFormatter.date(from: string)
    }

    private static func combineDateAndTime(date: Date, time: Date) -> Date {
        let calendar = Calendar.current
        let dateComponents = calendar.dateComponents([.year, .month, .day], from: date)
        let timeComponents = calendar.dateComponents([.hour, .minute, .second], from: time)

        var combined = DateComponents()
        combined.year = dateComponents.year
        combined.month = dateComponents.month
        combined.day = dateComponents.day
        combined.hour = timeComponents.hour
        combined.minute = timeComponents.minute
        combined.second = timeComponents.second

        return calendar.date(from: combined) ?? date
    }

    private static func parseMarkdownTableCells(_ line: String) -> [String] {
        var cells: [String] = []
        var cell = ""
        var index = line.startIndex
        while index < line.endIndex {
            let character = line[index]
            let next = line.index(after: index)
            if character == "\\", next < line.endIndex, line[next] == "\\" || line[next] == "|" {
                cell.append(line[next])
                index = line.index(after: next)
                continue
            }
            if character == "|" {
                cells.append(cell)
                cell = ""
            } else {
                cell.append(character)
            }
            index = next
        }
        cells.append(cell)
        if cells.first?.trimmingCharacters(in: .whitespaces).isEmpty == true { cells.removeFirst() }
        if cells.last?.trimmingCharacters(in: .whitespaces).isEmpty == true { cells.removeLast() }
        return cells.map { $0.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "<br>", with: "\n") }
    }

    private static func optionalCSVField(_ value: String) -> String? {
        value.isEmpty ? nil : value
    }

    private static func optionalMarkdownCell(_ value: String?) -> String? {
        guard let value, !value.isEmpty, value != "-" else { return nil }
        return value
    }

    // MARK: - CSV Parser (RFC 4180)

    private static func parseCSVRows(_ content: String) -> [[String]] {
        var rows: [[String]] = []
        var currentField = ""
        var currentRow: [String] = []
        var inQuotes = false
        let chars = Array(content)
        var i = 0

        while i < chars.count {
            let char = chars[i]

            if inQuotes {
                if char == "\"" {
                    if i + 1 < chars.count && chars[i + 1] == "\"" {
                        currentField.append("\"")
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                        i += 1
                        continue
                    }
                } else {
                    currentField.append(char)
                    i += 1
                }
            } else {
                if char == "\"" {
                    inQuotes = true
                    i += 1
                } else if char == "," {
                    currentRow.append(currentField)
                    currentField = ""
                    i += 1
                } else if char == "\n" {
                    currentRow.append(currentField)
                    currentField = ""
                    if !currentRow.allSatisfy({ $0.isEmpty }) || rows.isEmpty {
                        rows.append(currentRow)
                    }
                    currentRow = []
                    i += 1
                } else if char == "\r" {
                    i += 1
                    continue
                } else {
                    currentField.append(char)
                    i += 1
                }
            }
        }

        // Last field/row
        if !currentField.isEmpty || !currentRow.isEmpty {
            currentRow.append(currentField)
            rows.append(currentRow)
        }

        return rows
    }
}
