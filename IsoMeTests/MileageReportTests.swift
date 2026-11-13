import XCTest
@testable import IsoMe

final class MileageReportTests: XCTestCase {
    private func date(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }
    private func trip(_ start: Date, miles: Double = 1, classification: TripClassification = .business,
                      reviewed: Bool = true) -> Trip {
        let trip = Trip(startedAt: start, timezoneIdentifier: "UTC", now: start)
        trip.endedAt = start.addingTimeInterval(60)
        trip.distanceMeters = miles * 1609.344
        trip.classify(as: classification, reviewed: reviewed, now: start)
        return trip
    }
    private func options() throws -> MileageReportOptions {
        try .taxYear(2025, timeZone: TimeZone(secondsFromGMT: 0)!)
    }
    private func rates() throws -> MileageRateProvider {
        // Deliberately synthetic numbers, NOT actual IRS rates.
        try MileageRateProvider(rates: [
            MileageRate(startsAt: date("2025-01-01T00:00:00Z"), endsAt: date("2025-07-01T00:00:00Z"),
                        usdPerMile: Decimal(string: "0.50")!, source: "Synthetic test schedule A"),
            MileageRate(startsAt: date("2025-07-01T00:00:00Z"), endsAt: date("2026-01-01T00:00:00Z"),
                        usdPerMile: Decimal(string: "0.75")!, source: "Synthetic test schedule B")
        ])
    }
    private func report(_ trips: [Trip], options: MileageReportOptions? = nil,
                        rates: MileageRateProvider? = nil) throws -> MileageReportService.Report {
        try MileageReportService.build(trips: trips.map { MileageReportTrip(trip: $0) },
                                       options: options ?? self.options(), rates: rates ?? self.rates())
    }

    func testRatePeriodsHalfOpenAndMissingYearsNeverGuessed() throws {
        let provider = try rates()
        XCTAssertNil(provider.rate(on: date("2024-12-31T23:59:59Z")))
        XCTAssertEqual(provider.rate(on: date("2025-06-30T23:59:59Z"))?.usdPerMile, Decimal(string: "0.5"))
        XCTAssertEqual(provider.rate(on: date("2025-07-01T00:00:00Z"))?.usdPerMile, Decimal(string: "0.75"))
        XCTAssertNil(provider.rate(on: date("2026-01-01T00:00:00Z")))
        XCTAssertNil(try MileageRateProvider().rate(on: Date()))
    }

    func testCustomOverrideRequiresProvenanceAndWinsOverSchedule() throws {
        let provider = try MileageRateProvider(rates: [
            MileageRate(startsAt: date("2020-01-01T00:00:00Z"), endsAt: date("2040-01-01T00:00:00Z"),
                        usdPerMile: 2, source: "Synthetic schedule overridden")
        ], customRate: 1, customSource: "Explicit synthetic user rate")
        let rate = try XCTUnwrap(provider.rate(on: date("2030-01-01T00:00:00Z")))
        XCTAssertEqual(rate.usdPerMile, 1)
        XCTAssertTrue(rate.isCustomOverride)
        XCTAssertEqual(rate.source, "Explicit synthetic user rate")
        XCTAssertThrowsError(try MileageRateProvider(customRate: 1))
        XCTAssertThrowsError(try MileageRateProvider(customRate: -1, customSource: "Synthetic"))
        XCTAssertThrowsError(try MileageRateProvider(customRate: .nan, customSource: "Synthetic"))
        XCTAssertNoThrow(try MileageRateProvider(customRate: 0, customSource: "Synthetic zero rate"))
    }

    func testOverlappingOrMalformedRatesRejected() throws {
        let start = date("2025-01-01T00:00:00Z")
        let end = date("2026-01-01T00:00:00Z")
        let rate = MileageRate(startsAt: start, endsAt: end, usdPerMile: 1, source: "Synthetic")
        XCTAssertThrowsError(try MileageRateProvider(rates: [rate, rate]))
        XCTAssertThrowsError(try MileageRateProvider(rates: [MileageRate(startsAt: end, endsAt: start, usdPerMile: 1, source: "Synthetic")]))
        XCTAssertThrowsError(try MileageRateProvider(rates: [MileageRate(startsAt: start, endsAt: end, usdPerMile: 1, source: " \n")]))
    }

