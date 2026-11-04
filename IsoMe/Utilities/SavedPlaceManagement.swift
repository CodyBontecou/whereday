import Foundation
import CoreLocation
import SwiftData

struct SavedPlaceDraft: Identifiable, Equatable {
    let id: UUID
    var existingPlaceID: UUID?
    var name: String
    var address: String
    var latitude: Double?
    var longitude: Double?
    var radiusMeters: Double
    var kind: SavedPlaceKind?
    var notes: String
    /// CSV rows carry no kind or notes, so an import leaves a matched place's own values alone.
    var updatesKindAndNotes: Bool

    init(id: UUID = UUID(), existingPlaceID: UUID? = nil, name: String = "", address: String = "", latitude: Double? = nil, longitude: Double? = nil, radiusMeters: Double = 150, kind: SavedPlaceKind? = nil, notes: String = "", updatesKindAndNotes: Bool = true) {
        self.id = id
        self.existingPlaceID = existingPlaceID
        self.name = name
        self.address = address
        self.latitude = latitude
        self.longitude = longitude
        self.radiusMeters = radiusMeters
        self.kind = kind
        self.notes = notes
        self.updatesKindAndNotes = updatesKindAndNotes
    }

    init(_ place: SavedPlace) {
        self.init(id: place.id, existingPlaceID: place.id, name: place.name, address: place.address ?? "", latitude: place.latitude, longitude: place.longitude, radiusMeters: place.radiusMeters, kind: place.kind, notes: place.notes ?? "")
    }

    init(_ place: ImportedSavedPlace) {
        self.init(id: place.id, name: place.name, address: place.address ?? "", latitude: place.latitude, longitude: place.longitude, radiusMeters: place.radiusMeters, updatesKindAndNotes: false)
    }

    var coordinate: CLLocationCoordinate2D? {
        guard let latitude, let longitude else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var validationMessage: String? {
        if name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return String(localized: "Enter a location name.")
        }
        guard let coordinate, coordinate.latitude.isFinite, coordinate.longitude.isFinite,
              CLLocationCoordinate2DIsValid(coordinate) else {
            return String(localized: "Choose a valid location on the map.")
        }
        guard radiusMeters.isFinite, (25...10_000).contains(radiusMeters) else {
            return String(localized: "Match radius must be between 25 and 10,000 meters.")
        }
        return nil
    }

    func matches(_ other: SavedPlaceDraft) -> Bool {
        guard name.trimmingCharacters(in: .whitespacesAndNewlines)
            .localizedCaseInsensitiveCompare(other.name.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame,
              coordinate != nil, other.coordinate != nil else { return false }
        return distance(to: other) <= max(radiusMeters, other.radiusMeters)
    }

    func distance(to other: SavedPlaceDraft) -> Double {
        guard let coordinate, let otherCoordinate = other.coordinate else { return .infinity }
        return CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
            .distance(from: CLLocation(latitude: otherCoordinate.latitude, longitude: otherCoordinate.longitude))
    }

    var matchingName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).folding(options: .caseInsensitive, locale: .current)
    }

    func apply(to place: SavedPlace) {
        place.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedAddress = address.trimmingCharacters(in: .whitespacesAndNewlines)
        place.address = trimmedAddress.isEmpty ? nil : trimmedAddress
        if let latitude, let longitude {
            place.latitude = latitude
            place.longitude = longitude
        }
        place.radiusMeters = radiusMeters
        if updatesKindAndNotes {
            place.kind = kind
            let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
            place.notes = trimmedNotes.isEmpty ? nil : notes
        }
    }
}

enum SavedPlaceDuplicatePolicy: String, CaseIterable, Identifiable {
    case keepExisting
    case updateExisting

    var id: String { rawValue }
    var title: String {
        switch self {
        case .keepExisting: String(localized: "Keep existing locations")
        case .updateExisting: String(localized: "Update matching locations")
        }
    }
}

struct SavedPlaceImportSummary {
    var added = 0
    var updated = 0
    var skipped = 0

    var message: String {
        String(localized: "Added: \(added). Updated: \(updated). Skipped: \(skipped).")
    }
}

/// Preview and persistence share the same sequential, nearest-place merge plan.
struct SavedPlaceImportPlan {
    enum Action { case add, update, skip }
    struct Entry: Identifiable {
        let place: ImportedSavedPlace
        let targetID: UUID
        let action: Action
        var id: UUID { place.id }
    }

    let entries: [Entry]
    let summary: SavedPlaceImportSummary

