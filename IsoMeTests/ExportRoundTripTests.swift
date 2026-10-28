import XCTest
@testable import IsoMe

final class ExportRoundTripTests: XCTestCase {
    private struct ExportCase {
        let name: String
        let format: ExportFormat
        let expectedFileExtension: String
    }

    fileprivate struct NormalizedVisit: Equatable {
        let latitude: Double
        let longitude: Double
        let arrivedAt: Date?
        let departedAt: Date?
        let locationName: String?
        let address: String?
        let notes: String?
    }

    fileprivate struct NormalizedPoint: Equatable {
        let latitude: Double
        let longitude: Double
        let timestamp: Date?
        let altitude: Double?
        let speed: Double?
        let horizontalAccuracy: Double?
        let isOutlier: Bool?
    }

    private let cases: [ExportCase] = [
        ExportCase(name: "JSON", format: .json, expectedFileExtension: "json"),
        ExportCase(name: "CSV", format: .csv, expectedFileExtension: "csv"),
        ExportCase(name: "Markdown", format: .markdown, expectedFileExtension: "md"),
        ExportCase(name: "GeoJSON", format: .geojson, expectedFileExtension: "geojson"),
        ExportCase(name: "GPX", format: .gpx, expectedFileExtension: "gpx"),
        ExportCase(name: "KML", format: .kml, expectedFileExtension: "kml"),
        ExportCase(name: "OwnTracks", format: .owntracks, expectedFileExtension: "json"),
        ExportCase(name: "Overland", format: .overland, expectedFileExtension: "json")
    ]

    fileprivate static let iso8601: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private var baseDate: Date {
        Self.iso8601.date(from: "2026-05-10T14:15:30.000Z")!
    }

    func testAllExportFormatsRoundTripRepresentativeDataWithoutSchemaDrift() throws {
        let visits = representativeVisits()
        let points = representativePoints()

        for testCase in cases {
            var options = ExportOptions()
            options.dataKind = .all
            options.format = testCase.format

            let rendered = try ExportService.render(
                visits: visits,
                points: points,
                options: options,
                filenamePattern: "{kind}-{format}.{ext}"
            )

            XCTAssertTrue(
                rendered.fileName.hasSuffix(".\(testCase.expectedFileExtension)"),
                "\(testCase.name) emitted unexpected filename: \(rendered.fileName)"
            )

            let roundTripped = try parseRenderedExport(rendered.data, format: testCase.format)

            if testCase.format.isPointsOnly {
                XCTAssertTrue(roundTripped.visits.isEmpty, "\(testCase.name) should not emit visits")
            } else {
                assertVisits(roundTripped.visits, match: visits, formatName: testCase.name)
            }

            assertPoints(roundTripped.points, match: points, formatName: testCase.name)
        }
    }

    private func representativeVisits() -> [Visit] {
        [
            Visit(
                latitude: 37.776502,
                longitude: -122.424098,
                arrivedAt: baseDate,
                departedAt: baseDate.addingTimeInterval(45 * 60),
                locationName: "Civic Cafe",
                address: "1 Market St, San Francisco, CA",
                notes: "Coffee, sync, receipts"
            ),
            Visit(
                latitude: 37.786901,
                longitude: -122.399101,
                arrivedAt: baseDate.addingTimeInterval(2 * 60 * 60),
                departedAt: baseDate.addingTimeInterval(3 * 60 * 60 + 15 * 60),
                locationName: "Pier Office",
                address: "Pier 3, San Francisco, CA",
                notes: "Quarterly planning"
            )
        ]
    }

    private func representativePoints() -> [LocationPoint] {
        [
            LocationPoint(
                latitude: 37.776600,
                longitude: -122.424000,
                timestamp: baseDate.addingTimeInterval(5 * 60),
                altitude: 12.4,
                speed: 1.8,
                horizontalAccuracy: 4.5,
                isOutlier: false
            ),
            LocationPoint(
                latitude: 37.781200,
                longitude: -122.415700,
                timestamp: baseDate.addingTimeInterval(15 * 60),
                altitude: 18.9,
                speed: 3.2,
                horizontalAccuracy: 6.0,
                isOutlier: true
            )
        ]
    }

