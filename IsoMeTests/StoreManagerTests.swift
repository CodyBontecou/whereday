import XCTest
import StoreKitTest
import SwiftUI
@testable import IsoMe

final class StoreManagerTests: XCTestCase {
    @MainActor
    func testPendingPurchaseCheckDoesNotBlockAppContent() async {
        // Freeze StoreKit at its unresolved launch state, including a fresh user's
        // provisional paywall requirement. The actual launch gate must show content.
        let opened = expectation(description: "App content opens without a StoreKit response")
        let gate = PurchaseLaunchGate(hasResolvedEntitlements: false, requiresInitialPaywall: true) {
            Text("Paywall").onAppear { XCTFail("Pending verification must not force a purchase") }
        } content: {
            Text("Journal").onAppear { opened.fulfill() }
        }
        let host = UIHostingController(rootView: gate)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        defer { window.isHidden = true; window.rootViewController = nil }
        await fulfillment(of: [opened], timeout: 1)
    }

    @MainActor
    func testResolvedNewUserStillSeesRequiredPaywall() async {
        let opened = expectation(description: "Confirmed new user sees the required paywall")
        let gate = PurchaseLaunchGate(hasResolvedEntitlements: true, requiresInitialPaywall: true) {
            Text("Paywall").onAppear { opened.fulfill() }
        } content: {
            Text("Journal").onAppear { XCTFail("A confirmed paywall requirement must be enforced") }
        }
        let host = UIHostingController(rootView: gate)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        defer { window.isHidden = true; window.rootViewController = nil }
        await fulfillment(of: [opened], timeout: 1)
    }