    init(places: [ImportedSavedPlace], existing: [SavedPlaceDraft], policy: SavedPlaceDuplicatePolicy) {
        var groups: [String: PlaceIndex] = [:]
        for draft in existing {
            let key = draft.matchingName
            let index = groups[key] ?? PlaceIndex()
            index.insert(draft)
            groups[key] = index
        }
        var entries: [Entry] = []
        entries.reserveCapacity(places.count)
        var summary = SavedPlaceImportSummary()
        for place in places {
            let draft = SavedPlaceDraft(place)
            let key = draft.matchingName
            let index = groups[key] ?? PlaceIndex()
            groups[key] = index
            let match = index.nearestMatch(to: draft)
            if let match {
                if policy == .keepExisting {
                    entries.append(Entry(place: place, targetID: match.id, action: .skip))
                    summary.skipped += 1
                } else {
                    entries.append(Entry(place: place, targetID: match.id, action: .update))
                    summary.updated += 1
                    let replacement = SavedPlaceDraft(id: match.id, name: draft.name, address: draft.address, latitude: draft.latitude, longitude: draft.longitude, radiusMeters: draft.radiusMeters)
                    index.insert(replacement)
                }
            } else {
                entries.append(Entry(place: place, targetID: draft.id, action: .add))
                summary.added += 1
                index.insert(draft)
            }
        }
        self.entries = entries
        self.summary = summary
    }

    /// A reference keeps sequential updates from copying an entire name group.
    private final class PlaceIndex {
        private static let bucketDegrees = 0.1
        // Below the minimum Earth curvature radius, so bounds include all places
        // that CLLocation's ellipsoidal distance can accept.
        private static let minimumEarthRadiusMeters = 6_300_000.0
        private var buckets: [Int: [UUID: SavedPlaceDraft]] = [:]
        private var bucketByID: [UUID: Int] = [:]
        private var unbounded: [UUID: SavedPlaceDraft] = [:]
        private var maximumRadius = 0.0

        func insert(_ draft: SavedPlaceDraft) {
            if let oldBucket = bucketByID.removeValue(forKey: draft.id) {
                buckets[oldBucket]?.removeValue(forKey: draft.id)
                if buckets[oldBucket]?.isEmpty == true { buckets.removeValue(forKey: oldBucket) }
            }
            unbounded.removeValue(forKey: draft.id)
            // Keep a conservative upper bound after a radius shrinks, including
            // legacy radii larger than the editor's current maximum.
            maximumRadius = max(maximumRadius, draft.radiusMeters.isFinite ? draft.radiusMeters : .infinity)
            guard let coordinate = draft.coordinate,
                  coordinate.latitude.isFinite, coordinate.longitude.isFinite,
                  CLLocationCoordinate2DIsValid(coordinate) else {
                unbounded[draft.id] = draft
                return
            }
            let bucket = Self.bucket(for: coordinate.latitude)
            buckets[bucket, default: [:]][draft.id] = draft
            bucketByID[draft.id] = bucket
        }

        func nearestMatch(to draft: SavedPlaceDraft) -> SavedPlaceDraft? {
            guard let coordinate = draft.coordinate else { return nil }
            let radius = max(draft.radiusMeters, maximumRadius)
            let latitudeDelta = min(180, radius / Self.minimumEarthRadiusMeters * 180 / .pi)
            let extremeLatitude = min(90, abs(coordinate.latitude) + latitudeDelta)
            let longitudeDelta = extremeLatitude >= 90 ? 180
                : min(180, latitudeDelta / cos(extremeLatitude * .pi / 180))
            var best: SavedPlaceDraft?
            var bestDistance = Double.infinity

            func consider(_ candidate: SavedPlaceDraft, bounded: Bool) {
                guard let candidateCoordinate = candidate.coordinate else { return }
                if bounded {
                    guard abs(coordinate.latitude - candidateCoordinate.latitude) <= latitudeDelta else { return }
                    let difference = abs(coordinate.longitude - candidateCoordinate.longitude)
                    guard min(difference, 360 - difference) <= longitudeDelta else { return }
                }
                let distance = draft.distance(to: candidate)
                guard distance <= max(draft.radiusMeters, candidate.radiusMeters) else { return }
                if distance < bestDistance || (distance == bestDistance && (best == nil || candidate.id.uuidString < best!.id.uuidString)) {
                    best = candidate
                    bestDistance = distance
                }
            }

            if coordinate.latitude.isFinite, coordinate.longitude.isFinite,
               CLLocationCoordinate2DIsValid(coordinate) {
                let firstBucket = Self.bucket(for: max(-90, coordinate.latitude - latitudeDelta))
                let lastBucket = Self.bucket(for: min(90, coordinate.latitude + latitudeDelta))
                for bucket in firstBucket...lastBucket {
                    for candidate in buckets[bucket, default: [:]].values { consider(candidate, bounded: true) }
                }
            } else {
                for bucket in buckets.values {
                    for candidate in bucket.values { consider(candidate, bounded: false) }
                }
            }
            for candidate in unbounded.values { consider(candidate, bounded: false) }
            return best
        }

        private static func bucket(for latitude: Double) -> Int {
            Int(floor((latitude + 90) / bucketDegrees))
        }
    }
}

