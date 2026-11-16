import Foundation

struct WatchVisitSummary: Codable, Equatable, Identifiable {
    var id: UUID
    var name: String
    var arrivedAt: Date
    var source: String
    var confirmationStatus: String
}

struct WatchManualVisitStatus: Codable, Equatable {
    var generatedAt: Date
    var openVisits: [WatchVisitSummary]
    var openManualVisitCount: Int
}

struct WatchSavedPlace: Codable, Equatable, Identifiable {
    var id: UUID
    var name: String
}

struct WatchVisitSnapshot: Codable, Equatable {
    var status: WatchManualVisitStatus
    var savedPlaces: [WatchSavedPlace]
}

/// Immutable user intent: retries retain the original ID, time and destination.
struct WatchVisitCommand: Codable, Equatable, Identifiable {
    enum Kind: String, Codable { case checkIn, checkOut }
    var version = 1
    var id = UUID()
    var deviceID: UUID
    var kind: Kind
    var occurredAt: Date
    var name: String?
    var savedPlaceID: UUID?
    var latitude: Double?
    var longitude: Double?
    var locationMeasuredAt: Date?
    var horizontalAccuracy: Double?
    var visitID: UUID?
    var dependsOnCommandID: UUID?
    var allowOverlap = false
}

struct WatchVisitCommandResult: Codable, Equatable {
    enum Outcome: String, Codable { case succeeded, failed, retryable }
    var commandID: UUID
    var deviceID: UUID
    var outcome: Outcome
    var visitID: UUID?
    var message: String
}

struct WatchVisitReply: Codable {
    var result: WatchVisitCommandResult?
    var snapshot: WatchVisitSnapshot?
}

enum WatchVisitTransport {
    static let commandKey = "com.bontecou.isome.manualVisitCommand"
    static let replyKey = "com.bontecou.isome.manualVisitReply"
    static let snapshotRequestKey = "com.bontecou.isome.manualVisitSnapshotRequest"
    static let snapshotKey = "com.bontecou.isome.manualVisitSnapshot"
}

/// Transport completion never removes a command. Only a saved phone result is terminal.
struct WatchVisitCommandQueue: Codable {
    struct Entry: Codable, Identifiable {
        var command: WatchVisitCommand
        var result: WatchVisitCommandResult?
        var id: UUID { command.id }
        var isPending: Bool { result == nil || result?.outcome == .retryable }
    }
    var deviceID = UUID()
    var entries: [Entry] = []
    var snapshot: WatchVisitSnapshot?

    var nextCommand: WatchVisitCommand? { entries.first(where: \.isPending)?.command }
    var pendingCheckIn: WatchVisitCommand? {
        entries.first { $0.isPending && $0.command.kind == .checkIn }?.command
    }
    var hasPendingCommands: Bool { nextCommand != nil }

    mutating func enqueue(_ command: WatchVisitCommand) -> Bool {
        guard command.deviceID == deviceID, !entries.contains(where: { $0.id == command.id }) else { return false }
        if command.kind == .checkIn, pendingCheckIn != nil { return false }
        if command.kind == .checkOut,
           entries.contains(where: { $0.isPending && $0.command.kind == .checkOut && $0.command.visitID == command.visitID }) { return false }
        guard entries.filter(\.isPending).count < 100 else { return false }
        // Keep recent terminal results for display; never prune unsent commands.
        let terminal = entries.filter { !$0.isPending }.suffix(20).map(\.id)
        entries.removeAll { !$0.isPending && !terminal.contains($0.id) }
        entries.append(Entry(command: command))
        return true
    }

    mutating func receive(_ reply: WatchVisitReply) {
        if let snapshot = reply.snapshot,
           self.snapshot.map({ $0.status.generatedAt <= snapshot.status.generatedAt }) ?? true {
            self.snapshot = snapshot
        }
        guard let result = reply.result, result.deviceID == deviceID,
              let index = entries.firstIndex(where: { $0.id == result.commandID }), entries[index].isPending else { return }
        entries[index].result = result
        if result.outcome == .failed {
            for i in entries.indices where entries[i].isPending && entries[i].command.dependsOnCommandID == result.commandID {
                entries[i].result = WatchVisitCommandResult(commandID: entries[i].id, deviceID: deviceID,
                    outcome: .failed, message: String(localized: "Check In failed. Review it before checking out."))
            }
        }
    }
}