    func testPaywallCopyLocalizesEveryBranchAndPreservesStoreKitPrice() throws {
        let locales = ["ar", "bn", "en", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans"]
        // An already-formatted storefront price must not be parsed or reformatted.
        let price = "9\u{202F}876,54\u{00A0}€"
        for locale in locales {
            let url = Bundle.main.bundleURL.appendingPathComponent("\(locale).lproj")
            let bundle = try XCTUnwrap(Bundle(url: url), "Missing compiled locale: \(locale)")
            for option in IsoMePurchaseOption.allCases {
                for trial in [false, true] {
                    let key: String
                    switch option {
                    case .individualWeekly, .familyWeekly:
                        key = trial ? "7 days free, then %@ per week, automatically renewing until canceled."
                            : "%@ per week, automatically renewing until canceled."
                    case .individualYearly, .familyYearly:
                        key = trial ? "7 days free, then %@ per year, automatically renewing until canceled."
                            : "%@ per year, automatically renewing until canceled."
                    case .individual, .family, .familyUpgrade:
                        key = "%@ charged once to your Apple Account. No subscription."
                    }
                    let template = bundle.localizedString(forKey: key, value: nil, table: nil)
                    if locale != "en" { XCTAssertNotEqual(template, key, "\(locale): \(key)") }
                    let copy = PaywallCopy.disclosure(for: option, displayPrice: price, hasTrial: trial, bundle: bundle)
                    // Foundation isolates interpolated prices in RTL text; those marks
                    // prevent the currency and digits from being reordered visually.
                    let insertion = locale == "ar" ? "\u{2068}\(price)\u{2069}" : price
                    XCTAssertEqual(copy, String(format: template, insertion), "\(locale): \(option), trial=\(trial)")
                    XCTAssertTrue(copy.contains(price), "Price changed: \(locale): \(option)")
                    XCTAssertFalse(copy.contains("%@"))

                    let buttonKey = option == .familyUpgrade ? "Upgrade to Family"
                        : (!option.isSubscription ? "Buy Lifetime" : (trial ? "Start 7-day free trial" : "Subscribe"))
                    let button = bundle.localizedString(forKey: buttonKey, value: nil, table: nil)
                    if locale != "en" { XCTAssertNotEqual(button, buttonKey, "\(locale): \(buttonKey)") }
                    XCTAssertEqual(PaywallCopy.purchaseButtonTitle(for: option, hasTrial: trial, bundle: bundle), button)
                }
            }
        }
    }

    func testProductCatalogContainsIndividualFamilyAndUpgrade() {
        XCTAssertEqual(
            Set(IsoMePurchaseOption.allCases),
            Set([.individual, .family, .familyUpgrade, .individualWeekly, .individualYearly, .familyWeekly, .familyYearly])
        )
        XCTAssertEqual(
            Set(StoreManager.productIDs),
            Set([
                "com.bontecou.isome.lifetime.individual",
                "com.bontecou.isome.lifetime",
                "com.bontecou.isome.lifetime.family.upgrade",
                "com.bontecou.isome.individual.weekly",
                "com.bontecou.isome.individual.yearly",
                "com.bontecou.isome.family.weekly",
                "com.bontecou.isome.family.yearly",
            ])
        )
    }

    func testOriginalLifetimeProductIsTheGrandfatheredFamilyPlan() {
        XCTAssertEqual(StoreManager.familyProductID, "com.bontecou.isome.lifetime")
        XCTAssertTrue(StoreManager.isFamilyEntitlement(productID: StoreManager.familyProductID))
    }

    func testFamilyUpgradeEligibilityRequiresOnlyIndividualLifetime() {
        XCTAssertTrue(
            StoreManager.canBuyFamilyUpgrade(unlockedProductID: StoreManager.individualProductID)
        )
        XCTAssertFalse(
            StoreManager.canBuyFamilyUpgrade(unlockedProductID: StoreManager.familyProductID)
        )
        XCTAssertFalse(
            StoreManager.canBuyFamilyUpgrade(unlockedProductID: StoreManager.familyUpgradeProductID)
        )
        XCTAssertFalse(StoreManager.canBuyFamilyUpgrade(unlockedProductID: nil))
    }

    func testPreferredEntitlementPrioritizesFamilyOverUpgradeOverIndividual() {
        XCTAssertEqual(
            StoreManager.preferredEntitlementProductID(from: [
                StoreManager.individualProductID,
                StoreManager.familyUpgradeProductID,
                StoreManager.familyProductID,
            ]),
            StoreManager.familyProductID
        )
        XCTAssertEqual(
            StoreManager.preferredEntitlementProductID(from: [
                StoreManager.individualProductID,
                StoreManager.familyUpgradeProductID,
            ]),
            StoreManager.familyUpgradeProductID
        )
        XCTAssertEqual(
            StoreManager.preferredEntitlementProductID(from: [StoreManager.individualProductID]),
            StoreManager.individualProductID
        )
        XCTAssertNil(
            StoreManager.preferredEntitlementProductID(from: ["com.example.unknown"])
        )
    }

    func testExpirationRevocationAndUpgradeRemoveAccess() {
        let now = Date(timeIntervalSince1970: 1_000)
        XCTAssertTrue(StoreManager.isActive(revocationDate: nil, expirationDate: nil, isUpgraded: false, now: now))
        XCTAssertTrue(StoreManager.isActive(revocationDate: nil, expirationDate: now.addingTimeInterval(1), isUpgraded: false, now: now))
        XCTAssertFalse(StoreManager.isActive(revocationDate: nil, expirationDate: now, isUpgraded: false, now: now))
        XCTAssertFalse(StoreManager.isActive(revocationDate: now, expirationDate: nil, isUpgraded: false, now: now))
        XCTAssertFalse(StoreManager.isActive(revocationDate: nil, expirationDate: nil, isUpgraded: true, now: now))
    }

    func testSubscriptionClassificationAndLifetimePreference() {
        for option in [IsoMePurchaseOption.familyWeekly, .familyYearly] {
            XCTAssertTrue(option.isSubscription)
            XCTAssertTrue(StoreManager.isFamilyEntitlement(productID: option.productID))
        }
        XCTAssertFalse(IsoMePurchaseOption.individualWeekly.isFamilyPlan)
        XCTAssertFalse(IsoMePurchaseOption.individual.isSubscription)
        XCTAssertEqual(StoreManager.preferredEntitlementProductID(from: [IsoMePurchaseOption.familyYearly.productID, StoreManager.individualProductID]), StoreManager.individualProductID)
    }

    func testCohortMigrationIsStickyAndDoesNotGrandfatherNewOnboarding() {
        let suite = "StoreManagerTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertFalse(TrackingAccessPolicy.initializeLegacyCohort(defaults: defaults, hasLocationAuthorization: false))
        defaults.set(true, forKey: "hasCompletedOnboarding")
        defaults.set(true, forKey: "isTrackingEnabled")
        XCTAssertFalse(TrackingAccessPolicy.initializeLegacyCohort(defaults: defaults, hasLocationAuthorization: true))
        defaults.removePersistentDomain(forName: suite)
        defaults.set(false, forKey: "isTrackingEnabled")
        XCTAssertTrue(TrackingAccessPolicy.initializeLegacyCohort(defaults: defaults, hasLocationAuthorization: false))
    }

    func testOriginalBuildMigrationBoundary() {
        XCTAssertTrue(TrackingAccessPolicy.isLegacyOriginalVersion("49"))
        XCTAssertTrue(TrackingAccessPolicy.isLegacyOriginalVersion("50"))
        XCTAssertFalse(TrackingAccessPolicy.isLegacyOriginalVersion("51"))
        XCTAssertFalse(TrackingAccessPolicy.isLegacyOriginalVersion("52"))
        for invalid in ["", "invalid", "0", "-1", "+50", "50.0", "1.7.18", " 50", "9999999999999999999999999"] {
            XCTAssertFalse(TrackingAccessPolicy.isLegacyOriginalVersion(invalid), invalid)
        }
    }

    func testBuild50ReceiptRestoresFreeTrackingOnFreshDevicesAndRelaunch() {
        for _ in 0..<2 {
            // Reinstall and a second device both start without the old defaults.
            let suite = "Build50Migration.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            XCTAssertFalse(TrackingAccessPolicy.initializeLegacyCohort(defaults: defaults, hasLocationAuthorization: false))
            XCTAssertFalse(defaults.bool(forKey: PaidTrackingMigration.legacyCohortKey))
            let legacy = PaidTrackingMigration.restoreLegacyAccess(verifiedOriginalVersion: "50", defaults: defaults)
            XCTAssertTrue(legacy)
            XCTAssertTrue(TrackingAccessPolicy.canTrack(legacy: legacy, purchased: false, expiration: nil))
            XCTAssertFalse(TrackingAccessPolicy.requiresPaywall(legacy: legacy, purchasedBefore: false, purchased: false))
            // Grandfathering preserves the existing export gate, not a paid purchase.
            XCTAssertFalse(TrackingAccessPolicy.canExport(legacy: legacy, purchased: false))
            let relaunchedDefaults = UserDefaults(suiteName: suite)!
            XCTAssertTrue(TrackingAccessPolicy.initializeLegacyCohort(defaults: relaunchedDefaults, hasLocationAuthorization: false))
        }
    }

    func testSubscriptionBuildReceiptDoesNotGrandfatherNewUsers() {
        for version in ["51", "52", "invalid", "0"] {
            let suite = "NewTrackingCohort.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            XCTAssertFalse(PaidTrackingMigration.restoreLegacyAccess(verifiedOriginalVersion: version, defaults: defaults))
            XCTAssertFalse(defaults.bool(forKey: PaidTrackingMigration.legacyCohortKey))
        }
    }

    func testRestoredLegacyAccessIsStickyAcrossNewerOrInvalidVersion() {
        let suite = "StickyLegacyTracking.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertTrue(PaidTrackingMigration.restoreLegacyAccess(verifiedOriginalVersion: "50", defaults: defaults))
        XCTAssertTrue(PaidTrackingMigration.restoreLegacyAccess(verifiedOriginalVersion: "51", defaults: defaults))
        XCTAssertTrue(PaidTrackingMigration.restoreLegacyAccess(verifiedOriginalVersion: "invalid", defaults: defaults))
    }

    @MainActor
    func testReleaseBundleDoesNotReuseLiveBuild50() throws {
        XCTAssertEqual(PaidTrackingMigration.firstPaidTrackingBuild, 51)
        XCTAssertEqual(StoreManager.firstPaidTrackingBuild, PaidTrackingMigration.firstPaidTrackingBuild)
        let rawBuild = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String)
        let build = try XCTUnwrap(Int(rawBuild))
        XCTAssertGreaterThanOrEqual(build, PaidTrackingMigration.firstPaidTrackingBuild)
        let version = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
        XCTAssertNotEqual(version.compare("1.7.19", options: .numeric), .orderedAscending)
    }

