import Security
import XCTest
@testable import IsoMe

/// Tests for the security- and correctness-sensitive parts of WebhookManager:
/// keychain round-trip, the one-time UserDefaults → Keychain migration, and
/// the error-message sanitization that prevents API keys from leaking into
/// persisted error logs.
@MainActor
final class WebhookManagerTests: XCTestCase {

    private let testAccount = "webhook.tests.authValue"
    private let testLegacyKey = "webhook.tests.authValue"
    private var defaults: UserDefaults!
    private var defaultsSuiteName: String!

    override func setUpWithError() throws {
        try super.setUpWithError()
        defaultsSuiteName = "WebhookManagerTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: defaultsSuiteName)!
        WebhookManager.keychainWriteString("", account: testAccount)
        UserDefaults.standard.removeObject(forKey: testLegacyKey)
        MockWebhookURLProtocol.reset()

        // Fail in setup with the real Security error, rather than misleading nil-value assertions.
        let probe: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "WebhookManagerTests.preflight",
            kSecAttrAccount as String: UUID().uuidString,
            kSecValueData as String: Data("probe".utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let status = SecItemAdd(probe as CFDictionary, nil)
        guard status == errSecSuccess else {
            let message = status == errSecMissingEntitlement
                ? "Keychain setup failed: errSecMissingEntitlement (-34018). Run scripts/test-ios.sh with simulator signing enabled; CODE_SIGNING_ALLOWED=NO prevents Keychain access."
                : "Keychain setup failed with OSStatus \(status). Check simulator Keychain availability before diagnosing credential assertions."
            throw NSError(domain: "WebhookManagerTests.KeychainSetup", code: Int(status),
                          userInfo: [NSLocalizedDescriptionKey: message])
        }
        var deleteProbe = probe
        deleteProbe.removeValue(forKey: kSecValueData as String)
        SecItemDelete(deleteProbe as CFDictionary)
    }

    override func tearDown() {
        MockWebhookURLProtocol.reset()
        defaults.removePersistentDomain(forName: defaultsSuiteName)
        defaults = nil
        defaultsSuiteName = nil
        WebhookManager.keychainWriteString("", account: testAccount)
        UserDefaults.standard.removeObject(forKey: testLegacyKey)
        super.tearDown()
    }

    // MARK: - Keychain round-trip

    func testKeychainWriteThenReadReturnsSameValue() {
        WebhookManager.keychainWriteString("supersecret", account: testAccount)
        XCTAssertEqual(WebhookManager.keychainReadString(account: testAccount), "supersecret")
    }

    func testKeychainReadMissingReturnsNil() {
        XCTAssertNil(WebhookManager.keychainReadString(account: testAccount))
    }

    func testKeychainEmptyValueDeletes() {
        WebhookManager.keychainWriteString("first", account: testAccount)
        XCTAssertNotNil(WebhookManager.keychainReadString(account: testAccount))

        WebhookManager.keychainWriteString("", account: testAccount)
        XCTAssertNil(WebhookManager.keychainReadString(account: testAccount),
                     "Empty string must remove the keychain entry")
    }

    func testKeychainUpdateOverwritesPriorValue() {
        WebhookManager.keychainWriteString("first", account: testAccount)
        WebhookManager.keychainWriteString("second", account: testAccount)
        XCTAssertEqual(WebhookManager.keychainReadString(account: testAccount), "second")
    }

    func testKeychainHandlesUnicodeAndLongStrings() {
        let value = String(repeating: "🔐", count: 200) + "·secret·" + String(repeating: "x", count: 1000)
        WebhookManager.keychainWriteString(value, account: testAccount)
        XCTAssertEqual(WebhookManager.keychainReadString(account: testAccount), value)
    }

    // MARK: - loadCredential migration

    func testLoadCredentialReturnsKeychainValueWhenPresent() {
        WebhookManager.keychainWriteString("from-keychain", account: testAccount)
        UserDefaults.standard.set("from-defaults", forKey: testLegacyKey)

        let value = WebhookManager.loadCredential(
            account: testAccount,
            legacyKey: testLegacyKey,
            defaultValue: "fallback"
        )

        XCTAssertEqual(value, "from-keychain", "Keychain takes precedence over UserDefaults")
        XCTAssertEqual(UserDefaults.standard.string(forKey: testLegacyKey), "from-defaults",
                       "When keychain is already populated, UserDefaults is left alone")
    }

    func testLoadCredentialMigratesFromUserDefaults() {
        UserDefaults.standard.set("legacy-secret", forKey: testLegacyKey)

        let value = WebhookManager.loadCredential(
            account: testAccount,
            legacyKey: testLegacyKey,
            defaultValue: "fallback"
        )

        XCTAssertEqual(value, "legacy-secret")
        XCTAssertEqual(WebhookManager.keychainReadString(account: testAccount), "legacy-secret",
                       "Migrated value must be in Keychain")
        XCTAssertNil(UserDefaults.standard.string(forKey: testLegacyKey),
                     "Plaintext UserDefaults copy must be deleted after migration")
    }

    func testLoadCredentialReturnsDefaultWhenNothingStored() {
        let value = WebhookManager.loadCredential(
            account: testAccount,
            legacyKey: testLegacyKey,
            defaultValue: "fallback"
        )
        XCTAssertEqual(value, "fallback")
        XCTAssertNil(WebhookManager.keychainReadString(account: testAccount),
                     "Default fallback must not be persisted to Keychain")
    }

    func testLoadCredentialIgnoresEmptyLegacyValue() {
        UserDefaults.standard.set("", forKey: testLegacyKey)

        let value = WebhookManager.loadCredential(
            account: testAccount,
            legacyKey: testLegacyKey,
            defaultValue: "fallback"
        )

        XCTAssertEqual(value, "fallback", "Empty legacy string is not a credential")
        XCTAssertNil(WebhookManager.keychainReadString(account: testAccount))
    }

    func testLoadCredentialMigrationIsIdempotent() {
        UserDefaults.standard.set("once", forKey: testLegacyKey)

        let first = WebhookManager.loadCredential(
            account: testAccount,
            legacyKey: testLegacyKey,
            defaultValue: "fallback"
        )
        let second = WebhookManager.loadCredential(
            account: testAccount,
            legacyKey: testLegacyKey,
            defaultValue: "fallback"
        )

        XCTAssertEqual(first, "once")
        XCTAssertEqual(second, "once")
        XCTAssertNil(UserDefaults.standard.string(forKey: testLegacyKey))
    }

    // MARK: - KeychainKey account naming

    /// The Keychain account names are part of the on-device data contract —
    /// changing them silently would orphan every existing user's credentials.
    func testKeychainAccountNamesAreStable() {
        XCTAssertEqual(WebhookManager.keychainAccount(.authKey), "webhook.authKey")
        XCTAssertEqual(WebhookManager.keychainAccount(.authValue), "webhook.authValue")
        XCTAssertEqual(WebhookManager.keychainAccount(.authUsername), "webhook.authUsername")
    }

    func testLegacyDefaultsKeysMatchOriginalNames() {
        XCTAssertEqual(WebhookManager.legacyDefaultsKey(.authKey), "webhook.authKey")
        XCTAssertEqual(WebhookManager.legacyDefaultsKey(.authValue), "webhook.authValue")
        XCTAssertEqual(WebhookManager.legacyDefaultsKey(.authUsername), "webhook.authUsername")
    }

    // MARK: - Error sanitization

    func testSanitizeReplacesSecretWithMask() {
        let scrubbed = WebhookManager.sanitizeError(
            "Failed to load https://api.example.com?api_key=hunter2",
            masking: "hunter2"
        )
        XCTAssertEqual(scrubbed, "Failed to load https://api.example.com?api_key=***")
    }

    func testSanitizeReplacesAllOccurrences() {
        let scrubbed = WebhookManager.sanitizeError(
            "tried hunter2, then hunter2 again",
            masking: "hunter2"
        )
        XCTAssertEqual(scrubbed, "tried ***, then *** again")
    }

    func testSanitizeNoOpWhenSecretEmpty() {
        let message = "some error: token=abc"
        XCTAssertEqual(WebhookManager.sanitizeError(message, masking: ""), message)
    }

    func testSanitizeNoOpWhenSecretAbsent() {
        let message = "the request timed out"
        XCTAssertEqual(WebhookManager.sanitizeError(message, masking: "hunter2"), message)
    }

    func testSanitizeHandlesSpecialCharacterSecrets() {
        let secret = "a+b/c=d&e?f"
        let scrubbed = WebhookManager.sanitizeError(
            "url contains a+b/c=d&e?f in the query",
            masking: secret
        )
        XCTAssertEqual(scrubbed, "url contains *** in the query")
    }

    // MARK: - Delivery

    func testFlushBatchPostsPayloadAndClearsQueueOnSuccess() async {
        MockWebhookURLProtocol.enqueue(statusCode: 201)
        let manager = makeWebhookManager(retryPolicy: .init(maxAttempts: 1, baseDelay: 0, multiplier: 1))
        manager.urlString = "https://example.com/hooks/location"
        manager.enqueuePendingPoint(makePoint(latitude: 10.1, longitude: -20.2))

        await manager.flushBatch()

        XCTAssertEqual(manager.queuedPointCount, 0)
        XCTAssertNil(manager.lastError)
        XCTAssertNotNil(manager.lastSentAt)
        XCTAssertEqual(MockWebhookURLProtocol.requests.count, 1)
        XCTAssertEqual(MockWebhookURLProtocol.requests.first?.httpMethod, "POST")
        XCTAssertEqual(MockWebhookURLProtocol.requests.first?.value(forHTTPHeaderField: "Content-Type"), "application/json")

        let body = String(data: MockWebhookURLProtocol.requestBodies.first ?? Data(), encoding: .utf8)
        XCTAssertTrue(body?.contains("10.1") == true)
        XCTAssertTrue(body?.contains("-20.2") == true)
    }

    func testFlushBatchRecordsAndPersistsLastStatusCode() async {
        MockWebhookURLProtocol.enqueue(statusCode: 201)
        let manager = makeWebhookManager(retryPolicy: .init(maxAttempts: 1, baseDelay: 0, multiplier: 1))
        manager.urlString = "https://example.com/hooks/location"
        manager.enqueuePendingPoint(makePoint())

        await manager.flushBatch()

        XCTAssertEqual(manager.lastStatusCode, 201)
        XCTAssertEqual(defaults.object(forKey: "webhook.lastStatusCode") as? Int, 201)
        XCTAssertEqual(WebhookManager.statusText(200), "200 OK")
        XCTAssertEqual(WebhookManager.statusText(299), "299")
    }

    func testClearQueueDiscardsWaitingPointsWithoutSending() {
        let manager = makeWebhookManager(retryPolicy: .init(maxAttempts: 1, baseDelay: 0, multiplier: 1))
        manager.urlString = "https://example.com/hooks/location"
        manager.enqueuePendingPoint(makePoint())
        manager.enqueuePendingPoint(makePoint())

        manager.clearQueue()

        XCTAssertEqual(manager.queuedPointCount, 0)
        XCTAssertTrue(MockWebhookURLProtocol.requests.isEmpty)
        XCTAssertNil(manager.lastSentAt)
    }

    func testFlushBatchRetainsQueueAndRecordsUserVisibleErrorOnNon2xx() async {
        MockWebhookURLProtocol.enqueue(statusCode: 500)
        let manager = makeWebhookManager(retryPolicy: .init(maxAttempts: 1, baseDelay: 0, multiplier: 1))
        manager.urlString = "https://example.com/hooks/location"
        manager.enqueuePendingPoint(makePoint())

        await manager.flushBatch()

        XCTAssertEqual(manager.queuedPointCount, 1)
        XCTAssertEqual(manager.lastError, "Server returned HTTP 500")
        XCTAssertNil(manager.lastSentAt)
        XCTAssertEqual(defaults.string(forKey: "webhook.lastError"), "Server returned HTTP 500")
        XCTAssertEqual(MockWebhookURLProtocol.requests.count, 1)
    }

    func testFlushBatchRetriesWithBackoffBeforeSuccess() async {
        MockWebhookURLProtocol.enqueue(statusCode: 500)
        MockWebhookURLProtocol.enqueue(statusCode: 502)
        MockWebhookURLProtocol.enqueue(statusCode: 204)
        var requestedDelays: [TimeInterval] = []
        let manager = makeWebhookManager(
            retryPolicy: .init(maxAttempts: 3, baseDelay: 0.25, multiplier: 3),
            sleep: { requestedDelays.append($0) }
        )
        manager.urlString = "https://example.com/hooks/location"
        manager.enqueuePendingPoint(makePoint())

        await manager.flushBatch()

        XCTAssertEqual(MockWebhookURLProtocol.requests.count, 3)
        XCTAssertEqual(requestedDelays, [0.25, 0.75])
        XCTAssertEqual(manager.queuedPointCount, 0)
        XCTAssertNil(manager.lastError)
        XCTAssertNotNil(manager.lastSentAt)
    }

    func testTestConnectionThrowsUnauthorizedFor401() async {
        MockWebhookURLProtocol.enqueue(statusCode: 401)
        let manager = makeWebhookManager(retryPolicy: .init(maxAttempts: 1, baseDelay: 0, multiplier: 1))
        manager.urlString = "https://example.com/hooks/location"

        do {
            _ = try await manager.testConnection()
            XCTFail("Expected unauthorized webhook error")
        } catch {
            XCTAssertEqual(error.localizedDescription, "Unauthorized (HTTP 401). Check your credentials.")
        }
    }

    private func makeWebhookManager(
        retryPolicy: WebhookManager.RetryPolicy,
        sleep: @escaping (TimeInterval) async -> Void = { _ in }
    ) -> WebhookManager {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockWebhookURLProtocol.self]
        let session = URLSession(configuration: config)
        return WebhookManager(
            defaults: defaults,
            urlSession: session,
            retryPolicy: retryPolicy,
            sleep: sleep
        )
    }

