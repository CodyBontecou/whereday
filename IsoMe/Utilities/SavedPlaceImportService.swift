import Foundation

struct ImportedSavedPlace: Identifiable, Equatable, Sendable {
    let id: UUID
    let name: String
    let address: String?
    let latitude: Double
    let longitude: Double
    let radiusMeters: Double

    init(
        id: UUID = UUID(),
        name: String,
        address: String?,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double
    ) {
        self.id = id
        self.name = name
        self.address = address
        self.latitude = latitude
        self.longitude = longitude
        self.radiusMeters = radiusMeters
    }
}

struct SavedPlaceImportRow: Identifiable, Sendable {
    /// The physical line where this CSV record starts, including the header.
    let rowNumber: Int
    let name: String
    let place: ImportedSavedPlace?
    let errors: [String]

    var id: Int { rowNumber }
}

struct SavedPlaceImportPreview: Sendable {
    let rows: [SavedPlaceImportRow]

    var places: [ImportedSavedPlace] { rows.compactMap(\.place) }
    var invalidRows: [SavedPlaceImportRow] { rows.filter { !$0.errors.isEmpty } }
}

enum SavedPlaceImportError: LocalizedError, Sendable {
    case invalidFile(String)

    var errorDescription: String? {
        switch self {
        case .invalidFile(let detail):
            return detail
        }
    }
}

enum SavedPlaceImportService {
    static let defaultRadiusMeters: Double = 150
    static let minimumRadiusMeters: Double = 25
    static let maximumRadiusMeters: Double = 10_000
    static let maximumFileSize = 5 * 1_024 * 1_024
    static let maximumRowCount = 10_000

    /// Creates a preview without inserting or changing any saved places.
    static func parse(data: Data) throws -> SavedPlaceImportPreview {
        guard data.count <= maximumFileSize else {
            throw SavedPlaceImportError.invalidFile(String(localized: "Choose a CSV file of 5 MB or smaller."))
        }
        guard var content = String(data: data, encoding: .utf8) else {
            throw SavedPlaceImportError.invalidFile(String(localized: "Could not read the CSV file as UTF-8 text."))
        }
        if content.hasPrefix("\u{FEFF}") {
            content.removeFirst()
        }

        let records = try parseRecords(content)
        guard let headerRecord = records.first else {
            throw SavedPlaceImportError.invalidFile(String(localized: "The CSV file is empty."))
        }
        guard headerRecord.errors.isEmpty else {
            throw SavedPlaceImportError.invalidFile(String(localized: "The CSV header contains invalid quotation marks."))
        }

        let headers = headerRecord.fields.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        guard !headers.contains("") else {
            throw SavedPlaceImportError.invalidFile(String(localized: "Every CSV column must have a header."))
        }
        guard Set(headers).count == headers.count else {
            throw SavedPlaceImportError.invalidFile(String(localized: "The CSV header contains duplicate column names."))
        }
        let requiredHeaders = ["name", "latitude", "longitude"]
        let missingHeaders = requiredHeaders.filter { !headers.contains($0) }
        guard missingHeaders.isEmpty else {
            throw SavedPlaceImportError.invalidFile(String(localized: "Missing required CSV columns: \(missingHeaders.joined(separator: ", "))."))
        }
        guard records.count > 1 else {
            throw SavedPlaceImportError.invalidFile(String(localized: "The CSV file has no saved-place rows."))
        }

        let columns = Dictionary(uniqueKeysWithValues: headers.enumerated().map { ($1, $0) })
        let rows = records.dropFirst().map { record -> SavedPlaceImportRow in
            func field(_ column: String) -> String {
                guard let index = columns[column], record.fields.indices.contains(index) else { return "" }
                return record.fields[index].trimmingCharacters(in: .whitespacesAndNewlines)
            }

            let name = field("name")
            var errors = record.errors
            if record.fields.count != headers.count {
                errors.append(String(localized: "Expected \(headers.count) columns, found \(record.fields.count)."))
            }
            if !errors.isEmpty {
                return SavedPlaceImportRow(rowNumber: record.lineNumber, name: name, place: nil, errors: errors)
            }

            if name.isEmpty {
                errors.append(String(localized: "A saved place needs a name."))
            }
            let latitude = Double(field("latitude"))
            if latitude.map({ $0.isFinite && (-90...90).contains($0) }) != true {
                errors.append(String(localized: "Latitude must be a number from -90 to 90."))
            }
            let longitude = Double(field("longitude"))
            if longitude.map({ $0.isFinite && (-180...180).contains($0) }) != true {
                errors.append(String(localized: "Longitude must be a number from -180 to 180."))
            }
            let radiusField = field("radius_meters")
            let radiusMeters = radiusField.isEmpty ? defaultRadiusMeters : Double(radiusField)
            if radiusMeters.map({ $0.isFinite && (minimumRadiusMeters...maximumRadiusMeters).contains($0) }) != true {
                errors.append(String(localized: "Radius must be a number from 25 to 10,000 meters."))
            }

            guard errors.isEmpty, let latitude, let longitude, let radiusMeters else {
                return SavedPlaceImportRow(rowNumber: record.lineNumber, name: name, place: nil, errors: errors)
            }
            let address = field("address")
            let place = ImportedSavedPlace(
                name: name,
                address: address.isEmpty ? nil : address,
                latitude: latitude,
                longitude: longitude,
                radiusMeters: radiusMeters
            )
            return SavedPlaceImportRow(rowNumber: record.lineNumber, name: name, place: place, errors: [])
        }
        return SavedPlaceImportPreview(rows: rows)
    }

