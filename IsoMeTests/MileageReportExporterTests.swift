import Foundation
import XCTest
@testable import IsoMe

/// Synthetic fixtures only. Oracles: literal schema/values, an independent RFC4180 reader,
/// and Foundation JSONDecoder/JSONSerialization (never an exporter round trip).
final class MileageReportExporterTests: XCTestCase {
    private let firstID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
    private let secondID = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
    private let vehicleID = UUID(uuidString: "00000000-0000-0000-0000-000000000010")!
    private let disclaimer = "Mileage estimates are generated from GPS data and may differ from your vehicle odometer. Classifications, rates, and deduction estimates are for recordkeeping only and are not tax, legal, or accounting advice. Review every trip and consult a qualified tax professional. Tax rules and mileage rates can change."
    private let headers = [
        "trip_id", "date", "start_time", "end_time", "origin_name", "origin_address",
        "destination_name", "destination_address", "classification", "business_purpose", "vehicle",
        "distance_miles", "rate_per_mile_usd", "deduction_usd", "review_status", "source", "notes",
        "start_odometer_miles", "end_odometer_miles", "row_type", "vehicle_id", "trip_time_zone",
        "rate_source", "rate_is_custom_override", "metadata_key", "metadata_value"
    ]

    private func date(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }
    private func trip(_ id: UUID? = nil, start: String = "2025-02-01T12:00:00Z",
                      classification: TripClassification = .business) -> Trip {
        let instant = date(start)
        let trip = Trip(id: id ?? firstID, startedAt: instant, timezoneIdentifier: "UTC",
                        source: .imported, now: instant)
        trip.endedAt = instant.addingTimeInterval(60)
        trip.distanceMeters = 1609.344
        trip.classify(as: classification, reviewed: true, now: instant)
        trip.businessPurpose = "Synthetic purpose"
        return trip
    }
    private func report(_ trips: [Trip], rates: MileageRateProvider? = nil) throws -> MileageReportService.Report {
        try build(trips.map { MileageReportTrip(trip: $0) }, rates: rates)
    }
    private func build(_ snapshots: [MileageReportTrip], rates: MileageRateProvider? = nil) throws -> MileageReportService.Report {
        try MileageReportService.build(
            trips: snapshots,
            options: MileageReportOptions(startsAt: date("2024-01-01T00:00:00Z"), endsAt: date("2028-01-01T00:00:00Z")),
            rates: rates ?? MileageRateProvider(customRate: Decimal(string: "0.5")!, customSource: "Synthetic rate"))
    }
    private func json(_ report: MileageReportService.Report, zone: TimeZone = .gmt) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: MileageReportExporter.json(report, timeZone: zone)) as? [String: Any])
    }
    private func rows(_ document: [String: Any]) throws -> [[String: Any]] {
        try XCTUnwrap(document["rows"] as? [[String: Any]])
    }
    private func csv(_ report: MileageReportService.Report, zone: TimeZone = .gmt) throws -> [[String: String]] {
        let records = try decodeCSV(MileageReportExporter.csv(report, timeZone: zone))
        XCTAssertEqual(records.first, headers)
        return records.dropFirst().map { record in
            XCTAssertEqual(record.count, headers.count, "Every metadata/summary/trip record has the declared width")
            return Dictionary(uniqueKeysWithValues: zip(headers, record))
        }
    }

    func testCSVHasExactIssueColumnsAndExplicitTypedFullWidthNonTripRows() throws {
        let result = try csv(report([trip()]))
        let row = try XCTUnwrap(result.first { $0["row_type"] == "trip" })
        XCTAssertEqual(row["trip_id"], firstID.uuidString.lowercased())
        XCTAssertEqual(row["date"], "2025-02-01")
        XCTAssertEqual(row["distance_miles"], "1")
        XCTAssertEqual(row["rate_per_mile_usd"], "0.5")
        XCTAssertEqual(row["deduction_usd"], "0.5")
        XCTAssertEqual(row["classification"], "business")
        XCTAssertEqual(row["business_purpose"], "Synthetic purpose")
        XCTAssertEqual(row["review_status"], "reviewed")
        XCTAssertEqual(row["source"], "imported")
        XCTAssertEqual(row["rate_source"], "Synthetic rate")
        XCTAssertEqual(row["rate_is_custom_override"], "true")
        XCTAssertEqual(Set(result.compactMap { $0["row_type"] }), ["metadata", "trip", "summary", "vehicle_summary"])
        for row in result where row["row_type"] != "trip" { XCTAssertEqual(row["trip_id"], "") }
    }

    func testCSVIndependentReaderHandlesCommaQuoteCRLFAndUnicodeWithoutDataLoss() throws {
        let item = trip()
        item.startName = "Synthetic, origin"
        item.startAddress = "Synthetic \"quoted\" block"
        item.endName = "合成終点 é e\u{301}"
        item.endAddress = "Synthetic line one\r\nline two"
        item.notes = "Synthetic lone\nLF and lone\rCR"
        let row = try XCTUnwrap(csv(report([item])).first { $0["row_type"] == "trip" })
        XCTAssertEqual(row["origin_name"], "Synthetic, origin")
        XCTAssertEqual(row["origin_address"], "Synthetic \"quoted\" block")
        XCTAssertEqual(Array(try XCTUnwrap(row["destination_name"]).utf8), Array("合成終点 é e\u{301}".utf8))
        XCTAssertEqual(row["destination_address"], "Synthetic line one\r\nline two")
        XCTAssertEqual(row["notes"], "Synthetic lone\nLF and lone\rCR")
        let data = try MileageReportExporter.csv(report([item]), timeZone: .gmt)
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertTrue(text.contains("\"Synthetic \"\"quoted\"\" block\""))
        XCTAssertTrue(text.hasSuffix("\r\n"))
        XCTAssertFalse(data.starts(with: [0xef, 0xbb, 0xbf]))
    }

    func testCSVFormulaPolicyCoversAllUserTextFieldsButJSONRemainsLossless() throws {
        let item = trip()
        item.startName = "=1+1"
        item.startAddress = "+1"
        item.endName = "-1"
        item.endAddress = "@SUM(1)"
        item.businessPurpose = " \t=1"
        item.vehicleNameSnapshot = "\tSynthetic vehicle"
        item.notes = "\r\n=1"
        item.sourceRaw = "\u{FEFF}=1"
        let provider = try MileageRateProvider(customRate: 1, customSource: "  @Synthetic provenance")
        let value = try report([item], rates: provider)
        let row = try XCTUnwrap(csv(value).first { $0["row_type"] == "trip" })
        let expected = ["origin_name": "=1+1", "origin_address": "+1", "destination_name": "-1",
                        "destination_address": "@SUM(1)", "business_purpose": " \t=1", "vehicle": "\tSynthetic vehicle",
                        "notes": "\r\n=1", "source": "\u{FEFF}=1", "rate_source": "  @Synthetic provenance"]
        let jsonData = try MileageReportExporter.json(value, timeZone: .gmt)
        let document = try JSONDecoder().decode(FormulaDocument.self, from: jsonData)
        let raw = try XCTUnwrap(document.rows.first).textFields
        for (key, literal) in expected {
            XCTAssertEqual(row[key], "'" + literal)
            XCTAssertEqual(raw[key] as? String, literal)
        }
        item.notes = "'=already literal"
        XCTAssertEqual(try csv(report([item])).first { $0["row_type"] == "trip" }?["notes"], "'=already literal")
    }

    func testExplicitGregorianReportZoneHandlesDSTNotTripStoredZone() throws {
        let item = trip(start: "2025-03-09T09:30:00Z")
        item.endedAt = date("2025-03-09T10:30:00Z")
        item.timezoneIdentifier = "Pacific/Kiritimati"
        let zone = TimeZone(identifier: "America/Los_Angeles")!
        let value = try report([item])
        let row = try XCTUnwrap(csv(value, zone: zone).first { $0["row_type"] == "trip" })
        XCTAssertEqual(row["date"], "2025-03-09")
        XCTAssertEqual(row["start_time"], "2025-03-09T01:30:00.000-08:00")
        XCTAssertEqual(row["end_time"], "2025-03-09T03:30:00.000-07:00")
        XCTAssertEqual(row["trip_time_zone"], "Pacific/Kiritimati")
        let document = try json(value, zone: zone)
        let metadata = try XCTUnwrap(document["metadata"] as? [String: String])
        XCTAssertEqual(metadata["calendar"], "gregorian")
        XCTAssertEqual(metadata["time_zone"], "America/Los_Angeles")
        XCTAssertEqual(try rows(document).first?["start_time"] as? String, "2025-03-09T01:30:00.000-08:00")
    }

    func testReportZoneDateCanDifferFromUTCAndFallBackOffsetsDisambiguate() throws {
        let zone = TimeZone(identifier: "America/Los_Angeles")!
        let midnight = trip(start: "2025-01-01T00:30:00Z")
        XCTAssertEqual(try csv(report([midnight]), zone: zone).first { $0["row_type"] == "trip" }?["date"], "2024-12-31")
        let fall = trip(start: "2025-11-02T08:30:00Z")
        fall.endedAt = date("2025-11-02T09:30:00Z")
        let row = try XCTUnwrap(rows(json(report([fall]), zone: zone)).first)
        XCTAssertEqual(row["start_time"] as? String, "2025-11-02T01:30:00.000-07:00")
        XCTAssertEqual(row["end_time"] as? String, "2025-11-02T01:30:00.000-08:00")
    }

    func testSnapshotFreezesActualTripFieldsBeforeModelMutations() throws {
        let item = trip()
        item.startName = "Synthetic origin"; item.startAddress = "Synthetic origin address"
        item.endName = "Synthetic destination"; item.endAddress = "Synthetic destination address"
        item.notes = "Synthetic original notes"
        item.startOdometerMiles = 10.125; item.endOdometerMiles = 11.125
        item.vehicleID = vehicleID; item.vehicleNameSnapshot = "Synthetic original vehicle"
        let snapshot = MileageReportTrip(trip: item)
        item.startName = "Changed"; item.startAddress = nil; item.endName = nil; item.endAddress = "Changed"
        item.notes = "Changed"; item.sourceRaw = "changedSource"; item.timezoneIdentifier = "Asia/Kathmandu"
        item.startOdometerMiles = nil; item.endOdometerMiles = 99; item.vehicleNameSnapshot = "Changed"
        let row = try XCTUnwrap(rows(json(build([snapshot]))).first)
        XCTAssertEqual(row["origin_name"] as? String, "Synthetic origin")
        XCTAssertEqual(row["origin_address"] as? String, "Synthetic origin address")
        XCTAssertEqual(row["destination_name"] as? String, "Synthetic destination")
        XCTAssertEqual(row["destination_address"] as? String, "Synthetic destination address")
        XCTAssertEqual(row["notes"] as? String, "Synthetic original notes")
        XCTAssertEqual(row["source"] as? String, "imported")
        XCTAssertEqual(row["trip_time_zone"] as? String, "UTC")
        XCTAssertEqual(row["start_odometer_miles"] as? String, "10.125")
        XCTAssertEqual(row["end_odometer_miles"] as? String, "11.125")
        XCTAssertEqual(row["vehicle"] as? String, "Synthetic original vehicle")
        XCTAssertEqual(row["vehicle_id"] as? String, vehicleID.uuidString.lowercased())
    }

    func testOptionalNullsAndUnavailableRatesAreNotInventedZeros() throws {
        let value = try report([trip()], rates: MileageRateProvider())
        let row = try XCTUnwrap(rows(json(value)).first)
        for key in ["origin_name", "origin_address", "destination_name", "destination_address", "vehicle", "vehicle_id",
                    "start_odometer_miles", "end_odometer_miles", "rate_per_mile_usd", "deduction_usd",
                    "rate_source", "rate_is_custom_override"] {
            XCTAssertTrue(row[key] is NSNull, "Explicit null key: \(key)")
        }
        let record = try XCTUnwrap(csv(value).first { $0["row_type"] == "trip" })
        XCTAssertEqual(record["rate_per_mile_usd"], "")
        XCTAssertEqual(record["deduction_usd"], "")
        let summary = try XCTUnwrap(json(value)["summary"] as? [String: Any])
        XCTAssertEqual(summary["business_trips_missing_rate"] as? Int, 1)
        XCTAssertEqual(summary["is_estimate_complete"] as? Bool, false)
        XCTAssertEqual(summary["known_estimated_deduction_usd"] as? String, "0")
    }

    func testExplicitZeroRateIsDistinctFromMissingRate() throws {
        let provider = try MileageRateProvider(customRate: 0, customSource: "Synthetic explicit zero")
        let row = try XCTUnwrap(rows(json(report([trip()], rates: provider))).first)
        XCTAssertEqual(row["rate_per_mile_usd"] as? String, "0")
        XCTAssertEqual(row["deduction_usd"] as? String, "0")
        XCTAssertEqual(row["rate_is_custom_override"] as? Bool, true)
        XCTAssertEqual(row["rate_source"] as? String, "Synthetic explicit zero")
    }

    func testMixedClassificationsSummariesAndVehicleHistoricalNamesUseActualReport() throws {
        let business = trip(); business.distanceMeters = 3218.688
        business.vehicleID = vehicleID; business.vehicleNameSnapshot = "Synthetic old vehicle"
        let personal = trip(secondID, start: "2025-02-02T12:00:00Z", classification: .personal)
        personal.vehicleID = vehicleID; personal.vehicleNameSnapshot = "Synthetic new vehicle"
        let unclassified = trip(UUID(uuidString: "00000000-0000-0000-0000-000000000003")!, start: "2025-02-03T12:00:00Z", classification: .unclassified)
        let missing = trip(UUID(uuidString: "00000000-0000-0000-0000-000000000004")!, start: "2027-02-01T12:00:00Z")
        missing.businessPurpose = " \n"; missing.reviewStatusRaw = "needsReview"
        let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt)
        let value = try report([missing, unclassified, personal, business], rates: provider)
        let document = try json(value)
        let summary = try XCTUnwrap(document["summary"] as? [String: Any])
        XCTAssertEqual(summary["miles_by_classification"] as? [String: String], ["business": "3", "personal": "1", "unclassified": "1"])
        XCTAssertEqual(summary["reviewed_business_trips"] as? Int, 1)
        XCTAssertEqual(summary["business_trips_missing_purpose"] as? Int, 1)
        XCTAssertEqual(summary["business_trips_missing_rate"] as? Int, 1)
        XCTAssertEqual(summary["known_estimated_deduction_usd"] as? String, "1.4")
        XCTAssertEqual(summary["is_estimate_complete"] as? Bool, false)
        let projected = try rows(document)
        XCTAssertEqual(projected.map { $0["classification"] as? String }, ["business", "personal", "unclassified", "business"])
        XCTAssertEqual(projected[0]["rate_is_custom_override"] as? Bool, false)
        XCTAssertTrue(try XCTUnwrap(projected[0]["rate_source"] as? String).contains("https://www.irs.gov/"))
        for index in [1, 2, 3] { XCTAssertTrue(projected[index]["deduction_usd"] is NSNull) }
        let vehicles = try XCTUnwrap(summary["totals_by_vehicle"] as? [[String: Any]])
        XCTAssertEqual(vehicles.count, 2)
        XCTAssertTrue(vehicles[0]["vehicle_id"] is NSNull)
        XCTAssertEqual(vehicles[0]["distance_miles"] as? String, "2")
        XCTAssertEqual(vehicles[1]["distance_miles"] as? String, "3")
        XCTAssertEqual(vehicles[1]["name_snapshots"] as? [String], ["Synthetic new vehicle", "Synthetic old vehicle"])
        let records = try csv(value)
        XCTAssertEqual(records.first { $0["metadata_key"] == "known_estimated_deduction_usd" }?["metadata_value"], "1.4")
        XCTAssertEqual(records.first { $0["metadata_key"] == "is_estimate_complete" }?["metadata_value"], "false")
        XCTAssertEqual(records.filter { $0["row_type"] == "vehicle_summary" }.map { $0["distance_miles"] }, ["2", "3"])
        let csvSummary = Dictionary(uniqueKeysWithValues: records.filter { $0["row_type"] == "summary" }.map {
            ($0["metadata_key"]!, $0["metadata_value"]!)
        })
        XCTAssertEqual(csvSummary, ["miles.business": "3", "miles.personal": "1", "miles.unclassified": "1",
                                   "reviewed_business_trips": "1", "business_trips_missing_purpose": "1",
                                   "business_trips_missing_rate": "1", "known_estimated_deduction_usd": "1.4",
                                   "is_estimate_complete": "false"])
        let namedVehicle = try XCTUnwrap(records.first { $0["row_type"] == "vehicle_summary" && $0["vehicle_id"] == vehicleID.uuidString.lowercased() })
        let namesData = Data(try XCTUnwrap(namedVehicle["vehicle"]).utf8)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: namesData) as? [String], ["Synthetic new vehicle", "Synthetic old vehicle"])
    }

    func testLargeExactDecimalMoneySurvivesIndependentJSONDecoderAndCSV() throws {
        // Beyond binary64's exact integer range; literal is an independent precision oracle.
        let literal = "9007199254740993.01"
        let provider = try MileageRateProvider(customRate: Decimal(string: literal)!, customSource: "Synthetic precision boundary")
        let value = try report([trip()], rates: provider)
        let data = try MileageReportExporter.json(value, timeZone: .gmt)
        let decoded = try JSONDecoder().decode(PrecisionDocument.self, from: data)
        XCTAssertEqual(decoded.schema_version, 1)
        XCTAssertEqual(decoded.rows[0].rate_per_mile_usd, literal)
        XCTAssertEqual(decoded.rows[0].deduction_usd, literal)
        XCTAssertEqual(decoded.summary.known_estimated_deduction_usd, literal)
        let row = try XCTUnwrap(csv(value).first { $0["row_type"] == "trip" })
        XCTAssertEqual(row["rate_per_mile_usd"], literal)
        XCTAssertEqual(row["deduction_usd"], literal)
    }

    func testFractionalMilesAreReportDecimalVerbatimNotRecalculatedOrRounded() throws {
        let item = trip(classification: .personal); item.distanceMeters = 100
        let value = try report([item])
        // Report Decimal is the trusted input oracle; do not reimplement its repeating division.
        let jsonMiles = try XCTUnwrap(rows(json(value)).first?["distance_miles"] as? String)
        let csvMiles = try XCTUnwrap(csv(value).first { $0["row_type"] == "trip" }?["distance_miles"])
        XCTAssertTrue(jsonMiles.hasPrefix("0.06213711922373339696"))
        XCTAssertEqual(Decimal(string: jsonMiles, locale: Locale(identifier: "en_US_POSIX")), value.rows[0].miles)
        XCTAssertEqual(Decimal(string: csvMiles, locale: Locale(identifier: "en_US_POSIX")), value.rows[0].miles)
    }

    func testDisclaimerVersionDecimalContractAndEmptySummaryPresentInBothFormats() throws {
        let value = try report([])
        let document = try json(value)
        XCTAssertEqual(document["schema_version"] as? Int, 1)
        XCTAssertEqual(document["format"] as? String, "whereday-mileage-report")
        let metadata = try XCTUnwrap(document["metadata"] as? [String: String])
        XCTAssertEqual(metadata["disclaimer"], disclaimer)
        XCTAssertEqual(metadata["decimal_encoding"], "base10-strings")
        XCTAssertTrue(try rows(document).isEmpty)
        let summary = try XCTUnwrap(document["summary"] as? [String: Any])
        XCTAssertEqual(summary["miles_by_classification"] as? [String: String], ["business": "0", "personal": "0", "unclassified": "0"])
        XCTAssertEqual(summary["known_estimated_deduction_usd"] as? String, "0")
        XCTAssertEqual(summary["is_estimate_complete"] as? Bool, true)
        let records = try csv(value)
        XCTAssertEqual(records.first { $0["metadata_key"] == "disclaimer" }?["metadata_value"], disclaimer)
        XCTAssertEqual(records.first { $0["metadata_key"] == "schema_version" }?["metadata_value"], "1")
        XCTAssertFalse(records.contains { $0["row_type"] == "trip" })
    }

    func testDeterministicBytesAcrossRebuiltReportsWithReversedInputAndSameInstantTies() throws {
        let a = trip(); let b = trip(secondID)
        a.vehicleID = vehicleID; b.vehicleID = vehicleID
        a.vehicleNameSnapshot = "Synthetic Z"; b.vehicleNameSnapshot = "Synthetic A"
        let forward = try report([a, b]); let reverse = try report([b, a])
        let csvBytes = try MileageReportExporter.csv(forward, timeZone: .gmt)
        let jsonBytes = try MileageReportExporter.json(forward, timeZone: .gmt)
        for _ in 0..<3 {
            XCTAssertEqual(try MileageReportExporter.csv(reverse, timeZone: .gmt), csvBytes)
            XCTAssertEqual(try MileageReportExporter.json(reverse, timeZone: .gmt), jsonBytes)
        }
        let text = try XCTUnwrap(String(data: jsonBytes, encoding: .utf8))
        XCTAssertTrue(text.hasPrefix("{\"format\":\"whereday-mileage-report\",\"metadata\":"), "Stable lexicographic root keys")
        XCTAssertFalse(text.contains("generated_at"))
        XCTAssertEqual(try rows(json(reverse)).map { $0["trip_id"] as? String }, [firstID.uuidString.lowercased(), secondID.uuidString.lowercased()])
    }

    func testExcludedRowsStayExcludedAndManualOverrideIsNotRecomputedByWriter() throws {
        let a = trip(); try a.setManualDistanceMeters(3218.688, now: a.startedAt)
        let excluded = trip(secondID); excluded.excludeFromReports = true
        let value = try report([excluded, a])
        let projected = try rows(json(value))
        XCTAssertEqual(projected.count, 1)
        XCTAssertEqual(projected[0]["distance_miles"] as? String, "2")
        XCTAssertEqual(projected[0]["deduction_usd"] as? String, "1")
        XCTAssertEqual(a.distanceMeters, 1609.344)
        XCTAssertEqual(try csv(value).filter { $0["row_type"] == "trip" }.count, 1)
    }

    func testOdometerSerializationRejectsNonFiniteNegativeAndUnrepresentableValues() throws {
        for invalid in [Double.nan, .infinity, -.infinity, -1, .greatestFiniteMagnitude, .leastNonzeroMagnitude] {
            for atStart in [true, false] {
                let item = trip()
                if atStart { item.startOdometerMiles = invalid } else { item.endOdometerMiles = invalid }
                let value = try report([item])
                XCTAssertThrowsError(try MileageReportExporter.json(value, timeZone: .gmt))
                XCTAssertThrowsError(try MileageReportExporter.csv(value, timeZone: .gmt))
            }
        }
        let item = trip(); item.startOdometerMiles = 0; item.endOdometerMiles = 1.25
        let row = try XCTUnwrap(rows(json(report([item]))).first)
        XCTAssertEqual(row["start_odometer_miles"] as? String, "0")
        XCTAssertEqual(row["end_odometer_miles"] as? String, "1.25")
    }

    func testIndependentCSVDecoderRejectsMalformedRecordsAndAcceptsLiteralRFCVector() throws {
        XCTAssertEqual(try decodeCSV(Data("a,\"b,b\",\"c\"\"d\"\r\n".utf8)), [["a", "b,b", "c\"d"]])
        for text in ["a\n", "\"unterminated", "\"a\"junk\r\n", "a\"b\r\n", "a,b"] {
            XCTAssertThrowsError(try decodeCSV(Data(text.utf8)))
        }
    }

    func testJSONVersionOneHasExactStableRootMetadataSummaryAndRowKeys() throws {
        let document = try json(report([trip()]))
        XCTAssertEqual(Set(document.keys), ["format", "schema_version", "metadata", "summary", "rows"])
        let metadata = try XCTUnwrap(document["metadata"] as? [String: String])
        XCTAssertEqual(Set(metadata.keys), ["calendar", "time_zone", "disclaimer", "decimal_encoding"])
        let summary = try XCTUnwrap(document["summary"] as? [String: Any])
        XCTAssertEqual(Set(summary.keys), ["miles_by_classification", "reviewed_business_trips", "business_trips_missing_purpose",
                                           "business_trips_missing_rate", "known_estimated_deduction_usd", "is_estimate_complete", "totals_by_vehicle"])
        let row = try XCTUnwrap(rows(document).first)
        XCTAssertEqual(Set(row.keys), Set(headers.prefix(19)).union(["vehicle_id", "trip_time_zone", "rate_source", "rate_is_custom_override"]))
        XCTAssertEqual(row["start_time"] as? String, "2025-02-01T12:00:00.000Z")
        XCTAssertEqual(row["end_time"] as? String, "2025-02-01T12:01:00.000Z")
    }

    func testNonHourZoneAndMillisecondPrecisionUseCallerZone() throws {
        let item = trip()
        item.startedAt = item.startedAt.addingTimeInterval(0.125)
        item.endedAt = item.startedAt.addingTimeInterval(60)
        let zone = TimeZone(identifier: "Asia/Kathmandu")!
        let value = try report([item])
        let row = try XCTUnwrap(rows(json(value, zone: zone)).first)
        XCTAssertEqual(row["start_time"] as? String, "2025-02-01T17:45:00.125+05:45")
        XCTAssertEqual(row["end_time"] as? String, "2025-02-01T17:46:00.125+05:45")
        XCTAssertEqual(try csv(value, zone: zone).first { $0["row_type"] == "trip" }?["start_time"], "2025-02-01T17:45:00.125+05:45")
    }

    func testThirtyEightSignificantDigitDecimalIsNotClippedToBinaryPrecision() throws {
        let literal = "123456789012345678901234567890123456.78"
        let provider = try MileageRateProvider(customRate: Decimal(string: literal)!, customSource: "Synthetic 38-digit boundary")
        let value = try report([trip()], rates: provider)
        let decoded = try JSONDecoder().decode(PrecisionDocument.self, from: MileageReportExporter.json(value, timeZone: .gmt))
        XCTAssertEqual(decoded.rows[0].rate_per_mile_usd, literal)
        XCTAssertEqual(decoded.rows[0].deduction_usd, literal)
        XCTAssertEqual(decoded.summary.known_estimated_deduction_usd, literal)
        XCTAssertEqual(try csv(value).first { $0["row_type"] == "trip" }?["deduction_usd"], literal)
    }

    func testFutureStoredEnumsRemainConservativeButRawSourceIsPreserved() throws {
        let item = trip()
        item.classificationRaw = "futureSyntheticClassification"
        item.reviewStatusRaw = "futureSyntheticReview"
        item.sourceRaw = "futureSyntheticSource"
        let value = try report([item])
        let row = try XCTUnwrap(rows(json(value)).first)
        XCTAssertEqual(row["classification"] as? String, "unclassified")
        XCTAssertEqual(row["review_status"] as? String, "needsReview")
        XCTAssertEqual(row["source"] as? String, "futureSyntheticSource")
        XCTAssertTrue(row["rate_per_mile_usd"] is NSNull)
        XCTAssertTrue(row["deduction_usd"] is NSNull)
    }

    func testUnsupportedCSVControlsFailClosedWhileJSONPreservesText() throws {
        for control in ["\u{0000}", "\u{000B}", "\u{007F}", "\u{0085}"] {
            let item = trip(); item.notes = "Synthetic" + control + "=1"
            let value = try report([item])
            XCTAssertThrowsError(try MileageReportExporter.csv(value, timeZone: .gmt))
            XCTAssertEqual(try rows(json(value)).first?["notes"] as? String, "Synthetic" + control + "=1")
        }
    }

    func testHistoricalSecondOffsetFailsClosedInsteadOfEmittingNonRFC3339Timestamp() throws {
        let item = trip(start: "1971-06-01T00:00:00Z")
        let end = date("1971-06-01T00:01:00Z")
        item.endedAt = end
        let zone = try XCTUnwrap(TimeZone(identifier: "Africa/Monrovia"))
        // Independent TZDB fixture facts, not formatter output or a synthetic timezone.
        // If these fail on the admitted SDK, stop/review; never skip, substitute or weaken.
        XCTAssertEqual(zone.secondsFromGMT(for: item.startedAt), -2670)
        XCTAssertEqual(zone.secondsFromGMT(for: end), -2670)
        let value = try MileageReportService.build(
            trips: [MileageReportTrip(trip: item)],
            options: MileageReportOptions.taxYear(1971, timeZone: zone),
            rates: MileageRateProvider())
        XCTAssertEqual(value.rows.count, 1, "Actual closed historical Trip must reach the writer")
        XCTAssertThrowsError(try MileageReportExporter.csv(value, timeZone: zone)) { error in
            XCTAssertEqual(error as? MileageReportExporter.ExportError, .invalidDate(item.id))
        }
        XCTAssertThrowsError(try MileageReportExporter.json(value, timeZone: zone)) { error in
            XCTAssertEqual(error as? MileageReportExporter.ExportError, .invalidDate(item.id))
        }
    }

    /// Decode actual wire text without the JSONSerialization helper's observed FEFF loss.
    /// Only schema keys live here; no expected fixture values or normalization.
    private struct FormulaDocument: Decodable {
        let rows: [Row]
        struct Row: Decodable {
            let origin_name: String
            let origin_address: String
            let destination_name: String
            let destination_address: String
            let business_purpose: String
            let vehicle: String
            let notes: String
            let source: String
            let rate_source: String

            var textFields: [String: Any] {
                ["origin_name": origin_name, "origin_address": origin_address,
                 "destination_name": destination_name, "destination_address": destination_address,
                 "business_purpose": business_purpose, "vehicle": vehicle, "notes": notes,
                 "source": source, "rate_source": rate_source]
            }
        }
    }

    private struct PrecisionDocument: Decodable {
        let schema_version: Int
        let rows: [Row]
        let summary: Summary
        struct Row: Decodable { let rate_per_mile_usd: String; let deduction_usd: String }
        struct Summary: Decodable { let known_estimated_deduction_usd: String }
    }

    private enum CSVError: Error { case malformed }
    /// Independent reader: accepts both bare and quoted cells; strictly requires CRLF record
    /// endings, but preserves arbitrary CR/LF inside quotes. Does not call any writer helpers.
    private func decodeCSV(_ data: Data) throws -> [[String]] {
        enum State: Equatable { case start, bare, quoted, afterQuote }
        let bytes = Array(data)
        var state = State.start, index = 0
        var field: [UInt8] = [], record: [String] = [], records: [[String]] = []
        func finishField() throws {
            guard let value = String(bytes: field, encoding: .utf8) else { throw CSVError.malformed }
            record.append(value); field.removeAll(keepingCapacity: true); state = .start
        }
        while index < bytes.count {
            let byte = bytes[index]
            if state == .quoted {
                if byte == 34 { state = .afterQuote } else { field.append(byte) }
            } else if state == .afterQuote && byte == 34 {
                field.append(34); state = .quoted
            } else if byte == 44 {
                try finishField()
            } else if byte == 13 {
                guard index + 1 < bytes.count, bytes[index + 1] == 10 else { throw CSVError.malformed }
                try finishField(); records.append(record); record.removeAll(keepingCapacity: true); index += 1
            } else if byte == 10 || state == .afterQuote {
                throw CSVError.malformed
            } else if byte == 34 {
                guard state == .start else { throw CSVError.malformed }; state = .quoted
            } else {
                field.append(byte); state = .bare
            }
            index += 1
        }
        guard state == .start, field.isEmpty, record.isEmpty else { throw CSVError.malformed }
        return records
    }
}
