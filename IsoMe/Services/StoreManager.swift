import Foundation
import StoreKit
import CoreLocation

nonisolated enum IsoMePurchaseOption: String, CaseIterable, Identifiable, Sendable {
    // Preserve every legacy ID: previous purchases remain valid.
    case individual, family, familyUpgrade
    case individualWeekly, individualYearly, familyWeekly, familyYearly

    var id: String { rawValue }
    var productID: String { analyticsProductID.rawValue }
    var analyticsProductID: OnboardingAnalyticsProductID {
        switch self {
        case .individual: return .individualLifetime
        case .family: return .familyLifetime
        case .familyUpgrade: return .familyLifetimeUpgrade
        case .individualWeekly: return .individualWeekly
        case .individualYearly: return .individualYearly
        case .familyWeekly: return .familyWeekly
        case .familyYearly: return .familyYearly
        }
    }
    var isFamilyPlan: Bool { [.family, .familyUpgrade, .familyWeekly, .familyYearly].contains(self) }
    var isSubscription: Bool { [.individualWeekly, .individualYearly, .familyWeekly, .familyYearly].contains(self) }
    var title: String {
        switch self {
        case .individual: return String(localized: "Individual Lifetime")
        case .family: return String(localized: "Family Lifetime")
        case .familyUpgrade: return String(localized: "Family Lifetime Upgrade")
        case .individualWeekly: return String(localized: "Individual Weekly")
        case .individualYearly: return String(localized: "Individual Yearly")
        case .familyWeekly: return String(localized: "Family Weekly")
        case .familyYearly: return String(localized: "Family Yearly")
        }
    }
    /// Localize the whole caption, not an English preposition plus a period name.
    var billingCaption: String {
        switch self {
        case .individualWeekly, .familyWeekly: return String(localized: "per week")
        case .individualYearly, .familyYearly: return String(localized: "per year")
        default: return String(localized: "once")
        }
    }
}

nonisolated enum StorePurchaseFailure: Equatable, Sendable {
    case notEligible, productUnavailable, verificationFailed, storeError
}
nonisolated enum StorePurchaseOutcome: Equatable, Sendable {
    case succeeded, cancelled, pending, failed(StorePurchaseFailure)
}
nonisolated enum StoreRestoreOutcome: Equatable, Sendable {
    case restored, notFound, failed
}

nonisolated enum TrackingAccessPolicy {
    static func initializeLegacyCohort(defaults: UserDefaults, hasLocationAuthorization: Bool) -> Bool {
        let key = PaidTrackingMigration.legacyCohortKey
        if defaults.object(forKey: key) == nil {
            let existing = defaults.object(forKey: "hasCompletedOnboarding") != nil
                || defaults.object(forKey: "isTrackingEnabled") != nil || hasLocationAuthorization
            defaults.set(existing, forKey: key)
        }
        return defaults.bool(forKey: key)
    }

    static func isLegacyOriginalVersion(_ version: String) -> Bool {
        PaidTrackingMigration.isLegacyOriginalVersion(version)
    }

    static func canTrack(legacy: Bool, purchased: Bool, expiration: Date?, now: Date = Date()) -> Bool {
        legacy || (purchased && (expiration.map { $0 > now } ?? true))
    }
    static func canExport(legacy: Bool, purchased: Bool) -> Bool { !legacy || purchased }
    static func requiresPaywall(legacy: Bool, purchasedBefore: Bool, purchased: Bool) -> Bool {
        !legacy && !purchasedBefore && !purchased
    }
}

@MainActor
final class StoreManager: ObservableObject {
    static let shared = StoreManager()
    nonisolated static let individualProductID = IsoMePurchaseOption.individual.productID
    nonisolated static let familyProductID = IsoMePurchaseOption.family.productID
    nonisolated static let familyUpgradeProductID = IsoMePurchaseOption.familyUpgrade.productID
    nonisolated static let productIDs = IsoMePurchaseOption.allCases.map(\.productID)
    nonisolated static let firstPaidTrackingBuild = PaidTrackingMigration.firstPaidTrackingBuild

    @Published private(set) var productsByID: [String: Product] = [:]
    @Published private(set) var isPurchased = false
    @Published private(set) var unlockedProductID: String?
    @Published private(set) var purchaseError: String?
    @Published private(set) var productLoadError: String?
    @Published private(set) var isLoadingProducts = false
    @Published private(set) var purchasingOption: IsoMePurchaseOption?
    @Published private(set) var isRestoring = false
    @Published private(set) var hasResolvedEntitlements = false
    @Published private(set) var hasLegacyTrackingAccess = false
    @Published private(set) var hasPurchasedBefore = false
    @Published private(set) var trackingExpiration: Date?
    @Published private(set) var trialEligibleProductIDs: Set<String> = []

