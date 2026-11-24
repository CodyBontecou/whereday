import Combine
import Foundation

/// iPhone side of the Watch hand-off (#173): the spans this iPhone recorded
/// itself, the Watch's latest reported role, and which Watch visits were
/// folded into which iPhone visits.
@MainActor
final class RecordingHandoffStore: ObservableObject {
    static let shared = RecordingHandoffStore(defaults: sharedDefaults)

    /// Debug simulator builds only: `--watch-handoff-replay` (see docs/testing/watch-handoff-replay.md).
    nonisolated static var isReplay: Bool {
        #if DEBUG && targetEnvironment(simulator)
        ProcessInfo.processInfo.arguments.contains("--watch-handoff-replay")
        #else
        false
        #endif
    }

    private static var sharedDefaults: UserDefaults {
        guard isReplay, let defaults = UserDefaults(suiteName: "WatchHandoffReplay") else { return .standard }
        defaults.removePersistentDomain(forName: "WatchHandoffReplay")
        return defaults
    }

    /// The replay's outcome, shown on the Apple Watch sync screen (debug simulator only).
    @Published var replaySummary: String?

    private static let coverageKey = "recordingHandoff.coverage.v1"
    private static let aliasesKey = "recordingHandoff.watchVisitAliases.v1"
    private static let reportKey = "recordingHandoff.watchReport.v1"
    private static let maxAliases = 500

    private let defaults: UserDefaults
    let policy = RecordingHandoffPolicy()
    private(set) var coverage: RecordingCoverage
    @Published private(set) var watchReport: WatchHandoffReport?
    /// Local receipt time of `watchReport`.
    @Published private(set) var watchReportReceivedAt: Date?
    private var aliases: [String: String]
    private var aliasOrder: [String]

    init(defaults: UserDefaults) {
        self.defaults = defaults
        coverage = defaults.data(forKey: Self.coverageKey)
            .flatMap { try? JSONDecoder().decode(RecordingCoverage.self, from: $0) } ?? RecordingCoverage()
        let storedAliases = defaults.array(forKey: Self.aliasesKey) as? [[String]] ?? []
        aliases = Dictionary(storedAliases.compactMap { $0.count == 2 ? ($0[0], $0[1]) : nil },
                             uniquingKeysWith: { _, new in new })
        aliasOrder = storedAliases.compactMap(\.first)
        if let data = defaults.data(forKey: Self.reportKey),
           let stored = try? JSONDecoder().decode(StoredReport.self, from: data) {
            watchReport = stored.report
            watchReportReceivedAt = stored.receivedAt
        }
    }

    private struct StoredReport: Codable {
        var report: WatchHandoffReport
        var receivedAt: Date
    }

    // MARK: - Phone coverage

    /// This iPhone recorded at `date` (a fix while tracking, or a lease attestation).
    func recordPhoneCoverage(at date: Date) {
        coverage.extend(to: date)
        coverage.prune(now: date)
        saveCoverage()
    }

    /// Recording stopped or the process restarted: never bridge across it.
    func closePhoneCoverage() {
        guard coverage.isOpen else { return }
        coverage.close()
        saveCoverage()
    }

    private func saveCoverage() {
        if let data = try? JSONEncoder().encode(coverage) {
            defaults.set(data, forKey: Self.coverageKey)
        }
    }

    // MARK: - Lease

    /// Answers a Watch lease request. A recording phone attests coverage up to now.
    func lease(answering report: WatchHandoffReport?, isRecording: Bool, sessionID: UUID?,
               now: Date = Date()) -> PhoneRecordingLease {
        if let report { receive(report, now: now) }
        if isRecording { recordPhoneCoverage(at: now) }
        return PhoneRecordingLease(isRecording: isRecording, sessionID: sessionID, coveredUntil: now)
    }

    func receive(_ report: WatchHandoffReport, now: Date = Date()) {
        watchReport = report
        watchReportReceivedAt = now
        if let data = try? JSONEncoder().encode(StoredReport(report: report, receivedAt: now)) {
            defaults.set(data, forKey: Self.reportKey)
        }
    }

    /// The Watch's role, or nil when it hasn't reported recently enough to know.
    func watchRole(now: Date = Date()) -> RecordingDeviceRole? {
        guard let watchReport, let watchReportReceivedAt else { return nil }
        let age = now.timeIntervalSince(watchReportReceivedAt)
        guard age >= -5, age <= policy.leaseLifetime else { return nil }
        return watchReport.role
    }

    /// "iPhone · Apple Watch on standby", for the phone's recording status.
    func recordingDevicesDescription(phoneIsRecording: Bool, now: Date = Date()) -> String {
        switch (phoneIsRecording, watchRole(now: now)) {
        case (true, .standingBy?):
            return String(localized: "This iPhone · Apple Watch on standby")
        case (true, .recording?):
            return String(localized: "This iPhone and Apple Watch")
        case (true, _):
            return String(localized: "This iPhone")
        case (false, .recording?), (false, .standingBy?):
            return String(localized: "Apple Watch")
        case (false, _):
            return String(localized: "Not recording")
        }
    }

    // MARK: - Visit aliases

    func phoneVisitID(forWatchVisit id: UUID) -> UUID? {
        aliases[id.uuidString].flatMap(UUID.init(uuidString:))
    }

    func setPhoneVisitID(_ phoneID: UUID, forWatchVisit watchID: UUID) {
        let key = watchID.uuidString
        if aliases[key] == nil { aliasOrder.append(key) }
        aliases[key] = phoneID.uuidString
        while aliasOrder.count > Self.maxAliases {
            aliases.removeValue(forKey: aliasOrder.removeFirst())
        }
        defaults.set(aliasOrder.compactMap { key in aliases[key].map { [key, $0] } }, forKey: Self.aliasesKey)
    }
}
