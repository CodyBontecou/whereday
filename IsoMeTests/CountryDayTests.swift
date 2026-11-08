import XCTest
import SwiftData
import SwiftUI
import UIKit
import CoreLocation
@testable import IsoMe

@MainActor
final class CountryDayTests: XCTestCase {
    private var testContainer: ModelContainer?
    private let now = ISO8601DateFormatter().date(from: "2026-10-12T12:00:00Z")!
    private func instant(_ value: String) -> Date { ISO8601DateFormatter().date(from: value)! }
    private func point(_ code: String, _ timestamp: String, zone: String = "Europe/Lisbon") -> LocationPoint {
        let point = LocationPoint(latitude: 38.72, longitude: -9.14, timestamp: instant(timestamp), horizontalAccuracy: 5)
        point.countryCode = code
        point.countryTimeZoneIdentifier = zone
        return point
    }
    private func visit(_ start: String, _ end: String?, zone: String = "Europe/Lisbon") -> Visit {
        let visit = Visit(latitude: 38.72, longitude: -9.14, arrivedAt: instant(start), departedAt: end.map(instant))
        visit.countryCode = "PT"
        visit.countryTimeZoneIdentifier = zone
        return visit
    }

    func testDuplicatePointsVisitsAndOverlappingManualStaysCountOnce() {
        let summaries = CountryDayCalculator.summaries(
            visits: [visit("2026-10-01T12:00:00Z", "2026-10-03T10:00:00Z")],
            points: [point("PT", "2026-10-01T14:00:00Z"), point("PT", "2026-10-01T14:10:00Z")],
            stays: [CountryStay(countryCode: "PT", firstDay: "2026-10-02", lastDay: "2026-10-04"),
                    CountryStay(countryCode: "PT", firstDay: "2026-10-03", lastDay: "2026-10-04")], now: now)
        XCTAssertEqual(summaries.count, 1)
        XCTAssertEqual(summaries.first?.days.count, 4)
        XCTAssertEqual(summaries.first?.days.first(where: { $0.day == "2026-10-02" }),
                       CountryPresence(countryCode: "PT", day: "2026-10-02", recorded: true, manual: true))
    }

    func testTravelDayCountsInBothCountries() {
        let summaries = CountryDayCalculator.summaries(visits: [], points: [
            point("PT", "2026-10-01T08:00:00Z"),
            point("ES", "2026-10-01T18:00:00Z", zone: "Europe/Madrid")
        ], stays: [], now: now)
        XCTAssertEqual(Set(summaries.map(\.countryCode)), ["PT", "ES"])
        XCTAssertTrue(summaries.allSatisfy { $0.days.count == 1 })
    }

    func testCountryLocalDateDoesNotUseUTCOrDeviceZone() {
        let summaries = CountryDayCalculator.summaries(visits: [], points: [
            point("JP", "2026-10-01T16:00:00Z", zone: "Asia/Tokyo"),
            point("US", "2026-10-01T02:00:00Z", zone: "America/Los_Angeles")
        ], stays: [], now: now)
        XCTAssertEqual(summaries.first(where: { $0.countryCode == "JP" })?.days.first?.day, "2026-10-02")
        XCTAssertEqual(summaries.first(where: { $0.countryCode == "US" })?.days.first?.day, "2026-09-30")
    }

    func testAllTimeIncludesAValidLocalDateAheadOfTheDeviceDate() {
        let reference = instant("2026-10-01T21:00:00Z")
        let bounds = CountryDayPeriod.all.bounds(now: reference)
        let summaries = CountryDayCalculator.summaries(visits: [],
            points: [point("JP", "2026-10-01T21:00:00Z", zone: "Asia/Tokyo")], stays: [],
            firstDay: bounds.first, lastDay: bounds.last, now: reference)
        XCTAssertEqual(summaries.first?.days.first?.day, "2026-10-02")
    }

    func testMidnightDepartureDoesNotAddTheNextDay() {
        let days = CountryDayCalculator.recordedDays(start: instant("2026-09-30T23:00:00Z"),
                                                    end: instant("2026-10-02T23:00:00Z"),
                                                    timeZoneIdentifier: "Europe/Lisbon", now: now)
        XCTAssertEqual(days, ["2026-10-01", "2026-10-02"])
    }

