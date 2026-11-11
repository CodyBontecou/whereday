import Foundation
import CryptoKit
import CoreLocation
import SwiftData

/// Saved atomically with the visit mutation. Retained across phone/watch restarts.
@Model
final class WatchVisitCommandReceipt {
    @Attribute(.unique) var id: UUID
    var commandDigest: Data
    var resultData: Data
    init(id: UUID, commandDigest: Data, resultData: Data) {
        self.id = id
        self.commandDigest = commandDigest
        self.resultData = resultData
    }
}

@MainActor
final class WatchVisitCommandProcessor {
    private let container: ModelContainer
    init(context: ModelContext) { container = context.container }

    func snapshot() throws -> WatchVisitSnapshot {
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let open = try context.fetch(FetchDescriptor<Visit>(predicate: #Predicate { $0.departedAt == nil },
            sortBy: [SortDescriptor(\.arrivedAt, order: .reverse)]))
        let places = try context.fetch(FetchDescriptor<SavedPlace>(sortBy: [SortDescriptor(\.name)]))
        // Bound WatchConnectivity messages; the full journal remains on the phone.
        return WatchVisitSnapshot(status: WatchManualVisitStatus(generatedAt: Date(),
            openVisits: (open.filter { $0.source == .manual } + open.filter { $0.source != .manual }).prefix(20).map { WatchVisitSummary(id: $0.id, name: String($0.displayName.prefix(80)),
                arrivedAt: $0.arrivedAt, source: $0.source.rawValue, confirmationStatus: $0.confirmationStatus.rawValue) },
            openManualVisitCount: open.filter { $0.source == .manual }.count),
            savedPlaces: places.prefix(50).map { WatchSavedPlace(id: $0.id, name: String($0.name.prefix(80))) })
    }

    func process(_ command: WatchVisitCommand) -> WatchVisitCommandResult {
        func result(_ outcome: WatchVisitCommandResult.Outcome, _ message: String, visitID: UUID? = nil) -> WatchVisitCommandResult {
            WatchVisitCommandResult(commandID: command.id, deviceID: command.deviceID, outcome: outcome,
                visitID: visitID, message: message)
        }
        // Each delivery reads committed receipts in a fresh identity map. A
        // failed SwiftData save can leave registered objects visible even after
        // rollback; those objects must never acknowledge a later retry.
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        do {
            let id = command.id
            // Keep a fingerprint, rather than a second copy of location/name
            // data, while detecting reuse of an ID with a different intent.
            let requestDigest = Data(SHA256.hash(data: try encoder.encode(command)))
            if let receipt = try context.fetch(FetchDescriptor<WatchVisitCommandReceipt>(predicate: #Predicate { $0.id == id })).first {
                guard receipt.commandDigest == requestDigest else {
                    return result(.failed, String(localized: "This command ID was already used. Create a new command."))
                }
                return try JSONDecoder().decode(WatchVisitCommandResult.self, from: receipt.resultData)
            }
            func record(_ response: WatchVisitCommandResult) throws -> WatchVisitCommandReceipt {
                let receipt = WatchVisitCommandReceipt(id: id, commandDigest: requestDigest, resultData: try encoder.encode(response))
                context.insert(receipt)
                return receipt
            }
            func failure(_ message: String) throws -> WatchVisitCommandResult {
                let response = result(.failed, message)
                _ = try record(response)
                try context.save()
                return response
            }
            guard command.version == 1 else { return try failure(String(localized: "Update iso.me on both devices to use manual visits.")) }
            guard command.occurredAt.timeIntervalSinceReferenceDate.isFinite, command.occurredAt <= Date() else {
                return try failure(String(localized: "The visit time is invalid. Check the time on both devices."))
            }
            let store = ManualVisitStore(context: context)
            let success: WatchVisitCommandResult
            let saved: Bool
            switch command.kind {
            case .checkIn:
                let coordinate: CLLocationCoordinate2D
                let name: String
                let address: String?
                if let placeID = command.savedPlaceID {
                    guard command.latitude == nil, command.longitude == nil,
                          let place = try context.fetch(FetchDescriptor<SavedPlace>(predicate: #Predicate { $0.id == placeID })).first else {
                        return try failure(String(localized: "Choose an available saved place or use a fresh location."))
                    }
                    coordinate = place.coordinate
                    name = place.name
                    address = place.address
                } else {
                    guard let latitude = command.latitude, let longitude = command.longitude,
                          let measuredAt = command.locationMeasuredAt, let accuracy = command.horizontalAccuracy,
                          measuredAt.timeIntervalSinceReferenceDate.isFinite,
                          abs(measuredAt.timeIntervalSince(command.occurredAt)) <= 30,
                          accuracy.isFinite, accuracy >= 0, accuracy <= 100 else {
                        return try failure(String(localized: "A fresh accurate location is required. Choose a saved place or try again."))
                    }
                    coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
                    name = command.name ?? ""
                    address = nil
                }
                success = result(.succeeded, String(localized: "Checked in."), visitID: command.id)
                let receipt = WatchVisitCommandReceipt(id: id, commandDigest: requestDigest, resultData: try encoder.encode(success))
                saved = store.createManualVisit(name: name, address: address, coordinate: coordinate,
                    arrivedAt: command.occurredAt, departedAt: nil, allowOverlap: command.allowOverlap,
                    id: command.id, beforeSave: { _ in context.insert(receipt) }) != nil
            case .checkOut:
                let visit: Visit
                if let visitID = command.visitID {
                    guard let found = try context.fetch(FetchDescriptor<Visit>(predicate: #Predicate { $0.id == visitID })).first else {
                        if let dependency = command.dependsOnCommandID,
                           try context.fetchCount(FetchDescriptor<WatchVisitCommandReceipt>(predicate: #Predicate { $0.id == dependency })) == 0 {
                            return result(.retryable, String(localized: "Waiting for Check In to finish on iPhone."))
                        }
                        return try failure(String(localized: "That visit could not be found. Refresh visits on your Watch."))
                    }
                    visit = found
                } else {
                    let open = try ManualVisitAutomation.openVisits(context: context)
                    guard !open.isEmpty else { return try failure(String(localized: "There is no open manual visit to check out.")) }
                    guard open.count == 1 else { return try failure(String(localized: "More than one manual visit is open. Choose the visit to check out.")) }
                    visit = open[0]
                }
                guard visit.source == .manual, visit.departedAt == nil else {
                    return try failure(String(localized: "That visit is already checked out or is not a manual visit."))
                }
                guard command.occurredAt >= visit.arrivedAt else {
                    return try failure(String(localized: "Choose a time between the visit's arrival and now."))
                }
                success = result(.succeeded, String(localized: "Checked out."), visitID: visit.id)
                let receipt = WatchVisitCommandReceipt(id: id, commandDigest: requestDigest, resultData: try encoder.encode(success))
                saved = store.checkoutVisit(visit, at: command.occurredAt, beforeSave: {
                    context.insert(receipt)
                })
            }
            guard saved else {
                context.rollback()
                if case .persistence = store.visitMutationError {
                    return result(.retryable, String(localized: "iPhone could not save the visit. It will retry."))
                }
                return try failure(store.visitMutationError?.localizedDescription ?? String(localized: "The visit could not be saved. Please try again."))
            }
            return success
        } catch {
            // Deleting an inserted receipt alone can leave a registered object
            // visible to fetches. Roll back the entire isolated transaction.
            context.rollback()
            return result(.retryable, String(localized: "iPhone could not save the visit. It will retry."))
        }
    }
}
