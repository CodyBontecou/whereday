import Testing
import SwiftData
import CoreLocation
@testable import IsoMe

@Suite(.serialized) @MainActor
struct SplitVisitTests {
    private let arrival = Date(timeIntervalSince1970: 1_700_000_000)
    private var departure: Date { arrival.addingTimeInterval(50 * 60) }
    private var splitAt: Date { arrival.addingTimeInterval(25 * 60) }
    // Two storefronts about 40 m apart.
    private let traderJoes = CLLocationCoordinate2D(latitude: 37.7750, longitude: -122.4194)
    private let marshalls = CLLocationCoordinate2D(latitude: 37.7753, longitude: -122.4196)

    private func makeContext() throws -> ModelContext {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let container = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, isStoredInMemoryOnly: true))
        return ModelContext(container)
    }

    /// A manager with its own defaults suite that never replaces `LocationManager.shared`, as in IsolatedDefaults.swift.
    private func isolatedManager(context: ModelContext) -> LocationManager {
        let suiteName = "IsoMeTests.SplitVisit.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        let manager = LocationManager(defaults: defaults, registersAsShared: false)
        manager.setModelContext(context)
        return manager
    }

    private func detectedVisit(in context: ModelContext, departedAt: Date? = nil) throws -> Visit {
        let visit = Visit(
            latitude: traderJoes.latitude,
            longitude: traderJoes.longitude,
            arrivedAt: arrival,
            departedAt: departedAt ?? departure,
            locationName: "Trader Joe's",
            address: "10 Main St",
            notes: "Groceries",
            geocodingCompleted: true
        )
        visit.countryCode = "US"
        visit.countryDepartureIsInferred = true
        context.insert(visit)
        try context.save()
        return visit
    }

    private func split(_ visit: Visit, store: ManualVisitStore, at date: Date? = nil, name: String = "Marshalls") -> Visit? {
        store.splitVisit(
            visit,
            at: date ?? splitAt,
            secondPlaceName: name,
            address: "12 Main St",
            coordinate: marshalls,
            placeSource: .appleMaps,
            distanceMeters: 40
        )
    }

    @Test func splitProducesTwoBackToBackVisits() throws {
        let context = try makeContext()
        let visit = try detectedVisit(in: context)
        let store = ManualVisitStore(context: context)

        let second = try #require(split(visit, store: store))

        #expect(store.visitMutationError == nil)
        #expect(visit.arrivedAt == arrival)
        #expect(visit.departedAt == splitAt)
        #expect(visit.displayName == "Trader Joe's")
        #expect(visit.notes == "Groceries")
        #expect(visit.confirmationStatus == .confirmed)
        #expect(visit.countryDepartureIsInferred == false)

        #expect(second.id != visit.id)
        #expect(second.arrivedAt == splitAt)
        #expect(second.departedAt == departure)
        #expect(second.displayName == "Marshalls")
        #expect(second.address == "12 Main St")
        #expect(second.latitude == marshalls.latitude)
        #expect(second.longitude == marshalls.longitude)
        #expect(second.source == .automatic)
        #expect(second.confirmationStatus == .corrected)
        #expect(second.placeSource == .appleMaps)
        #expect(second.placeDistanceMeters == 40)
        #expect(second.notes == nil)
        #expect(second.countryCode == "US")
        // The original departure's provenance moves to the part that now owns it.
        #expect(second.countryDepartureIsInferred == true)
        // Undo Correction on the second part returns it to what detection saw.
        #expect(second.originalLatitude == traderJoes.latitude)
        #expect(second.originalLocationName == "Trader Joe's")
        #expect(second.detectedLocationName == "Trader Joe's")

        let stored = try context.fetch(FetchDescriptor<Visit>(sortBy: [SortDescriptor(\.arrivedAt)]))
        #expect(stored.map(\.displayName) == ["Trader Joe's", "Marshalls"])
        // Together the parts still cover the original stop exactly.
        #expect(stored.first?.departedAt == stored.last?.arrivedAt)
    }

    @Test func reconcileDoesNotMergeSplitVisitsBackTogether() throws {
        let context = try makeContext()
        let visit = try detectedVisit(in: context)
        try #require(split(visit, store: ManualVisitStore(context: context)) != nil)

        let manager = isolatedManager(context: context)
        _ = manager.reconcileOpenVisits(referenceDate: departure.addingTimeInterval(3600))

        let stored = try context.fetch(FetchDescriptor<Visit>(sortBy: [SortDescriptor(\.arrivedAt)]))
        #expect(stored.count == 2)
        #expect(stored.map(\.displayName) == ["Trader Joe's", "Marshalls"])
        #expect(stored.first?.departedAt == splitAt)
    }

    @Test func reconcileStillMergesUnreviewedJitterDuplicates() throws {
        let context = try makeContext()
        context.insert(Visit(latitude: traderJoes.latitude, longitude: traderJoes.longitude,
                             arrivedAt: arrival, departedAt: splitAt))
        context.insert(Visit(latitude: marshalls.latitude, longitude: marshalls.longitude,
                             arrivedAt: splitAt.addingTimeInterval(5 * 60), departedAt: departure))
        try context.save()

        let manager = isolatedManager(context: context)
        _ = manager.reconcileOpenVisits(referenceDate: departure.addingTimeInterval(3600))

        let stored = try context.fetch(FetchDescriptor<Visit>())
        #expect(stored.count == 1)
        #expect(stored.first?.arrivedAt == arrival)
        #expect(stored.first?.departedAt == departure)
    }

    @Test func distinctReviewedPlacesRequireBothReviewedAndDifferentNames() {
        let first = Visit(latitude: 0, longitude: 0, arrivedAt: arrival, locationName: "Trader Joe's")
        let second = Visit(latitude: 0, longitude: 0, arrivedAt: arrival, locationName: "Marshalls")
        #expect(!LocationManager.areDistinctReviewedPlaces(first, second))
        first.confirmationStatus = .confirmed
        #expect(!LocationManager.areDistinctReviewedPlaces(first, second))
        second.confirmationStatus = .corrected
        #expect(LocationManager.areDistinctReviewedPlaces(first, second))
        second.locationName = "trader joe's"
        #expect(!LocationManager.areDistinctReviewedPlaces(first, second))
    }

    @Test func splitRejectsTimesOutsideTheVisit() throws {
        let context = try makeContext()
        let visit = try detectedVisit(in: context)
        let store = ManualVisitStore(context: context)

        for date in [arrival, arrival.addingTimeInterval(30), departure, departure.addingTimeInterval(-30),
                     arrival.addingTimeInterval(-600), departure.addingTimeInterval(600)] {
            #expect(split(visit, store: store, at: date) == nil)
            if case .invalidSplit = store.visitMutationError {} else {
                Issue.record("Expected invalidSplit for \(date), got \(String(describing: store.visitMutationError))")
            }
        }
        // The one-minute boundaries are allowed.
        #expect(ManualVisitStore.splitRange(for: visit) == arrival.addingTimeInterval(60)...departure.addingTimeInterval(-60))
        #expect(visit.departedAt == departure)
        #expect(try context.fetchCount(FetchDescriptor<Visit>()) == 1)
    }

    @Test func splitRejectsOpenAndTooShortVisits() throws {
        let context = try makeContext()
        let store = ManualVisitStore(context: context)
        let open = Visit(latitude: traderJoes.latitude, longitude: traderJoes.longitude, arrivedAt: arrival)
        let short = Visit(latitude: traderJoes.latitude, longitude: traderJoes.longitude,
                          arrivedAt: arrival, departedAt: arrival.addingTimeInterval(90))
        context.insert(open)
        context.insert(short)
        try context.save()

        #expect(!ManualVisitStore.canSplit(open))
        #expect(!ManualVisitStore.canSplit(short))
        #expect(ManualVisitStore.splitRange(for: open) == nil)
        #expect(split(open, store: store, at: arrival.addingTimeInterval(120)) == nil)
        #expect(split(short, store: store, at: arrival.addingTimeInterval(45)) == nil)
        #expect(open.departedAt == nil)
        #expect(try context.fetchCount(FetchDescriptor<Visit>()) == 2)
    }

    @Test func splitRejectsTheSamePlaceAndBlankNames() throws {
        let context = try makeContext()
        let visit = try detectedVisit(in: context)
        let store = ManualVisitStore(context: context)

        #expect(split(visit, store: store, name: "  trader joe's ") == nil)
        if case .sameSplitPlace = store.visitMutationError {} else { Issue.record("Expected sameSplitPlace") }

        #expect(split(visit, store: store, name: "   ") == nil)
        if case .invalidInput = store.visitMutationError {} else { Issue.record("Expected invalidInput") }

        #expect(visit.departedAt == departure)
        #expect(visit.confirmationStatus == .unconfirmed)
        #expect(try context.fetchCount(FetchDescriptor<Visit>()) == 1)
    }

    @Test func splittingAManualVisitKeepsBothPartsManual() throws {
        let context = try makeContext()
        let visit = Visit(latitude: traderJoes.latitude, longitude: traderJoes.longitude,
                          arrivedAt: arrival, departedAt: departure, locationName: "Trader Joe's",
                          geocodingCompleted: true, source: .manual, confirmationStatus: .confirmed)
        context.insert(visit)
        try context.save()

        let second = try #require(split(visit, store: ManualVisitStore(context: context)))
        #expect(second.source == .manual)
        #expect(second.detectedLatitude == nil)
        #expect(visit.confirmationStatus == .confirmed)
        #expect(visit.departedAt == second.arrivedAt)
    }

    @Test func splitPersistsAcrossContexts() throws {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let container = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, isStoredInMemoryOnly: true))
        let writer = ModelContext(container)
        let visit = try detectedVisit(in: writer)
        try #require(split(visit, store: ManualVisitStore(context: writer)) != nil)

        let stored = try ModelContext(container).fetch(FetchDescriptor<Visit>(sortBy: [SortDescriptor(\.arrivedAt)]))
        #expect(stored.map(\.displayName) == ["Trader Joe's", "Marshalls"])
        #expect(stored.map(\.departedAt) == [splitAt, departure])
    }
}