    func testUnclosedVisitDoesNotInventMonthsOfPresence() {
        let summaries = CountryDayCalculator.summaries(visits: [visit("2026-01-01T12:00:00Z", nil)],
                                                       points: [], stays: [], now: now)
        XCTAssertEqual(summaries.first?.days.map(\.day), ["2026-01-01"])
    }

    func testInferredOrLegacyDepartureDoesNotFillMissingDays() {
        let repaired = visit("2026-01-01T12:00:00Z", "2026-10-01T12:00:00Z")
        repaired.countryDepartureIsInferred = true
        let legacy = visit("2026-02-01T12:00:00Z", "2026-10-01T12:00:00Z")
        legacy.countryDepartureIsInferred = nil
        let summaries = CountryDayCalculator.summaries(visits: [repaired, legacy], points: [], stays: [], now: now)
        XCTAssertEqual(summaries.first?.days.map(\.day), ["2026-02-01", "2026-01-01"])
    }

    func testRepairingOpenVisitsMarksDepartureAsInferred() throws {
        let context = try makeContext()
        let earlier = visit("2026-01-01T12:00:00Z", nil)
        context.insert(earlier)
        context.insert(visit("2026-10-01T12:00:00Z", nil))
        try context.save()
        let manager = makeIsolatedLocationManager()
        manager.setModelContext(context)
        _ = manager.reconcileOpenVisits(referenceDate: now)
        XCTAssertEqual(earlier.countryDepartureIsInferred, true)
        let summaries = CountryDayCalculator.summaries(visits: [earlier], points: [], stays: [], now: now)
        XCTAssertEqual(summaries.first?.days.map(\.day), ["2026-01-01"])
    }

    func testDSTVisitCountsCivilDaysRatherThan24HourBuckets() {
        let days = CountryDayCalculator.recordedDays(start: instant("2026-03-28T12:00:00Z"),
                                                    end: instant("2026-03-30T12:00:00Z"),
                                                    timeZoneIdentifier: "Europe/Lisbon", now: now)
        XCTAssertEqual(days, ["2026-03-28", "2026-03-29", "2026-03-30"])
    }

    func testOutliersUnknownZonesAndFutureFixesAreExcluded() {
        let outlier = point("ES", "2026-10-01T12:00:00Z")
        outlier.isOutlier = true
        let missingZone = point("PT", "2026-10-01T12:00:00Z")
        missingZone.countryTimeZoneIdentifier = nil
        let invalidCountry = point("ZZ", "2026-10-01T12:00:00Z")
        let future = point("JP", "2027-10-01T12:00:00Z")
        XCTAssertTrue(CountryDayCalculator.summaries(visits: [], points: [outlier, missingZone, invalidCountry, future], stays: [], now: now).isEmpty)
    }

    func testDateRangeClipsStaysAndRejectsInvalidRanges() {
        let summaries = CountryDayCalculator.summaries(visits: [], points: [], stays: [
            CountryStay(countryCode: "PT", firstDay: "2025-12-30", lastDay: "2026-01-03")
        ], firstDay: "2026-01-01", lastDay: "2026-01-02", now: now)
        XCTAssertEqual(summaries.first?.days.map(\.day), ["2026-01-02", "2026-01-01"])
        XCTAssertTrue(CountryDayCalculator.inclusiveDays(from: "2026-10-03", through: "2026-10-01").isEmpty)
        XCTAssertNil(CountryDayCalculator.date(for: "2026-02-30"))
        XCTAssertEqual(CountryDayCalculator.inclusiveDays(from: "2024-02-28", through: "2024-03-01").count, 3)
    }

    func testCSVContainsOneStableRowPerCountryDayAndSource() {
        let summaries = CountryDayCalculator.summaries(visits: [], points: [point("PT", "2026-10-01T12:00:00Z")],
                                                       stays: [CountryStay(countryCode: "PT", firstDay: "2026-10-01", lastDay: "2026-10-02")], now: now)
        XCTAssertEqual(CountryDayCalculator.csv(summaries), "country_code,local_date,source\r\nPT,2026-10-01,recorded+manual\r\nPT,2026-10-02,manual\r\n")
    }

