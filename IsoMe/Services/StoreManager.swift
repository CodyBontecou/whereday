import Foundation
import StoreKit
import CoreLocation
import UserNotifications

nonisolated enum IsoMePurchaseOption: String, CaseIterable, Identifiable, Sendable {
    // Preserve every legacy ID: previous purchases remain valid.
    case individual, family, familyUpgrade
    case individualWeekly, individualYearly, familyWeekly, familyYearly

    var id: String { rawValue }
    var productID: String {
        switch self {
        case .individual: return "com.bontecou.isome.lifetime.individual"
        case .family: return "com.bontecou.isome.lifetime"
        case .familyUpgrade: return "com.bontecou.isome.lifetime.family.upgrade"
        case .individualWeekly: return "com.bontecou.isome.individual.weekly"
        case .individualYearly: return "com.bontecou.isome.individual.yearly"
        case .familyWeekly: return "com.bontecou.isome.family.weekly"
        case .familyYearly: return "com.bontecou.isome.family.yearly"
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

/// The reminder the paywall's free-week page promises: two days before a free trial ends.
enum TrialReminder {
    static let identifier = "com.bontecou.isome.trial-reminder"
    static let leadTime: TimeInterval = 2 * 24 * 60 * 60

    /// Nil when the trial ends too soon for a reminder to be useful.
    nonisolated static func fireDate(trialEnds: Date, now: Date = Date()) -> Date? {
        let date = trialEnds.addingTimeInterval(-leadTime)
        return date > now ? date : nil
    }

    nonisolated static func body(displayPrice: String, renewal: Date, bundle: Bundle = .main) -> String {
        let day = renewal.formatted(date: .abbreviated, time: .omitted)
        return String(localized: "Your plan starts on \(day) at \(displayPrice). Cancel in Apple settings before then if you don't want to continue.", bundle: bundle)
    }

    /// Asks for notification permission at the moment it's needed: right after the trial starts.
    static func schedule(trialEnds: Date, displayPrice: String, center: UNUserNotificationCenter = .current()) async {
        guard let fireDate = fireDate(trialEnds: trialEnds),
              (try? await center.requestAuthorization(options: [.alert, .sound])) == true else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Your free week ends in 2 days")
        content.body = body(displayPrice: displayPrice, renewal: trialEnds)
        content.sound = .default
        let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fireDate)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
    }

    static func cancel(center: UNUserNotificationCenter = .current()) {
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
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
    /// StoreKit's renewal intent for the active subscription: true renews, false ends at `trackingExpiration`,
    /// nil when there's no subscription or the status couldn't be read.
    @Published private(set) var willAutoRenew: Bool?
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
        let offersTrial = option.isSubscription && trialEligibleProductIDs.contains(option.productID)
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
                if offersTrial, Self.isIntroductoryOffer(transaction), let trialEnds = transaction.expirationDate {
                    await TrialReminder.schedule(trialEnds: trialEnds, displayPrice: product.displayPrice)
                }
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
        var groupIDs: [String: String] = [:]
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  Self.productIDs.contains(transaction.productID),
                  Self.isActive(revocationDate: transaction.revocationDate,
                                expirationDate: transaction.expirationDate,
                                isUpgraded: transaction.isUpgraded) else { continue }
            active.append(transaction.productID)
            expirations[transaction.productID] = transaction.expirationDate
            groupIDs[transaction.productID] = transaction.subscriptionGroupID
        }
        let preferred = Self.preferredEntitlementProductID(from: active)
        trackingExpiration = preferred.flatMap { expirations[$0] }
        if let preferred, let groupID = groupIDs[preferred] {
            willAutoRenew = await Self.autoRenewStatus(productID: preferred, subscriptionGroupID: groupID)
        } else {
            willAutoRenew = nil
        }
        unlockedProductID = preferred
        isPurchased = preferred != nil
        // A canceled trial, or Lifetime bought during one, has nothing left to remind about.
        if willAutoRenew == false || (hasResolvedEntitlements && activePurchaseOption?.isSubscription != true) {
            TrialReminder.cancel()
        }
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

    /// Reads the verified renewal info for the subscription that grants access.
    private static func autoRenewStatus(productID: String, subscriptionGroupID: String) async -> Bool? {
        guard let statuses = try? await Product.SubscriptionInfo.status(for: subscriptionGroupID) else { return nil }
        let matching = statuses.first { status in
            if case .verified(let renewal) = status.renewalInfo, renewal.currentProductID == productID { return true }
            if case .verified(let transaction) = status.transaction, transaction.productID == productID { return true }
            return false
        } ?? statuses.first
        guard let matching, case .verified(let renewal) = matching.renewalInfo else { return nil }
        return renewal.willAutoRenew
    }

    /// `Transaction.offer` is iOS 17.2+; earlier iOS 17 releases only have `offerType`.
    nonisolated static func isIntroductoryOffer(_ transaction: Transaction) -> Bool {
        if #available(iOS 17.2, *) {
            return transaction.offer?.type == .introductory
        }
        return transaction.offerType == .introductory
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