    func testDateAwareDeductionMathAndClassificationTotals() throws {
        let businessA = trip(date("2025-02-01T00:00:00Z"), miles: 10)
        businessA.businessPurpose = "Synthetic delivery"
        let businessB = trip(date("2025-08-01T00:00:00Z"), miles: 20, reviewed: false)
        let personal = trip(date("2025-08-02T00:00:00Z"), miles: 3, classification: .personal)
        let unclassified = trip(date("2025-08-03T00:00:00Z"), miles: 4, classification: .unclassified)
        let result = try report([personal, businessB, unclassified, businessA])
        XCTAssertEqual(result.milesByClassification[.business], 30)
        XCTAssertEqual(result.milesByClassification[.personal], 3)
        XCTAssertEqual(result.milesByClassification[.unclassified], 4)
        XCTAssertEqual(result.knownEstimatedDeductionUSD, 20)
        XCTAssertEqual(result.reviewedBusinessTripCount, 1)
        XCTAssertEqual(result.businessTripsMissingPurpose, 1)
        XCTAssertTrue(result.isEstimateComplete)
        XCTAssertNil(result.rows[2].estimatedDeductionUSD)
        XCTAssertEqual(result.rows.first?.trip.id, businessA.id)
    }

    func testMissingRateIsFlaggedNotSilentlyZeroEstimate() throws {
        let result = try report([trip(date("2025-02-01T00:00:00Z"))], rates: MileageRateProvider())
        XCTAssertEqual(result.businessTripsMissingRate, 1)
        XCTAssertFalse(result.isEstimateComplete)
        XCTAssertNil(result.rows.first?.estimatedDeductionUSD)
        XCTAssertEqual(result.knownEstimatedDeductionUSD, 0)
    }

    func testDefaultFiltersIncludeUnclassifiedButExcludeOptedOutTrips() throws {
        let included = trip(date("2025-02-01T00:00:00Z"), classification: .unclassified, reviewed: false)
        let excluded = trip(date("2025-02-02T00:00:00Z"))
        excluded.excludeFromReports = true
        let result = try report([included, excluded])
        XCTAssertEqual(result.rows.count, 1)
        XCTAssertEqual(result.rows.first?.trip.id, included.id)
        XCTAssertEqual(result.knownEstimatedDeductionUSD, 0)
    }

    func testVehicleClassificationAndReviewedFiltersCompose() throws {
        let vehicle = Vehicle(name: "Synthetic Van")
        let a = trip(date("2025-02-01T00:00:00Z"))
        a.assignVehicle(vehicle)
        let b = trip(date("2025-02-02T00:00:00Z"), reviewed: false)
        b.assignVehicle(vehicle)
        let c = trip(date("2025-02-03T00:00:00Z"), classification: .personal)
        c.assignVehicle(vehicle)
        let d = trip(date("2025-02-04T00:00:00Z"))
        var filter = try options()
        filter.vehicleID = vehicle.id
        filter.classifications = [.business]
        filter.reviewedOnly = true
        let result = try report([a, b, c, d], options: filter)
        XCTAssertEqual(result.rows.map(\.trip.id), [a.id])
    }

    func testVehicleTotalsUseIDsAndHistoricalSnapshotsIncludingUnassigned() throws {
        let vehicle = Vehicle(name: "Synthetic Old Name")
        let a = trip(date("2025-02-01T00:00:00Z"), miles: 2)
        a.assignVehicle(vehicle)
        vehicle.name = "Synthetic New Name"
        let b = trip(date("2025-02-02T00:00:00Z"), miles: 3)
        b.assignVehicle(vehicle)
        let c = trip(date("2025-02-03T00:00:00Z"), miles: 4)
        vehicle.isArchived = true
        let result = try report([a, b, c])
        XCTAssertEqual(result.totalsByVehicle.count, 2)
        let total = try XCTUnwrap(result.totalsByVehicle.first { $0.vehicleID == vehicle.id })
        XCTAssertEqual(total.miles, 5)
        XCTAssertEqual(total.nameSnapshots, ["Synthetic New Name", "Synthetic Old Name"])
        XCTAssertEqual(result.totalsByVehicle.first { $0.vehicleID == nil }?.miles, 4)
    }

