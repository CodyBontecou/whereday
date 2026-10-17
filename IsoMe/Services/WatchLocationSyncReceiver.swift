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

    func configure(modelContainer: ModelContainer) {
        modelContext = modelContainer.mainContext
        activate()
    }

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
        isActivated = true
    }

    @discardableResult
    func importPayload(_ payload: WatchLocationSyncPayload) -> WatchLocationSyncAck {
        guard payload.version == WatchLocationSyncPayload.currentVersion else {
            return WatchLocationSyncAck(
                deviceID: payload.deviceID,
                sequence: payload.sequence,
                succeeded: false,
                errorMessage: "Unsupported watch sync payload version \(payload.version)."
            )
        }

        guard let modelContext else {
            return WatchLocationSyncAck(
                deviceID: payload.deviceID,
                sequence: payload.sequence,
                succeeded: false,
                errorMessage: "The iPhone data store is not ready yet."
            )
        }

        do {
            var importedSessionCount = 0
            var importedPointCount = 0
            var importedVisitCount = 0
            var updatedVisit = false

            for sessionDTO in payload.sessions {
                if let session = try recordingSession(withID: sessionDTO.id, in: modelContext) {
                    if session.startedAt > sessionDTO.startedAt {
                        session.startedAt = sessionDTO.startedAt
                    }
                    if let endedAt = sessionDTO.endedAt {
                        let normalizedEnd = max(session.startedAt, endedAt)
                        if let currentEnd = session.endedAt {
                            if normalizedEnd > currentEnd {
                                session.endedAt = normalizedEnd
                            }
                        } else {
                            session.endedAt = normalizedEnd
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

            if importedSessionCount > 0 || importedPointCount > 0 || importedVisitCount > 0 || updatedVisit {
                LocationManager.shared?.syncDataToWatch()
                WidgetCenter.shared.reloadAllTimelines()
                NotificationCenter.default.post(name: .watchLocationDataImported, object: nil)
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

    private func importPayloadData(_ data: Data) -> WatchLocationSyncAck? {
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
        // No-op; receiving queued userInfo after activation drives imports.
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

    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
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
