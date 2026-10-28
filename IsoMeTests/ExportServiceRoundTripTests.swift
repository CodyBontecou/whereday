import XCTest
import CoreLocation
@testable import IsoMe

final class ExportServiceRoundTripTests: XCTestCase {
    private let coordinateAccuracy = 0.000001
    private let dateAccuracy = 1.0
    private let distanceAccuracy = 0.5

    func testJSONRoundTripsVisitsAndLocationPoints() throws {
        let visits = makeVisits()
        let points = makeLocationPoints()

        let importedVisits = try ImportService.importFile(
            data: ExportService.exportToJSON(visits: visits),
            format: .json
        ).visits
        let importedPoints = try ImportService.importFile(
            data: ExportService.exportLocationPointsToJSON(points: points),
            format: .json
        ).points

        assertVisits(importedVisits, match: visits)
        assertLocationPoints(importedPoints, match: points)
    }

    func testJSONRoundTripsVisitCorrectionMetadata() throws {
        let visit = Visit(
            latitude: 37.7749,
            longitude: -122.4194,
            arrivedAt: fixtureDate(hour: 9, minute: 0),
            departedAt: fixtureDate(hour: 10, minute: 0),
            locationName: "Detected Block",
            address: "Market St",
            notes: "Reviewed"
        )
        visit.preserveOriginalValuesIfNeeded()
        visit.latitude = 37.7755
        visit.longitude = -122.4188
        visit.locationName = "Correct Cafe"
        visit.address = "1 Correct Way"
        visit.confirmationStatus = VisitConfirmationStatus.corrected
        visit.confirmedAt = fixtureDate(hour: 9, minute: 30)
        visit.updatedAt = fixtureDate(hour: 9, minute: 45)
        visit.placeSource = VisitPlaceSource.appleMaps
        visit.placeDistanceMeters = 42

        let imported = try XCTUnwrap(try ImportService.importFile(
            data: ExportService.exportToJSON(visits: [visit]),
            format: .json
        ).visits.first)

        XCTAssertEqual(imported.sourceRaw, VisitSource.automatic.rawValue)
        XCTAssertEqual(imported.confirmationStatusRaw, VisitConfirmationStatus.corrected.rawValue)
        XCTAssertEqual(try XCTUnwrap(imported.confirmedAt?.timeIntervalSince1970), try XCTUnwrap(visit.confirmedAt?.timeIntervalSince1970), accuracy: dateAccuracy)
        XCTAssertEqual(try XCTUnwrap(imported.updatedAt?.timeIntervalSince1970), try XCTUnwrap(visit.updatedAt?.timeIntervalSince1970), accuracy: dateAccuracy)
        XCTAssertEqual(try XCTUnwrap(imported.originalLatitude), 37.7749, accuracy: coordinateAccuracy)
        XCTAssertEqual(try XCTUnwrap(imported.originalLongitude), -122.4194, accuracy: coordinateAccuracy)
        XCTAssertEqual(imported.locationName, "Correct Cafe")
        XCTAssertEqual(imported.address, "1 Correct Way")
        XCTAssertEqual(imported.originalLocationName, "Detected Block")
        XCTAssertEqual(imported.originalAddress, "Market St")
        XCTAssertEqual(imported.placeSourceRaw, VisitPlaceSource.appleMaps.rawValue)
        XCTAssertEqual(imported.placeDistanceMeters, 42)
    }

    func testCSVRoundTripsVisitsAndLocationPoints() throws {
        let visits = makeVisits()
        let points = makeLocationPoints()

        let importedVisits = try ImportService.importFile(
            data: ExportService.exportToCSV(visits: visits),
            format: .csv
        ).visits
        let importedPoints = try ImportService.importFile(
            data: ExportService.exportLocationPointsToCSV(points: points),
            format: .csv
        ).points

        assertVisits(importedVisits, match: visits)
        assertLocationPoints(importedPoints, match: points)
    }

    func testMarkdownRoundTripsVisitsAndLocationPoints() throws {
        let visits = makeVisits()
        let points = makeLocationPoints()

        let importedVisits = try ImportService.importFile(
            data: ExportService.exportToMarkdown(visits: visits),
            format: .markdown
        ).visits
        let importedPoints = try ImportService.importFile(
            data: ExportService.exportLocationPointsToMarkdown(points: points),
            format: .markdown
        ).points

        assertVisits(importedVisits, match: visits, coordinateAccuracy: 0.000001)
        assertLocationPoints(importedPoints, match: points, coordinateAccuracy: 0.000001, valueAccuracy: 0.06)
    }