    private func parseRenderedExport(
        _ data: Data,
        format: ExportFormat
    ) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        switch format {
        case .json:
            return try parseCombinedJSON(data)
        case .csv:
            return try parseCombinedCSV(data)
        case .markdown:
            return try parseCombinedMarkdown(data)
        case .geojson:
            return try parseGeoJSON(data)
        case .gpx:
            return try parseGPX(data)
        case .kml:
            return try parseKML(data)
        case .owntracks:
            return try parseOwnTracks(data)
        case .overland:
            return try parseOverland(data)
        }
    }

    private func parseCombinedJSON(_ data: Data) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(
            Set(object.keys),
            ["dateRange", "exportDate", "points", "totalPoints", "totalVisits", "visits"]
        )

        let visitsJSON = try XCTUnwrap(object["visits"] as? [[String: Any]])
        let pointsJSON = try XCTUnwrap(object["points"] as? [[String: Any]])
        XCTAssertEqual(try XCTUnwrap(object["totalVisits"] as? Int), visitsJSON.count)
        XCTAssertEqual(try XCTUnwrap(object["totalPoints"] as? Int), pointsJSON.count)

        return (
            visits: visitsJSON.map(parseJSONVisit),
            points: pointsJSON.map(parseJSONPoint)
        )
    }

    private func parseOwnTracks(_ data: Data) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        let messages = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [[String: Any]])

        let points = try messages.map { message -> NormalizedPoint in
            XCTAssertEqual(Set(message.keys), ["_type", "acc", "alt", "lat", "lon", "tid", "tst", "vel"])
            XCTAssertEqual(try XCTUnwrap(message["_type"] as? String), "location")
            XCTAssertEqual(try XCTUnwrap(message["tid"] as? String), "IM")

            return NormalizedPoint(
                latitude: try XCTUnwrap(message["lat"] as? Double),
                longitude: try XCTUnwrap(message["lon"] as? Double),
                timestamp: Date(timeIntervalSince1970: TimeInterval(try XCTUnwrap(message["tst"] as? Int))),
                altitude: Double(try XCTUnwrap(message["alt"] as? Int)),
                speed: Double(try XCTUnwrap(message["vel"] as? Int)) / 3.6,
                horizontalAccuracy: Double(try XCTUnwrap(message["acc"] as? Int)),
                isOutlier: nil
            )
        }

        return (visits: [], points: points)
    }

    private func parseOverland(_ data: Data) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        let payload = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(payload.keys), ["current", "locations"])

        let locations = try XCTUnwrap(payload["locations"] as? [[String: Any]])
        _ = try XCTUnwrap(payload["current"] as? [String: Any])

        let points = try locations.map { feature -> NormalizedPoint in
            XCTAssertEqual(Set(feature.keys), ["geometry", "properties", "type"])
            XCTAssertEqual(try XCTUnwrap(feature["type"] as? String), "Feature")

            let geometry = try XCTUnwrap(feature["geometry"] as? [String: Any])
            XCTAssertEqual(Set(geometry.keys), ["coordinates", "type"])
            XCTAssertEqual(try XCTUnwrap(geometry["type"] as? String), "Point")
            let coordinates = try XCTUnwrap(geometry["coordinates"] as? [Double])

            let properties = try XCTUnwrap(feature["properties"] as? [String: Any])
            XCTAssertEqual(Set(properties.keys), ["altitude", "device_id", "horizontal_accuracy", "speed", "timestamp"])
            XCTAssertEqual(try XCTUnwrap(properties["device_id"] as? String), "isome")

            return NormalizedPoint(
                latitude: coordinates[1],
                longitude: coordinates[0],
                timestamp: Self.iso8601.date(from: try XCTUnwrap(properties["timestamp"] as? String)),
                altitude: properties["altitude"] as? Double,
                speed: properties["speed"] as? Double,
                horizontalAccuracy: properties["horizontal_accuracy"] as? Double,
                isOutlier: nil
            )
        }

        return (visits: [], points: points)
    }

    private func parseGeoJSON(_ data: Data) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        let collection = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(collection.keys), ["exportDate", "features", "generator", "type"])
        XCTAssertEqual(try XCTUnwrap(collection["type"] as? String), "FeatureCollection")
        XCTAssertEqual(try XCTUnwrap(collection["generator"] as? String), "Whereday")

        let features = try XCTUnwrap(collection["features"] as? [[String: Any]])
        var visits: [NormalizedVisit] = []
        var points: [NormalizedPoint] = []

        for feature in features {
            XCTAssertEqual(Set(feature.keys), ["geometry", "properties", "type"])
            XCTAssertEqual(try XCTUnwrap(feature["type"] as? String), "Feature")
            let geometry = try XCTUnwrap(feature["geometry"] as? [String: Any])
            XCTAssertEqual(Set(geometry.keys), ["coordinates", "type"])
            XCTAssertEqual(try XCTUnwrap(geometry["type"] as? String), "Point")
            let coordinates = try XCTUnwrap(geometry["coordinates"] as? [Double])
            let properties = try XCTUnwrap(feature["properties"] as? [String: Any])

            switch try XCTUnwrap(properties["kind"] as? String) {
            case "visit":
                XCTAssertEqual(Set(properties.keys), ["address", "arrivedAt", "departedAt", "durationMinutes", "kind", "locationName", "notes"])
                visits.append(NormalizedVisit(
                    latitude: coordinates[1],
                    longitude: coordinates[0],
                    arrivedAt: Self.iso8601.date(from: try XCTUnwrap(properties["arrivedAt"] as? String)),
                    departedAt: Self.iso8601.date(from: try XCTUnwrap(properties["departedAt"] as? String)),
                    locationName: properties["locationName"] as? String,
                    address: properties["address"] as? String,
                    notes: properties["notes"] as? String
                ))
            case "point":
                XCTAssertEqual(Set(properties.keys), ["altitude", "horizontalAccuracy", "isOutlier", "kind", "speed", "timestamp", "timestampUnix"])
                var altitude: Double? = properties["altitude"] as? Double
                if altitude == nil, coordinates.count == 3 { altitude = coordinates[2] }
                points.append(NormalizedPoint(
                    latitude: coordinates[1],
                    longitude: coordinates[0],
                    timestamp: Self.iso8601.date(from: try XCTUnwrap(properties["timestamp"] as? String)),
                    altitude: altitude,
                    speed: properties["speed"] as? Double,
                    horizontalAccuracy: properties["horizontalAccuracy"] as? Double,
                    isOutlier: properties["isOutlier"] as? Bool
                ))
            default:
                XCTFail("Unexpected GeoJSON kind: \(properties)")
            }
        }

        return (visits: visits, points: points)
    }

    private func parseCombinedCSV(_ data: Data) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        let rows = parseCSVRows(try XCTUnwrap(String(data: data, encoding: .utf8)))
        guard let visitHeaderIndex = rows.firstIndex(where: { $0.first == "arrived_at" }),
              let pointsHeaderIndex = rows.firstIndex(where: { $0.first == "timestamp" }) else {
            XCTFail("Combined CSV missing visit or point sections")
            return ([], [])
        }

        let visitHeaders = rows[visitHeaderIndex]
        let pointHeaders = rows[pointsHeaderIndex]
        XCTAssertEqual(visitHeaders, [
            "arrived_at",
            "departed_at",
            "duration_minutes",
            "latitude",
            "longitude",
            "location_name",
            "address",
            "notes",
            "source",
            "confirmation_status",
            "original_location_name",
            "original_address"
        ])
        XCTAssertEqual(pointHeaders, ["timestamp", "timestamp_unix", "latitude", "longitude", "altitude", "speed", "horizontal_accuracy", "is_outlier"])

        let visitRows = rows[(visitHeaderIndex + 1)..<pointsHeaderIndex]
            .filter { !$0.isEmpty && !$0[0].isEmpty && !$0[0].hasPrefix("#") }
        let pointRows = rows[(pointsHeaderIndex + 1)..<rows.endIndex]
            .filter { !$0.isEmpty && !$0[0].isEmpty && !$0[0].hasPrefix("#") }

        return (
            visits: visitRows.map { row in
                NormalizedVisit(
                    latitude: Double(row[3])!,
                    longitude: Double(row[4])!,
                    arrivedAt: Self.iso8601.date(from: row[0]),
                    departedAt: Self.iso8601.date(from: row[1]),
                    locationName: row[5],
                    address: row[6],
                    notes: row[7]
                )
            },
            points: pointRows.map { row in
                NormalizedPoint(
                    latitude: Double(row[2])!,
                    longitude: Double(row[3])!,
                    timestamp: Self.iso8601.date(from: row[0]),
                    altitude: Double(row[4]),
                    speed: Double(row[5]),
                    horizontalAccuracy: Double(row[6]),
                    isOutlier: row[7] == "true"
                )
            }
        )
    }

    private func parseCombinedMarkdown(_ data: Data) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertTrue(text.contains("# Whereday Complete Export"))
        XCTAssertTrue(text.contains("# Visits"))
        XCTAssertTrue(text.contains("# Location Points"))

        let visitSection = try markdownSection(named: "# Visits", in: text)
        let pointSection = try markdownSection(named: "# Location Points", in: text)
        let visitRows = markdownTableRows(in: visitSection)
        let pointRows = markdownTableRows(in: pointSection)

        XCTAssertEqual(visitRows.first, "| Arrived | Departed | Duration | Lat | Lon | Location | Address | Notes | Source | Status |")
        XCTAssertEqual(pointRows.first, "| Time | Lat | Lon | Speed | Altitude | Accuracy | Outlier |")

        return (
            visits: visitRows.dropFirst().map { row in
                let cells = markdownCells(row)
                return NormalizedVisit(
                    latitude: Double(cells[3])!,
                    longitude: Double(cells[4])!,
                    arrivedAt: nil,
                    departedAt: nil,
                    locationName: cells[5],
                    address: cells[6],
                    notes: cells[7]
                )
            },
            points: pointRows.dropFirst().map { row in
                let cells = markdownCells(row)
                return NormalizedPoint(
                    latitude: Double(cells[1])!,
                    longitude: Double(cells[2])!,
                    timestamp: nil,
                    altitude: numericPrefix(cells[4]),
                    speed: numericPrefix(cells[3]),
                    horizontalAccuracy: numericPrefix(cells[5]),
                    isOutlier: cells[6] == "yes"
                )
            }
        )
    }

    private func parseGPX(_ data: Data) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        let parser = GPXRoundTripParser()
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        XCTAssertTrue(xmlParser.parse(), xmlParser.parserError?.localizedDescription ?? "GPX parse failed")

        XCTAssertEqual(parser.rootAttributes["version"], "1.1")
        XCTAssertEqual(parser.rootAttributes["creator"], "Whereday")
        XCTAssertEqual(parser.rootAttributes["xmlns"], "http://www.topografix.com/GPX/1/1")
        XCTAssertEqual(parser.rootAttributes["xmlns:isome"], "https://isome.isolated.tech/gpx/1.0")

        return (visits: parser.visits, points: parser.points)
    }

    private func parseKML(_ data: Data) throws -> (visits: [NormalizedVisit], points: [NormalizedPoint]) {
        let parser = KMLRoundTripParser()
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        XCTAssertTrue(xmlParser.parse(), xmlParser.parserError?.localizedDescription ?? "KML parse failed")

        XCTAssertEqual(parser.rootAttributes["xmlns"], "http://www.opengis.net/kml/2.2")
        XCTAssertEqual(parser.rootAttributes["xmlns:gx"], "http://www.google.com/kml/ext/2.2")
        XCTAssertEqual(parser.rootAttributes["xmlns:isome"], "https://isome.isolated.tech/gpx/1.0")

        return (visits: parser.visits, points: parser.points)
    }

    private func parseJSONVisit(_ dict: [String: Any]) -> NormalizedVisit {
        XCTAssertEqual(Set(dict.keys), [
            "address",
            "arrivedAt",
            "confirmationStatus",
            "departedAt",
            "detectedAddress",
            "detectedLatitude",
            "detectedLocationName",
            "detectedLongitude",
            "durationMinutes",
            "latitude",
            "locationName",
            "longitude",
            "notes",
            "source"
        ])
        XCTAssertEqual(dict["source"] as? String, "automatic")
        XCTAssertEqual(dict["confirmationStatus"] as? String, "unconfirmed")
        return NormalizedVisit(
            latitude: dict["latitude"] as! Double,
            longitude: dict["longitude"] as! Double,
            arrivedAt: Self.iso8601.date(from: dict["arrivedAt"] as! String),
            departedAt: Self.iso8601.date(from: dict["departedAt"] as! String),
            locationName: dict["locationName"] as? String,
            address: dict["address"] as? String,
            notes: dict["notes"] as? String
        )
    }

    private func parseJSONPoint(_ dict: [String: Any]) -> NormalizedPoint {
        XCTAssertEqual(Set(dict.keys), ["altitude", "horizontalAccuracy", "isOutlier", "latitude", "longitude", "speed", "timestamp", "timestampUnix"])
        return NormalizedPoint(
            latitude: dict["latitude"] as! Double,
            longitude: dict["longitude"] as! Double,
            timestamp: Self.iso8601.date(from: dict["timestamp"] as! String),
            altitude: dict["altitude"] as? Double,
            speed: dict["speed"] as? Double,
            horizontalAccuracy: dict["horizontalAccuracy"] as? Double,
            isOutlier: dict["isOutlier"] as? Bool
        )
    }

    private func assertVisits(_ actual: [NormalizedVisit], match expected: [Visit], formatName: String) {
        XCTAssertEqual(actual.count, expected.count, "\(formatName) visit count drifted")
        for (index, pair) in zip(actual, expected).enumerated() {
            XCTAssertEqual(pair.0.latitude, pair.1.latitude, accuracy: 0.000001, "\(formatName) visit \(index) latitude")
            XCTAssertEqual(pair.0.longitude, pair.1.longitude, accuracy: 0.000001, "\(formatName) visit \(index) longitude")
            if let arrivedAt = pair.0.arrivedAt {
                XCTAssertEqual(arrivedAt.timeIntervalSince1970, pair.1.arrivedAt.timeIntervalSince1970, accuracy: 0.001, "\(formatName) visit \(index) arrival")
            }
            if let departedAt = pair.0.departedAt, let expectedDeparture = pair.1.departedAt {
                XCTAssertEqual(departedAt.timeIntervalSince1970, expectedDeparture.timeIntervalSince1970, accuracy: 0.001, "\(formatName) visit \(index) departure")
            }
            XCTAssertEqual(pair.0.locationName, pair.1.locationName, "\(formatName) visit \(index) location")
            XCTAssertEqual(pair.0.address, pair.1.address, "\(formatName) visit \(index) address")
            XCTAssertEqual(pair.0.notes, pair.1.notes, "\(formatName) visit \(index) notes")
        }
    }

    private func assertPoints(_ actual: [NormalizedPoint], match expected: [LocationPoint], formatName: String) {
        XCTAssertEqual(actual.count, expected.count, "\(formatName) point count drifted")
        for (index, pair) in zip(actual, expected).enumerated() {
            XCTAssertEqual(pair.0.latitude, pair.1.latitude, accuracy: 0.000001, "\(formatName) point \(index) latitude")
            XCTAssertEqual(pair.0.longitude, pair.1.longitude, accuracy: 0.000001, "\(formatName) point \(index) longitude")
            if let timestamp = pair.0.timestamp {
                XCTAssertEqual(timestamp.timeIntervalSince1970, pair.1.timestamp.timeIntervalSince1970, accuracy: 1.0, "\(formatName) point \(index) timestamp")
            }
            if let altitude = pair.0.altitude {
                XCTAssertEqual(altitude, pair.1.altitude ?? 0, accuracy: 0.51, "\(formatName) point \(index) altitude")
            }
            if let speed = pair.0.speed {
                XCTAssertEqual(speed, pair.1.speed ?? 0, accuracy: 0.15, "\(formatName) point \(index) speed")
            }
            if let horizontalAccuracy = pair.0.horizontalAccuracy {
                XCTAssertEqual(horizontalAccuracy, pair.1.horizontalAccuracy, accuracy: 0.51, "\(formatName) point \(index) accuracy")
            }
            if let isOutlier = pair.0.isOutlier {
                XCTAssertEqual(isOutlier, pair.1.isOutlier, "\(formatName) point \(index) outlier flag")
            }
        }
    }

    private func markdownCells(_ row: String) -> [String] {
        row.split(separator: "|", omittingEmptySubsequences: false)
            .dropFirst()
            .dropLast()
            .map { $0.trimmingCharacters(in: .whitespaces) }
    }

    private func markdownSection(named heading: String, in text: String) throws -> String {
        let components = text.components(separatedBy: heading)
        guard components.count >= 2 else {
            XCTFail("Missing Markdown section \(heading)")
            return ""
        }
        let tail = components[1]
        if let nextSection = tail.range(of: "\n# ") {
            return String(tail[..<nextSection.lowerBound])
        }
        return tail
    }

    private func markdownTableRows(in section: String) -> [String] {
        section.components(separatedBy: .newlines)
            .filter { $0.hasPrefix("| ") && !$0.contains("------") }
    }

    private func numericPrefix(_ value: String) -> Double? {
        Double(value.components(separatedBy: " ").first ?? "")
    }

    private func parseCSVRows(_ content: String) -> [[String]] {
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
                    } else {
                        inQuotes = false
                        i += 1
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
                    if !currentRow.allSatisfy({ $0.isEmpty }) {
                        rows.append(currentRow)
                    }
                    currentField = ""
                    currentRow = []
                    i += 1
                } else if char == "\r" {
                    i += 1
                } else {
                    currentField.append(char)
                    i += 1
                }
            }
        }

        if !currentField.isEmpty || !currentRow.isEmpty {
            currentRow.append(currentField)
            rows.append(currentRow)
        }

        return rows
    }
}

