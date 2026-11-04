import Foundation
import CoreLocation
import SwiftData
import Combine

/// Resolves history in bounded batches. Each completed lookup is saved, so cancellation is resumable.
@MainActor
final class CountryDayResolver: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var completed = 0
    @Published private(set) var resolved = 0
    @Published private(set) var message: String?
    private var cancelled = false
    private let geocoder = GeocodingService()
    private let reverseGeocode: ((CLLocation) async throws -> GeocodingResult)?
    private let minimumLookupInterval: TimeInterval
    private var nextLookupAt = Date.distantPast
    private var lookupCache: [String: GeocodingResult] = [:]

    init(minimumLookupInterval: TimeInterval? = nil,
         reverseGeocode: ((CLLocation) async throws -> GeocodingResult)? = nil) {
        self.reverseGeocode = reverseGeocode
        // Apple recommends at most one automatic geocode per minute. Mock lookups need no delay.
        self.minimumLookupInterval = max(0, minimumLookupInterval ?? (reverseGeocode == nil ? 60 : 0))
    }

    func cancel() {
        guard isRunning else { return }
        cancelled = true
        message = String(localized: "Stopping analysis…")
    }

    func resolveHistory(in context: ModelContext) async {
        guard !isRunning else { return }
        guard networkAllowed else {
            message = String(localized: "Enable network geocoding in Settings to identify countries, or add a manual stay.")
            return
        }
        isRunning = true
        cancelled = false
        completed = 0
        resolved = 0
        message = nil
        defer { isRunning = false }
        // Unresolved locations retry after an hour; unavailable geocodes never create invented days.
        let retryBefore = Date().addingTimeInterval(-3600)
        let neverAttempted = Date.distantPast
        do {
            while !shouldStop {
                var descriptor = FetchDescriptor<Visit>(predicate: #Predicate {
                    $0.countryCode == nil && ($0.countryResolutionAttemptedAt ?? neverAttempted) < retryBefore
                }, sortBy: [SortDescriptor(\.arrivedAt)])
                descriptor.fetchLimit = 100
                let visits = try context.fetch(descriptor)
                guard !visits.isEmpty else { break }
                for visit in visits {
                    guard !shouldStop else { break }
                    let latitude = visit.latitude
                    let longitude = visit.longitude
                    let result = try await lookup(latitude: latitude, longitude: longitude)
                    guard !shouldStop else { break }
                    // An edit or deletion while awaiting the network invalidates this result.
                    guard !visit.isDeleted, visit.modelContext != nil,
                          visit.latitude == latitude, visit.longitude == longitude else { continue }
                    visit.countryCode = result?.countryCode
                    visit.countryTimeZoneIdentifier = result?.timeZoneIdentifier
                    visit.countryResolutionAttemptedAt = Date()
                    try context.save()
                    record(result)
                }
            }
            while !shouldStop {
                var descriptor = FetchDescriptor<LocationPoint>(predicate: #Predicate {
                    !$0.isOutlier && $0.countryCode == nil && ($0.countryResolutionAttemptedAt ?? neverAttempted) < retryBefore
                }, sortBy: [SortDescriptor(\.timestamp)])
                descriptor.fetchLimit = 100
                let points = try context.fetch(descriptor)
                guard !points.isEmpty else { break }
                for point in points {
                    guard !shouldStop else { break }
                    let result = try await lookup(latitude: point.latitude, longitude: point.longitude)
                    guard !shouldStop else { break }
                    guard !point.isDeleted, point.modelContext != nil else { continue }
                    point.countryCode = result?.countryCode
                    point.countryTimeZoneIdentifier = result?.timeZoneIdentifier
                    point.countryResolutionAttemptedAt = Date()
                    try context.save()
                    record(result)
                }
            }
            if cancelled || Task.isCancelled {
                message = String(localized: "Analysis stopped. Completed results are saved; analyze again to continue.")
            } else if !networkAllowed {
                message = String(localized: "Analysis stopped because network geocoding is disabled.")
            } else if completed == 0 {
                message = String(localized: "No saved locations need analysis right now. Unavailable lookups can be retried after an hour.")
            } else {
                message = String(localized: "Identified \(resolved) of \(completed) locations. Unavailable lookups can be retried after an hour.")
            }
        } catch {
            if cancelled || Task.isCancelled {
                message = String(localized: "Analysis stopped. Completed results are saved; analyze again to continue.")
            } else if !networkAllowed {
                message = String(localized: "Analysis stopped because network geocoding is disabled.")
            } else if (error as NSError).domain == kCLErrorDomain || error is URLError {
                message = String(localized: "Country lookup is temporarily unavailable. Completed results are saved. Check your connection and try again in a minute.")
            } else {
                message = error.localizedDescription
            }
        }
    }

    private var networkAllowed: Bool {
        UserDefaults.standard.object(forKey: "allowNetworkGeocoding") == nil || UserDefaults.standard.bool(forKey: "allowNetworkGeocoding")
    }
    private var shouldStop: Bool { cancelled || Task.isCancelled || !networkAllowed }

    private func lookup(latitude: Double, longitude: Double) async throws -> GeocodingResult? {
        // Match the geocoder's existing cache precision, so repeated coordinates don't incur waits.
        let key = "\((latitude * 10000).rounded()),\((longitude * 10000).rounded())"
        let result: GeocodingResult
        if let cached = lookupCache[key] {
            result = cached
        } else {
            while Date() < nextLookupAt {
                guard !shouldStop else { throw CancellationError() }
                message = String(localized: "Waiting before the next country lookup… You can stop and continue later.")
                try await Task.sleep(for: .milliseconds(100))
            }
            guard !shouldStop else { throw CancellationError() }
            message = nil
            // Keep this deadline after failures and cancellation, including an immediate retry.
            nextLookupAt = Date().addingTimeInterval(minimumLookupInterval)
            let location = CLLocation(latitude: latitude, longitude: longitude)
            if let reverseGeocode {
                result = try await reverseGeocode(location)
            } else {
                result = try await geocoder.reverseGeocode(location: location)
            }
            lookupCache[key] = result
        }
        // A country without a local time zone cannot be assigned a reliable civil date.
        guard result.countryCode != nil, result.timeZoneIdentifier != nil else { return nil }
        return result
    }

    private func record(_ result: GeocodingResult?) {
        completed += 1
        if result?.countryCode != nil { resolved += 1 }
    }
}

