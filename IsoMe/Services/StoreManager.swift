import Foundation
import StoreKit

/// The one-time purchase options offered by Whereday.
///
/// `family` deliberately uses the original lifetime product ID. That product already
/// has Family Sharing enabled in App Store Connect, so every owner from before the
/// tiered plans launched is grandfathered into Family Lifetime automatically.
nonisolated enum IsoMePurchaseOption: String, CaseIterable, Identifiable, Sendable {
    case individual
    case family
    case familyUpgrade

    var id: String { rawValue }

    var productID: String {
        switch self {
        case .individual:
            return "com.bontecou.isome.lifetime.individual"
        case .family:
            return "com.bontecou.isome.lifetime"
        case .familyUpgrade:
            return "com.bontecou.isome.lifetime.family.upgrade"
        }
    }

    var analyticsProductID: OnboardingAnalyticsProductID {
        switch self {
        case .individual:
            return .individualLifetime
        case .family:
            return .familyLifetime
        case .familyUpgrade:
            return .familyLifetimeUpgrade
        }
    }

    var isFamilyPlan: Bool {
        switch self {
        case .family, .familyUpgrade:
            return true
        case .individual:
            return false
        }
    }
}

nonisolated enum StorePurchaseFailure: Equatable, Sendable {
    case notEligible
    case productUnavailable
    case verificationFailed
    case storeError
}

nonisolated enum StorePurchaseOutcome: Equatable, Sendable {
    case succeeded
    case cancelled
    case pending
    case failed(StorePurchaseFailure)
}

nonisolated enum StoreRestoreOutcome: Equatable, Sendable {
    case restored
    case notFound
    case failed
}

/// Manages Individual Lifetime, Family Lifetime, and Family Upgrade purchases
/// through StoreKit 2. Apple stores transaction history, so entitlements survive
/// deletion and reinstall without a separate Whereday account or server.
@MainActor
final class StoreManager: ObservableObject {
    static let shared = StoreManager()

    nonisolated static let individualProductID = IsoMePurchaseOption.individual.productID
    nonisolated static let familyProductID = IsoMePurchaseOption.family.productID
    nonisolated static let familyUpgradeProductID = IsoMePurchaseOption.familyUpgrade.productID
    nonisolated static let productIDs = IsoMePurchaseOption.allCases.map(\.productID)

    @Published private(set) var productsByID: [String: Product] = [:]
    @Published private(set) var isPurchased: Bool = false
    @Published private(set) var unlockedProductID: String?
    @Published private(set) var purchaseError: String?
    @Published private(set) var productLoadError: String?
    @Published private(set) var isLoadingProducts: Bool = false
    @Published private(set) var purchasingOption: IsoMePurchaseOption?
    @Published private(set) var isRestoring: Bool = false

    var isLoading: Bool {
        isLoadingProducts || purchasingOption != nil || isRestoring
    }

    var activePurchaseOption: IsoMePurchaseOption? {
        guard let unlockedProductID else { return nil }
        return IsoMePurchaseOption.allCases.first { $0.productID == unlockedProductID }
    }

    var isIndividualUnlocked: Bool {
        unlockedProductID == Self.individualProductID
    }

    var isFamilyUnlocked: Bool {
        guard let unlockedProductID else { return false }
        return Self.isFamilyEntitlement(productID: unlockedProductID)
    }

    /// App Store Connect cannot make the upgrade product depend on the Individual
    /// product, so eligibility is enforced in-app. A family member receiving a
    /// shared upgrade transaction still unlocks normally without the base purchase.
    var canBuyFamilyUpgrade: Bool {
        Self.canBuyFamilyUpgrade(unlockedProductID: unlockedProductID)
    }

    private var transactionListener: Task<Void, Never>?

    private init() {
        #if DEBUG
        // Preserve deterministic development and screenshot behavior. Pass
        // `--use-storekit` when running from Xcode to exercise Whereday.storekit.
        if !ProcessInfo.processInfo.arguments.contains("--use-storekit") {
            let debugProductID = ProcessInfo.processInfo.arguments.contains("--review-upgrade-paywall")
                ? Self.individualProductID
                : Self.familyProductID
            applyEntitlement(productID: debugProductID)
            return
        }
        #endif

        transactionListener = listenForTransactions()
        Task {
            await loadProducts()
            await checkEntitlement()
        }
    }

    deinit {
        transactionListener?.cancel()
    }

    // MARK: - Product Catalog

    func product(for option: IsoMePurchaseOption) -> Product? {
        productsByID[option.productID]
    }

    func loadProducts() async {
        guard !isLoadingProducts else { return }

        isLoadingProducts = true
        productLoadError = nil
        defer { isLoadingProducts = false }

        do {
            let products = try await Product.products(for: Self.productIDs)
            productsByID = Dictionary(uniqueKeysWithValues: products.map { ($0.id, $0) })

            if productsByID.isEmpty {
                productLoadError = String(localized: "Purchase options are unavailable. Please try again later.")
            }
        } catch {
            productLoadError = String(localized: "Failed to load purchase options: \(error.localizedDescription)")
        }
    }

