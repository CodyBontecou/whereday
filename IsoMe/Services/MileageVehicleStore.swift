import Foundation
import SwiftData

/// Free vehicle-management backend. Cooperative committed inputs, serialized on MainActor.
/// No caller context, managed models or cache escape. Not cross-process UUID uniqueness.
@MainActor
final class MileageVehicleStore {
    struct Draft: Equatable {
        let name: String
        let make: String?
        let model: String?
        let year: Int?
        let licensePlateLast4: String?
        let notes: String

        init(name: String, make: String? = nil, model: String? = nil, year: Int? = nil,
             licensePlateLast4: String? = nil, notes: String = "") {
            self.name = name
            self.make = make
            self.model = model
            self.year = year
            self.licensePlateLast4 = licensePlateLast4
            self.notes = notes
        }
    }

    struct Snapshot: Equatable {
        let id: UUID
        let name: String
        let make: String?
        let model: String?
        let year: Int?
        let licensePlateLast4: String?
        let notes: String
        let isDefault: Bool
        let isArchived: Bool
        let createdAt: Date
        let updatedAt: Date

        fileprivate init(_ vehicle: Vehicle) {
            id = vehicle.id
            name = vehicle.name
            make = vehicle.make
            model = vehicle.model
            year = vehicle.year
            licensePlateLast4 = vehicle.licensePlateLast4
            notes = vehicle.notes
            isDefault = vehicle.isDefault
            isArchived = vehicle.isArchived
            createdAt = vehicle.createdAt
            updatedAt = vehicle.updatedAt
        }
    }

    enum StoreError: Error, Equatable {
        case blankName
        case missingVehicle(UUID)
        case archivedVehicle(UUID)
        case duplicateIdentity(UUID)
        case reentrantMutation
    }

    private let container: ModelContainer
    private let clock: () -> Date
    private let makeID: () -> UUID
    private let beforeSave: () throws -> Void
    private var mutating = false

    /// beforeSave is a narrow rejection/reentry seam, NOT a replacement save/context.
    /// A throwing hook proves owned rollback behavior only, not real SQLite IO failure.
    init(container: ModelContainer, clock: @escaping () -> Date = Date.init,
         makeID: @escaping () -> UUID = UUID.init,
         beforeSave: @escaping () throws -> Void = {}) {
        self.container = container
        self.clock = clock
        self.makeID = makeID
        self.beforeSave = beforeSave
    }

    /// Passive committed-state projection. Does not normalize, insert or save.
    func snapshots() throws -> [Snapshot] {
        let context = ownedContext()
        return try allVehicles(in: context).map(Snapshot.init)
    }

    @discardableResult
    func create(_ draft: Draft, makeDefault: Bool = false) throws -> Snapshot {
        try mutate { context, vehicles, now in
            try self.validate(draft)
            let id = self.makeID()
            guard !vehicles.contains(where: { $0.id == id }) else {
                throw StoreError.duplicateIdentity(id)
            }
            let vehicle = Vehicle(id: id, name: draft.name, now: now)
            self.apply(draft, to: vehicle)
            context.insert(vehicle)
            vehicles.append(vehicle)
            return (vehicle, makeDefault ? id : nil)
        }
    }

    @discardableResult
    func update(id: UUID, draft: Draft) throws -> Snapshot {
        try mutate { _, vehicles, now in
            let vehicle = try self.target(id, in: vehicles)
            try self.validate(draft)
            self.apply(draft, to: vehicle)
            vehicle.updatedAt = now
            return (vehicle, nil)
        }
    }

    @discardableResult
    func setDefault(id: UUID) throws -> Snapshot {
        try mutate { _, vehicles, _ in
            let vehicle = try self.target(id, in: vehicles)
            guard !vehicle.isArchived else { throw StoreError.archivedVehicle(id) }
            return (vehicle, id)
        }
    }

    @discardableResult
    func archive(id: UUID) throws -> Snapshot {
        try mutate { _, vehicles, now in
            let vehicle = try self.target(id, in: vehicles)
            if !vehicle.isArchived {
                vehicle.isArchived = true
                vehicle.updatedAt = now
            }
            // Existing policy clears archived defaults and chooses a remaining active row.
            return (vehicle, nil)
        }
    }

    private func ownedContext() -> ModelContext {
        let context = ModelContext(container)
        context.autosaveEnabled = false
        return context
    }

    private func allVehicles(in context: ModelContext) throws -> [Vehicle] {
        let vehicles = try context.fetch(FetchDescriptor<Vehicle>())
        var seen = Set<UUID>()
        // Sort first so duplicate diagnostics and snapshots do not depend on fetch order.
        let ordered = vehicles.sorted {
            if $0.createdAt != $1.createdAt { return $0.createdAt < $1.createdAt }
            return $0.id.uuidString < $1.id.uuidString
        }
        for vehicle in ordered {
            guard seen.insert(vehicle.id).inserted else { throw StoreError.duplicateIdentity(vehicle.id) }
        }
        return ordered
    }

    private func target(_ id: UUID, in vehicles: [Vehicle]) throws -> Vehicle {
        guard let vehicle = vehicles.first(where: { $0.id == id }) else {
            throw StoreError.missingVehicle(id)
        }
        return vehicle
    }

    private func validate(_ draft: Draft) throws {
        guard !draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw StoreError.blankName
        }
        // Store exact supplied values. No year/plate/country/optional-field policy invented.
    }

    private func apply(_ draft: Draft, to vehicle: Vehicle) {
        vehicle.name = draft.name
        vehicle.make = draft.make
        vehicle.model = draft.model
        vehicle.year = draft.year
        vehicle.licensePlateLast4 = draft.licensePlateLast4
        vehicle.notes = draft.notes
    }

    private func mutate(_ change: (ModelContext, inout [Vehicle], Date) throws -> (Vehicle, UUID?)) throws -> Snapshot {
        guard !mutating else { throw StoreError.reentrantMutation }
        mutating = true
        defer { mutating = false }
        let context = ownedContext()
        do {
            var vehicles = try allVehicles(in: context)
            let now = clock()
            let (vehicle, preferredID) = try change(context, &vehicles, now)
            MileageVehiclePolicy.normalizeDefaults(in: vehicles, preferredID: preferredID, now: now)
            try beforeSave()
            // One synchronous private-context unit of work; no await or caller-context save.
            try context.save()
            return Snapshot(vehicle)
        } catch {
            context.rollback()
            throw error
        }
    }
}