/// Resolves city names (placemark locality) for coordinates, throttled and cached on disk.
///
/// Coordinates are grouped into ~1 km cells, so a city is looked up once per neighbourhood and
/// reused by every day, month and screen that needs it. Nothing is looked up when network
/// geocoding is disabled; callers then simply show no city.
@MainActor
final class LocalityResolver: ObservableObject {
    static let shared = LocalityResolver()

    /// Bumped whenever a new locality is cached, so views can recompute.
    @Published private(set) var revision = 0

    private static let cacheKey = "localityCache.v1"
    /// Cells that returned no locality; retried after a day.
    private static let missKey = "localityCacheMisses.v1"
    private let geocoder = CLGeocoder()
    private let minimumLookupInterval: TimeInterval
    private var cache: [String: String]
    private var misses: [String: Date]
    private var queue: [CLLocationCoordinate2D] = []
    private var queuedCells = Set<String>()
    private var worker: Task<Void, Never>?

    init(minimumLookupInterval: TimeInterval = 1.5) {
        self.minimumLookupInterval = minimumLookupInterval
        cache = UserDefaults.standard.dictionary(forKey: Self.cacheKey) as? [String: String] ?? [:]
        let storedMisses = UserDefaults.standard.dictionary(forKey: Self.missKey) as? [String: Double] ?? [:]
        misses = storedMisses.mapValues { Date(timeIntervalSince1970: $0) }
    }

    nonisolated static func cell(latitude: Double, longitude: Double) -> String {
        "\((latitude * 100).rounded() / 100),\((longitude * 100).rounded() / 100)"
    }

    /// The cached city for a coordinate, if it has been resolved.
    func locality(latitude: Double, longitude: Double) -> String? {
        cache[Self.cell(latitude: latitude, longitude: longitude)]
    }

    /// Queues lookups for coordinates without a cached city. Returns immediately.
    func request(_ coordinates: [CLLocationCoordinate2D]) {
        guard NetworkGeocodingPolicy.isAllowed else { return }
        let retryBefore = Date().addingTimeInterval(-24 * 3600)
        for coordinate in coordinates where CLLocationCoordinate2DIsValid(coordinate) {
            let cell = Self.cell(latitude: coordinate.latitude, longitude: coordinate.longitude)
            guard cache[cell] == nil, !queuedCells.contains(cell) else { continue }
            if let missed = misses[cell], missed > retryBefore { continue }
            queuedCells.insert(cell)
            queue.append(coordinate)
        }
        guard worker == nil, !queue.isEmpty else { return }
        worker = Task { [weak self] in
            await self?.drain()
        }
    }

    private func drain() async {
        defer { worker = nil }
        while !queue.isEmpty, !Task.isCancelled, NetworkGeocodingPolicy.isAllowed {
            let coordinate = queue.removeFirst()
            let cell = Self.cell(latitude: coordinate.latitude, longitude: coordinate.longitude)
            defer { queuedCells.remove(cell) }
            guard cache[cell] == nil else { continue }
            do {
                let placemarks = try await geocoder.reverseGeocodeLocation(
                    CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
                )
                let placemark = placemarks.first
                if let city = placemark?.locality ?? placemark?.subAdministrativeArea ?? placemark?.administrativeArea,
                   !city.isEmpty {
                    cache[cell] = city
                    UserDefaults.standard.set(cache, forKey: Self.cacheKey)
                    revision += 1
                } else {
                    recordMiss(cell)
                }
            } catch {
                // Offline or throttled: stop for now; a later request retries these cells.
                let code = (error as NSError).code
                if code == CLError.network.rawValue {
                    queue.removeAll()
                    queuedCells.removeAll()
                    return
                }
                recordMiss(cell)
            }
            try? await Task.sleep(for: .seconds(minimumLookupInterval))
        }
    }

    private func recordMiss(_ cell: String) {
        misses[cell] = Date()
        UserDefaults.standard.set(misses.mapValues(\.timeIntervalSince1970), forKey: Self.missKey)
    }
}
