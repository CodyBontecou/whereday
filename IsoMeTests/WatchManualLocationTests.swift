import CoreLocation
import SwiftData
import Testing
@testable import IsoMe

@Suite @MainActor
struct WatchManualLocationTests {
    @MainActor
    private final class Manager: WatchManualLocationManaging {
        var authorizationStatus: CLAuthorizationStatus
        weak var delegate: (any CLLocationManagerDelegate)?
        var desiredAccuracy: CLLocationAccuracy = 0
        var authorizationRequests = 0
        var locationRequests = 0
        var stops = 0
        let authorization = AsyncStream<Void>.makeStream()
        let locations = AsyncStream<Void>.makeStream()
        private let source = CLLocationManager()

        init(_ status: CLAuthorizationStatus) { authorizationStatus = status }
        func requestWhenInUseAuthorization() {
            authorizationRequests += 1
            authorization.continuation.yield(())
        }
        func requestLocation() {
            locationRequests += 1
            locations.continuation.yield(())
        }
        func stopUpdatingLocation() { stops += 1 }
        func authorize(_ status: CLAuthorizationStatus) {
            authorizationStatus = status
            delegate?.locationManagerDidChangeAuthorization?(source)
        }
        func deliver(_ location: CLLocation) {
            delegate?.locationManager?(source, didUpdateLocations: [location])
        }
    }

    @Test func firstPermissionGrantCompletesTheOriginalCheckInRequest() async throws {
        let manager = Manager(.notDetermined)
        let request = WatchManualLocation(manager: manager)
        let task = Task { try await request.location() }
        var authorization = manager.authorization.stream.makeAsyncIterator()
        await authorization.next()
        #expect(manager.authorizationRequests == 1)
        #expect(manager.locationRequests == 0)
        manager.authorize(.authorizedWhenInUse)
        #expect(manager.locationRequests == 1)
        // Repeated status callbacks must not create competing one-shot requests.
        manager.authorize(.authorizedWhenInUse)
        #expect(manager.locationRequests == 1)
        let fix = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 18, longitude: -66),
                             altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date())
        manager.deliver(fix)
        let actual = try await task.value
        #expect(actual.location.coordinate.latitude == fix.coordinate.latitude)
        #expect(manager.delegate == nil)
    }

    @Test func cancellationWhilePermissionIsOpenCannotStartALaterFix() async {
        let manager = Manager(.notDetermined)
        let request = WatchManualLocation(manager: manager)
        let task = Task { try await request.location() }
        var authorization = manager.authorization.stream.makeAsyncIterator()
        await authorization.next()
        task.cancel()
        switch await task.result {
        case .success: Issue.record("Canceled permission request returned a location")
        case .failure(let error): #expect(error is CancellationError)
        }
        manager.authorize(.authorizedWhenInUse)
        #expect(manager.locationRequests == 0)
        #expect(manager.delegate == nil)
    }

    @Test func deniedPermissionDoesNotStartLocationOrPromptAgain() async {
        let manager = Manager(.denied)
        do {
            _ = try await WatchManualLocation(manager: manager).location()
            Issue.record("Denied location request unexpectedly succeeded")
        } catch {
            #expect(error is WatchManualLocation.LocationError)
        }
        #expect(manager.authorizationRequests == 0)
        #expect(manager.locationRequests == 0)
    }

    @Test func delayedPermissionUsesTheAuthorizedRequestTimeThroughPhonePersistence() async throws {
        let manager = Manager(.notDetermined)
        var now = Date().addingTimeInterval(-120)
        let originalTap = now
        let request = WatchManualLocation(manager: manager, now: { now })
        let task = Task { try await request.location() }
        var authorization = manager.authorization.stream.makeAsyncIterator()
        await authorization.next()
        now = Date()
        manager.authorize(.authorizedWhenInUse)
        manager.deliver(CLLocation(coordinate: CLLocationCoordinate2D(latitude: 18, longitude: -66),
                                  altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: now))
        let fix = try await task.value
        #expect(fix.requestedAt.timeIntervalSince(originalTap) > 30)
        let command = WatchVisitCommand(deviceID: UUID(), kind: .checkIn, occurredAt: fix.requestedAt,
            name: "Delayed permission fixture", latitude: fix.location.coordinate.latitude,
            longitude: fix.location.coordinate.longitude, locationMeasuredAt: fix.location.timestamp,
            horizontalAccuracy: fix.location.horizontalAccuracy)
        let schema = Schema([Visit.self, SavedPlace.self, WatchVisitCommandReceipt.self])
        let db = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, isStoredInMemoryOnly: true))
        let processor = WatchVisitCommandProcessor(context: ModelContext(db))
        #expect(processor.process(command).outcome == .succeeded)
        #expect(processor.process(command).outcome == .succeeded)
        let reader = ModelContext(db)
        let visits = try reader.fetch(FetchDescriptor<Visit>())
        #expect(visits.count == 1)
        #expect(visits.first?.arrivedAt == fix.requestedAt)
        #expect(try reader.fetchCount(FetchDescriptor<WatchVisitCommandReceipt>()) == 1)
    }
}
