import Foundation
import Testing
import SwiftData
@testable import IsoMe

@Suite(.serialized) @MainActor
struct WatchManualVisitTests {
    private func expectEnqueue(_ value: Bool) { #expect(value) }
    private let start = Date(timeIntervalSince1970: 1_700_000_000)
    private func container() throws -> ModelContainer {
        let schema = Schema([Visit.self, SavedPlace.self, WatchVisitCommandReceipt.self])
        return try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, isStoredInMemoryOnly: true))
    }
    private func checkIn(device: UUID = UUID()) -> WatchVisitCommand {
        WatchVisitCommand(deviceID: device, kind: .checkIn, occurredAt: start, name: "Cafe",
            latitude: 18, longitude: -67, locationMeasuredAt: start, horizontalAccuracy: 10)
    }
    private func result(_ command: WatchVisitCommand, _ outcome: WatchVisitCommandResult.Outcome) -> WatchVisitReply {
        WatchVisitReply(result: WatchVisitCommandResult(commandID: command.id, deviceID: command.deviceID,
            outcome: outcome, visitID: command.id, message: "Result"))
    }

    @Test func replayAfterContextRestartCreatesOneVisitAndOneReceipt() throws {
        let db = try container(), command = checkIn()
        let first = WatchVisitCommandProcessor(context: ModelContext(db)).process(command)
        let reader = ModelContext(db)
        let replay = WatchVisitCommandProcessor(context: reader).process(command)
        #expect(first.outcome == .succeeded)
        #expect(first == replay)
        #expect(try reader.fetchCount(FetchDescriptor<Visit>()) == 1)
        #expect(try reader.fetchCount(FetchDescriptor<WatchVisitCommandReceipt>()) == 1)
        let visit = try #require(reader.fetch(FetchDescriptor<Visit>()).first)
        #expect(visit.id == command.id)
        #expect(visit.source == .manual && visit.confirmationStatus == .confirmed)
        #expect(visit.arrivedAt == start)
    }

    @Test func checkoutReplayDoesNotChangeDepartureOrCorrectionMetadata() throws {
        let db = try container(), context = ModelContext(db)
        let processor = WatchVisitCommandProcessor(context: context), command = checkIn()
        #expect(processor.process(command).outcome == .succeeded)
        let visit = try #require(context.fetch(FetchDescriptor<Visit>()).first)
        visit.originalLatitude = 17; visit.detectedLatitude = 16
        visit.originalLocationName = "Original"; visit.detectedLocationName = "Detected"
        visit.confirmationStatus = .corrected
        try context.save()
        let checkout = WatchVisitCommand(deviceID: command.deviceID, kind: .checkOut,
            occurredAt: start.addingTimeInterval(60), visitID: visit.id)
        let response = processor.process(checkout)
        let stored = try #require(ModelContext(db).fetch(FetchDescriptor<Visit>()).first)
        let updated = stored.updatedAt
        #expect(response.outcome == .succeeded)
        #expect(WatchVisitCommandProcessor(context: ModelContext(db)).process(checkout) == response)
        #expect(stored.departedAt == checkout.occurredAt && stored.updatedAt == updated)
        #expect(stored.originalLatitude == 17 && stored.detectedLatitude == 16)
        #expect(stored.originalLocationName == "Original" && stored.detectedLocationName == "Detected")
        #expect(stored.confirmationStatus == .corrected)
        var repeated = checkout; repeated.id = UUID(); repeated.occurredAt = start.addingTimeInterval(120)
        #expect(processor.process(repeated).outcome == .failed)
        #expect(stored.departedAt == checkout.occurredAt)
    }

    @Test func reusedIDWithChangedPayloadCannotMutateVisit() throws {
        let db = try container(), context = ModelContext(db), command = checkIn()
        let processor = WatchVisitCommandProcessor(context: context)
        #expect(processor.process(command).outcome == .succeeded)
        var changed = command; changed.name = "Changed"; changed.allowOverlap = true
        #expect(processor.process(changed).outcome == .failed)
        #expect(try context.fetchCount(FetchDescriptor<Visit>()) == 1)
        #expect(try context.fetch(FetchDescriptor<Visit>()).first?.locationName == "Cafe")
    }

    @Test func overlapRequiresNewExplicitOverrideAndFailedReplayStaysFailed() throws {
        let db = try container(), context = ModelContext(db)
        let processor = WatchVisitCommandProcessor(context: context)
        #expect(processor.process(checkIn()).outcome == .succeeded)
        var second = checkIn(); second.occurredAt = start.addingTimeInterval(60)
        second.locationMeasuredAt = second.occurredAt
        let failed = processor.process(second)
        #expect(failed.outcome == .failed)
        let existing = try #require(context.fetch(FetchDescriptor<Visit>()).first)
        existing.departedAt = start.addingTimeInterval(30); try context.save()
        #expect(processor.process(second) == failed)
        // A new command is a new explicit user decision, never an edited retry.
        second.id = UUID(); second.allowOverlap = true
        #expect(processor.process(second).outcome == .succeeded)
        #expect(try context.fetchCount(FetchDescriptor<Visit>()) == 2)
    }

    @Test func offlineLocationValidatedAtTapTimeRatherThanDelivery() throws {
        let db = try container(), context = ModelContext(db)
        #expect(WatchVisitCommandProcessor(context: context).process(checkIn()).outcome == .succeeded)
    }

    @Test(arguments: ["missing", "stale", "inaccurate", "invalidCoordinate", "future", "version"])
    func invalidInputsPersistFailureWithoutCreatingVisit(_ reason: String) throws {
        let db = try container(), context = ModelContext(db)
        var command = checkIn()
        switch reason {
        case "missing": command.latitude = nil
        case "stale": command.locationMeasuredAt = start.addingTimeInterval(-31)
        case "inaccurate": command.horizontalAccuracy = 101
        case "invalidCoordinate": command.latitude = 91
        case "future": command.occurredAt = Date().addingTimeInterval(3600)
        default: command.version = 99
        }
        let processor = WatchVisitCommandProcessor(context: context)
        let failed = processor.process(command)
        #expect(failed.outcome == .failed)
        #expect(processor.process(command) == failed)
        #expect(try context.fetchCount(FetchDescriptor<Visit>()) == 0)
        #expect(try context.fetchCount(FetchDescriptor<WatchVisitCommandReceipt>()) == 1)
    }

    @Test func savedPlaceDoesNotRequireGPSAndMissingPlaceFails() throws {
        let db = try container(), context = ModelContext(db)
        let place = SavedPlace(name: "Library", latitude: 18, longitude: -67, address: "Main Street")
        context.insert(place); try context.save()
        let command = WatchVisitCommand(deviceID: UUID(), kind: .checkIn, occurredAt: start, savedPlaceID: place.id)
        let processor = WatchVisitCommandProcessor(context: context)
        #expect(processor.process(command).outcome == .succeeded)
        #expect(try context.fetch(FetchDescriptor<Visit>()).first?.address == "Main Street")
        var missing = command; missing.id = UUID(); missing.savedPlaceID = UUID()
        #expect(processor.process(missing).outcome == .failed)
    }

    @Test func checkoutRequiresUnambiguousOpenManualVisitAndValidTime() throws {
        let db = try container(), context = ModelContext(db)
        let processor = WatchVisitCommandProcessor(context: context)
        var checkout = WatchVisitCommand(deviceID: UUID(), kind: .checkOut, occurredAt: start.addingTimeInterval(60))
        #expect(processor.process(checkout).outcome == .failed)
        let first = checkIn(); #expect(processor.process(first).outcome == .succeeded)
        var second = checkIn(); second.allowOverlap = true
        #expect(processor.process(second).outcome == .succeeded)
        checkout.id = UUID()
        #expect(processor.process(checkout).outcome == .failed)
        checkout.id = UUID(); checkout.visitID = first.id; checkout.occurredAt = start.addingTimeInterval(-1)
        #expect(processor.process(checkout).outcome == .failed)
        let automatic = Visit(latitude: 18, longitude: -67, arrivedAt: start)
        context.insert(automatic); try context.save()
        checkout.id = UUID(); checkout.visitID = automatic.id; checkout.occurredAt = start.addingTimeInterval(60)
        #expect(processor.process(checkout).outcome == .failed)
        #expect(automatic.departedAt == nil)
    }

    @Test func outOfOrderDependentCheckoutIsRetryableUntilCheckInCommits() throws {
        let db = try container(), context = ModelContext(db), command = checkIn()
        let processor = WatchVisitCommandProcessor(context: context)
        let checkout = WatchVisitCommand(deviceID: command.deviceID, kind: .checkOut,
            occurredAt: start.addingTimeInterval(60), visitID: command.id, dependsOnCommandID: command.id)
        #expect(processor.process(checkout).outcome == .retryable)
        #expect(try context.fetchCount(FetchDescriptor<WatchVisitCommandReceipt>()) == 0)
        #expect(processor.process(command).outcome == .succeeded)
        #expect(processor.process(checkout).outcome == .succeeded)
    }

    @Test func offlineQueueRoundTripReplaysInOrderWithDuplicateAcknowledgments() throws {
        var queue = WatchVisitCommandQueue()
        let command = checkIn(device: queue.deviceID)
        let checkout = WatchVisitCommand(deviceID: queue.deviceID, kind: .checkOut,
            occurredAt: start.addingTimeInterval(60), visitID: command.id, dependsOnCommandID: command.id)
        expectEnqueue((queue.enqueue(command))); expectEnqueue((queue.enqueue(checkout)))
        expectEnqueue((!queue.enqueue(command))); expectEnqueue((!queue.enqueue(checkIn(device: queue.deviceID))))
        expectEnqueue((!queue.enqueue(WatchVisitCommand(deviceID: queue.deviceID, kind: .checkOut,
            occurredAt: checkout.occurredAt, visitID: command.id))))
        queue = try JSONDecoder().decode(WatchVisitCommandQueue.self, from: JSONEncoder().encode(queue))
        #expect(queue.nextCommand == command)
        let db = try container(), context = ModelContext(db)
        let processor = WatchVisitCommandProcessor(context: context)
        while let next = queue.nextCommand {
            let response = WatchVisitReply(result: processor.process(next), snapshot: try processor.snapshot())
            #expect(response.result?.outcome == .succeeded)
            queue.receive(response); queue.receive(response)
        }
        #expect(!queue.hasPendingCommands)
        #expect(try context.fetchCount(FetchDescriptor<Visit>()) == 1)
        #expect(try context.fetch(FetchDescriptor<Visit>()).first?.departedAt == checkout.occurredAt)
        #expect(queue.snapshot?.status.openManualVisitCount == 0)
    }

    @Test func failedCheckInFailsDependentCheckoutAndRetryableResultStaysPending() {
        var queue = WatchVisitCommandQueue()
        let command = checkIn(device: queue.deviceID)
        expectEnqueue((queue.enqueue(command)))
        expectEnqueue((queue.enqueue(WatchVisitCommand(deviceID: queue.deviceID, kind: .checkOut,
            occurredAt: start.addingTimeInterval(60), visitID: command.id, dependsOnCommandID: command.id))))
        queue.receive(result(command, .retryable))
        #expect(queue.nextCommand == command)
        var foreign = command; foreign.deviceID = UUID()
        queue.receive(result(foreign, .succeeded))
        #expect(queue.nextCommand == command)
        queue.receive(result(command, .failed))
        #expect(!queue.hasPendingCommands)
        #expect(queue.entries.allSatisfy { $0.result?.outcome == .failed })
        queue.receive(result(command, .succeeded))
        #expect(queue.entries[0].result?.outcome == .failed)
    }

    @Test func legacySharedPayloadDecodesAndNewStatusRoundTripsWithoutChangingTracking() throws {
        let legacy = Data(#"{"isTrackingEnabled":true,"todayVisitsCount":3,"todayDistanceMeters":12,"todayPointsCount":4}"#.utf8)
        var shared = try JSONDecoder().decode(SharedLocationData.self, from: legacy)
        #expect(shared.manualVisitStatus == nil)
        shared.manualVisitStatus = WatchManualVisitStatus(generatedAt: start, openVisits: [], openManualVisitCount: 0)
        let decoded = try JSONDecoder().decode(SharedLocationData.self, from: JSONEncoder().encode(shared))
        #expect(decoded.manualVisitStatus == shared.manualVisitStatus)
        #expect(decoded.isTrackingEnabled && decoded.todayVisitsCount == 3)
        #expect(decoded.todayDistanceMeters == 12 && decoded.todayPointsCount == 4)
    }

    @Test func olderSnapshotCannotOverwriteFreshPhoneStatus() {
        var queue = WatchVisitCommandQueue()
        let newer = WatchVisitSnapshot(status: WatchManualVisitStatus(generatedAt: start, openVisits: [], openManualVisitCount: 0), savedPlaces: [])
        var older = newer; older.status.generatedAt = start.addingTimeInterval(-1); older.status.openManualVisitCount = 99
        queue.receive(WatchVisitReply(snapshot: newer)); queue.receive(WatchVisitReply(snapshot: older))
        #expect(queue.snapshot == newer)
    }

    @Test func readOnlyStoreNeverAcknowledgesAnUnsavedVisit() throws {
        let schema = Schema([Visit.self, SavedPlace.self, WatchVisitCommandReceipt.self])
        let directory = URL.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("readonly.store")
        do {
            let writable = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url))
            try ModelContext(writable).save()
        }
        let db = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema,
            url: url, allowsSave: false))
        let context = ModelContext(db), command = checkIn()
        let processor = WatchVisitCommandProcessor(context: context)
        #expect(processor.process(command).outcome == .retryable)
        #expect(processor.process(command).outcome == .retryable)
        #expect(try context.fetchCount(FetchDescriptor<Visit>()) == 0)
        #expect(try context.fetchCount(FetchDescriptor<WatchVisitCommandReceipt>()) == 0)
    }

    @Test func existingStoreMigratesWithoutLosingVisitMetadata() throws {
        let directory = URL.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("journal.store")
        let visitID = UUID()
        do {
            let legacy = Schema([Visit.self, SavedPlace.self])
            let db = try ModelContainer(for: legacy, configurations: ModelConfiguration(schema: legacy, url: url))
            let context = ModelContext(db)
            let visit = Visit(id: visitID, latitude: 18, longitude: -67, arrivedAt: start,
                locationName: "Existing", source: .manual, confirmationStatus: .corrected,
                originalLatitude: 17, detectedLocationName: "Detected")
            context.insert(visit); try context.save()
        }
        let schema = Schema([Visit.self, SavedPlace.self, WatchVisitCommandReceipt.self])
        let upgraded = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url))
        let context = ModelContext(upgraded)
        let visit = try #require(context.fetch(FetchDescriptor<Visit>()).first)
        #expect(visit.id == visitID && visit.originalLatitude == 17)
        #expect(visit.confirmationStatus == .corrected && visit.detectedLocationName == "Detected")
        let checkout = WatchVisitCommand(deviceID: UUID(), kind: .checkOut,
            occurredAt: start.addingTimeInterval(60), visitID: visitID)
        #expect(WatchVisitCommandProcessor(context: context).process(checkout).outcome == .succeeded)
        #expect(try context.fetchCount(FetchDescriptor<WatchVisitCommandReceipt>()) == 1)
    }

    @Test func checkoutRefreshesAnAlreadyLoadedPhoneVisit() async throws {
        let db = try container(), main = db.mainContext
        let visit = Visit(latitude: 18, longitude: -67, arrivedAt: start, source: .manual)
        main.insert(visit); try main.save()
        let receiver = WatchLocationSyncReceiver()
        receiver.configure(modelContainer: db, activatesConnectivity: false)
        let checkout = WatchVisitCommand(deviceID: UUID(), kind: .checkOut,
            occurredAt: start.addingTimeInterval(60), visitID: visit.id)
        let data = try #require(receiver.manualVisitReplyData(JSONEncoder().encode(checkout)))
        #expect(try JSONDecoder().decode(WatchVisitReply.self, from: data).result?.outcome == .succeeded)
        await Task.yield()
        let reloaded = try #require(main.fetch(FetchDescriptor<Visit>()).first)
        #expect(reloaded.departedAt == checkout.occurredAt)
    }

    @Test func receiverDispatchesManualCommandAndReturnsCommittedResult() throws {
        let db = try container(), receiver = WatchLocationSyncReceiver()
        receiver.configure(modelContainer: db, activatesConnectivity: false)
        let command = checkIn(), encoded = try JSONEncoder().encode(command)
        let reply = try #require(receiver.manualVisitReplyData(encoded))
        let decoded = try JSONDecoder().decode(WatchVisitReply.self, from: reply)
        #expect(decoded.result?.outcome == .succeeded)
        #expect(decoded.snapshot?.status.openVisits.first?.id == command.id)
        #expect(receiver.manualVisitReplyData(Data("{}".utf8)) == nil)
    }
}
