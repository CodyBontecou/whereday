import Foundation
import Combine
import Security
import SwiftData

/// HTTP webhook delivery of location data to a user-configured endpoint.
///
/// Supports OwnTracks, Overland, GPX, KML, GeoJSON, JSON, CSV, and Markdown payload formats.
/// Multiple auth methods (none, API key query param, Bearer, Basic, custom header).
/// Multiple send modes (realtime per-point, batch count, batch time, manual).
///
/// Privacy: entirely opt-in, disabled by default. When enabled, a prominent
/// warning tells the user their location data will be sent off-device.
@MainActor
final class WebhookManager: ObservableObject {
    static let shared = WebhookManager()

    struct RetryPolicy {
        let maxAttempts: Int
        let baseDelay: TimeInterval
        let multiplier: Double

        static let `default` = RetryPolicy(maxAttempts: 3, baseDelay: 1, multiplier: 2)

        func delay(beforeAttempt attempt: Int) -> TimeInterval {
            guard attempt > 1 else { return 0 }
            return baseDelay * pow(multiplier, Double(attempt - 2))
        }
    }

    // MARK: - Auth types

    enum AuthType: String, CaseIterable, Identifiable {
        case none
        case apiKeyQuery
        case bearer
        case basic
        case customHeader

        var id: String { rawValue }

        var label: String {
            switch self {
            case .none: return String(localized: "NONE")
            case .apiKeyQuery: return String(localized: "API KEY (QUERY)")
            case .bearer: return String(localized: "BEARER TOKEN")
            case .basic: return String(localized: "BASIC AUTH")
            case .customHeader: return String(localized: "CUSTOM HEADER")
            }
        }
    }

    enum SendMode: String, CaseIterable, Identifiable {
        case realtime
        case batchCount
        case batchTime
        case manual

        var id: String { rawValue }

        var label: String {
            switch self {
            case .realtime: return String(localized: "REAL-TIME")
            case .batchCount: return String(localized: "BY COUNT")
            case .batchTime: return String(localized: "BY TIME")
            case .manual: return String(localized: "MANUAL")
            }
        }
    }

    // MARK: - Published state

    @Published var isEnabled: Bool {
        didSet { defaults.set(isEnabled, forKey: Self.key(.enabled)); syncObservation(); syncTimer() }
    }
    @Published var urlString: String {
        didSet { defaults.set(urlString, forKey: Self.key(.url)) }
    }
    @Published var format: ExportFormat {
        didSet { defaults.set(format.token, forKey: Self.key(.format)) }
    }
    @Published var authType: AuthType {
        didSet { defaults.set(authType.rawValue, forKey: Self.key(.authType)) }
    }
    @Published var authKey: String {
        didSet { Self.keychainWriteString(authKey, account: Self.keychainAccount(.authKey)) }
    }
    @Published var authValue: String {
        didSet { Self.keychainWriteString(authValue, account: Self.keychainAccount(.authValue)) }
    }
    @Published var authUsername: String {
        didSet { Self.keychainWriteString(authUsername, account: Self.keychainAccount(.authUsername)) }
    }
    @Published var sendMode: SendMode {
        didSet { defaults.set(sendMode.rawValue, forKey: Self.key(.sendMode)); syncObservation(); syncTimer() }
    }
    @Published var batchCount: Int {
        didSet { defaults.set(batchCount, forKey: Self.key(.batchCount)) }
    }
    @Published var batchTimeMinutes: Int {
        didSet { defaults.set(batchTimeMinutes, forKey: Self.key(.batchTimeMinutes)); syncTimer() }
    }

    @Published private(set) var lastSentAt: Date?
    @Published private(set) var lastError: String?
    @Published private(set) var queuedPointCount: Int = 0
    @Published private(set) var isSending: Bool = false

    // MARK: - Private

    private let defaults: UserDefaults
    private var cancellables = Set<AnyCancellable>()
    private var flushTimer: Timer?
    private var pendingPoints: [LocationPoint] = []
    private weak var modelContainer: ModelContainer?
    private var urlSession: URLSession!
    private let retryPolicy: RetryPolicy
    private let sleep: (TimeInterval) async -> Void
    // Realtime cursor — nil means we haven't observed a tick yet. The first tick
    // arms the cursor without sending so we never bulk-resend the historical DB
    // after launch; subsequent ticks send everything strictly newer.
    private var lastSentTimestamp: Date?

    private enum DefaultsKey: String {
        case enabled, url, format, authType
        case sendMode, batchCount, batchTimeMinutes
        case lastSentAt, lastError
    }

    enum KeychainKey: String {
        case authKey, authValue, authUsername
    }

