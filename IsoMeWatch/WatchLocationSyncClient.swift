import CoreLocation
import Combine
import Foundation
import WatchConnectivity

/// File-backed offline queue for Apple Watch route recordings. The watch writes
/// every accepted point locally first, then opportunistically transfers batches
/// to the iPhone. Batches stay queued until the phone acknowledges a successful
/// import, so recording can happen while the phone is unavailable.
final class WatchLocationSyncClient: NSObject, ObservableObject {
    static let shared = WatchLocationSyncClient()

    @Published private(set) var syncStatus: String?

    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let stateURL: URL
    private var state: WatchLocationSyncState

    private let maxPointsPerPayload = 500
    private let liveBatchPointInterval = 25
    private let minimumTransferInterval: TimeInterval = 60

    private override init() {
        stateURL = Self.makeStateURL()
        state = Self.loadState(from: stateURL) ?? WatchLocationSyncState(deviceID: UUID())
        super.init()
    }

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        if session.activationState != .activated {
            session.activate()
        }
    }

    @discardableResult
    func ensureActiveSession(startedAt: Date = Date()) -> UUID {
        if let activeSessionID = state.activeSessionID,
           state.sessions.contains(where: { $0.id == activeSessionID }) {
            return activeSessionID
        }

        let session = WatchLocationSyncSession(
            id: UUID(),
            startedAt: startedAt,
            endedAt: nil
        )
        state.sessions.append(session)
        state.activeSessionID = session.id
        saveState()
        syncPending(force: true)
        return session.id
    }

    func endActiveSession(endedAt: Date = Date()) {
        state.endVisit(at: endedAt)
        saveState()
        guard let activeSessionID = state.activeSessionID else {
            syncPending(force: true)
            return
        }

        if let index = state.sessions.firstIndex(where: { $0.id == activeSessionID }) {
            state.sessions[index].endedAt = max(state.sessions[index].startedAt, endedAt)
        }
        state.activeSessionID = nil
        saveState()
        syncPending(force: true)
    }

    @discardableResult
    func recordLocation(_ location: CLLocation) -> Bool {
        let sessionID = ensureActiveSession(startedAt: location.timestamp)
        let point = WatchLocationSyncPoint(
            id: UUID(),
            sessionID: sessionID,
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: location.timestamp,
            altitude: location.altitude,
            speed: location.speed >= 0 ? location.speed : nil,
            horizontalAccuracy: location.horizontalAccuracy
        )

        let createdVisit = state.recordPoint(point)
        saveState()

        if createdVisit || state.points.count % liveBatchPointInterval == 0 {
            // Send place changes immediately, even when the session-start
            // payload was just sent and no more movement follows.
            syncPending(force: createdVisit)
        }
        return createdVisit
    }

    func syncPending(force: Bool = false) {
        guard !state.sessions.isEmpty || !state.points.isEmpty || !state.visits.isEmpty else {
            syncStatus = String(localized: "Nothing to sync.")
            return
        }
        guard WCSession.isSupported() else {
            syncStatus = String(localized: "iPhone sync is unavailable.")
            return
        }

        activate()
        let session = WCSession.default
        guard session.activationState == .activated else {
            syncStatus = String(localized: "Connecting to iPhone…")
            return
        }

        if !force,
           let lastTransferDate = state.lastTransferDate,
           Date().timeIntervalSince(lastTransferDate) < minimumTransferInterval {
            return
        }

        guard let payload = state.makePayload(maxPoints: maxPointsPerPayload) else { return }
        guard let payloadData = try? encoder.encode(payload) else { return }

        state.registerTransfer(payload)
        saveState()

        syncStatus = String(localized: "Sync queued. Open Whereday on your iPhone.")
        session.transferUserInfo([
            WatchLocationSyncTransport.payloadUserInfoKey: payloadData
        ])

        if session.isReachable {
            session.sendMessageData(payloadData) { [weak self] replyData in
                DispatchQueue.main.async {
                    self?.handleAckData(replyData)
                }
            } errorHandler: { _ in
                // Durable transferUserInfo above remains queued for later delivery.
            }
        }
    }

    private func handleAckData(_ data: Data) {
        guard let ack = try? decoder.decode(WatchLocationSyncAck.self, from: data) else { return }
        handleAck(ack)
    }

    private func handleAck(_ ack: WatchLocationSyncAck) {
        guard ack.deviceID == state.deviceID else { return }
        guard ack.succeeded else {
            syncStatus = String(localized: "Sync failed. Please try again.")
            return
        }
        guard let transfer = state.pendingTransfers.first(where: { $0.sequence == ack.sequence }) else {
            state.lastAckedSequence = max(state.lastAckedSequence ?? ack.sequence, ack.sequence)
            saveState()
            return
        }

        state.acknowledge(transfer)
        saveState()

        if state.needsFollowup(after: transfer) {
            syncPending(force: true)
        } else {
            syncStatus = String(localized: "Synced with iPhone.")
        }
    }

    private func saveState() {
        do {
            try FileManager.default.createDirectory(
                at: stateURL.deletingLastPathComponent(),
                withIntermediateDirectories: true,
                attributes: nil
            )
            let data = try encoder.encode(state)
            try data.write(to: stateURL, options: [.atomic])
        } catch {
            // Keep the in-memory queue alive for this process; retry on the next save.
        }
    }

    private static func loadState(from url: URL) -> WatchLocationSyncState? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(WatchLocationSyncState.self, from: data)
    }

    private static func makeStateURL() -> URL {
        let baseURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: SharedLocationData.appGroupIdentifier
        ) ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!

        return baseURL
            .appendingPathComponent("WatchLocationSync", isDirectory: true)
            .appendingPathComponent("state.json")
    }
}

extension WatchLocationSyncClient: WCSessionDelegate {
    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        guard activationState == .activated, error == nil else {
            DispatchQueue.main.async { [weak self] in
                self?.syncStatus = String(localized: "Couldn’t connect. Please try again.")
            }
            return
        }
        DispatchQueue.main.async { [weak self] in
            self?.syncPending(force: true)
        }
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        guard session.isReachable else { return }
        DispatchQueue.main.async { [weak self] in
            self?.syncPending(force: true)
        }
    }

    func session(_ session: WCSession, didReceiveMessageData messageData: Data) {
        DispatchQueue.main.async { [weak self] in
            self?.handleAckData(messageData)
        }
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let data = userInfo[WatchLocationSyncTransport.ackUserInfoKey] as? Data else { return }
        DispatchQueue.main.async { [weak self] in
            self?.handleAckData(data)
        }
    }
}