private final class KMLRoundTripParser: NSObject, XMLParserDelegate {
    private(set) var rootAttributes: [String: String] = [:]
    private(set) var visits: [ExportRoundTripTests.NormalizedVisit] = []
    private(set) var points: [ExportRoundTripTests.NormalizedPoint] = []

    private var elementStack: [String] = []
    private var textBuffer = ""
    private var currentPlacemark: CurrentPlacemark?
    private var currentDataName: String?

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        elementStack.append(elementName)
        textBuffer = ""

        if elementName == "kml" {
            rootAttributes = attributeDict
        } else if elementName == "Placemark" {
            currentPlacemark = CurrentPlacemark()
        } else if elementName == "Data", currentPlacemark != nil {
            currentDataName = attributeDict["name"]
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        textBuffer += string
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        let value = textBuffer.trimmingCharacters(in: .whitespacesAndNewlines)

        if currentPlacemark != nil {
            switch elementName {
            case "name":
                currentPlacemark?.name = value
            case "description":
                currentPlacemark?.description = value
            case "value":
                if let currentDataName {
                    currentPlacemark?.data[currentDataName] = value
                    self.currentDataName = nil
                }
            case "when":
                if elementStack.contains("gx:Track") {
                    currentPlacemark?.trackTimestamps.append(ExportRoundTripTests.iso8601.date(from: value))
                } else {
                    currentPlacemark?.timestamp = ExportRoundTripTests.iso8601.date(from: value)
                }
            case "gx:coord":
                if let coordinate = parseGXCoordinate(value) {
                    currentPlacemark?.trackCoordinates.append(coordinate)
                }
            case "coordinates":
                if currentPlacemark?.data["kind"] == "visit",
                   currentPlacemark?.visitCoordinate == nil,
                   let coordinate = parseKMLCoordinate(value) {
                    currentPlacemark?.visitCoordinate = coordinate
                }
            default:
                break
            }
        }

        if elementName == "Placemark", let placemark = currentPlacemark {
            finalize(placemark)
            currentPlacemark = nil
        }

        _ = elementStack.popLast()
        textBuffer = ""
    }