    func testEmbeddedCompanionsMatchPhoneVersionAndBuild() throws {
        let expectedBuild = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String)
        let expectedVersion = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
        for path in [
            "PlugIns/IsoMeWidgetExtension.appex",
            "Watch/IsoMeWatch.app",
            "Watch/IsoMeWatch.app/PlugIns/IsoMeWatchWidgetExtension.appex"
        ] {
            let url = Bundle.main.bundleURL.appendingPathComponent(path).appendingPathComponent("Info.plist")
            let plist = try PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil)
            let info = try XCTUnwrap(plist as? [String: Any])
            XCTAssertEqual(info["CFBundleVersion"] as? String, expectedBuild, path)
            XCTAssertEqual(info["CFBundleShortVersionString"] as? String, expectedVersion, path)
        }
    }

    func testNewUsersMustPurchaseButExpiredUsersKeepHistoryAndExport() {
        XCTAssertTrue(TrackingAccessPolicy.requiresPaywall(legacy: false, purchasedBefore: false, purchased: false))
        XCTAssertFalse(TrackingAccessPolicy.canTrack(legacy: false, purchased: false, expiration: nil))
        XCTAssertFalse(TrackingAccessPolicy.requiresPaywall(legacy: false, purchasedBefore: true, purchased: false))
        XCTAssertTrue(TrackingAccessPolicy.canExport(legacy: false, purchased: false))
        XCTAssertFalse(TrackingAccessPolicy.canTrack(legacy: false, purchased: true, expiration: .distantPast))
        XCTAssertTrue(TrackingAccessPolicy.canTrack(legacy: true, purchased: false, expiration: nil))
        XCTAssertFalse(TrackingAccessPolicy.canExport(legacy: true, purchased: false))
        XCTAssertFalse(TrackingAccessPolicy.requiresPaywall(legacy: true, purchasedBefore: false, purchased: false))
    }

    @MainActor
    func testStoreKitCancellationAndOfflineRestoreKeepFreshUserLocked() async throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "IsoMe", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        defer { session.clearTransactions(); session.resetToDefaultState() }
        let suite = "StoreKitFaults.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.set(false, forKey: "tracking.cohort.legacy.v1")
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = StoreManager(defaults: defaults, forceStoreKit: true)
        for _ in 0..<100 {
            if store.productsByID.count == 7 && !store.isLoadingProducts { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(store.productsByID.count, 7)
        XCTAssertFalse(store.canTrack)
        try await session.setSimulatedError(SKTestFailures.Purchase.generic(.userCancelled), forAPI: .purchase)
        let cancelled = await store.purchase(.familyWeekly)
        // SKTestSession on iOS 27 throws StoreKitError.unknown for a simulated cancellation,
        // so only require that a cancelled sheet never unlocks a fresh user.
        XCTAssertTrue(cancelled == .cancelled || cancelled == .failed(.storeError), "\(cancelled)")
        XCTAssertFalse(store.canTrack)
        try await session.setSimulatedError(nil, forAPI: .purchase)
        try await session.setSimulatedError(SKTestFailures.AppStoreSync.generic(.networkError(URLError(.notConnectedToInternet))), forAPI: .appStoreSync)
        let failedRestore = await store.restorePurchases()
        XCTAssertEqual(failedRestore, .failed)
        XCTAssertFalse(store.canTrack)
    }

    @MainActor
    func testStoreKitCatalogTrialPurchaseExpirationAndLifetime() async throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "IsoMe", withExtension: "storekit"))
        let session = try SKTestSession(contentsOf: url)
        session.resetToDefaultState()
        session.disableDialogs = true
        session.timeRate = .realTime
        session.clearTransactions()
        defer { session.clearTransactions() }
        let suite = "StoreKitIntegration.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.set(false, forKey: "tracking.cohort.legacy.v1")
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = StoreManager(defaults: defaults, forceStoreKit: true)
        // The startup task may already be loading; wait for its full catalog.
        for _ in 0..<100 {
            if store.productsByID.count == 7 && !store.isLoadingProducts { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(store.productsByID.count, 7)
        let prices: [IsoMePurchaseOption: Decimal] = [
            .individualWeekly: Decimal(string: "0.99")!, .individualYearly: Decimal(string: "29.99")!,
            .individual: Decimal(string: "19.99")!, .familyWeekly: Decimal(string: "1.99")!,
            .familyYearly: Decimal(string: "49.99")!, .family: Decimal(string: "39.99")!
        ]
        for (option, price) in prices {
            let product = try XCTUnwrap(store.product(for: option))
            XCTAssertEqual(product.price, price)
            XCTAssertEqual(product.isFamilyShareable, option.isFamilyPlan)
        }
        XCTAssertTrue(store.trialEligibleProductIDs.contains(IsoMePurchaseOption.individualWeekly.productID))
        // Exercise real StoreKit transactions without purchase-sheet presentation,
        // which requires a foreground scene unavailable in hosted test runners.
        let trial = try await session.buyProduct(identifier: IsoMePurchaseOption.individualWeekly.productID)
        XCTAssertEqual(trial.productID, IsoMePurchaseOption.individualWeekly.productID)
        await trial.finish()
        for _ in 0..<50 {
            await store.checkEntitlement()
            if store.isPurchased { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(store.isPurchased)
        XCTAssertNotNil(store.trackingExpiration)
        let restoredSuite = "StoreKitRestore.\(UUID().uuidString)"
        let restoredDefaults = UserDefaults(suiteName: restoredSuite)!
        defer { restoredDefaults.removePersistentDomain(forName: restoredSuite) }
        restoredDefaults.set(false, forKey: "tracking.cohort.legacy.v1")
        let restoredStore = StoreManager(defaults: restoredDefaults, forceStoreKit: true)
        let restoreResult = await restoredStore.restorePurchases()
        XCTAssertEqual(restoreResult, .restored)
        XCTAssertTrue(restoredStore.canTrack)
        let subscription = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == IsoMePurchaseOption.individualWeekly.productID })
        try session.disableAutoRenewForTransaction(identifier: subscription.identifier)
        try session.expireSubscription(productIdentifier: IsoMePurchaseOption.individualWeekly.productID)
        for _ in 0..<50 {
            await store.checkEntitlement()
            if !store.isPurchased { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertFalse(store.isPurchased)
        XCTAssertTrue(store.hasPurchasedBefore)
        XCTAssertTrue(store.canExport)
        let lifetime = try await session.buyProduct(identifier: IsoMePurchaseOption.family.productID)
        XCTAssertEqual(lifetime.productID, IsoMePurchaseOption.family.productID)
        await lifetime.finish()
        for _ in 0..<50 {
            await store.checkEntitlement()
            if store.isFamilyUnlocked { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(store.isFamilyUnlocked)
        XCTAssertNil(store.trackingExpiration)
    }


    func testPlanCardsUseShortNamesAndYearlyMonthlyEquivalent() {
        let usd = Decimal.FormatStyle.Currency(code: "USD", locale: Locale(identifier: "en_US"))
        let english = Bundle.main.url(forResource: "en", withExtension: "lproj").flatMap(Bundle.init(url:)) ?? .main
        XCTAssertEqual(PaywallCopy.planName(for: .individualYearly, bundle: english), "Yearly")
        XCTAssertEqual(PaywallCopy.planName(for: .family, bundle: english), "Lifetime")
        XCTAssertEqual(PaywallCopy.planDetail(for: .individualYearly, price: Decimal(string: "29.99")!, format: usd, bundle: english),
                       "$2.50 a month, billed yearly")
        XCTAssertEqual(PaywallCopy.planDetail(for: .familyYearly, price: Decimal(string: "49.99")!, format: usd, bundle: english),
                       "$4.17 a month, billed yearly")
        XCTAssertEqual(PaywallCopy.planDetail(for: .individual, price: Decimal(string: "49.99")!, format: usd, bundle: english),
                       "One-time purchase. Yours for good.")
    }

    func testTrialReminderFiresTwoDaysBeforeTheTrialEnds() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let trialEnds = start.addingTimeInterval(7 * 24 * 60 * 60)
        XCTAssertEqual(TrialReminder.fireDate(trialEnds: trialEnds, now: start), start.addingTimeInterval(5 * 24 * 60 * 60))
        // A trial ending within two days gets no reminder rather than one in the past.
        XCTAssertNil(TrialReminder.fireDate(trialEnds: start.addingTimeInterval(24 * 60 * 60), now: start))
        let body = TrialReminder.body(displayPrice: "$29.99", renewal: trialEnds)
        XCTAssertTrue(body.contains("$29.99"))
        XCTAssertTrue(body.contains(trialEnds.formatted(date: .abbreviated, time: .omitted)))
    }

    func testCarouselPaywallCopyIsTranslatedInEveryShippedLocale() throws {
        let keys = ["How your *free week* works", "Choose your *plan*", "%@ a month, billed yearly",
                    "Your free week ends in 2 days", "Day 5 · A reminder", "EXPORT IS PART OF YOUR PLAN"]
        for locale in ["ar", "bn", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans"] {
            let url = Bundle.main.bundleURL.appendingPathComponent("\(locale).lproj")
            let bundle = try XCTUnwrap(Bundle(url: url), "Missing compiled locale: \(locale)")
            for key in keys {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: nil, table: nil), key, "\(locale): \(key)")
            }
            let detail = PaywallCopy.planDetail(for: .individualYearly, price: 12, format: .currency(code: "USD"), bundle: bundle)
            XCTAssertFalse(detail.contains("%@"), "\(locale): \(detail)")
        }
    }
}
