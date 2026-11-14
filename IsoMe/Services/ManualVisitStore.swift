import Foundation
import SwiftData
import CoreLocation
import WidgetKit

enum VisitMutationError: LocalizedError {
    case invalidInput
    case trackingAccessRequired
    case overlap(String)
    case persistence

    var errorDescription: String? {
        switch self {
        case .trackingAccessRequired:
            return String(localized: "Choose a Whereday plan before adding new visits.")
        case .invalidInput:
            return String(localized: "Enter a place name, location, and valid times.")
        case .overlap(let name):
            return String(format: String(localized: "These times overlap your visit to %@. Review the times or save both visits."), name)
        case .persistence:
            return String(localized: "The visit could not be saved. Please try again.")
        }
    }
}

/// Shared mutation boundary for the app and background App Intents.
@MainActor
final class ManualVisitStore {
    let modelContext: ModelContext
    private(set) var visitMutationError: VisitMutationError?

    init(context: ModelContext) { self.modelContext = context }

    @discardableResult
    func createManualVisit(
        name: String,
        address: String?,
        coordinate: CLLocationCoordinate2D,
        arrivedAt: Date,
        departedAt: Date?,
        placeSource: VisitPlaceSource = .userEntered,
        allowOverlap: Bool = false
    ) -> Visit? {
        visitMutationError = nil
        guard StoreManager.shared.canTrack else {
            visitMutationError = .trackingAccessRequired
            NotificationCenter.default.post(name: .trackingAccessRequired, object: nil)
            return nil
        }
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty,
              CLLocationCoordinate2DIsValid(coordinate),
              coordinate.latitude.isFinite,
              coordinate.longitude.isFinite else {
            visitMutationError = .invalidInput
            return nil
        }
        guard validateVisitTimes(arrivedAt: arrivedAt, departedAt: departedAt, allowOverlap: allowOverlap) else { return nil }

        let now = Date()
        let visit = Visit(
            latitude: coordinate.latitude,
            longitude: coordinate.longitude,
            arrivedAt: arrivedAt,
            departedAt: departedAt,
            locationName: trimmedName,
            address: address?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            geocodingCompleted: true,
            source: .manual,
            confirmationStatus: .confirmed,
            confirmedAt: now,
            updatedAt: now,
            placeSource: placeSource
        )
        modelContext.insert(visit)
        do {
            try modelContext.save()
        } catch {
            modelContext.delete(visit)
            visitMutationError = .persistence
            return nil
        }
        refreshSharedVisitCount()
        return visit
    }

    /// A manual save can run while tracking is off or before the UI exists.
    /// Publish only the count so cached GPS, distance and tracking state stay intact.
    private func refreshSharedVisitCount() {
        guard let day = Calendar.current.dateInterval(of: .day, for: Date()) else { return }
        let start = day.start
        let end = day.end
        let descriptor = FetchDescriptor<Visit>(predicate: #Predicate { visit in
            visit.arrivedAt >= start && visit.arrivedAt < end
        })
        // A snapshot read failure must not turn a saved visit into a retryable mutation failure.
        guard let count = try? modelContext.fetchCount(descriptor) else { return }
        var snapshot = SharedLocationData.load() ?? .empty
        snapshot.todayVisitsCount = count
        snapshot.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Uses stored visits rather than the UI cache, which may contain only a date range.
    /// Adjacent visits may share an arrival/departure boundary without overlapping.
    private func validateVisitTimes(
        arrivedAt: Date,
        departedAt: Date?,
        excluding visit: Visit? = nil,
        allowOverlap: Bool
    ) -> Bool {
        guard arrivedAt.timeIntervalSinceReferenceDate.isFinite,
              departedAt.map({ $0.timeIntervalSinceReferenceDate.isFinite && $0 >= arrivedAt }) ?? true else {
            visitMutationError = .invalidInput
            return false
        }
        guard !allowOverlap else { return true }
        let end = departedAt ?? Date.distantFuture
        let distantFuture = Date.distantFuture
        let predicate = #Predicate<Visit> { candidate in
            candidate.arrivedAt <= end && (candidate.departedAt ?? distantFuture) >= arrivedAt
        }
        var descriptor = FetchDescriptor<Visit>(predicate: predicate)
        descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .reverse)]
        do {
            let overlap = try modelContext.fetch(descriptor).first { candidate in
                guard candidate.id != visit?.id else { return false }
                let candidateEnd = candidate.departedAt ?? Date.distantFuture
                // Equal, zero-length records are duplicates too.
                return (candidate.arrivedAt < end && candidateEnd > arrivedAt)
                    || (candidate.arrivedAt == arrivedAt && candidateEnd == end)
            }
            if let overlap {
                visitMutationError = .overlap(overlap.displayName)
                return false
            }
            return true
        } catch {
            visitMutationError = .persistence
            return false
        }
    }

    @discardableResult
    func updateVisitTimes(
        _ visit: Visit,
        arrivedAt: Date,
        departedAt: Date?,
        allowOverlap: Bool = false
    ) -> Bool {
        visitMutationError = nil
        guard validateVisitTimes(
            arrivedAt: arrivedAt,
            departedAt: departedAt,
            excluding: visit,
            allowOverlap: allowOverlap || visit.source != .manual
        ) else { return false }

        let previous = (visit.arrivedAt, visit.departedAt, visit.countryDepartureIsInferred, visit.updatedAt)
        visit.arrivedAt = arrivedAt
        visit.departedAt = departedAt
        visit.countryDepartureIsInferred = false
        visit.updatedAt = Date()
        do {
            try modelContext.save()
        } catch {
            (visit.arrivedAt, visit.departedAt, visit.countryDepartureIsInferred, visit.updatedAt) = previous
            visitMutationError = .persistence
            return false
        }
        return true
    }

    @discardableResult
    func checkoutVisit(_ visit: Visit, at date: Date = Date()) -> Bool {
        visitMutationError = nil
        guard visit.source == .manual, visit.departedAt == nil,
              date >= visit.arrivedAt, date <= Date() else {
            visitMutationError = .invalidInput
            return false
        }
        // Closing an existing visit cannot add an overlap, even if the user chose
        // to save overlapping records earlier.
        return updateVisitTimes(visit, arrivedAt: visit.arrivedAt, departedAt: date, allowOverlap: true)
    }

}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
