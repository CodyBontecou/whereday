import Foundation
import OSLog
#if canImport(UIKit)
import UIKit
#endif

@MainActor
final class LogManager: ObservableObject {
    static let shared = LogManager()

    struct LogEntry: Identifiable, Codable {
        let id: UUID
        let timestamp: Date
        let level: Level
        let category: Category
        let message: String

        init(id: UUID = UUID(), timestamp: Date, level: Level, category: Category = .general, message: String) {
            self.id = id
            self.timestamp = timestamp
            self.level = level
            self.category = category
            self.message = message
        }

        enum Level: String, Codable {
            case info = "INFO"
            case warning = "WARN"
            case error = "ERROR"
        }

        /// What part of the app wrote the entry, shown as a tag in Diagnostics.
        enum Category: String, CaseIterable, Codable {
            case location = "LOCATION"
            case export = "EXPORT"
            case webhook = "WEBHOOK"
            case visit = "VISIT"
            case watch = "WATCH"
            case general = "GENERAL"

            /// Best guess for call sites that don't pass a category: the calling file first, then the message.
            static func infer(message: String, fileID: String = "") -> Category {
                let file = fileID.lowercased()
                let fileRules: [(String, Category)] = [
                    ("webhook", .webhook),
                    ("export", .export),
                    ("watch", .watch),
                    ("visit", .visit),
                    ("location", .location),
                    ("tracking", .location)
                ]
                if let match = fileRules.first(where: { file.contains($0.0) }) {
                    return match.1
                }

                let text = message.lowercased()
                let messageRules: [([String], Category)] = [
                    (["webhook", "http ", "endpoint"], .webhook),
                    (["export", "gpx", "geojson", "obsidian", "daily note"], .export),
                    (["watch"], .watch),
                    (["visit", "arrived", "departed", "check in", "check out"], .visit),
                    (["location", "gps", "point", "accuracy", "tracking", "background session"], .location)
                ]
                for (keywords, category) in messageRules where keywords.contains(where: text.contains) {
                    return category
                }
                return .general
            }
        }

        var display: String {
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss"
            return "[\(formatter.string(from: timestamp))] \(level.rawValue) \(category.rawValue): \(message)"
        }
    }

    @Published private(set) var entries: [LogEntry] = []
    private let maxEntries = 500
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "com.bontecou.isome", category: "app")

    /// Entries survive relaunches in Application Support, so Diagnostics can show what happened overnight.
    private let storeURL: URL?
    private var pendingSave: Task<Void, Never>?
    private var backgroundObserver: NSObjectProtocol?
    private static let saveDelay: Duration = .seconds(2)
    private static let writeQueue = DispatchQueue(label: "LogManager.persistence", qos: .utility)

    private init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        storeURL = directory?.appendingPathComponent("DiagnosticsLog.json")
        entries = Self.load(from: storeURL, limit: maxEntries)
        #if canImport(UIKit)
        // Don't lose the last couple of seconds when the app is suspended mid-debounce.
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.pendingSave != nil else { return }
                self.pendingSave?.cancel()
                self.pendingSave = nil
                self.saveNow()
            }
        }
        #endif
    }

    private static func load(from url: URL?, limit: Int) -> [LogEntry] {
        guard let url, let data = try? Data(contentsOf: url) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let stored = try? decoder.decode([LogEntry].self, from: data) else { return [] }
        return Array(stored.suffix(limit))
    }

    /// Writes are coalesced: a burst of entries costs one file write a couple of seconds later.
    private func scheduleSave() {
        pendingSave?.cancel()
        pendingSave = Task { [weak self] in
            try? await Task.sleep(for: Self.saveDelay)
            guard !Task.isCancelled else { return }
            self?.pendingSave = nil
            self?.saveNow()
        }
    }

    private func saveNow() {
        guard let storeURL else { return }
        let snapshot = entries
        // A serial queue keeps writes in order, off the main thread.
        Self.writeQueue.async {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            guard let data = try? encoder.encode(snapshot) else { return }
            try? FileManager.default.createDirectory(
                at: storeURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try? data.write(to: storeURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    func log(
        _ message: String,
        level: LogEntry.Level = .info,
        category: LogEntry.Category? = nil,
        fileID: String = #fileID
    ) {
        let resolved = category ?? LogEntry.Category.infer(message: message, fileID: fileID)
        let entry = LogEntry(timestamp: Date(), level: level, category: resolved, message: message)
        entries.append(entry)
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
        }
        scheduleSave()

        let tag = resolved.rawValue
        switch level {
        case .info: logger.info("[\(tag)] \(message)")
        case .warning: logger.warning("[\(tag)] \(message)")
        case .error: logger.error("[\(tag)] \(message)")
        }
    }

    func info(_ message: String, category: LogEntry.Category? = nil, fileID: String = #fileID) {
        log(message, level: .info, category: category, fileID: fileID)
    }

    func warning(_ message: String, category: LogEntry.Category? = nil, fileID: String = #fileID) {
        log(message, level: .warning, category: category, fileID: fileID)
    }

    func error(_ message: String, category: LogEntry.Category? = nil, fileID: String = #fileID) {
        log(message, level: .error, category: category, fileID: fileID)
    }

    func clear() {
        entries.removeAll()
        scheduleSave()
    }

    var exportText: String {
        entries.map(\.display).joined(separator: "\n")
    }
}
