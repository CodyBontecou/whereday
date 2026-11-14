import AppIntents
import CoreLocation
import Foundation
import SwiftData

enum ManualVisitIntentError: Error, CustomLocalizedStringResourceConvertible {
    case invalidDestination, locationUnavailable, noOpenVisit, ambiguousVisit, closedVisit, invalidTime, invalidArrival, missingPlace
    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .invalidDestination: return "Choose one saved place or provide both latitude and longitude."
        case .locationUnavailable: return "A fresh accurate location is unavailable. Choose a saved place or provide coordinates."
        case .noOpenVisit: return "There is no open manual visit to check out."
        case .ambiguousVisit: return "More than one manual visit is open. Choose the visit to check out."
        case .closedVisit: return "That visit is already checked out or is not a manual visit."
        case .invalidArrival: return "Choose an arrival time at or before now."
        case .invalidTime: return "Choose a time between the visit's arrival and now."
        case .missingPlace: return "That saved place no longer exists. Choose another place."
        }
    }
}

struct SavedPlaceEntity: AppEntity {
    let id: UUID
    let name: String
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Saved Place"
    static var defaultQuery = SavedPlaceQuery()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
    init(place: SavedPlace) { id = place.id; name = place.name }
}

struct SavedPlaceQuery: EntityStringQuery {
    @MainActor
    func entities(for identifiers: [UUID]) async throws -> [SavedPlaceEntity] {
        try places().filter { identifiers.contains($0.id) }.map(SavedPlaceEntity.init)
    }
    @MainActor
    func entities(matching string: String) async throws -> [SavedPlaceEntity] {
        try places().filter { $0.name.localizedStandardContains(string) }.map(SavedPlaceEntity.init)
    }
    @MainActor
    func suggestedEntities() async throws -> [SavedPlaceEntity] { try places().map(SavedPlaceEntity.init) }
    @MainActor
    private func places() throws -> [SavedPlace] {
        try ManualVisitAutomation.makeContext().fetch(FetchDescriptor<SavedPlace>(sortBy: [SortDescriptor(\.name)]))
    }
}

struct OpenManualVisitQuery: EntityQuery {
    @MainActor
    func entities(for identifiers: [UUID]) async throws -> [VisitEntity] {
        // Resolve closed records too, so a stale shortcut can report a repeated checkout explicitly.
        try ManualVisitAutomation.makeContext().fetch(FetchDescriptor<Visit>())
            .filter { identifiers.contains($0.id) }.map(VisitEntity.init)
    }
    @MainActor
    func suggestedEntities() async throws -> [VisitEntity] {
        try ManualVisitAutomation.openVisits(context: ManualVisitAutomation.makeContext()).map(VisitEntity.init)
    }
}

/// Independent of UI caches, tracking, geocoding and permission prompts.
@MainActor
enum ManualVisitAutomation {
    // Reuse live model identities when the UI is running; cold background launches open the store directly.
    static weak var foregroundContext: ModelContext?
    static func makeContext() -> ModelContext { foregroundContext ?? IntentSupport.makeContext() }

    static func openVisits(context: ModelContext) throws -> [Visit] {
        try context.fetch(FetchDescriptor<Visit>(predicate: #Predicate { $0.departedAt == nil },
            sortBy: [SortDescriptor(\.arrivedAt, order: .reverse)])).filter { $0.source == .manual }
    }

    static func checkIn(context: ModelContext, name: String?, savedPlaceID: UUID?,
                        latitude: Double?, longitude: Double?, at date: Date = Date(),
                        allowOverlap: Bool = false,
                        currentLocation: () async throws -> CLLocationCoordinate2D) async throws -> Visit {
        guard date.timeIntervalSinceReferenceDate.isFinite, date <= Date() else { throw ManualVisitIntentError.invalidArrival }
        let coordinate: CLLocationCoordinate2D
        let visitName: String
        let address: String?
        if let savedPlaceID {
            guard latitude == nil, longitude == nil else { throw ManualVisitIntentError.invalidDestination }
            let places = try context.fetch(FetchDescriptor<SavedPlace>())
            guard let place = places.first(where: { $0.id == savedPlaceID }) else { throw ManualVisitIntentError.missingPlace }
            coordinate = place.coordinate
            visitName = name?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? place.name
            address = place.address
        } else {
            guard (latitude == nil) == (longitude == nil) else { throw ManualVisitIntentError.invalidDestination }
            if let latitude, let longitude {
                coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
            } else {
                coordinate = try await currentLocation()
            }
            visitName = name ?? ""
            address = nil
        }
        try Task.checkCancellation()
        let store = ManualVisitStore(context: context)
        guard let visit = store.createManualVisit(name: visitName, address: address, coordinate: coordinate,
            arrivedAt: date, departedAt: nil, allowOverlap: allowOverlap) else {
            throw store.visitMutationError ?? .persistence
        }
        return visit
    }

