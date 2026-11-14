import Foundation
import SwiftData
import WatchConnectivity
import WidgetKit

@MainActor
final class WatchLocationSyncReceiver: NSObject, ObservableObject {
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var modelContext: ModelContext?
    private var isActivated = false

    func configure(modelContainer: ModelContainer, activatesConnectivity: Bool = true) {
        modelContext = modelContainer.mainContext
        #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--watch-sync-replay") {
            replaySimulatorSnapshot()
            return
        }
        #endif
        if activatesConnectivity { activate() }
    }

    #if DEBUG && targetEnvironment(simulator)
    /// Explicit opt-in, isolated simulator store; never enabled on a phone or in Release.
    private func replaySimulatorSnapshot() {
        let directory = URL.documentsDirectory.appendingPathComponent("WatchSyncReplay")
        let reportURL = directory.appendingPathComponent("report.json")
        do {
            let preparedURL = directory.appendingPathComponent("prepared-snapshot.json")
            let sourceURL = FileManager.default.fileExists(atPath: preparedURL.path)
                ? preparedURL : directory.appendingPathComponent("snapshot.json")
            var queue = try decoder.decode(WatchLocationSyncState.self, from: Data(contentsOf: sourceURL))
            // Persist migration-created visit IDs before the first delivery,
            // just as the Watch saves its upgraded queue before transmission.
            // Later launches replay that same snapshot to test duplicate delivery.
            try encoder.encode(queue).write(to: preparedURL, options: .atomic)
            var batches = 0
            while let payload = queue.makePayload() {
                queue.registerTransfer(payload)
                guard let ack = importPayloadData(try encoder.encode(payload)), ack.succeeded,
                      let transfer = queue.pendingTransfers.first(where: { $0.sequence == ack.sequence }) else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                queue.acknowledge(transfer)
                batches += 1
                if !queue.needsFollowup(after: transfer) { break }
                guard batches < 10_000 else { throw CocoaError(.fileReadTooLarge) }
            }
            try encoder.encode(queue).write(to: directory.appendingPathComponent("remaining-state.json"), options: .atomic)
            guard let modelContext else { throw CocoaError(.fileReadUnknown) }
            let report: [String: Any] = [
                "succeeded": true, "batches": batches,
                "points": try modelContext.fetchCount(FetchDescriptor<LocationPoint>()),
                "visits": try modelContext.fetchCount(FetchDescriptor<Visit>()),
                "sessions": try modelContext.fetchCount(FetchDescriptor<RecordingSession>()),
                "queuedPoints": queue.points.count
            ]
            try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys, .prettyPrinted])
                .write(to: reportURL, options: .atomic)
        } catch {
            let report: [String: Any] = ["succeeded": false, "error": error.localizedDescription]
            if let data = try? JSONSerialization.data(withJSONObject: report) {
                try? data.write(to: reportURL, options: .atomic)
            }
        }
    }
    #endif

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
        isActivated = true
    }

    // MARK: - Today context → Apple Watch

    /// Latest context handed to `publishTodayContext`, kept until a session can deliver it.
    private static var latestTodayContext: WatchTodayContext?
    /// Last context the session accepted, to skip identical application-context updates.
    private static var lastSentTodayContext: WatchTodayContext?

    /// Sends today's stays to the watch through the WatchConnectivity application context,
    /// which keeps only the newest value and delivers it when the watch app next runs.
    /// Asks a reachable watch to send its waiting points now.
    /// Completes with the number of points that were waiting, or nil when the watch can't be reached.
    static func requestWatchSync(completion: @escaping @MainActor (Int?) -> Void) {
        guard WCSession.isSupported() else {
            Task { @MainActor in completion(nil) }
            return
        }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled, session.isReachable else {
            Task { @MainActor in completion(nil) }
            return
        }
        session.sendMessage(
            [WatchLocationSyncTransport.syncRequestMessageKey: true],
            replyHandler: { reply in
                let count = reply[WatchLocationSyncTransport.pendingPointCountReplyKey] as? Int
                Task { @MainActor in completion(count) }
            },
            errorHandler: { _ in
                Task { @MainActor in completion(nil) }
            }
        )
    }

    static func publishTodayContext(_ context: WatchTodayContext) {
        latestTodayContext = context
        flushTodayContext()
    }

    fileprivate static func flushTodayContext() {
        guard let context = latestTodayContext, WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated,
              session.isPaired,
              session.isWatchAppInstalled else { return }
        if let lastSent = lastSentTodayContext,
           lastSent.isSameDay(as: context.generatedAt),
           Self.contentEquals(lastSent, context) {
            return
        }
        guard let data = try? JSONEncoder().encode(context) else { return }
        do {
            try session.updateApplicationContext([WatchTodayContext.applicationContextKey: data])
            lastSentTodayContext = context
        } catch {
            // Keep `latestTodayContext`; the next publish or activation retries.
        }
    }

    private static func contentEquals(_ lhs: WatchTodayContext, _ rhs: WatchTodayContext) -> Bool {
        var lhs = lhs
        lhs.generatedAt = rhs.generatedAt
        return lhs == rhs
    }

    // MARK: - "Mark" from Apple Watch

    private static let handledMarkRequestsKey = "watchHandledMarkRequestIDs"

    /// A queued (transferUserInfo) mark: process it and send the result back the same way.
    private func handleMarkRequest(_ data: Data) async {
        guard let ack = await processMarkRequest(data),
              WCSession.isSupported(), WCSession.default.activationState == .activated,
              let ackData = try? encoder.encode(ack) else { return }
        WCSession.default.transferUserInfo([WatchLocationSyncTransport.markAckUserInfoKey: ackData])
    }

    /// Checks in once per request ID; nil for an undecodable or already-handled request.
    private func processMarkRequest(_ data: Data) async -> WatchMarkPlaceAck? {
        guard let request = try? decoder.decode(WatchMarkPlaceRequest.self, from: data) else { return nil }
        let defaults = UserDefaults.standard
        var handled = defaults.stringArray(forKey: Self.handledMarkRequestsKey) ?? []
        // The watch may send a message and later fall back to a queued transfer.
        guard !handled.contains(request.id.uuidString) else { return nil }
        handled.append(request.id.uuidString)
        defaults.set(Array(handled.suffix(50)), forKey: Self.handledMarkRequestsKey)

        let ack: WatchMarkPlaceAck
        do {
            let visit = try await MarkPlaceSupport.markCurrentPlace(
                name: nil,
                latitude: request.latitude,
                longitude: request.longitude
            )
            ack = WatchMarkPlaceAck(requestID: request.id, succeeded: true, placeName: visit.displayName)
        } catch {
            let message = (error as? CustomLocalizedStringResourceConvertible)
                .map { String(localized: $0.localizedStringResource) } ?? error.localizedDescription
            ack = WatchMarkPlaceAck(requestID: request.id, succeeded: false, errorMessage: message)
        }
        return ack
    }

    @discardableResult
    func importPayload(_ payload: WatchLocationSyncPayload) -> WatchLocationSyncAck {
        guard payload.version == WatchLocationSyncPayload.currentVersion else {
            return WatchLocationSyncAck(
                deviceID: payload.deviceID,
                sequence: payload.sequence,
                succeeded: false,
                errorMessage: String(localized: "Unsupported watch sync payload version \(payload.version).")
            )
        }

        guard let modelContext else {
            return WatchLocationSyncAck(
                deviceID: payload.deviceID,
                sequence: payload.sequence,
                succeeded: false,
                errorMessage: String(localized: "The iPhone data store is not ready yet.")
            )
        }

        do {
            var updatedSession = false
            var importedSessionCount = 0
            var importedPointCount = 0
            var importedVisitCount = 0
            var updatedVisit = false

            for sessionDTO in payload.sessions {
                if let session = try recordingSession(withID: sessionDTO.id, in: modelContext) {
                    if session.startedAt > sessionDTO.startedAt {
                        session.startedAt = sessionDTO.startedAt
                        updatedSession = true
                    }
                    if let endedAt = sessionDTO.endedAt {
                        let normalizedEnd = max(session.startedAt, endedAt)
                        if let currentEnd = session.endedAt {
                            if normalizedEnd > currentEnd {
                                session.endedAt = normalizedEnd
                                updatedSession = true
                            }
                        } else {
                            session.endedAt = normalizedEnd
                            updatedSession = true
                        }
                    }
                } else {
                    modelContext.insert(
                        RecordingSession(
                            id: sessionDTO.id,
                            startedAt: sessionDTO.startedAt,
                            endedAt: sessionDTO.endedAt
                        )
                    )
                    importedSessionCount += 1
                }
            }

            for pointDTO in payload.points {
                guard try locationPoint(withID: pointDTO.id, in: modelContext) == nil else { continue }
                modelContext.insert(
                    LocationPoint(
                        id: pointDTO.id,
                        latitude: pointDTO.latitude,
                        longitude: pointDTO.longitude,
                        timestamp: pointDTO.timestamp,
                        altitude: pointDTO.altitude,
                        speed: pointDTO.speed,
                        horizontalAccuracy: pointDTO.horizontalAccuracy
                    )
                )
                importedPointCount += 1
            }

            for visitDTO in payload.visits {
                if let visit = try visit(withID: visitDTO.id, in: modelContext) {
                    if visit.arrivedAt > visitDTO.arrivedAt {
                        visit.arrivedAt = visitDTO.arrivedAt
                        updatedVisit = true
                    }
                    if let departedAt = visitDTO.departedAt {
                        let normalizedDeparture = max(visit.arrivedAt, departedAt)
                        if let currentDeparture = visit.departedAt {
                            if normalizedDeparture > currentDeparture {
                                visit.departedAt = normalizedDeparture
                                updatedVisit = true
                            }
                        } else {
                            visit.departedAt = normalizedDeparture
                            updatedVisit = true
                        }
                    }
                } else {
                    modelContext.insert(
                        Visit(
                            id: visitDTO.id,
                            latitude: visitDTO.latitude,
                            longitude: visitDTO.longitude,
                            arrivedAt: visitDTO.arrivedAt,
                            departedAt: visitDTO.departedAt,
                            geocodingCompleted: false
                        )
                    )
                    importedVisitCount += 1
                }
            }

            try modelContext.save()

            if importedSessionCount > 0 || importedPointCount > 0 || importedVisitCount > 0 || updatedVisit || updatedSession {
                LocationManager.shared?.syncDataToWatch()
                WidgetCenter.shared.reloadAllTimelines()
                NotificationCenter.default.post(name: .watchLocationDataImported, object: nil)
                LogManager.shared.info(
                    "Imported from Apple Watch: \(importedPointCount) points, \(importedVisitCount) visits",
                    category: .watch
                )
            }

            return WatchLocationSyncAck(
                deviceID: payload.deviceID,
                sequence: payload.sequence,
                importedSessionCount: importedSessionCount,
                importedPointCount: importedPointCount,
                importedVisitCount: importedVisitCount
            )
        } catch {
            return WatchLocationSyncAck(
                deviceID: payload.deviceID,
                sequence: payload.sequence,
                succeeded: false,
                errorMessage: error.localizedDescription
            )
        }
    }

    func importPayloadData(_ data: Data) -> WatchLocationSyncAck? {
        guard let payload = try? decoder.decode(WatchLocationSyncPayload.self, from: data) else { return nil }
        return importPayload(payload)
    }

    private func sendAck(_ ack: WatchLocationSyncAck) {
        guard ack.succeeded else { return }
        guard WCSession.isSupported(), WCSession.default.activationState == .activated else { return }
        guard let data = try? encoder.encode(ack) else { return }

        WCSession.default.transferUserInfo([
            WatchLocationSyncTransport.ackUserInfoKey: data
        ])

        if WCSession.default.isReachable {
            WCSession.default.sendMessageData(data, replyHandler: nil, errorHandler: nil)
        }
    }

    private func recordingSession(withID id: UUID, in context: ModelContext) throws -> RecordingSession? {
        let predicate = #Predicate<RecordingSession> { session in
            session.id == id
        }
        var descriptor = FetchDescriptor<RecordingSession>(predicate: predicate)
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first
    }

    private func locationPoint(withID id: UUID, in context: ModelContext) throws -> LocationPoint? {
        let predicate = #Predicate<LocationPoint> { point in
            point.id == id
        }
        var descriptor = FetchDescriptor<LocationPoint>(predicate: predicate)
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first
    }

    private func visit(withID id: UUID, in context: ModelContext) throws -> Visit? {
        let predicate = #Predicate<Visit> { visit in
            visit.id == id
        }
        var descriptor = FetchDescriptor<Visit>(predicate: predicate)
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first
    }
}