    private func finalize(_ placemark: CurrentPlacemark) {
        switch placemark.data["kind"] {
        case "visit":
            guard let coordinate = placemark.visitCoordinate else { return }
            visits.append(ExportRoundTripTests.NormalizedVisit(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude,
                arrivedAt: placemark.data["arrivedAt"].flatMap(ExportRoundTripTests.iso8601.date(from:)) ?? placemark.timestamp,
                departedAt: placemark.data["departedAt"].flatMap(ExportRoundTripTests.iso8601.date(from:)),
                locationName: placemark.name == "Visit" ? nil : placemark.name,
                address: placemark.data["address"],
                notes: placemark.data["notes"]
            ))
        case "trackingSession":
            for (index, coordinate) in placemark.trackCoordinates.enumerated() {
                let timestamp = index < placemark.trackTimestamps.count ? placemark.trackTimestamps[index] : nil
                points.append(ExportRoundTripTests.NormalizedPoint(
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude,
                    timestamp: timestamp,
                    altitude: coordinate.altitude,
                    speed: nil,
                    horizontalAccuracy: nil,
                    isOutlier: nil
                ))
            }
        default:
            break
        }
    }

    private func parseKMLCoordinate(_ value: String) -> Coordinate? {
        guard let firstCoordinate = value.split(whereSeparator: { $0.isWhitespace }).first else { return nil }
        let parts = firstCoordinate.split(separator: ",").map(String.init)
        guard parts.count >= 2,
              let longitude = Double(parts[0]),
              let latitude = Double(parts[1]) else { return nil }
        let altitude = parts.count >= 3 ? Double(parts[2]) : nil
        return Coordinate(latitude: latitude, longitude: longitude, altitude: altitude)
    }