enum SavedPlaceManagementError: LocalizedError {
    case invalid(String)
    case missingPlace

    var errorDescription: String? {
        switch self {
        case .invalid(let message): message
        case .missingPlace: String(localized: "This saved location no longer exists. Close the editor and try again.")
        }
    }
}

// MARK: - Merging

/// What folding one saved place into another changes, worked out before the user confirms.
///
/// Visits are tied to saved places by geometry (the nearest containing match radius) and by the
/// name copied onto them when they were detected. A merge therefore widens the kept place's
/// radius to cover the removed circle, and renames visits that carry the removed place's name.
struct SavedPlaceMergePlan: Identifiable {
    /// Largest radius the editor accepts; a merge never grows a place beyond it.
    static let maximumRadius = 10_000.0

    let keptID: UUID
    let removedID: UUID
    let keptName: String
    let removedName: String
    /// Visits currently matched to the removed place by its radius.
    let movedVisitIDs: Set<UUID>
    /// Visits whose stored name came from the removed place; they take the kept name.
    let renamedVisitIDs: Set<UUID>
    let mergedRadius: Double
    /// False when the removed circle is too far away to cover; renamed visits still keep the new name.
    let coversRemovedPlace: Bool

    var id: String { "\(keptID)-\(removedID)" }

    init(keeping kept: SavedPlace, removing removed: SavedPlace, places: [SavedPlace], visits: [Visit]) {
        keptID = kept.id
        removedID = removed.id
        keptName = kept.normalizedName
        removedName = removed.normalizedName
        let moved = PlaceVisitIndex(places: places, visits: visits).visits(for: removed)
        movedVisitIDs = Set(moved.map(\.id))

        let oldName = removed.normalizedName
        var renamed = Set<UUID>()
        for visit in visits {
            let named = Self.matches(visit.normalizedCustomName, oldName)
                || (visit.placeSource == .userEntered && Self.matches(visit.locationName, oldName))
            if named || (movedVisitIDs.contains(visit.id) && Self.carriesName(visit, oldName)) {
                renamed.insert(visit.id)
            }
        }
        renamedVisitIDs = renamed

        let needed = kept.distanceMeters(to: removed.coordinate) + removed.radiusMeters
        coversRemovedPlace = needed <= Self.maximumRadius
        mergedRadius = min(Self.maximumRadius, max(kept.radiusMeters, needed.rounded(.up)))
        radiusGrows = mergedRadius > kept.radiusMeters
    }

    let radiusGrows: Bool

    static func matches(_ value: String?, _ name: String) -> Bool {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty, !name.isEmpty else { return false }
        return value.localizedCaseInsensitiveCompare(name) == .orderedSame
    }

    fileprivate static func carriesName(_ visit: Visit, _ name: String) -> Bool {
        matches(visit.normalizedCustomName, name) || matches(visit.locationName, name) || matches(visit.detectedLocationName, name)
    }
}

extension LocationViewModel {
    /// Folds the plan's removed place into the kept one: its visits and name matches join the kept
    /// place, its kind, address and notes fill any gaps, and the removed place is deleted.
    func mergeSavedPlaces(_ plan: SavedPlaceMergePlan) throws {
        guard plan.keptID != plan.removedID,
              let kept = savedPlaces.first(where: { $0.id == plan.keptID }),
              let removed = savedPlaces.first(where: { $0.id == plan.removedID }),
              let context = kept.modelContext else {
            throw SavedPlaceManagementError.missingPlace
        }
        let now = Date()
        let oldName = removed.normalizedName
        let newName = kept.normalizedName
        for visit in allVisits where plan.renamedVisitIDs.contains(visit.id) {
            var changed = false
            if SavedPlaceMergePlan.matches(visit.normalizedCustomName, oldName) {
                visit.customName = newName
                changed = true
            }
            if SavedPlaceMergePlan.matches(visit.locationName, oldName) {
                visit.locationName = newName
                if visit.placeSource == .userEntered, let address = kept.address { visit.address = address }
                changed = true
            }
            if SavedPlaceMergePlan.matches(visit.detectedLocationName, oldName) {
                visit.detectedLocationName = newName
                changed = true
            }
            if changed { visit.updatedAt = now }
        }

        kept.radiusMeters = plan.mergedRadius
        if kept.kind == nil { kept.kind = removed.kind }
        if (kept.address ?? "").isEmpty { kept.address = removed.address }
        let notes = [kept.notes, removed.notes]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        kept.notes = notes.isEmpty ? nil : notes.joined(separator: "\n\n")
        kept.createdAt = min(kept.createdAt, removed.createdAt)
        kept.updatedAt = now
        context.delete(removed)

        do {
            try context.save()
        } catch {
            context.rollback()
            loadSavedPlaces()
            loadAllVisits()
            throw error
        }
        loadSavedPlaces()
        loadTodayVisits()
        loadAllVisits()
    }
}