    func testMalformedJSONThrowsParsingError() {
        let corruptJSON = Data(#"{ "visits": [ }"#.utf8)

        XCTAssertThrowsError(try ImportService.importFile(data: corruptJSON, format: .json)) { error in
            guard case ImportError.parsingFailed(let detail) = error else {
                return XCTFail("Expected parsingFailed, got \(error)")
            }
            XCTAssertTrue(detail.contains("Invalid JSON"))
        }
    }

    func testMalformedCSVThrowsClearError() {
        let corruptCSV = """
        arrived_at,departed_at,duration_minutes,latitude,longitude,location_name,address,notes
        2026-04-03T09:00:00.000Z,2026-04-03T10:00:00.000Z,60.0,not-a-lat,-122.4194,Cafe,Address,Notes
        """.data(using: .utf8)!

        XCTAssertThrowsError(try ImportService.importFile(data: corruptCSV, format: .csv)) { error in
            guard case ImportError.invalidData(let detail) = error else {
                return XCTFail("Expected invalidData, got \(error)")
            }
            XCTAssertEqual(detail, "Row 2: invalid latitude")
        }
    }

    func testMarkdownTypeDetectionAcceptsBothBrands() {
        for brand in ["iso.me", "Whereday"] {
            let points = "# \(brand) Location Points Export\n".data(using: .utf8)!
            let visits = "# \(brand) Export\n".data(using: .utf8)!
            if case .locationPoints = ImportService.detectDataType(from: points, format: .markdown) {} else {
                XCTFail("Expected location points for \(brand)")
            }
            if case .visits = ImportService.detectDataType(from: visits, format: .markdown) {} else {
                XCTFail("Expected visits for \(brand)")
            }
        }
    }

    func testMalformedMarkdownThrowsClearError() {
        let corruptMarkdown = """
        # iso.me Export

        ## Friday, April 3, 2026

        This file has no visit table rows.
        """.data(using: .utf8)!

        XCTAssertThrowsError(try ImportService.importFile(data: corruptMarkdown, format: .markdown)) { error in
            guard case ImportError.invalidData(let detail) = error else {
                return XCTFail("Expected invalidData, got \(error)")
            }
            XCTAssertEqual(detail, "No visits found in Markdown file")
        }
    }

    private func makeVisits() -> [Visit] {
        [
            Visit(
                latitude: 37.774900,
                longitude: -122.419400,
                arrivedAt: fixtureDate(hour: 9, minute: 0),
                departedAt: fixtureDate(hour: 10, minute: 30),
                locationName: "Ferry Building",
                address: "1 Ferry Building, San Francisco, CA",
                notes: "Breakfast stop"
            ),
            Visit(
                latitude: 37.776500,
                longitude: -122.450600,
                arrivedAt: fixtureDate(hour: 12, minute: 15),
                departedAt: fixtureDate(hour: 13, minute: 0),
                customName: "Golden Gate Park Walk",
                locationName: "Panhandle",
                address: "Stanyan St, San Francisco, CA",
                notes: "Walked west"
            ),
            Visit(
                latitude: 37.769400,
                longitude: -122.486200,
                arrivedAt: fixtureDate(hour: 15, minute: 45),
                departedAt: nil,
                locationName: "Ocean Beach",
                address: "Great Highway, San Francisco, CA",
                notes: nil
            )
        ]
    }

    private func makeLocationPoints() -> [LocationPoint] {
        [
            LocationPoint(
                latitude: 37.774900,
                longitude: -122.419400,
                timestamp: fixtureDate(hour: 9, minute: 0, second: 5),
                altitude: 4.25,
                speed: 1.20,
                horizontalAccuracy: 5.0
            ),
            LocationPoint(
                latitude: 37.775200,
                longitude: -122.421000,
                timestamp: fixtureDate(hour: 9, minute: 5, second: 20),
                altitude: 5.10,
                speed: 1.75,
                horizontalAccuracy: 4.5
            ),
            LocationPoint(
                latitude: 37.776000,
                longitude: -122.423500,
                timestamp: fixtureDate(hour: 9, minute: 12, second: 45),
                altitude: nil,
                speed: nil,
                horizontalAccuracy: 8.0,
                isOutlier: true
            )
        ]
    }

    private func fixtureDate(hour: Int, minute: Int, second: Int = 0) -> Date {
        var components = DateComponents()
        components.year = 2026
        components.month = 4
        components.day = 3
        components.hour = hour
        components.minute = minute
        components.second = second
        return Calendar.current.date(from: components)!
    }

    private func assertVisits(
        _ imported: [ImportedVisit],
        match expected: [Visit],
        coordinateAccuracy: Double? = nil
    ) {
        let imported = imported.sorted { $0.arrivedAt < $1.arrivedAt }
        let expected = expected.sorted { $0.arrivedAt < $1.arrivedAt }
        let accuracy = coordinateAccuracy ?? self.coordinateAccuracy

        XCTAssertEqual(imported.count, expected.count)
        for (importedVisit, expectedVisit) in zip(imported, expected) {
            XCTAssertEqual(importedVisit.latitude, expectedVisit.latitude, accuracy: accuracy)
            XCTAssertEqual(importedVisit.longitude, expectedVisit.longitude, accuracy: accuracy)
            XCTAssertEqual(importedVisit.arrivedAt.timeIntervalSince1970, expectedVisit.arrivedAt.timeIntervalSince1970, accuracy: dateAccuracy)
            XCTAssertEqualOptional(importedVisit.departedAt?.timeIntervalSince1970, expectedVisit.departedAt?.timeIntervalSince1970, accuracy: dateAccuracy)
            XCTAssertEqual(importedVisit.locationName, expectedVisit.exportLocationName)
            XCTAssertEqual(importedVisit.address, expectedVisit.address)
            XCTAssertEqual(importedVisit.notes, expectedVisit.notes)
            XCTAssertEqualOptional(
                importedVisit.departedAt?.timeIntervalSince(importedVisit.arrivedAt),
                expectedVisit.departedAt?.timeIntervalSince(expectedVisit.arrivedAt),
                accuracy: dateAccuracy
            )
        }
    }

    private func assertLocationPoints(
        _ imported: [ImportedLocationPoint],
        match expected: [LocationPoint],
        coordinateAccuracy: Double? = nil,
        valueAccuracy: Double = 0.001
    ) {
        let imported = imported.sorted { $0.timestamp < $1.timestamp }
        let expected = expected.sorted { $0.timestamp < $1.timestamp }
        let accuracy = coordinateAccuracy ?? self.coordinateAccuracy

        XCTAssertEqual(imported.count, expected.count)
        for (importedPoint, expectedPoint) in zip(imported, expected) {
            XCTAssertEqual(importedPoint.latitude, expectedPoint.latitude, accuracy: accuracy)
            XCTAssertEqual(importedPoint.longitude, expectedPoint.longitude, accuracy: accuracy)
            XCTAssertEqual(importedPoint.timestamp.timeIntervalSince1970, expectedPoint.timestamp.timeIntervalSince1970, accuracy: dateAccuracy)
            XCTAssertEqualOptional(importedPoint.altitude, expectedPoint.altitude, accuracy: valueAccuracy)
            XCTAssertEqualOptional(importedPoint.speed, expectedPoint.speed, accuracy: valueAccuracy)
            XCTAssertEqual(importedPoint.horizontalAccuracy, expectedPoint.horizontalAccuracy, accuracy: valueAccuracy)
            XCTAssertEqual(importedPoint.isOutlier, expectedPoint.isOutlier)
        }

        XCTAssertEqual(routeDistance(imported), routeDistance(expected), accuracy: distanceAccuracy)
    }

    private func routeDistance(_ points: [ImportedLocationPoint]) -> Double {
        points.sorted { $0.timestamp < $1.timestamp }
            .map { CLLocation(latitude: $0.latitude, longitude: $0.longitude) }
            .adjacentDistance()
    }

    private func routeDistance(_ points: [LocationPoint]) -> Double {
        points.sorted { $0.timestamp < $1.timestamp }
            .map { CLLocation(latitude: $0.latitude, longitude: $0.longitude) }
            .adjacentDistance()
    }

    private func XCTAssertEqualOptional(
        _ expression1: Double?,
        _ expression2: Double?,
        accuracy: Double,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        switch (expression1, expression2) {
        case let (value1?, value2?):
            XCTAssertEqual(value1, value2, accuracy: accuracy, file: file, line: line)
        case (nil, nil):
            break
        default:
            XCTFail("Expected \(String(describing: expression2)), got \(String(describing: expression1))", file: file, line: line)
        }
    }
}

private extension Array where Element == CLLocation {
    func adjacentDistance() -> Double {
        guard count > 1 else { return 0 }
        return zip(self, dropFirst()).reduce(0) { total, pair in
            total + pair.0.distance(from: pair.1)
        }
    }
}
