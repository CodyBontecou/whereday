import CoreLocation
import Combine
import Foundation
import WatchConnectivity
import WidgetKit

/// File-backed offline queue for Apple Watch route recordings. The watch writes
/// every accepted point locally first, then opportunistically transfers batches
/// to the iPhone. Batches stay queued until the phone acknowledges a successful
/// import, so recording can happen while the phone is unavailable.
@MainActor
final class WatchLocationSyncClient: NSObject, ObservableObject {
    static let shared = WatchLocationSyncClient()

    @Published private(set) var syncStatus: String?
    let manualVisits = WatchManualVisitClient()
    /// Points recorded on the watch that the iPhone hasn't acknowledged yet.
    @Published private(set) var pendingPointCount = 0
    /// Today's stays from the iPhone, for the glance dial. Persisted in the App Group.
    @Published private(set) var todayContext: WatchTodayContext?
    /// Result of the latest "Mark", e.g. "Marked Mission Office."
    @Published private(set) var markStatus: String?

    /// Marks waiting for an activated session; WatchConnectivity queues them after that.
    private var pendingMarkRequests: [WatchMarkPlaceRequest] = []
    private static let pendingMarkRequestsKey = "watchPendingMarkRequests"

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
        pendingPointCount = state.points.count
        todayContext = WatchTodayContext.load()
        if let data = Self.sharedDefaults.data(forKey: Self.pendingMarkRequestsKey),
           let requests = try? decoder.decode([WatchMarkPlaceRequest].self, from: data) {
            pendingMarkRequests = requests
        }
    }

    private static var sharedDefaults: UserDefaults {
        UserDefaults(suiteName: SharedLocationData.appGroupIdentifier) ?? .standard
    }

    // MARK: - Today context from iPhone

    private func applyApplicationContext(_ context: [String: Any]) {
        if let data = context[WatchVisitTransport.snapshotKey] as? Data {
            manualVisits.handleSnapshot(data)
        }
        guard let data = context[WatchTodayContext.applicationContextKey] as? Data,
              let decoded = try? decoder.decode(WatchTodayContext.self, from: data) else { return }
        // Application contexts can arrive out of order across relaunches; keep the newest.
        if let current = todayContext, current.generatedAt > decoded.generatedAt { return }
        todayContext = decoded
        decoded.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - Mark

    /// Asks the iPhone to check in at the current place. Sent live when the iPhone is
    /// reachable; otherwise queued with `transferUserInfo` until it is.
    func requestMarkPlace(latitude: Double?, longitude: Double?, horizontalAccuracy: Double?) {
        let request = WatchMarkPlaceRequest(
            id: UUID(),
            requestedAt: Date(),
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy
        )
        guard WCSession.isSupported() else {
            markStatus = String(localized: "iPhone sync is unavailable.")
            return
        }
        activate()
        let session = WCSession.default
        guard session.activationState == .activated else {
            pendingMarkRequests.append(request)
            savePendingMarkRequests()
            markStatus = String(localized: "Mark queued for iPhone.")
            return
        }
        send(request, over: session)
    }

    private func send(_ request: WatchMarkPlaceRequest, over session: WCSession) {
        guard let data = try? encoder.encode(request) else { return }
        let message = [WatchLocationSyncTransport.markRequestUserInfoKey: data]
        guard session.isReachable else {
            session.transferUserInfo(message)
            markStatus = String(localized: "Mark queued for iPhone.")
            return
        }
        markStatus = String(localized: "Marking…")
        session.sendMessage(message) { [weak self] reply in
            DispatchQueue.main.async {
                if let ackData = reply[WatchLocationSyncTransport.markAckUserInfoKey] as? Data {
                    self?.handleMarkAckData(ackData)
                } else {
                    self?.markStatus = String(localized: "Marked on iPhone.")
                }
            }
        } errorHandler: { _ in
            // Fall back to the durable queue; the iPhone ignores a repeat of a handled request.
            session.transferUserInfo(message)
            DispatchQueue.main.async { [weak self] in
                self?.markStatus = String(localized: "Mark queued for iPhone.")
            }
        }
    }

    private func flushPendingMarkRequests() {
        guard !pendingMarkRequests.isEmpty, WCSession.isSupported(),
              WCSession.default.activationState == .activated else { return }
        let requests = pendingMarkRequests
        pendingMarkRequests = []
        savePendingMarkRequests()
        for request in requests {
            send(request, over: WCSession.default)
        }
    }

    private func savePendingMarkRequests() {
        if let data = try? encoder.encode(pendingMarkRequests) {
            Self.sharedDefaults.set(data, forKey: Self.pendingMarkRequestsKey)
        }
    }

    private func handleMarkAckData(_ data: Data) {
        guard let ack = try? decoder.decode(WatchMarkPlaceAck.self, from: data) else { return }
        if ack.succeeded {
            if let name = ack.placeName {
                markStatus = String(localized: "Marked \(name).")
            } else {
                markStatus = String(localized: "Marked on iPhone.")
            }
        } else {
            markStatus = ack.errorMessage ?? String(localized: "Couldn’t mark this place.")
        }
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
        manualVisits.sync()
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
        if manualVisits.handleReply(data) { return }
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
        if pendingPointCount != state.points.count {
            pendingPointCount = state.points.count
        }
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
    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        guard activationState == .activated, error == nil else {
            DispatchQueue.main.async { [weak self] in
                self?.syncStatus = String(localized: "Couldn’t connect. Please try again.")
            }
            return
        }
        let receivedContext = session.receivedApplicationContext
        DispatchQueue.main.async { [weak self] in
            self?.applyApplicationContext(receivedContext)
            self?.flushPendingMarkRequests()
            self?.syncPending(force: true)
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        DispatchQueue.main.async { [weak self] in
            self?.applyApplicationContext(applicationContext)
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        guard session.isReachable else { return }
        DispatchQueue.main.async { [weak self] in
            self?.syncPending(force: true)
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessageData messageData: Data) {
        DispatchQueue.main.async { [weak self] in
            self?.handleAckData(messageData)
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        guard message[WatchLocationSyncTransport.syncRequestMessageKey] != nil else {
            replyHandler([:])
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return replyHandler([:]) }
            let waiting = self.pendingPointCount
            self.syncPending(force: true)
            replyHandler([WatchLocationSyncTransport.pendingPointCountReplyKey: waiting])
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        if let data = userInfo[WatchVisitTransport.replyKey] as? Data {
            Task { @MainActor [weak self] in _ = self?.manualVisits.handleReply(data) }
            return
        }
        if let markAck = userInfo[WatchLocationSyncTransport.markAckUserInfoKey] as? Data {
            DispatchQueue.main.async { [weak self] in
                self?.handleMarkAckData(markAck)
            }
            return
        }
        guard let data = userInfo[WatchLocationSyncTransport.ackUserInfoKey] as? Data else { return }
        DispatchQueue.main.async { [weak self] in
            self?.handleAckData(data)
        }
    }
}