    private static func key(_ k: DefaultsKey) -> String { "webhook.\(k.rawValue)" }
    static func keychainAccount(_ k: KeychainKey) -> String { "webhook.\(k.rawValue)" }
    static func legacyDefaultsKey(_ k: KeychainKey) -> String { "webhook.\(k.rawValue)" }

    init(
        defaults: UserDefaults = .standard,
        urlSession: URLSession? = nil,
        retryPolicy: RetryPolicy = .default,
        sleep: @escaping (TimeInterval) async -> Void = { delay in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }
    ) {
        self.defaults = defaults
        self.retryPolicy = retryPolicy
        self.sleep = sleep
        let d = defaults
        self.isEnabled = d.bool(forKey: Self.key(.enabled))
        self.urlString = d.string(forKey: Self.key(.url)) ?? ""
        self.format = Self.formatFromToken(d.string(forKey: Self.key(.format)))
        self.authType = AuthType(rawValue: d.string(forKey: Self.key(.authType)) ?? "") ?? .none

        self.authKey = Self.loadCredential(.authKey, defaults: d, defaultValue: "api_key")
        self.authValue = Self.loadCredential(.authValue, defaults: d, defaultValue: "")
        self.authUsername = Self.loadCredential(.authUsername, defaults: d, defaultValue: "")

        self.sendMode = SendMode(rawValue: d.string(forKey: Self.key(.sendMode)) ?? "") ?? .realtime
        self.batchCount = d.object(forKey: Self.key(.batchCount)) as? Int ?? 10
        self.batchTimeMinutes = d.object(forKey: Self.key(.batchTimeMinutes)) as? Int ?? 5
        self.lastSentAt = d.object(forKey: Self.key(.lastSentAt)) as? Date
        self.lastError = d.string(forKey: Self.key(.lastError))

        if let urlSession {
            self.urlSession = urlSession
        } else {
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 30
            config.timeoutIntervalForResource = 60
            self.urlSession = URLSession(configuration: config)
        }

        syncTimer()
    }

    /// Read a credential from Keychain. If absent but a non-empty legacy
    /// UserDefaults value exists, migrate it into Keychain and clear the
    /// plaintext copy.
    static func loadCredential(
        account: String,
        legacyKey: String,
        defaultValue: String,
        defaults: UserDefaults = .standard
    ) -> String {
        if let stored = keychainReadString(account: account) {
            return stored
        }
        if let legacy = defaults.string(forKey: legacyKey), !legacy.isEmpty {
            keychainWriteString(legacy, account: account)
            defaults.removeObject(forKey: legacyKey)
            return legacy
        }
        return defaultValue
    }

    private static func loadCredential(_ key: KeychainKey, defaults: UserDefaults, defaultValue: String) -> String {
        loadCredential(
            account: keychainAccount(key),
            legacyKey: legacyDefaultsKey(key),
            defaultValue: defaultValue,
            defaults: defaults
        )
    }

    // MARK: - Wiring

    /// Must be called once on app launch so the manager can observe location updates.
    func attach(modelContainer: ModelContainer, locationManager: LocationManager) {
        self.modelContainer = modelContainer
        syncObservation(locationManager: locationManager)
    }

