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

    func testAcceptedHighRateFailsClosedWhenDeductionAggregateOverflows() throws {
        // Synthetic representable Decimal rate: not an IRS rate or a proposed product cap.
        let highRate = NSDecimalNumber.maximum.decimalValue
        let provider = try MileageRateProvider(customRate: highRate, customSource: "Synthetic numeric boundary")
        let start = date("2025-02-01T00:00:00Z")
        let trips = (0..<100).map { trip(start.addingTimeInterval(Double($0)), miles: 1) }
        let single = try report([trips[0]], rates: provider)
        XCTAssertEqual(single.rows[0].estimatedDeductionUSD, highRate)
        XCTAssertEqual(single.knownEstimatedDeductionUSD, highRate)
        XCTAssertTrue(single.isEstimateComplete)
        do {
            let result = try report(trips, rates: provider)
            XCTFail("Expected aggregate overflow to throw; returned \(result.knownEstimatedDeductionUSD), complete=\(result.isEstimateComplete)")
        } catch {
            XCTAssertEqual(error as? MileageReportService.CalculationError, .unrepresentableAggregate)
        }
    }

    func testExactDeductionAggregateCannotSilentlyDiscardOneCent() throws {
        let start = date("2025-02-01T00:00:00Z")
        let boundary = start.addingTimeInterval(100)
        let highRate = NSDecimalNumber.maximum.decimalValue
        let provider = try MileageRateProvider(rates: [
            MileageRate(startsAt: start, endsAt: boundary, usdPerMile: highRate, source: "Synthetic maximum rate"),
            MileageRate(startsAt: boundary, endsAt: boundary.addingTimeInterval(100),
                        usdPerMile: Decimal(string: "0.01")!, source: "Synthetic cent rate")
        ])
        let a = trip(start, miles: 1)
        let b = trip(boundary, miles: 1)
        XCTAssertEqual(try report([a], rates: provider).knownEstimatedDeductionUSD, highRate)
        XCTAssertEqual(try report([b], rates: provider).knownEstimatedDeductionUSD, Decimal(string: "0.01"))
        XCTAssertThrowsError(try report([a, b], rates: provider), "An exact money total must not discard the cent") { error in
            XCTAssertEqual(error as? MileageReportService.CalculationError, .unrepresentableAggregate)
        }
    }

    func testFractionalMileageAggregatesRemainUsableAtDecimalPrecision() throws {
        let vehicle = Vehicle(name: "Synthetic fractional-distance vehicle")
        let start = date("2025-02-01T00:00:00Z")
        let trips = (0..<100).map { index in
            let item = trip(start.addingTimeInterval(Double(index)), miles: 0, classification: .personal)
            item.distanceMeters = 100
            item.assignVehicle(vehicle)
            return item
        }
        let result = try report(trips, rates: MileageRateProvider())
        // 10 km in international miles. Repeating quotients may round at Decimal precision.
        let expectedMiles = 6.21371192237334
        let classificationMiles = try XCTUnwrap(result.milesByClassification[.personal])
        XCTAssertEqual(NSDecimalNumber(decimal: classificationMiles).doubleValue, expectedMiles, accuracy: 1e-12)
        let vehicleMiles = try XCTUnwrap(result.totalsByVehicle.first { $0.vehicleID == vehicle.id }?.miles)
        XCTAssertEqual(NSDecimalNumber(decimal: vehicleMiles).doubleValue, expectedMiles, accuracy: 1e-12)
        XCTAssertEqual(result.knownEstimatedDeductionUSD, 0)
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

    // BEGIN frozen catalog tests: independent published decimals and ISO instants.
    private func exactUSD(_ text: String) -> Decimal {
        Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))!
    }

    func testFrozenUSBusinessCatalogEveryPublishedUTCPeriodAndPrimaryProvenance() throws {
        let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt)
        let periods = [
            ("2022-01-01T00:00:00Z", "2022-07-01T00:00:00Z", "0.585", "https://www.irs.gov/newsroom/irs-issues-standard-mileage-rates-for-2022"),
            ("2022-07-01T00:00:00Z", "2023-01-01T00:00:00Z", "0.625", "https://www.irs.gov/irb/2022-26_IRB#ANN-2022-13"),
            ("2023-01-01T00:00:00Z", "2024-01-01T00:00:00Z", "0.655", "https://www.irs.gov/irb/2023-03_IRB#NOT-2023-3"),
            ("2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z", "0.67", "https://www.irs.gov/newsroom/irs-issues-standard-mileage-rates-for-2024-mileage-rate-increases-to-67-cents-a-mile-up-1-point-5-cents-from-2023"),
            ("2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "0.70", "https://www.irs.gov/newsroom/irs-increases-the-standard-mileage-rate-for-business-use-in-2025-key-rate-increases-3-cents-to-70-cents-per-mile"),
            ("2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z", "0.725", "https://www.irs.gov/newsroom/irs-sets-2026-business-standard-mileage-rate-at-725-cents-per-mile-up-25-cents"),
            ("2026-07-01T00:00:00Z", "2027-01-01T00:00:00Z", "0.76", "https://www.irs.gov/irb/2026-29_irb")
        ]
        for (index, period) in periods.enumerated() {
            let start = date(period.0), end = date(period.1)
            let expected = exactUSD(period.2)
            XCTAssertEqual(provider.rate(on: start)?.usdPerMile, expected)
            XCTAssertEqual(provider.rate(on: end.addingTimeInterval(-1))?.usdPerMile, expected)
            let previous: Decimal? = index == 0 ? nil : exactUSD(periods[index - 1].2)
            let next: Decimal? = index == periods.count - 1 ? nil : exactUSD(periods[index + 1].2)
            XCTAssertEqual(provider.rate(on: start.addingTimeInterval(-1))?.usdPerMile, previous)
            XCTAssertEqual(provider.rate(on: end)?.usdPerMile, next)
            let selection = try XCTUnwrap(provider.rate(on: start))
            XCTAssertFalse(selection.isCustomOverride)
            XCTAssertTrue(selection.source.contains(period.3))
            XCTAssertTrue(selection.source.contains("https://www.irs.gov/tax-professionals/standard-mileage-rates"))
            XCTAssertTrue(selection.source.contains(String(period.0.prefix(10))))
        }
        XCTAssertTrue(try XCTUnwrap(provider.rate(on: date("2026-07-01T00:00:00Z"))).source.contains("Announcement 2026-11"))
    }

    private func assertFrozenBoundaries(timeZone: TimeZone, utcInstants: [String],
                                        file: StaticString = #filePath, line: UInt = #line) throws {
        let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: timeZone)
        let rates: [String?] = ["0.585", "0.625", "0.655", "0.67", "0.70", "0.725", "0.76", nil]
        XCTAssertEqual(utcInstants.count, 8, file: file, line: line)
        for (index, instant) in utcInstants.enumerated() {
            let before = index == 0 ? nil : rates[index - 1].map(exactUSD)
            XCTAssertEqual(provider.rate(on: date(instant).addingTimeInterval(-1))?.usdPerMile,
                           before, "second before \(instant)", file: file, line: line)
            XCTAssertEqual(provider.rate(on: date(instant))?.usdPerMile,
                           rates[index].map(exactUSD), "exact midnight \(instant)", file: file, line: line)
        }
    }

    func testFrozenCatalogLosAngelesAllCivilMidnightsIncludingBothMidyearSplits() throws {
        try assertFrozenBoundaries(timeZone: TimeZone(identifier: "America/Los_Angeles")!, utcInstants: [
            "2022-01-01T08:00:00Z", "2022-07-01T07:00:00Z", "2023-01-01T08:00:00Z",
            "2024-01-01T08:00:00Z", "2025-01-01T08:00:00Z", "2026-01-01T08:00:00Z",
            "2026-07-01T07:00:00Z", "2027-01-01T08:00:00Z"
        ])
    }

    func testFrozenCatalogExtremeAndNonHourZonesUseExplicitCivilDates() throws {
        try assertFrozenBoundaries(timeZone: TimeZone(identifier: "Pacific/Kiritimati")!, utcInstants: [
            "2021-12-31T10:00:00Z", "2022-06-30T10:00:00Z", "2022-12-31T10:00:00Z",
            "2023-12-31T10:00:00Z", "2024-12-31T10:00:00Z", "2025-12-31T10:00:00Z",
            "2026-06-30T10:00:00Z", "2026-12-31T10:00:00Z"
        ])
        try assertFrozenBoundaries(timeZone: TimeZone(identifier: "Asia/Kathmandu")!, utcInstants: [
            "2021-12-31T18:15:00Z", "2022-06-30T18:15:00Z", "2022-12-31T18:15:00Z",
            "2023-12-31T18:15:00Z", "2024-12-31T18:15:00Z", "2025-12-31T18:15:00Z",
            "2026-06-30T18:15:00Z", "2026-12-31T18:15:00Z"
        ])
    }

    func testFrozenCatalogLeapDayMissingYearsAndEmptyDefaultRemainExplicit() throws {
        let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt)
        XCTAssertEqual(provider.rate(on: date("2024-02-29T12:00:00Z"))?.usdPerMile, exactUSD("0.67"))
        XCTAssertNil(provider.rate(on: date("2021-06-01T00:00:00Z")))
        XCTAssertNil(provider.rate(on: date("2027-06-01T00:00:00Z")))
        let emptyDefault = try MileageRateProvider()
        for year in 2021...2027 {
            XCTAssertNil(emptyDefault.rate(on: date("\(year)-06-01T00:00:00Z")))
        }
    }

    func testFrozenCatalogCustomOverrideZeroAndValidationMatchExistingInitializer() throws {
        let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(
            timeZone: .gmt, customRate: exactUSD("0.1234"), customSource: "Explicit synthetic override")
        for instant in ["2021-01-01T00:00:00Z", "2022-07-01T00:00:00Z", "2026-07-01T00:00:00Z", "2027-01-01T00:00:00Z"] {
            let selection = try XCTUnwrap(provider.rate(on: date(instant)))
            XCTAssertEqual(selection.usdPerMile, exactUSD("0.1234"))
            XCTAssertTrue(selection.isCustomOverride)
            XCTAssertEqual(selection.source, "Explicit synthetic override")
        }
        let zero = try MileageRateProvider.frozenUSBusiness2022Through2026(
            timeZone: .gmt, customRate: 0, customSource: "Explicit synthetic zero")
        XCTAssertEqual(zero.rate(on: date("2026-07-01T00:00:00Z"))?.usdPerMile, 0)
        XCTAssertTrue(zero.rate(on: date("2026-07-01T00:00:00Z"))?.isCustomOverride == true)
        XCTAssertThrowsError(try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt, customRate: 1))
        XCTAssertThrowsError(try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt, customRate: 1, customSource: " \n"))
        XCTAssertThrowsError(try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt, customRate: .nan, customSource: "Synthetic"))
        XCTAssertThrowsError(try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt, customRate: -1, customSource: "Synthetic"))
        // A source without an override remains unused, exactly as with init.
        let unusedSource = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt, customSource: " ")
        XCTAssertEqual(unusedSource.rate(on: date("2025-01-01T00:00:00Z"))?.usdPerMile, exactUSD("0.70"))
    }

    func testFrozenCatalogReportUsesWholeTripStartRateWithoutProrationAtBothSplits() throws {
        for example in [(2022, "1.17", "1.88", "3.05"), (2026, "1.45", "2.28", "3.73")] {
            let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt)
            let filter = try MileageReportOptions.taxYear(example.0, timeZone: .gmt)
            let before = trip(date("\(example.0)-06-30T23:59:59Z"), miles: 2)
            let after = trip(date("\(example.0)-07-01T00:00:00Z"), miles: 3)
            let result = try report([before, after], options: filter, rates: provider)
            XCTAssertEqual(result.rows.map(\.estimatedDeductionUSD), [exactUSD(example.1), exactUSD(example.2)])
            XCTAssertEqual(result.knownEstimatedDeductionUSD, exactUSD(example.3))
            XCTAssertTrue(result.isEstimateComplete)
        }
    }

    func testFrozenCatalogReportZoneIsExplicitNotTripStoredZone() throws {
        let zone = TimeZone(identifier: "America/Los_Angeles")!
        let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: zone)
        let item = trip(date("2026-07-01T06:59:59Z"))
        item.timezoneIdentifier = "Pacific/Kiritimati"
        let result = try report([item], options: MileageReportOptions.taxYear(2026, timeZone: zone), rates: provider)
        XCTAssertEqual(result.rows.first?.rate?.usdPerMile, exactUSD("0.725"))
        let utcProvider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt)
        XCTAssertEqual(utcProvider.rate(on: item.startedAt)?.usdPerMile, exactUSD("0.76"))
    }

    func testFrozenAndCustomSchedulesRejectMalformedDatesAndNonFiniteLookups() throws {
        let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt)
        let custom = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt, customRate: 1, customSource: "Synthetic")
        for interval in [Double.nan, .infinity, -.infinity] {
            let invalid = Date(timeIntervalSince1970: interval)
            XCTAssertNil(provider.rate(on: invalid))
            XCTAssertNil(custom.rate(on: invalid))
            XCTAssertThrowsError(try MileageRateProvider(rates: [MileageRate(
                startsAt: invalid, endsAt: date("2025-01-01T00:00:00Z"), usdPerMile: 1, source: "Synthetic")]))
            XCTAssertThrowsError(try MileageRateProvider(rates: [MileageRate(
                startsAt: date("2025-01-01T00:00:00Z"), endsAt: invalid, usdPerMile: 1, source: "Synthetic")]))
        }
        XCTAssertThrowsError(try MileageRateProvider(rates: [MileageRate(
            startsAt: date("2025-01-01T00:00:00Z"), endsAt: date("2026-01-01T00:00:00Z"),
            usdPerMile: .nan, source: "Synthetic")]))
    }
    func testFrozenCatalogMixedKnown2026AndMissing2027KeepsSubtotalIncomplete() throws {
        let provider = try MileageRateProvider.frozenUSBusiness2022Through2026(timeZone: .gmt)
        let range = try MileageReportOptions(startsAt: date("2026-01-01T00:00:00Z"),
                                            endsAt: date("2028-01-01T00:00:00Z"))
        let known = trip(date("2026-08-01T00:00:00Z"), miles: 10)
        let missing = trip(date("2027-08-01T00:00:00Z"), miles: 4)
        let result = try report([known, missing], options: range, rates: provider)
        XCTAssertEqual(result.rows.count, 2)
        XCTAssertEqual(result.rows.first?.rate?.usdPerMile, exactUSD("0.76"))
        XCTAssertEqual(result.rows.map(\.estimatedDeductionUSD), [exactUSD("7.60"), nil])
        XCTAssertEqual(result.knownEstimatedDeductionUSD, exactUSD("7.60"))
        XCTAssertEqual(result.businessTripsMissingRate, 1)
        XCTAssertFalse(result.isEstimateComplete)
    }
    // END frozen catalog tests.

    func testEmptyReportAndVerbatimDisclaimerMetadata() throws {
        let result = try report([])
        XCTAssertTrue(result.rows.isEmpty)
        XCTAssertTrue(result.isEstimateComplete)
        XCTAssertEqual(result.milesByClassification[.unclassified], 0)
        XCTAssertEqual(result.disclaimer, "Mileage estimates are generated from GPS data and may differ from your vehicle odometer. Classifications, rates, and deduction estimates are for recordkeeping only and are not tax, legal, or accounting advice. Review every trip and consult a qualified tax professional. Tax rules and mileage rates can change.")
    }
}