    func testManualOverrideUsedWithoutChangingGPS() throws {
        let a = trip(date("2025-02-01T00:00:00Z"), miles: 1)
        try a.setManualDistanceMeters(1609.344 * 2)
        let result = try report([a])
        XCTAssertEqual(result.rows.first?.miles, 2)
        XCTAssertEqual(result.knownEstimatedDeductionUSD, 1)
        XCTAssertEqual(a.distanceMeters, 1609.344)
    }

    func testRoundingPerRowMatchesKnownTotal() throws {
        let a = trip(date("2025-02-01T00:00:00Z"), miles: 1)
        let b = trip(date("2025-02-02T00:00:00Z"), miles: 1)
        let provider = try MileageRateProvider(customRate: Decimal(string: "0.005")!, customSource: "Synthetic rounding rate")
        let result = try report([a, b], rates: provider)
        XCTAssertEqual(result.rows.map(\.estimatedDeductionUSD), [Decimal(string: "0.01"), Decimal(string: "0.01")])
        XCTAssertEqual(result.knownEstimatedDeductionUSD, Decimal(string: "0.02"))
    }

    func testTaxYearUsesExplicitTimezoneAndHalfOpenStartDates() throws {
        let filter = try MileageReportOptions.taxYear(2025, timeZone: TimeZone(identifier: "America/Los_Angeles")!)
        XCTAssertEqual(filter.startsAt, date("2025-01-01T08:00:00Z"))
        XCTAssertEqual(filter.endsAt, date("2026-01-01T08:00:00Z"))
        let before = trip(filter.startsAt.addingTimeInterval(-1))
        let atStart = trip(filter.startsAt)
        let beforeEnd = trip(filter.endsAt.addingTimeInterval(-1)) // Ends next year; whole trip uses start.
        let atEnd = trip(filter.endsAt)
        XCTAssertEqual(try report([before, atStart, beforeEnd, atEnd], options: filter).rows.count, 2)
    }

    func testInvalidRangesRejected() {
        XCTAssertThrowsError(try MileageReportOptions(startsAt: Date(), endsAt: .distantPast))
        XCTAssertThrowsError(try MileageReportOptions.taxYear(0, timeZone: .gmt))
        XCTAssertThrowsError(try MileageReportOptions.taxYear(9999, timeZone: .gmt))
    }

    func testInvalidTripDuplicateAndActiveTripRejected() throws {
        let a = trip(date("2025-02-01T00:00:00Z"))
        XCTAssertThrowsError(try report([a, a]))
        a.endedAt = nil
        XCTAssertThrowsError(try report([a]))
        a.endedAt = a.startedAt.addingTimeInterval(-1)
        XCTAssertThrowsError(try report([a]))
        a.endedAt = a.startedAt
        for invalid in [-1, Double.nan, Double.infinity, Double.greatestFiniteMagnitude] {
            a.distanceMeters = invalid
            XCTAssertThrowsError(try report([a]))
        }
    }

    func testInvalidStartDateRejectedBeforeFilteringOrSorting() throws {
        let a = trip(date("2025-02-01T00:00:00Z"))
        a.startedAt = Date(timeIntervalSince1970: .nan)
        XCTAssertThrowsError(try report([a]))
        a.excludeFromReports = true
        XCTAssertTrue(try report([a]).rows.isEmpty)
    }

    func testEmptyReportAndVerbatimDisclaimerMetadata() throws {
        let result = try report([])
        XCTAssertTrue(result.rows.isEmpty)
        XCTAssertTrue(result.isEstimateComplete)
        XCTAssertEqual(result.milesByClassification[.unclassified], 0)
        XCTAssertEqual(result.disclaimer, "Mileage estimates are generated from GPS data and may differ from your vehicle odometer. Classifications, rates, and deduction estimates are for recordkeeping only and are not tax, legal, or accounting advice. Review every trip and consult a qualified tax professional. Tax rules and mileage rates can change.")
    }
}