    private struct CSVRecord {
        let lineNumber: Int
        let fields: [String]
        let errors: [String]
    }

    private enum FieldState {
        case start
        case unquoted
        case quoted
        case closedQuote
    }

    /// RFC 4180 records can span lines. Keep parsing after a malformed record so
    /// the preview can show its error alongside the other places in the file.
    private static func parseRecords(_ content: String) throws -> [CSVRecord] {
        let scalars = Array(content.unicodeScalars)
        var records: [CSVRecord] = []
        var fields: [String] = []
        var field = ""
        var state = FieldState.start
        var errors: [String] = []
        var lineNumber = 1
        var recordStartLine = 1
        var index = 0
        var touchedRecord = false
        var hasQuotedField = false

        func appendRecord() throws {
            fields.append(field)
            // Ignore empty lines, while keeping rows such as ",," for validation.
            if fields.count > 1 || hasQuotedField || !fields[0].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !errors.isEmpty {
                records.append(CSVRecord(lineNumber: recordStartLine, fields: fields, errors: errors))
                guard records.count <= maximumRowCount + 1 else {
                    throw SavedPlaceImportError.invalidFile(String(localized: "Import up to 10,000 saved places at a time."))
                }
            }
            fields = []
            field = ""
            errors = []
            state = .start
            touchedRecord = false
            hasQuotedField = false
        }

        func addSyntaxError(_ message: String) {
            if !errors.contains(message) { errors.append(message) }
        }

        while index < scalars.count {
            let scalar = scalars[index]
            let isNewline = scalar.value == 10 || scalar.value == 13
            let isCRLF = scalar.value == 13 && index + 1 < scalars.count && scalars[index + 1].value == 10

            if state == .quoted {
                if scalar.value == 34 {
                    if index + 1 < scalars.count && scalars[index + 1].value == 34 {
                        field.append("\"")
                        index += 2
                        continue
                    }
                    state = .closedQuote
                } else {
                    field.unicodeScalars.append(scalar)
                    if isNewline {
                        if isCRLF {
                            field.append("\n")
                            index += 1
                        }
                        lineNumber += 1
                    }
                }
                index += 1
                continue
            }

            if isNewline {
                try appendRecord()
                lineNumber += 1
                recordStartLine = lineNumber
                index += isCRLF ? 2 : 1
                continue
            }
            touchedRecord = true
            if scalar.value == 44 {
                fields.append(field)
                field = ""
                state = .start
            } else if scalar.value == 34 {
                if state == .start {
                    state = .quoted
                    hasQuotedField = true
                } else {
                    addSyntaxError(String(localized: "Quotation marks must enclose the whole field; escape an inner quote with two quotation marks."))
                    field.append("\"")
                    state = .unquoted
                }
            } else {
                if state == .closedQuote {
                    addSyntaxError(String(localized: "Unexpected text after a quoted field."))
                }
                field.unicodeScalars.append(scalar)
                state = .unquoted
            }
            index += 1
        }

        if state == .quoted {
            errors.append(String(localized: "A quoted field is missing its closing quotation mark."))
        }
        if touchedRecord || !fields.isEmpty || !field.isEmpty || !errors.isEmpty {
            try appendRecord()
        }
        return records
    }
}