    private func parseGXCoordinate(_ value: String) -> Coordinate? {
        let parts = value.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count >= 2,
              let longitude = Double(parts[0]),
              let latitude = Double(parts[1]) else { return nil }
        let altitude = parts.count >= 3 ? Double(parts[2]) : nil
        return Coordinate(latitude: latitude, longitude: longitude, altitude: altitude)
    }

    private struct CurrentPlacemark {
        var name: String?
        var description: String?
        var timestamp: Date?
        var data: [String: String] = [:]
        var visitCoordinate: Coordinate?
        var trackTimestamps: [Date?] = []
        var trackCoordinates: [Coordinate] = []
    }

    private struct Coordinate {
        let latitude: Double
        let longitude: Double
        let altitude: Double?
    }
}

private final class GPXRoundTripParser: NSObject, XMLParserDelegate {
    private(set) var rootAttributes: [String: String] = [:]
    private(set) var visits: [ExportRoundTripTests.NormalizedVisit] = []
    private(set) var points: [ExportRoundTripTests.NormalizedPoint] = []

    private var elementStack: [String] = []
    private var textBuffer = ""
    private var currentVisit: CurrentVisit?
    private var currentPoint: CurrentPoint?

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        elementStack.append(elementName)
        textBuffer = ""

        if elementName == "gpx" {
            rootAttributes = attributeDict
        } else if elementName == "wpt" {
            currentVisit = CurrentVisit(
                latitude: Double(attributeDict["lat"] ?? "")!,
                longitude: Double(attributeDict["lon"] ?? "")!
            )
        } else if elementName == "trkpt" {
            currentPoint = CurrentPoint(
                latitude: Double(attributeDict["lat"] ?? "")!,
                longitude: Double(attributeDict["lon"] ?? "")!
            )
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        textBuffer += string
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        let value = textBuffer.trimmingCharacters(in: .whitespacesAndNewlines)

        if currentVisit != nil {
            switch elementName {
            case "time":
                currentVisit?.arrivedAt = ExportRoundTripTests.iso8601.date(from: value)
            case "name":
                currentVisit?.locationName = value
            case "desc":
                let parts = value.components(separatedBy: " — ")
                currentVisit?.address = parts.first
                currentVisit?.notes = parts.dropFirst().joined(separator: " — ")
            case "isome:departedAt":
                currentVisit?.departedAt = ExportRoundTripTests.iso8601.date(from: value)
            default:
                break
            }
        }

        if currentPoint != nil {
            switch elementName {
            case "ele":
                currentPoint?.altitude = Double(value)
            case "time":
                currentPoint?.timestamp = ExportRoundTripTests.iso8601.date(from: value)
            case "isome:speed":
                currentPoint?.speed = Double(value)
            case "isome:horizontalAccuracy":
                currentPoint?.horizontalAccuracy = Double(value)
            case "isome:isOutlier":
                currentPoint?.isOutlier = value == "true"
            default:
                break
            }
        }

        if elementName == "wpt", let visit = currentVisit {
            visits.append(visit.normalized)
            currentVisit = nil
        } else if elementName == "trkpt", let point = currentPoint {
            points.append(point.normalized)
            currentPoint = nil
        }

        _ = elementStack.popLast()
        textBuffer = ""
    }

    private struct CurrentVisit {
        let latitude: Double
        let longitude: Double
        var arrivedAt: Date?
        var departedAt: Date?
        var locationName: String?
        var address: String?
        var notes: String?

        var normalized: ExportRoundTripTests.NormalizedVisit {
            ExportRoundTripTests.NormalizedVisit(
                latitude: latitude,
                longitude: longitude,
                arrivedAt: arrivedAt,
                departedAt: departedAt,
                locationName: locationName,
                address: address,
                notes: notes
            )
        }
    }

    private struct CurrentPoint {
        let latitude: Double
        let longitude: Double
        var timestamp: Date?
        var altitude: Double?
        var speed: Double?
        var horizontalAccuracy: Double?
        var isOutlier: Bool?

        var normalized: ExportRoundTripTests.NormalizedPoint {
            ExportRoundTripTests.NormalizedPoint(
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
}