    private func syncObservation(locationManager: LocationManager? = nil) {
        cancellables.removeAll()
        guard isEnabled else { return }

        guard let lm = locationManager ?? LocationManager.shared else { return }

        if sendMode == .realtime {
            lm.$locationPointsSavedCount
                .dropFirst()
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in
                    Task { @MainActor in
                        await self?.onNewPoint(locationManager: lm)
                    }
                }
                .store(in: &cancellables)
        }
    }

    private func syncTimer() {
        flushTimer?.invalidate()
        flushTimer = nil
        guard isEnabled, sendMode == .batchTime, batchTimeMinutes > 0 else { return }
        flushTimer = Timer.scheduledTimer(
            withTimeInterval: TimeInterval(batchTimeMinutes * 60),
            repeats: true
        ) { [weak self] _ in
            Task { @MainActor in await self?.flushBatch() }
        }
    }

    // MARK: - Sending

    private func onNewPoint(locationManager: LocationManager) async {
        guard let context = modelContainer?.mainContext else { return }
        do {
            switch sendMode {
            case .realtime:
                guard let sentinel = lastSentTimestamp else {
                    lastSentTimestamp = Date()
                    return
                }
                let descriptor = FetchDescriptor<LocationPoint>(
                    predicate: #Predicate<LocationPoint> { $0.timestamp > sentinel },
                    sortBy: [SortDescriptor(\.timestamp, order: .forward)]
                )
                let newPoints = try context.fetch(descriptor)
                let showOutliers = UserDefaults.standard.bool(forKey: "showOutliers")
                let filtered = newPoints.filter { !$0.isOutlier || showOutliers }
                guard !filtered.isEmpty else { return }
                try await sendPoints(filtered)
                if let lastTimestamp = filtered.last?.timestamp {
                    lastSentTimestamp = lastTimestamp
                }
            case .batchCount, .batchTime, .manual:
                var descriptor = FetchDescriptor<LocationPoint>()
                descriptor.sortBy = [SortDescriptor(\.timestamp, order: .reverse)]
                descriptor.fetchLimit = 1
                let points = try context.fetch(descriptor)
                guard let latest = points.first else { return }

                if latest.isOutlier && !UserDefaults.standard.bool(forKey: "showOutliers") {
                    return
                }

                switch sendMode {
                case .batchCount:
                    enqueuePendingPoint(latest)
                    if pendingPoints.count >= batchCount {
                        await flushBatch()
                    }
                case .batchTime:
                    enqueuePendingPoint(latest)
                case .manual, .realtime:
                    break
                }
            }
        } catch {
            recordError(error.localizedDescription)
        }
    }

    /// Send all currently queued points (batch flush).
    func flushBatch() async {
        guard !pendingPoints.isEmpty else { return }
        let batch = pendingPoints
        pendingPoints.removeAll()
        do {
            try await sendPoints(batch)
            queuedPointCount = pendingPoints.count
        } catch {
            pendingPoints.insert(contentsOf: batch, at: 0)
            queuedPointCount = pendingPoints.count
            recordError(error.localizedDescription)
        }
    }

    func enqueuePendingPoint(_ point: LocationPoint) {
        pendingPoints.append(point)
        queuedPointCount = pendingPoints.count
    }

    /// Send a manual export of all saved data.
    func sendNow() async {
        guard let context = modelContainer?.mainContext else { return }
        do {
            let visits = try context.fetch(FetchDescriptor<Visit>())
            let points = try context.fetch(FetchDescriptor<LocationPoint>())
            try await sendPoints(points, visits: visits)
        } catch {
            recordError(error.localizedDescription)
        }
    }

    /// Test the configured endpoint with a minimal payload.
    func testConnection() async throws -> String {
        let fakePoint = LocationPoint(
            latitude: 0,
            longitude: 0,
            timestamp: Date(),
            horizontalAccuracy: 0
        )
        let body = try formatPayload(points: [fakePoint])
        guard let url = buildURL() else {
            throw WebhookError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue(format.mimeType, forHTTPHeaderField: "Content-Type")
        request.setValue("Whereday/\(AppInfo.version)", forHTTPHeaderField: "User-Agent")
        applyAuth(to: &request)

        let (_, response) = try await urlSession.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw WebhookError.httpError(0)
        }
        if (200...299).contains(httpResponse.statusCode) {
            return String(localized: "Connected (HTTP \(httpResponse.statusCode))")
        } else if httpResponse.statusCode == 401 {
            throw WebhookError.unauthorized
        } else {
            throw WebhookError.httpError(httpResponse.statusCode)
        }
    }

    // MARK: - Core send

    private func sendPoints(_ points: [LocationPoint], visits: [Visit] = []) async throws {
        guard !points.isEmpty else { return }
        guard let url = buildURL() else {
            throw WebhookError.invalidURL
        }

        isSending = true
        defer { isSending = false }

        let body = try formatPayload(points: points, visits: visits)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue(format.mimeType, forHTTPHeaderField: "Content-Type")
        request.setValue("Whereday/\(AppInfo.version)", forHTTPHeaderField: "User-Agent")
        applyAuth(to: &request)

        let (_, response) = try await sendRequestWithRetry(request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            throw WebhookError.httpError((response as? HTTPURLResponse)?.statusCode ?? 0)
        }

        lastSentAt = Date()
        defaults.set(lastSentAt, forKey: Self.key(.lastSentAt))
        lastError = nil
        defaults.removeObject(forKey: Self.key(.lastError))
    }

    private func sendRequestWithRetry(_ request: URLRequest) async throws -> (Data, URLResponse) {
        let attempts = max(1, retryPolicy.maxAttempts)

        for attempt in 1...attempts {
            if attempt > 1 {
                await sleep(retryPolicy.delay(beforeAttempt: attempt))
            }

            do {
                let result = try await urlSession.data(for: request)
                guard let httpResponse = result.1 as? HTTPURLResponse else {
                    throw WebhookError.httpError(0)
                }
                if (200...299).contains(httpResponse.statusCode) {
                    return result
                }
                throw WebhookError.httpError(httpResponse.statusCode)
            } catch {
                guard attempt < attempts, Self.shouldRetry(error) else {
                    throw error
                }
            }
        }

        throw WebhookError.httpError(0)
    }

    private static func shouldRetry(_ error: Error) -> Bool {
        if error is URLError {
            return true
        }

        guard let webhookError = error as? WebhookError else {
            return false
        }

        switch webhookError {
        case .httpError(let code):
            return code == 0 || code == 408 || code == 429 || code >= 500
        case .invalidURL, .unauthorized:
            return false
        }
    }

    // MARK: - URL building

    private func buildURL() -> URL? {
        guard !urlString.isEmpty else { return nil }
        var urlStr = urlString.trimmingCharacters(in: .whitespacesAndNewlines)

        if authType == .apiKeyQuery, !authKey.isEmpty, !authValue.isEmpty {
            let separator = urlStr.contains("?") ? "&" : "?"
            let encodedKey = authKey.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? authKey
            let encodedValue = authValue.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? authValue
            urlStr += "\(separator)\(encodedKey)=\(encodedValue)"
        }

        return URL(string: urlStr)
    }

    // MARK: - Auth

    private func applyAuth(to request: inout URLRequest) {
        switch authType {
        case .none, .apiKeyQuery:
            break
        case .bearer:
            if !authValue.isEmpty {
                request.setValue("Bearer \(authValue)", forHTTPHeaderField: "Authorization")
            }
        case .basic:
            if !authUsername.isEmpty || !authValue.isEmpty {
                let login = "\(authUsername):\(authValue)"
                if let encoded = login.data(using: .utf8)?.base64EncodedString() {
                    request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
                }
            }
        case .customHeader:
            if !authKey.isEmpty, !authValue.isEmpty {
                request.setValue(authValue, forHTTPHeaderField: authKey)
            }
        }
    }

    // MARK: - Payload formatting

    private func formatPayload(points: [LocationPoint], visits: [Visit] = []) throws -> Data {
        let hasVisits = !visits.isEmpty
        switch format {
        case .owntracks:
            return try ExportService.exportLocationPointsToOwnTracks(points: points)
        case .overland:
            return try ExportService.exportLocationPointsToOverland(points: points)
        case .json:
            if hasVisits {
                return try ExportService.exportCombinedToJSON(visits: visits, points: points)
            }
            return try ExportService.exportLocationPointsToJSON(points: points)
        case .csv:
            if hasVisits {
                return ExportService.exportCombinedToCSV(visits: visits, points: points)
            }
            return ExportService.exportLocationPointsToCSV(points: points)
        case .markdown:
            if hasVisits {
                return ExportService.exportCombinedToMarkdown(visits: visits, points: points)
            }
            return ExportService.exportLocationPointsToMarkdown(points: points)
        case .gpx:
            if hasVisits {
                return ExportService.exportCombinedToGPX(visits: visits, points: points)
            }
            return ExportService.exportLocationPointsToGPX(points: points)
        case .kml:
            if hasVisits {
                return ExportService.exportCombinedToKML(visits: visits, points: points)
            }
            return ExportService.exportLocationPointsToKML(points: points)
        case .geojson:
            if hasVisits {
                return try ExportService.exportCombinedToGeoJSON(visits: visits, points: points)
            }
            return try ExportService.exportLocationPointsToGeoJSON(points: points)
        }
    }

    // MARK: - Helpers

    static func sanitizeError(_ message: String, masking secret: String) -> String {
        guard !secret.isEmpty else { return message }
        return message.replacingOccurrences(of: secret, with: "***")
    }

    private func recordError(_ message: String) {
        let sanitized = Self.sanitizeError(message, masking: authValue)
        lastError = sanitized
        defaults.set(sanitized, forKey: Self.key(.lastError))
    }

    static func formatFromToken(_ token: String?) -> ExportFormat {
        switch token {
        case "csv": return .csv
        case "md", "markdown": return .markdown
        case "owntracks": return .owntracks
        case "overland": return .overland
        case "gpx": return .gpx
        case "kml": return .kml
        case "geojson": return .geojson
        default: return .json
        }
    }

    // MARK: - Keychain

    static func keychainReadString(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess,
              let data = result as? Data,
              let string = String(data: data, encoding: .utf8) else {
            return nil
        }
        return string
    }

    static func keychainWriteString(_ value: String, account: String) {
        if value.isEmpty {
            let deleteQuery: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrAccount as String: account,
            ]
            SecItemDelete(deleteQuery as CFDictionary)
            return
        }

        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let updateAttributes: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, updateAttributes as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }
}

// MARK: - Errors

enum WebhookError: LocalizedError {
    case invalidURL
    case httpError(Int)
    case unauthorized

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return String(localized: "Invalid URL")
        case .httpError(let code):
            return String(localized: "Server returned HTTP \(code)")
        case .unauthorized:
            return String(localized: "Unauthorized (HTTP 401). Check your credentials.")
        }
    }
}
