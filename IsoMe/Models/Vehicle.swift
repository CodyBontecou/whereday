import Foundation
import SwiftData

@Model
final class Vehicle {
    var id: UUID
    var name: String
    var make: String?
    var model: String?
    var year: Int?
    var licensePlateLast4: String?
    var isDefault: Bool = false
    var isArchived: Bool = false
    var notes: String = ""
    var createdAt: Date
    var updatedAt: Date

    init(id: UUID = UUID(), name: String, now: Date = Date()) {
        self.id = id
        self.name = name
        self.createdAt = now
        self.updatedAt = now
    }
}

/// Call with ALL vehicles in the same ModelContext transaction, then save once.
/// Exactly one non-archived vehicle is default if any exist; otherwise none is.
/// This is an in-context policy, not a cross-process database uniqueness constraint.
enum MileageVehiclePolicy {
    @discardableResult
    static func normalizeDefaults(in vehicles: [Vehicle], preferredID: UUID? = nil,
                                  now: Date = Date()) -> UUID? {
        let active = vehicles.filter { !$0.isArchived }.sorted {
            if $0.createdAt != $1.createdAt { return $0.createdAt < $1.createdAt }
            return $0.id.uuidString < $1.id.uuidString
        }
        let selected = active.first { $0.id == preferredID }
            ?? active.first { $0.isDefault }
            ?? active.first
        for vehicle in vehicles {
            let shouldBeDefault = vehicle.id == selected?.id && !vehicle.isArchived
            if vehicle.isDefault != shouldBeDefault {
                vehicle.isDefault = shouldBeDefault
                vehicle.updatedAt = now
            }
        }
        return selected?.id
    }
}
