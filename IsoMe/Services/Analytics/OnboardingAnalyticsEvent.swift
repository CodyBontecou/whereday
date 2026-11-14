//
//  OnboardingAnalyticsEvent.swift
//  IsoMe
//
//  Privacy-safe event model for onboarding and activation analytics.
//

import Foundation

/// Privacy contract for onboarding analytics:
/// - Allowed: app version/build, platform, coarse onboarding step,
///   coarse location authorization status/request type, tracking-start intent,
///   paywall context, StoreKit product ID, purchase/restore outcome, and coarse
///   error category.
/// - Prohibited: coordinates, addresses, place names, visit names, route points,
///   raw location timestamps, export contents, file/folder paths, webhook URLs,
///   device names, and user-entered text.
///
/// This model is deliberately local and transport-free. Future sinks should
/// consume `encodedPayload()` and must not add keys outside
/// `OnboardingAnalyticsPropertyKey`.
nonisolated struct OnboardingAnalyticsEvent: Equatable, Sendable {
    let name: OnboardingAnalyticsEventName
    let properties: OnboardingAnalyticsProperties

    init(
        name: OnboardingAnalyticsEventName,
        properties: OnboardingAnalyticsProperties = OnboardingAnalyticsProperties()
    ) {
        self.name = name
        self.properties = properties
    }

    func encodedPayload() -> OnboardingAnalyticsPayload {
        OnboardingAnalyticsPayload(
            eventName: name.rawValue,
            properties: properties.encodedProperties()
        )
    }
}

nonisolated enum OnboardingAnalyticsEventName: String, CaseIterable, Sendable {
    case onboardingStarted = "onboarding_started"
    case onboardingStepViewed = "onboarding_step_viewed"
    case locationAuthorizationRequested = "onboarding_location_authorization_requested"
    case locationAuthorizationCompleted = "onboarding_location_authorization_completed"
    case trackingIntentChanged = "onboarding_tracking_intent_changed"
    case onboardingCompleted = "onboarding_completed"
    case paywallShown = "onboarding_paywall_shown"
    case purchaseStarted = "onboarding_purchase_started"
    case purchaseFinished = "onboarding_purchase_finished"
    case restoreStarted = "onboarding_restore_started"
    case restoreFinished = "onboarding_restore_finished"
}