    var isLoading: Bool { isLoadingProducts || purchasingOption != nil || isRestoring }
    var activePurchaseOption: IsoMePurchaseOption? {
        IsoMePurchaseOption.allCases.first { $0.productID == unlockedProductID }
    }
    var isIndividualUnlocked: Bool { activePurchaseOption?.isFamilyPlan == false }
    var isFamilyUnlocked: Bool { activePurchaseOption?.isFamilyPlan == true }
    var canBuyFamilyUpgrade: Bool { Self.canBuyFamilyUpgrade(unlockedProductID: unlockedProductID) }
    var canTrack: Bool {
        TrackingAccessPolicy.canTrack(legacy: hasLegacyTrackingAccess, purchased: isPurchased, expiration: trackingExpiration)
    }
    // New-cohort users retain access to their own exports after expiration.
    // Existing free users retain the previous export-only purchase requirement.
    var canExport: Bool { TrackingAccessPolicy.canExport(legacy: hasLegacyTrackingAccess, purchased: isPurchased) }
    var requiresInitialPaywall: Bool {
        TrackingAccessPolicy.requiresPaywall(legacy: hasLegacyTrackingAccess, purchasedBefore: hasPurchasedBefore, purchased: isPurchased)
    }
    private var transactionListener: Task<Void, Never>?
    private var expirationTask: Task<Void, Never>?
    private var reconciliationTask: Task<Void, Never>?
    private var reconciliationGeneration = 0
    private let defaults: UserDefaults
    private let forceStoreKit: Bool

    init(defaults: UserDefaults = .standard, forceStoreKit: Bool = false) {
        self.defaults = defaults
        self.forceStoreKit = forceStoreKit
        hasLegacyTrackingAccess = TrackingAccessPolicy.initializeLegacyCohort(
            defaults: defaults, hasLocationAuthorization: CLLocationManager().authorizationStatus != .notDetermined
        )
        hasPurchasedBefore = defaults.bool(forKey: "tracking.hasPurchased.v1")
        #if DEBUG
        if !forceStoreKit && !ProcessInfo.processInfo.arguments.contains("--use-storekit") {
            if !ProcessInfo.processInfo.arguments.contains("--review-hard-paywall") {
                let id = ProcessInfo.processInfo.arguments.contains("--review-upgrade-paywall")
                    ? Self.individualProductID : Self.familyProductID
                applyEntitlement(productID: id)
            }
            hasResolvedEntitlements = true
            return
        }
        #endif
        transactionListener = Task { [weak self] in
            for await result in Transaction.updates {
                guard case .verified(let transaction) = result,
                      Self.productIDs.contains(transaction.productID) else { continue }
                await transaction.finish()
                await self?.checkEntitlement()
            }
        }
        Task {
            await checkEntitlement()
            await loadProducts()
        }
    }
    deinit {
        transactionListener?.cancel()
        expirationTask?.cancel()
    }

    func product(for option: IsoMePurchaseOption) -> Product? { productsByID[option.productID] }

    func loadProducts() async {
        guard !isLoadingProducts else { return }
        isLoadingProducts = true
        productLoadError = nil
        defer { isLoadingProducts = false }
        do {
            let products = try await Product.products(for: Self.productIDs)
            productsByID = Dictionary(uniqueKeysWithValues: products.map { ($0.id, $0) })
            var eligible: Set<String> = []
            for product in products {
                if let subscription = product.subscription,
                   let offer = subscription.introductoryOffer,
                   offer.paymentMode == .freeTrial,
                   ((offer.period.unit == .day && offer.period.value == 7)
                    || (offer.period.unit == .week && offer.period.value == 1)),
                   await subscription.isEligibleForIntroOffer {
                    eligible.insert(product.id)
                }
            }
            trialEligibleProductIDs = eligible
            if productsByID.isEmpty {
                productLoadError = String(localized: "Purchase options are unavailable. Please try again later.")
            }
        } catch {
            productLoadError = String(localized: "Failed to load purchase options: \(error.localizedDescription)")
        }
    }

    @discardableResult
    func purchase(_ option: IsoMePurchaseOption = .individual) async -> StorePurchaseOutcome {
        guard !isRestoring, purchasingOption == nil else { return .failed(.storeError) }
        if option == .familyUpgrade && !canBuyFamilyUpgrade {
            purchaseError = String(localized: "Family Upgrade requires an Individual Lifetime purchase.")
            return .failed(.notEligible)
        }
        purchasingOption = option
        purchaseError = nil
        defer { purchasingOption = nil }
        if product(for: option) == nil { await loadProducts() }
        guard let product = product(for: option) else {
            purchaseError = String(localized: "Product not available")
            return .failed(.productUnavailable)
        }
        do {
            switch try await product.purchase() {
            case .success(let verification):
                guard case .verified(let transaction) = verification,
                      Self.productIDs.contains(transaction.productID),
                      Self.isActive(revocationDate: transaction.revocationDate,
                                    expirationDate: transaction.expirationDate,
                                    isUpgraded: transaction.isUpgraded) else {
                    purchaseError = String(localized: "Purchase verification failed.")
                    return .failed(.verificationFailed)
                }
                rememberPurchase()
                await transaction.finish()
                await checkEntitlement()
                return .succeeded
            case .userCancelled: return .cancelled
            case .pending:
                purchaseError = String(localized: "Purchase is pending approval")
                return .pending
            @unknown default: return .failed(.storeError)
            }
        } catch {
            purchaseError = String(localized: "Purchase failed: \(error.localizedDescription)")
            return .failed(.storeError)
        }
    }