    func testCountryMetadataAndManualStayPersistAndClearWithHistory() throws {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let container = try ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        context.insert(point("PT", "2026-10-01T12:00:00Z"))
        context.insert(CountryStay(countryCode: "ES", firstDay: "2026-10-02", lastDay: "2026-10-03"))
        try context.save()
        let read = ModelContext(container)
        XCTAssertEqual(try read.fetch(FetchDescriptor<LocationPoint>()).first?.countryCode, "PT")
        XCTAssertEqual(try read.fetch(FetchDescriptor<CountryStay>()).count, 1)
        let manager = makeIsolatedLocationManager()
        let viewModel = LocationViewModel(modelContext: context, locationManager: manager)
        viewModel.clearAllData()
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<CountryStay>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<LocationPoint>()), 0)
    }

    private func makeContext() throws -> ModelContext {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let container = try ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        testContainer = container
        return container.mainContext
    }

    func testHistoryResolverPersistsCountriesForVisitsAndPoints() async throws {
        let context = try makeContext()
        let storedVisit = visit("2026-10-01T12:00:00Z", nil)
        storedVisit.countryCode = nil
        let storedPoint = point("PT", "2026-10-02T12:00:00Z")
        storedPoint.countryCode = nil
        context.insert(storedVisit)
        context.insert(storedPoint)
        try context.save()
        let resolver = CountryDayResolver { _ in
            GeocodingResult(name: nil, address: nil, countryCode: "PT", timeZoneIdentifier: "Europe/Lisbon")
        }
        await resolver.resolveHistory(in: context)
        XCTAssertEqual(resolver.resolved, 2, resolver.message ?? "no message")
        XCTAssertEqual(storedVisit.countryCode, "PT")
        XCTAssertEqual(storedPoint.countryTimeZoneIdentifier, "Europe/Lisbon")
        XCTAssertFalse(resolver.isRunning)
    }

    func testHistoryResolverHonorsNetworkOptOut() async throws {
        let original = UserDefaults.standard.object(forKey: "allowNetworkGeocoding")
        defer { UserDefaults.standard.set(original, forKey: "allowNetworkGeocoding") }
        UserDefaults.standard.set(false, forKey: "allowNetworkGeocoding")
        var calls = 0
        let resolver = CountryDayResolver { _ in
            calls += 1
            return GeocodingResult(name: nil, address: nil)
        }
        await resolver.resolveHistory(in: try makeContext())
        XCTAssertEqual(calls, 0)
        XCTAssertNotNil(resolver.message)
        XCTAssertFalse(resolver.isRunning)
    }

    func testUnavailableLookupDoesNotInventCountryAndUsesRetryCooldown() async throws {
        let context = try makeContext()
        let storedPoint = point("PT", "2026-10-02T12:00:00Z")
        storedPoint.countryCode = nil
        context.insert(storedPoint)
        try context.save()
        var calls = 0
        let resolver = CountryDayResolver { _ in
            calls += 1
            return GeocodingResult(name: nil, address: nil, countryCode: "PT", timeZoneIdentifier: nil)
        }
        await resolver.resolveHistory(in: context)
        await resolver.resolveHistory(in: context)
        XCTAssertEqual(calls, 1, resolver.message ?? "no message")
        XCTAssertNil(storedPoint.countryCode)
        XCTAssertNotNil(storedPoint.countryResolutionAttemptedAt)
    }

    func testNetworkFailureStopsHistoryWithoutChangingCountry() async throws {
        let context = try makeContext()
        let storedPoint = point("PT", "2026-10-02T12:00:00Z")
        storedPoint.countryCode = nil
        context.insert(storedPoint)
        try context.save()
        let resolver = CountryDayResolver { _ in throw URLError(.notConnectedToInternet) }
        await resolver.resolveHistory(in: context)
        XCTAssertNil(storedPoint.countryCode)
        XCTAssertFalse(resolver.isRunning)
        XCTAssertNotNil(resolver.message)
    }

    func testCancellationDoesNotCommitAnInFlightLookup() async throws {
        let context = try makeContext()
        let storedPoint = point("PT", "2026-10-02T12:00:00Z")
        storedPoint.countryCode = nil
        context.insert(storedPoint)
        try context.save()
        var resolver: CountryDayResolver!
        resolver = CountryDayResolver { _ in
            resolver.cancel()
            return GeocodingResult(name: nil, address: nil, countryCode: "PT", timeZoneIdentifier: "Europe/Lisbon")
        }
        await resolver.resolveHistory(in: context)
        XCTAssertNil(storedPoint.countryCode)
        XCTAssertEqual(resolver.completed, 0)
        XCTAssertFalse(resolver.isRunning)
    }

    func testAppleNetworkErrorExplainsRetryAndPreservesCompletedResults() async throws {
        let context = try makeContext()
        let first = point("PT", "2026-10-02T12:00:00Z")
        let second = point("PT", "2026-10-03T12:00:00Z")
        first.countryCode = nil
        second.countryCode = nil
        second.latitude += 1
        context.insert(first)
        context.insert(second)
        try context.save()
        var calls = 0
        let resolver = CountryDayResolver { _ in
            calls += 1
            if calls == 2 { throw CLError(.network) }
            return GeocodingResult(name: nil, address: nil, countryCode: "PT", timeZoneIdentifier: "Europe/Lisbon")
        }
        await resolver.resolveHistory(in: context)
        XCTAssertEqual(first.countryCode, "PT")
        XCTAssertNil(second.countryCode)
        XCTAssertEqual(resolver.completed, 1)
        XCTAssertEqual(resolver.message, "Country lookup is temporarily unavailable. Completed results are saved. Check your connection and try again in a minute.")
    }

    func testCancellingPacedLookupPreservesProgressAndResumeUsesCache() async throws {
        let context = try makeContext()
        let first = point("PT", "2026-10-02T12:00:00Z")
        let sameLocation = point("PT", "2026-10-03T12:00:00Z")
        let differentLocation = point("PT", "2026-10-04T12:00:00Z")
        for item in [first, sameLocation, differentLocation] {
            item.countryCode = nil
            context.insert(item)
        }
        differentLocation.latitude += 1
        try context.save()
        var calls = 0
        let resolver = CountryDayResolver(minimumLookupInterval: 0.3) { _ in
            calls += 1
            return GeocodingResult(name: nil, address: nil, countryCode: "PT", timeZoneIdentifier: "Europe/Lisbon")
        }
        let analysis = Task { await resolver.resolveHistory(in: context) }
        // The third location waits, while both repeated coordinates resolve from one request.
        for _ in 0..<100 where resolver.completed < 2 {
            try await Task.sleep(for: .milliseconds(1))
        }
        XCTAssertEqual(resolver.completed, 2)
        resolver.cancel()
        await analysis.value
        XCTAssertEqual(calls, 1)
        XCTAssertNil(differentLocation.countryCode)
        XCTAssertTrue(resolver.message?.contains("Analysis stopped") == true)
        await resolver.resolveHistory(in: context)
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(differentLocation.countryCode, "PT")
    }

    func testDistinctLookupsRespectPacing() async throws {
        let context = try makeContext()
        for (index, date) in ["2026-10-02T12:00:00Z", "2026-10-03T12:00:00Z"].enumerated() {
            let item = point("PT", date)
            item.countryCode = nil
            item.latitude += Double(index)
            context.insert(item)
        }
        try context.save()
        var startedAt: [Date] = []
        let resolver = CountryDayResolver(minimumLookupInterval: 0.15) { _ in
            startedAt.append(Date())
            return GeocodingResult(name: nil, address: nil, countryCode: "PT", timeZoneIdentifier: "Europe/Lisbon")
        }
        await resolver.resolveHistory(in: context)
        XCTAssertEqual(startedAt.count, 2)
        XCTAssertGreaterThanOrEqual(startedAt[1].timeIntervalSince(startedAt[0]), 0.15)
    }

    func testAppearancePreferenceAndPaletteResolveBothThemes() {
        XCTAssertNil(AppAppearance.system.colorScheme)
        XCTAssertEqual(AppAppearance.dark.colorScheme, .dark)
        XCTAssertEqual(AppAppearance.light.colorScheme, .light)
        let light = UITraitCollection(userInterfaceStyle: .light)
        let dark = UITraitCollection(userInterfaceStyle: .dark)
        XCTAssertNotEqual(UIColor(TE.card).resolvedColor(with: light), UIColor(TE.card).resolvedColor(with: dark))
        XCTAssertNotEqual(UIColor(TE.textPrimary).resolvedColor(with: light), UIColor(TE.textPrimary).resolvedColor(with: dark))
    }
}