nonisolated struct OnboardingAnalyticsPayload: Equatable, Sendable, Codable {
    let eventId: String?
    let eventName: String
    let properties: [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue]

    var transportProperties: [String: OnboardingAnalyticsValue] {
        Dictionary(uniqueKeysWithValues: properties.map { ($0.key.rawValue, $0.value) })
    }

    private enum CodingKeys: String, CodingKey {
        case eventId
        case eventName
        case properties
    }

    init(
        eventId: String? = nil,
        eventName: String,
        properties: [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue]
    ) {
        self.eventId = eventId
        self.eventName = eventName
        self.properties = properties
    }

    func withEventId(_ eventId: String) -> OnboardingAnalyticsPayload {
        OnboardingAnalyticsPayload(
            eventId: eventId,
            eventName: eventName,
            properties: properties
        )
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let eventId = try container.decodeIfPresent(String.self, forKey: .eventId)
        let eventName = try container.decode(String.self, forKey: .eventName)
        let transportProperties = try container.decode(
            [String: OnboardingAnalyticsValue].self,
            forKey: .properties
        )

        self.eventId = eventId
        self.eventName = eventName
        self.properties = Dictionary(
            uniqueKeysWithValues: transportProperties.compactMap { key, value in
                guard let propertyKey = OnboardingAnalyticsPropertyKey(rawValue: key) else { return nil }
                return (propertyKey, value)
            }
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(eventId, forKey: .eventId)
        try container.encode(eventName, forKey: .eventName)
        try container.encode(transportProperties, forKey: .properties)
    }
}

nonisolated enum OnboardingAnalyticsPropertyKey: String, CaseIterable, Sendable {
    case appVersion
    case buildNumber
    case platform
    case onboardingStep
    case authorizationStatus
    case authorizationRequestKind
    case trackingIntent
    case paywallContext
    case productId
    case purchaseOutcome
    case errorCategory
}

nonisolated enum OnboardingAnalyticsValue: Equatable, Sendable, Codable {
    case string(String)
    case int(Int)

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if let intValue = try? container.decode(Int.self) {
            self = .int(intValue)
            return
        }

        if let stringValue = try? container.decode(String.self) {
            self = .string(stringValue)
            return
        }

        throw DecodingError.typeMismatch(
            OnboardingAnalyticsValue.self,
            DecodingError.Context(
                codingPath: decoder.codingPath,
                debugDescription: "Expected onboarding analytics value to be a string or integer."
            )
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()

        switch self {
        case .string(let value):
            try container.encode(value)
        case .int(let value):
            try container.encode(value)
        }
    }
}

nonisolated struct OnboardingAnalyticsProperties: Equatable, Sendable {
    private let appVersion: String?
    private let buildNumber: String?
    private let platform: OnboardingAnalyticsPlatform?
    private let onboardingStep: OnboardingAnalyticsStep?
    private let authorizationStatus: OnboardingAnalyticsAuthorizationStatus?
    private let authorizationRequestKind: OnboardingAnalyticsAuthorizationRequestKind?
    private let trackingIntent: OnboardingAnalyticsTrackingIntent?
    private let paywallContext: OnboardingAnalyticsPaywallContext?
    private let productId: OnboardingAnalyticsProductID?
    private let purchaseOutcome: OnboardingAnalyticsPurchaseOutcome?
    private let errorCategory: OnboardingAnalyticsErrorCategory?

    init(
        appVersion: String? = nil,
        buildNumber: String? = nil,
        platform: OnboardingAnalyticsPlatform? = nil,
        onboardingStep: OnboardingAnalyticsStep? = nil,
        authorizationStatus: OnboardingAnalyticsAuthorizationStatus? = nil,
        authorizationRequestKind: OnboardingAnalyticsAuthorizationRequestKind? = nil,
        trackingIntent: OnboardingAnalyticsTrackingIntent? = nil,
        paywallContext: OnboardingAnalyticsPaywallContext? = nil,
        productId: OnboardingAnalyticsProductID? = nil,
        purchaseOutcome: OnboardingAnalyticsPurchaseOutcome? = nil,
        errorCategory: OnboardingAnalyticsErrorCategory? = nil
    ) {
        self.appVersion = OnboardingAnalyticsSanitizer.sanitizedAppVersion(appVersion)
        self.buildNumber = OnboardingAnalyticsSanitizer.sanitizedBuildNumber(buildNumber)
        self.platform = platform
        self.onboardingStep = onboardingStep
        self.authorizationStatus = authorizationStatus
        self.authorizationRequestKind = authorizationRequestKind
        self.trackingIntent = trackingIntent
        self.paywallContext = paywallContext
        self.productId = productId
        self.purchaseOutcome = purchaseOutcome
        self.errorCategory = errorCategory
    }

    func encodedProperties() -> [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue] {
        var encoded: [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue] = [:]

        encode(appVersion, for: .appVersion, into: &encoded)
        encode(buildNumber, for: .buildNumber, into: &encoded)
        encode(platform?.rawValue, for: .platform, into: &encoded)
        encode(onboardingStep?.rawValue, for: .onboardingStep, into: &encoded)
        encode(authorizationStatus?.rawValue, for: .authorizationStatus, into: &encoded)
        encode(authorizationRequestKind?.rawValue, for: .authorizationRequestKind, into: &encoded)
        encode(trackingIntent?.rawValue, for: .trackingIntent, into: &encoded)
        encode(paywallContext?.rawValue, for: .paywallContext, into: &encoded)
        encode(productId?.rawValue, for: .productId, into: &encoded)
        encode(purchaseOutcome?.rawValue, for: .purchaseOutcome, into: &encoded)
        encode(errorCategory?.rawValue, for: .errorCategory, into: &encoded)

        return encoded
    }

    private func encode(
        _ value: String?,
        for key: OnboardingAnalyticsPropertyKey,
        into encoded: inout [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue]
    ) {
        guard let value else { return }
        encoded[key] = .string(value)
    }
}

nonisolated enum OnboardingAnalyticsPlatform: String, CaseIterable, Sendable {
    case iOS = "ios"
}

nonisolated enum OnboardingAnalyticsStep: String, CaseIterable, Sendable {
    case welcome
    case features
    case permissions
    case photos
    case ready
}

nonisolated enum OnboardingAnalyticsAuthorizationStatus: String, CaseIterable, Sendable {
    case notDetermined = "not_determined"
    case whenInUse = "when_in_use"
    case always
    case denied
    case restricted
    case unknown
}

nonisolated enum OnboardingAnalyticsAuthorizationRequestKind: String, CaseIterable, Sendable {
    case whenInUse = "when_in_use"
    case always
    case settings
}

nonisolated enum OnboardingAnalyticsTrackingIntent: String, CaseIterable, Sendable {
    case startImmediately = "start_immediately"
    case later
    case unavailable
}

nonisolated enum OnboardingAnalyticsPaywallContext: String, CaseIterable, Sendable {
    case export
    case settings
    case webhook
    case onboarding
}

nonisolated enum OnboardingAnalyticsProductID: String, CaseIterable, Sendable {
    case individualLifetime = "com.bontecou.isome.lifetime.individual"
    case familyLifetime = "com.bontecou.isome.lifetime"
    case familyLifetimeUpgrade = "com.bontecou.isome.lifetime.family.upgrade"
    case individualWeekly = "com.bontecou.isome.individual.weekly"
    case individualYearly = "com.bontecou.isome.individual.yearly"
    case familyWeekly = "com.bontecou.isome.family.weekly"
    case familyYearly = "com.bontecou.isome.family.yearly"
}

nonisolated enum OnboardingAnalyticsPurchaseOutcome: String, CaseIterable, Sendable {
    case started
    case succeeded
    case failed
    case cancelled
    case pending
    case restored
    case notFound = "not_found"
}

nonisolated enum OnboardingAnalyticsErrorCategory: String, CaseIterable, Sendable {
    case productUnavailable = "product_unavailable"
    case storeUnavailable = "store_unavailable"
    case networkUnavailable = "network_unavailable"
    case userCancelled = "user_cancelled"
    case verificationFailed = "verification_failed"
    case paymentPending = "payment_pending"
    case notUnlocked = "not_unlocked"
    case unknown
}

nonisolated private enum OnboardingAnalyticsSanitizer {
    private static let digitCharacters = CharacterSet(charactersIn: "0123456789")
    private static let versionCharacters = CharacterSet(charactersIn: "0123456789.")

    static func sanitizedAppVersion(_ rawValue: String?) -> String? {
        guard let value = trimmed(rawValue), value.count <= 20 else { return nil }
        guard containsOnly(value, characters: versionCharacters) else { return nil }
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...4).contains(parts.count) else { return nil }
        guard parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) else { return nil }
        return value
    }

    static func sanitizedBuildNumber(_ rawValue: String?) -> String? {
        guard let value = trimmed(rawValue), (1...12).contains(value.count) else { return nil }
        guard containsOnly(value, characters: digitCharacters) else { return nil }
        return value
    }

    private static func trimmed(_ rawValue: String?) -> String? {
        guard let rawValue else { return nil }
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private static func containsOnly(_ value: String, characters: CharacterSet) -> Bool {
        value.unicodeScalars.allSatisfy { characters.contains($0) }
    }
}