    @discardableResult
    func restorePurchases() async -> StoreRestoreOutcome {
        guard !isRestoring, purchasingOption == nil else { return .failed }
        isRestoring = true
        purchaseError = nil
        defer { isRestoring = false }
        do { try await AppStore.sync() } catch {
            purchaseError = String(localized: "Restore failed: \(error.localizedDescription)")
            return .failed
        }
        await checkEntitlement()
        guard isPurchased else {
            purchaseError = String(localized: "No active purchase found. For a Family plan, check your Family Sharing settings.")
            return .notFound
        }
        return .restored
    }

    // Serialize snapshots: startup, transaction updates, foreground refresh, and
    // purchase completion can otherwise overwrite each other with stale results.
    func checkEntitlement() async {
        reconciliationGeneration += 1
        let generation = reconciliationGeneration
        let preceding = reconciliationTask
        let task = Task { [weak self] in
            await preceding?.value
            await self?.reconcileEntitlement()
        }
        reconciliationTask = task
        await task.value
        if generation == reconciliationGeneration { reconciliationTask = nil }
    }

    private func reconcileEntitlement() async {
        #if DEBUG
        if !forceStoreKit && !ProcessInfo.processInfo.arguments.contains("--use-storekit") { return }
        #endif
        if !hasLegacyTrackingAccess,
           let result = try? await AppTransaction.shared,
           case .verified(let app) = result,
           PaidTrackingMigration.restoreLegacyAccess(verifiedOriginalVersion: app.originalAppVersion, defaults: defaults) {
            hasLegacyTrackingAccess = true
        }
        var active: [String] = []
        var expirations: [String: Date] = [:]
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  Self.productIDs.contains(transaction.productID),
                  Self.isActive(revocationDate: transaction.revocationDate,
                                expirationDate: transaction.expirationDate,
                                isUpgraded: transaction.isUpgraded) else { continue }
            active.append(transaction.productID)
            expirations[transaction.productID] = transaction.expirationDate
        }
        let preferred = Self.preferredEntitlementProductID(from: active)
        trackingExpiration = preferred.flatMap { expirations[$0] }
        unlockedProductID = preferred
        isPurchased = preferred != nil
        if isPurchased { rememberPurchase() }
        if !hasPurchasedBefore {
            for await result in Transaction.all {
                if case .verified(let transaction) = result, Self.productIDs.contains(transaction.productID) {
                    rememberPurchase()
                    break
                }
            }
        }
        hasResolvedEntitlements = true
        expirationTask?.cancel()
        if let expiration = trackingExpiration {
            expirationTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(max(0, expiration.timeIntervalSinceNow)))
                guard !Task.isCancelled else { return }
                await self?.checkEntitlement()
            }
        }
    }

    nonisolated static func isActive(revocationDate: Date?, expirationDate: Date?, isUpgraded: Bool, now: Date = Date()) -> Bool {
        revocationDate == nil && !isUpgraded && (expirationDate.map { $0 > now } ?? true)
    }
    nonisolated static func isFamilyEntitlement(productID: String) -> Bool {
        IsoMePurchaseOption.allCases.first { $0.productID == productID }?.isFamilyPlan == true
    }
    nonisolated static func canBuyFamilyUpgrade(unlockedProductID: String?) -> Bool {
        unlockedProductID == individualProductID
    }
    nonisolated static func preferredEntitlementProductID<S: Sequence>(from productIDs: S) -> String? where S.Element == String {
        let available = Set(productIDs)
        return [familyProductID, familyUpgradeProductID, individualProductID,
                IsoMePurchaseOption.familyYearly.productID, IsoMePurchaseOption.familyWeekly.productID,
                IsoMePurchaseOption.individualYearly.productID, IsoMePurchaseOption.individualWeekly.productID]
            .first { available.contains($0) }
    }
    private func applyEntitlement(productID: String) {
        unlockedProductID = productID
        isPurchased = true
        // Debug unlocks must not mutate the real purchase history/cohort.
    }
    private func rememberPurchase() {
        hasPurchasedBefore = true
        defaults.set(true, forKey: "tracking.hasPurchased.v1")
    }
}
