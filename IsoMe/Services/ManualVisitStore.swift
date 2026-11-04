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

/// A stored stay that a new stop would overlap, and how saving the stop trims it.
@MainActor
struct VisitOverlapResolution: Identifiable {
    enum Change: Equatable {
        /// The earlier stay ends where the new stop begins.
        case endsAt(Date)
        /// The later stay begins where the new stop ends.
        case startsAt(Date)
    }

    let visit: Visit
    let change: Change
    /// How long the two overlap, measured against now for a stay still in progress.
    let overlap: TimeInterval

    var id: UUID { visit.id }
}

/// What saving a stop over the given times would do to stored stays.
@MainActor
enum VisitOverlapCheck {
    case none
    /// Every overlapped stay can be trimmed to make room.
    case trims([VisitOverlapResolution])
    /// This stay sits inside the new stop (or the new stop is open-ended over it); it cannot be trimmed.
    case conflict(Visit)
}

/// Shared mutation boundary for the app and background App Intents.
@MainActor
final class ManualVisitStore {
    let modelContext: ModelContext
    private(set) var visitMutationError: VisitMutationError?

    init(context: ModelContext) { self.modelContext = context }

    /// Checks stored stays (manual and recorded) against a new stop's times.
    /// Adjacent stays that share a boundary do not overlap.
    func overlapCheck(arrivedAt: Date, departedAt: Date?, excluding excluded: Visit? = nil, now: Date = Date()) -> VisitOverlapCheck {
        guard arrivedAt.timeIntervalSinceReferenceDate.isFinite,
              departedAt.map({ $0 >= arrivedAt }) ?? true,
              let candidates = try? overlappingVisits(arrivedAt: arrivedAt, departedAt: departedAt, excluding: excluded) else {
            return .none
        }
        guard !candidates.isEmpty else { return .none }

        var resolutions: [VisitOverlapResolution] = []
        for candidate in candidates.sorted(by: { $0.arrivedAt < $1.arrivedAt }) {
            let candidateEnd = candidate.departedAt ?? Date.distantFuture
            let change: VisitOverlapResolution.Change
            if candidate.arrivedAt < arrivedAt {
                change = .endsAt(arrivedAt)
            } else if let departedAt, candidateEnd > departedAt, candidate.arrivedAt < departedAt {
                change = .startsAt(departedAt)
            } else {
                return .conflict(candidate)
            }
            let overlapStart = max(candidate.arrivedAt, arrivedAt)
            let overlapEnd = min(candidate.departedAt ?? now, departedAt ?? now)
            resolutions.append(VisitOverlapResolution(
                visit: candidate,
                change: change,
                overlap: max(0, overlapEnd.timeIntervalSince(overlapStart))
            ))
        }

        // Two stays cannot both end at the new arrival without one swallowing the other.
        let endings = resolutions.filter { if case .endsAt = $0.change { true } else { false } }
        let startings = resolutions.filter { if case .startsAt = $0.change { true } else { false } }
        if endings.count > 1 { return .conflict(endings[1].visit) }
        if startings.count > 1 { return .conflict(startings[0].visit) }
        return .trims(resolutions)
    }

    /// Saves a stop, first trimming any stays it overlaps so the timeline stays continuous:
    /// an earlier stay ends at the stop's arrival, a later one starts at its departure.
    /// A stay that cannot be trimmed is reported as `.overlap`, as without trimming.
    @discardableResult
    func createManualVisitTrimmingOverlaps(
        name: String,
        address: String?,
        coordinate: CLLocationCoordinate2D,
        arrivedAt: Date,
        departedAt: Date?,
        placeSource: VisitPlaceSource = .userEntered
    ) -> Visit? {
        visitMutationError = nil
        let resolutions: [VisitOverlapResolution]
        switch overlapCheck(arrivedAt: arrivedAt, departedAt: departedAt) {
        case .none:
            resolutions = []
        case .conflict(let visit):
            visitMutationError = .overlap(visit.displayName)
            return nil
        case .trims(let trims):
            resolutions = trims
        }

        let previous = resolutions.map { resolution in
            (resolution.visit, resolution.visit.arrivedAt, resolution.visit.departedAt,
             resolution.visit.countryDepartureIsInferred, resolution.visit.updatedAt)
        }
        let now = Date()
        for resolution in resolutions {
            switch resolution.change {
            case .endsAt(let end):
                resolution.visit.departedAt = end
                resolution.visit.countryDepartureIsInferred = false
            case .startsAt(let start):
                resolution.visit.arrivedAt = start
            }
            resolution.visit.updatedAt = now
        }

        // The trims and the new stop are saved together; undo the trims if the stop fails.
        let visit = createManualVisit(
            name: name,
            address: address,
            coordinate: coordinate,
            arrivedAt: arrivedAt,
            departedAt: departedAt,
            placeSource: placeSource,
            allowOverlap: true
        )
        if visit == nil, !previous.isEmpty {
            for (trimmed, arrived, departed, inferred, updated) in previous {
                trimmed.arrivedAt = arrived
                trimmed.departedAt = departed
                trimmed.countryDepartureIsInferred = inferred
                trimmed.updatedAt = updated
            }
            try? modelContext.save()
        }
        return visit
    }

    private func overlappingVisits(arrivedAt: Date, departedAt: Date?, excluding excluded: Visit?) throws -> [Visit] {
        let end = departedAt ?? Date.distantFuture
        let distantFuture = Date.distantFuture
        let predicate = #Predicate<Visit> { candidate in
            candidate.arrivedAt <= end && (candidate.departedAt ?? distantFuture) >= arrivedAt
        }
        return try modelContext.fetch(FetchDescriptor<Visit>(predicate: predicate)).filter { candidate in
            guard candidate.id != excluded?.id else { return false }
            let candidateEnd = candidate.departedAt ?? Date.distantFuture
            return (candidate.arrivedAt < end && candidateEnd > arrivedAt)
                || (candidate.arrivedAt == arrivedAt && candidateEnd == end)
        }
    }

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
