import Combine
import CoreLocation
import Foundation
import WatchConnectivity
import WidgetKit

@MainActor
final class WatchManualVisitClient: ObservableObject {
    @Published private(set) var queue: WatchVisitCommandQueue
    @Published private(set) var errorMessage: String?
    private let stateURL: URL
    private var storageUnavailable = false
    private let encoder = JSONEncoder()

    init(stateURL: URL? = nil) {
        self.stateURL = stateURL ?? (FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedLocationData.appGroupIdentifier)
            ?? URL.documentsDirectory).appendingPathComponent("ManualVisitCommands.json")
        if FileManager.default.fileExists(atPath: self.stateURL.path) {
            do { queue = try JSONDecoder().decode(WatchVisitCommandQueue.self, from: Data(contentsOf: self.stateURL)) }
            catch {
                queue = WatchVisitCommandQueue()
                storageUnavailable = true
                errorMessage = String(localized: "The saved command queue could not be read. Keep it and reopen the app; commands have not been discarded.")
            }
        } else { queue = WatchVisitCommandQueue() }
    }

    func checkIn(name: String, savedPlaceID: UUID?, location: CLLocation?, allowOverlap: Bool, at date: Date = Date()) {
        enqueue(WatchVisitCommand(deviceID: queue.deviceID, kind: .checkIn, occurredAt: date,
            name: name, savedPlaceID: savedPlaceID, latitude: location?.coordinate.latitude,
            longitude: location?.coordinate.longitude, locationMeasuredAt: location?.timestamp,
            horizontalAccuracy: location?.horizontalAccuracy, allowOverlap: allowOverlap))
    }

    func checkOut(visitID: UUID?, dependsOn: UUID? = nil) {
        enqueue(WatchVisitCommand(deviceID: queue.deviceID, kind: .checkOut, occurredAt: Date(),
            visitID: visitID, dependsOnCommandID: dependsOn))
    }

    private func enqueue(_ command: WatchVisitCommand) {
        guard !storageUnavailable else { return }
        var next = queue
        guard next.enqueue(command) else {
            errorMessage = String(localized: "That visit command is already pending. Wait for iPhone confirmation.")
            return
        }
        guard persist(next) else { return }
        errorMessage = nil
        queue = next
        sync()
    }

    private func persist(_ state: WatchVisitCommandQueue) -> Bool {
        do {
            try FileManager.default.createDirectory(at: stateURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try encoder.encode(state).write(to: stateURL, options: .atomic)
            return true
        } catch {
            errorMessage = String(localized: "The command could not be saved on Watch. Nothing new was sent. Try again.")
            return false
        }
    }

    func sync() {
        guard !storageUnavailable, WCSession.isSupported(), WCSession.default.activationState == .activated else { return }
        let session = WCSession.default
        if let command = queue.nextCommand, let data = try? encoder.encode(command) {
            let alreadyQueued = session.outstandingUserInfoTransfers.contains {
                guard let old = $0.userInfo[WatchVisitTransport.commandKey] as? Data,
                      let item = try? JSONDecoder().decode(WatchVisitCommand.self, from: old) else { return false }
                return item.id == command.id
            }
            if !alreadyQueued { session.transferUserInfo([WatchVisitTransport.commandKey: data]) }
            if session.isReachable {
                session.sendMessageData(data) { [weak self] reply in
                    Task { @MainActor in _ = self?.handleReply(reply) }
                } errorHandler: { _ in /* Remains pending until the phone commits and acknowledges. */ }
            }
        }
        if session.isReachable {
            session.sendMessage([WatchVisitTransport.snapshotRequestKey: true]) { [weak self] reply in
                guard let data = reply[WatchVisitTransport.snapshotKey] as? Data else { return }
                Task { @MainActor in self?.handleSnapshot(data) }
            } errorHandler: { _ in }
        } else if !session.outstandingUserInfoTransfers.contains(where: { $0.userInfo[WatchVisitTransport.snapshotRequestKey] as? Bool == true }) {
            session.transferUserInfo([WatchVisitTransport.snapshotRequestKey: true])
        }
    }

    @discardableResult
    func handleReply(_ data: Data) -> Bool {
        guard !storageUnavailable, let reply = try? JSONDecoder().decode(WatchVisitReply.self, from: data),
              reply.result != nil || reply.snapshot != nil else { return false }
        var next = queue
        next.receive(reply)
        guard persist(next) else { return true }
        let previousID = queue.nextCommand?.id
        queue = next
        errorMessage = nil
        publishSharedStatus()
        if queue.nextCommand?.id != previousID { sync() }
        return true
    }

    func handleSnapshot(_ data: Data) {
        guard let snapshot = try? JSONDecoder().decode(WatchVisitSnapshot.self, from: data), !storageUnavailable else { return }
        var next = queue
        next.receive(WatchVisitReply(snapshot: snapshot))
        guard persist(next) else { return }
        queue = next
        errorMessage = nil
        publishSharedStatus()
    }

    private func publishSharedStatus() {
        guard let status = queue.snapshot?.status else { return }
        var shared = SharedLocationData.load() ?? .empty
        shared.manualVisitStatus = status
        shared.save()
        WidgetCenter.shared.reloadAllTimelines()
    }
}