@MainActor
final class CountryStayEditorTests: XCTestCase {
    private enum SaveFailure: Error { case injected }

    private func container() throws -> ModelContainer {
        try ModelContainer(for: CountryStay.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    private var draft: CountryStayDraft {
        CountryStayDraft(countryCode: "JP", firstDay: "2026-11-01", lastDay: "2026-11-03", note: "  東京 trip\n ")
    }

    func testFailedInsertDoesNotLeakIntoLaterSaveOrDuplicateOnRetry() throws {
        let container = try container()
        let context = ModelContext(container)
        context.autosaveEnabled = false
        XCTAssertThrowsError(try draft.save(in: context, commit: { _ in throw SaveFailure.injected }))
        try context.save() // An unrelated later save must not commit the failed stay.
        XCTAssertTrue(try ModelContext(container).fetch(FetchDescriptor<CountryStay>()).isEmpty)
        try draft.save(in: context)
        let stored = try ModelContext(container).fetch(FetchDescriptor<CountryStay>())
        XCTAssertEqual(stored.count, 1)
        XCTAssertEqual(stored.first?.note, "東京 trip")
    }

    func testFailedUpdateRestoresEveryOriginalFieldBeforeAutosave() throws {
        let container = try container()
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let stay = CountryStay(countryCode: "PT", firstDay: "2026-10-01", lastDay: "2026-10-02", note: " original \n")
        let id = stay.id
        context.insert(stay)
        try context.save()
        XCTAssertThrowsError(try draft.save(in: context, updating: stay, commit: { _ in throw SaveFailure.injected }))
        XCTAssertEqual(stay.countryCode, "PT")
        XCTAssertEqual(stay.firstDay, "2026-10-01")
        XCTAssertEqual(stay.lastDay, "2026-10-02")
        XCTAssertEqual(stay.note, " original \n")
        try context.save()
        let stored = try XCTUnwrap(ModelContext(container).fetch(FetchDescriptor<CountryStay>()).first)
        XCTAssertEqual(stored.countryCode, "PT")
        XCTAssertEqual(stored.note, " original \n")
        try draft.save(in: context, updating: stay)
        let retried = try ModelContext(container).fetch(FetchDescriptor<CountryStay>())
        XCTAssertEqual(retried.count, 1)
        XCTAssertEqual(retried.first?.id, id)
        XCTAssertEqual(retried.first?.lastDay, "2026-11-03")
    }

    func testCountrySearchTrimsCodesNamesAndWhitespaceOnlyQueries() {
        let english = Locale(identifier: "en_US")
        XCTAssertEqual(CountrySelectionView.matchingCountries("  jp \n", locale: english).map(\.code), ["JP"])
        XCTAssertEqual(CountrySelectionView.matchingCountries("  Japan  ", locale: english).map(\.code), ["JP"])
        XCTAssertEqual(CountrySelectionView.matchingCountries(" \n\t", locale: english).count,
                       CountrySelectionView.matchingCountries("", locale: english).count)
        XCTAssertTrue(CountrySelectionView.matchingCountries("🍜zzzz", locale: english).isEmpty)
    }
    func testFailedSavePreservesUnrelatedPendingChanges() throws {
        let container = try container()
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let unrelated = CountryStay(countryCode: "ES", firstDay: "2026-09-01", lastDay: "2026-09-02")
        context.insert(unrelated)
        XCTAssertThrowsError(try draft.save(in: context, commit: { _ in throw SaveFailure.injected }))
        try context.save()
        let stored = try ModelContext(container).fetch(FetchDescriptor<CountryStay>())
        XCTAssertEqual(stored.map(\.id), [unrelated.id])
        XCTAssertEqual(stored.first?.countryCode, "ES")
    }

}