    private func makePoint(latitude: Double = 37.7749, longitude: Double = -122.4194) -> LocationPoint {
        LocationPoint(
            latitude: latitude,
            longitude: longitude,
            timestamp: Date(timeIntervalSince1970: 1_776_000_000),
            horizontalAccuracy: 5
        )
    }
}

private final class MockWebhookURLProtocol: URLProtocol {
    private struct Response {
        let statusCode: Int
        let body: Data
    }

    private static let lock = NSLock()
    private static var queuedResponses: [Response] = []
    private(set) static var requests: [URLRequest] = []
    private(set) static var requestBodies: [Data] = []

    static func reset() {
        lock.withLock {
            queuedResponses = []
            requests = []
            requestBodies = []
        }
    }

    static func enqueue(statusCode: Int, body: Data = Data()) {
        lock.withLock {
            queuedResponses.append(Response(statusCode: statusCode, body: body))
        }
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let next: Response? = Self.lock.withLock {
            Self.requests.append(request)
            Self.requestBodies.append(Self.bodyData(from: request))
            return Self.queuedResponses.isEmpty ? nil : Self.queuedResponses.removeFirst()
        }

        guard let next else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }

        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: next.statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: nil
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: next.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func bodyData(from request: URLRequest) -> Data {
        if let body = request.httpBody {
            return body
        }
        guard let stream = request.httpBodyStream else {
            return Data()
        }
        stream.open()
        defer { stream.close() }

        var data = Data()
        let bufferSize = 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }

        while stream.hasBytesAvailable {
            let count = stream.read(buffer, maxLength: bufferSize)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private extension NSLock {
    func withLock<T>(_ body: () -> T) -> T {
        lock()
        defer { unlock() }
        return body()
    }
}