    static func checkOut(context: ModelContext, visitID: UUID?, at date: Date = Date()) throws -> Visit {
        let visit: Visit
        if let visitID {
            guard let stored = try context.fetch(FetchDescriptor<Visit>()).first(where: { $0.id == visitID }) else {
                throw IsoMeIntentError.itemNotFound
            }
            guard stored.source == .manual, stored.departedAt == nil else { throw ManualVisitIntentError.closedVisit }
            visit = stored
        } else {
            let open = try openVisits(context: context)
            guard !open.isEmpty else { throw ManualVisitIntentError.noOpenVisit }
            guard open.count == 1 else { throw ManualVisitIntentError.ambiguousVisit }
            visit = open[0]
        }
        guard date.timeIntervalSinceReferenceDate.isFinite, date >= visit.arrivedAt, date <= Date() else {
            throw ManualVisitIntentError.invalidTime
        }
        let store = ManualVisitStore(context: context)
        guard store.checkoutVisit(visit, at: date) else { throw store.visitMutationError ?? .persistence }
        return visit
    }
}

/// One bounded request. Never starts tracking or asks for permission from a background shortcut.
@MainActor
final class ManualIntentLocation: NSObject, @preconcurrency CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLLocationCoordinate2D, Error>?
    private var timeout: Task<Void, Never>?

    func coordinate() async throws -> CLLocationCoordinate2D {
        guard manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse else {
            throw IsoMeIntentError.noLocationPermission
        }
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                timeout = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(12)) } catch { return }
                    self?.finish(.failure(ManualVisitIntentError.locationUnavailable))
                }
                manager.requestLocation()
            }
        } onCancel: {
            Task { @MainActor in self.finish(.failure(CancellationError())) }
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last,
              abs(location.timestamp.timeIntervalSinceNow) <= 30,
              location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 100,
              CLLocationCoordinate2DIsValid(location.coordinate) else {
            finish(.failure(ManualVisitIntentError.locationUnavailable)); return
        }
        finish(.success(location.coordinate))
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(.failure(ManualVisitIntentError.locationUnavailable))
    }
    private func finish(_ result: Result<CLLocationCoordinate2D, Error>) {
        timeout?.cancel(); timeout = nil
        manager.delegate = nil
        let pending = continuation; continuation = nil
        pending?.resume(with: result)
    }
}

struct CheckInIntent: AppIntent {
    static var title: LocalizedStringResource = "Check In"
    static var description = IntentDescription("Start a manual visit at a saved place, coordinates, or your current location. Overlaps require Allow Overlap to be explicitly enabled.")
    static var openAppWhenRun: Bool = false
    @Parameter(title: "Saved Place") var savedPlace: SavedPlaceEntity?
    @Parameter(title: "Place Name") var name: String?
    @Parameter(title: "Latitude") var latitude: Double?
    @Parameter(title: "Longitude") var longitude: Double?
    @Parameter(title: "Arrival Time") var arrivedAt: Date?
    @Parameter(title: "Allow Overlap", default: false) var allowOverlap: Bool

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<VisitEntity> {
        await StoreManager.shared.checkEntitlement()
        var resolvedName = name
        if savedPlace == nil && (name?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) {
            resolvedName = try await $name.requestValue("What is the place name?")
        }
        let locator = ManualIntentLocation()
        let visit = try await ManualVisitAutomation.checkIn(context: ManualVisitAutomation.makeContext(), name: resolvedName,
            savedPlaceID: savedPlace?.id, latitude: latitude, longitude: longitude,
            at: arrivedAt ?? Date(), allowOverlap: allowOverlap, currentLocation: { try await locator.coordinate() })
        NotificationCenter.default.post(name: .manualVisitsChanged, object: nil)
        return .result(value: VisitEntity(visit: visit), dialog: "Checked in at \(visit.displayName).")
    }
}

struct CheckOutIntent: AppIntent {
    static var title: LocalizedStringResource = "Check Out"
    static var description = IntentDescription("Finish an open manual visit. Choose a visit when more than one is open.")
    static var openAppWhenRun: Bool = false
    @Parameter(title: "Visit", query: OpenManualVisitQuery()) var visit: VisitEntity?
    @Parameter(title: "Departure Time") var departedAt: Date?

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<VisitEntity> {
        let context = ManualVisitAutomation.makeContext()
        var selected = visit
        if selected == nil {
            let candidates = try ManualVisitAutomation.openVisits(context: context).map(VisitEntity.init)
            if candidates.count > 1 {
                selected = try await $visit.requestDisambiguation(among: candidates, dialog: "Which visit should be checked out?")
            }
        }
        let finished = try ManualVisitAutomation.checkOut(context: context, visitID: selected?.id, at: departedAt ?? Date())
        NotificationCenter.default.post(name: .manualVisitsChanged, object: nil)
        return .result(value: VisitEntity(visit: finished), dialog: "Checked out of \(finished.displayName).")
    }
}

extension Notification.Name {
    static let manualVisitsChanged = Notification.Name("manualVisitsChanged")
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

extension VisitMutationError: CustomLocalizedStringResourceConvertible {
    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .invalidInput: return "Enter a place name, location, and valid times."
        case .trackingAccessRequired: return "Choose a Whereday plan before adding new visits."
        case .overlap(let name): return "These times overlap your visit to \(name). Review the times or save both visits."
        case .persistence: return "The visit could not be saved. Please try again."
        }
    }
}