    // MARK: - Purchase

    @discardableResult
    func purchase(_ option: IsoMePurchaseOption = .individual) async -> StorePurchaseOutcome {
        if option == .familyUpgrade, !canBuyFamilyUpgrade {
            purchaseError = String(localized: "Family Upgrade requires an Individual Lifetime purchase.")
            return .failed(.notEligible)
        }

        var selectedProduct = product(for: option)
        if selectedProduct == nil {
            await loadProducts()
            selectedProduct = product(for: option)
        }

        guard let selectedProduct else {
            purchaseError = String(localized: "Product not available")
            return .failed(.productUnavailable)
        }

        purchasingOption = option
        purchaseError = nil
        defer { purchasingOption = nil }

        do {
            let result = try await selectedProduct.purchase()
            switch result {
            case .success(let verification):
                let transaction: Transaction
                do {
                    transaction = try checkVerified(verification)
                } catch {
                    purchaseError = String(localized: "Purchase verification failed.")
                    return .failed(.verificationFailed)
                }

                guard Self.productIDs.contains(transaction.productID),
                      transaction.revocationDate == nil else {
                    purchaseError = String(localized: "Purchase verification failed.")
                    return .failed(.verificationFailed)
                }

                await transaction.finish()
                applyPreferredEntitlement(adding: transaction.productID)
                return .succeeded

            case .userCancelled:
                return .cancelled

            case .pending:
                purchaseError = String(localized: "Purchase is pending approval")
                return .pending

            @unknown default:
                purchaseError = String(localized: "Purchase failed. Please try again.")
                return .failed(.storeError)
            }
        } catch {
            purchaseError = String(localized: "Purchase failed: \(error.localizedDescription)")
            return .failed(.storeError)
        }
    }

    /// Syncs with the App Store and re-evaluates every supported entitlement,
    /// including Family Sharing transactions received from another family member.
    @discardableResult
    func restorePurchases() async -> StoreRestoreOutcome {
        isRestoring = true
        purchaseError = nil
        defer { isRestoring = false }

        do {
            try await AppStore.sync()
        } catch {
            purchaseError = String(localized: "Restore failed: \(error.localizedDescription)")
            return .failed
        }

        await checkEntitlement()
        guard isPurchased else {
            purchaseError = String(localized: "No purchase found. For a Family plan, confirm that Purchase Sharing is enabled, then try again.")
            return .notFound
        }

        return .restored
    }

    // MARK: - Entitlements

    /// Reconciles all current transactions instead of toggling access from a single
    /// update. This prevents one revoked product from locking someone who still owns
    /// another valid Individual or Family entitlement.
    func checkEntitlement() async {
        var activeProductIDs: [String] = []

        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  Self.productIDs.contains(transaction.productID),
                  transaction.revocationDate == nil else {
                continue
            }
            activeProductIDs.append(transaction.productID)
        }

        if let productID = Self.preferredEntitlementProductID(from: activeProductIDs) {
            applyEntitlement(productID: productID)
        } else {
            clearEntitlement()
        }
    }

    nonisolated static func isFamilyEntitlement(productID: String) -> Bool {
        productID == familyProductID || productID == familyUpgradeProductID
    }

    nonisolated static func canBuyFamilyUpgrade(unlockedProductID: String?) -> Bool {
        unlockedProductID == individualProductID
    }

    /// Prefer the complete Family purchase, then the Family Upgrade, then Individual.
    /// Existing owners have the original Family product ID and therefore always win.
    nonisolated static func preferredEntitlementProductID<S: Sequence>(
        from productIDs: S
    ) -> String? where S.Element == String {
        let available = Set(productIDs)
        return [familyProductID, familyUpgradeProductID, individualProductID]
            .first { available.contains($0) }
    }

    private func applyPreferredEntitlement(adding productID: String) {
        let candidates = [unlockedProductID, productID].compactMap { $0 }
        guard let preferred = Self.preferredEntitlementProductID(from: candidates) else { return }
        applyEntitlement(productID: preferred)
    }

    private func applyEntitlement(productID: String) {
        unlockedProductID = productID
        isPurchased = true
    }

    private func clearEntitlement() {
        unlockedProductID = nil
        isPurchased = false
    }

    // MARK: - Transaction Listener

    private func listenForTransactions() -> Task<Void, Never> {
        let supportedProductIDs = Set(Self.productIDs)
        return Task.detached {
            for await result in Transaction.updates {
                guard case .verified(let transaction) = result,
                      supportedProductIDs.contains(transaction.productID) else {
                    continue
                }

                await transaction.finish()
                await StoreManager.shared.checkEntitlement()
            }
        }
    }

    // MARK: - Verification

    private func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .unverified(_, let error):
            throw error
        case .verified(let item):
            return item
        }
    }
}