extension WatchLocationSyncReceiver: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        // Receiving queued userInfo after activation drives imports; push any
        // today context published before the session was ready.
        guard activationState == .activated else { return }
        Task { @MainActor in
            WatchLocationSyncReceiver.flushTodayContext()
            SharedRecordingControl.shared.flush()
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in SharedRecordingControl.shared.flush() }
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in
            WatchLocationSyncReceiver.flushTodayContext()
        }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    nonisolated func session(_ session: WCSession, didReceiveMessageData messageData: Data) {
        Task { @MainActor [weak self] in
            guard let ack = self?.importPayloadData(messageData) else { return }
            self?.sendAck(ack)
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void) {
        Task { @MainActor [weak self] in
            guard let self,
                  let ack = importPayloadData(messageData),
                  let ackData = try? encoder.encode(ack) else { return }
            replyHandler(ackData)
            sendAck(ack)
        }
    }

    /// "Mark" sent live while the iPhone is reachable; the reply carries the result.
    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        if message[SharedRecordingControl.messageKey] != nil || message[SharedRecordingControl.ackKey] != nil {
            Task { @MainActor in replyHandler(SharedRecordingControl.shared.receive(message)) }
            return
        }
        guard let markData = message[WatchLocationSyncTransport.markRequestUserInfoKey] as? Data else {
            replyHandler([:])
            return
        }
        Task { @MainActor [weak self] in
            guard let self,
                  let ack = await processMarkRequest(markData),
                  let ackData = try? encoder.encode(ack) else {
                replyHandler([:])
                return
            }
            replyHandler([WatchLocationSyncTransport.markAckUserInfoKey: ackData])
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        if userInfo[SharedRecordingControl.messageKey] != nil || userInfo[SharedRecordingControl.ackKey] != nil {
            Task { @MainActor in SharedRecordingControl.shared.receive(userInfo) }
            return
        }
        if let markData = userInfo[WatchLocationSyncTransport.markRequestUserInfoKey] as? Data {
            Task { @MainActor [weak self] in
                await self?.handleMarkRequest(markData)
            }
            return
        }
        guard let data = userInfo[WatchLocationSyncTransport.payloadUserInfoKey] as? Data else { return }
        Task { @MainActor [weak self] in
            guard let ack = self?.importPayloadData(data) else { return }
            self?.sendAck(ack)
        }
    }
}

extension Notification.Name {
    static let watchLocationDataImported = Notification.Name("watchLocationDataImported")
}
