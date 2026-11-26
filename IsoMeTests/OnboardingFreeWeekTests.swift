import XCTest
@testable import IsoMe

@MainActor
final class OnboardingFreeWeekTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        return calendar
    }
    private let locale = Locale(identifier: "en_US")

    private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    private var english: Bundle {
        Bundle(url: Bundle.main.bundleURL.appendingPathComponent("en.lproj")) ?? .main
    }

    // MARK: Access routing

    func testNewUserIsOfferedTheFreeWeek() {
        XCTAssertEqual(OnboardingAccessRouting.route(hasResolvedEntitlements: true, legacy: false,
                                                     purchased: false, expiration: nil), .freeWeek)
    }

    func testNewUserWaitsForStoreKitBeforeAnyPrice() {
        XCTAssertEqual(OnboardingAccessRouting.route(hasResolvedEntitlements: false, legacy: false,
                                                     purchased: false, expiration: nil), .checking)
    }

    func testLegacyCohortNeverPaysEvenBeforeStoreKitAnswers() {
        for resolved in [false, true] {
            XCTAssertEqual(OnboardingAccessRouting.route(hasResolvedEntitlements: resolved, legacy: true,
                                                         purchased: false, expiration: nil), .startDay)
        }
    }

    func testLegacyCohortClassificationIsUnchanged() {
        // Existing installs (onboarding flag, tracking flag or location permission) stay free; fresh installs don't.
        let existing = makeIsolatedDefaults()
        existing.set(true, forKey: "hasCompletedOnboarding")
        XCTAssertTrue(TrackingAccessPolicy.initializeLegacyCohort(defaults: existing, hasLocationAuthorization: false))
        let located = makeIsolatedDefaults()
        XCTAssertTrue(TrackingAccessPolicy.initializeLegacyCohort(defaults: located, hasLocationAuthorization: true))
        let fresh = makeIsolatedDefaults()
        XCTAssertFalse(TrackingAccessPolicy.initializeLegacyCohort(defaults: fresh, hasLocationAuthorization: false))
    }

    func testActivePurchaseOrRestoreStartsTheDayWithoutPaying() {
        let now = date(2026, 11, 23)
        // Lifetime (no expiration) and a subscription that is still running.
        XCTAssertEqual(OnboardingAccessRouting.route(hasResolvedEntitlements: true, legacy: false, purchased: true,
                                                     expiration: nil, now: now), .startDay)
        XCTAssertEqual(OnboardingAccessRouting.route(hasResolvedEntitlements: true, legacy: false, purchased: true,
                                                     expiration: date(2026, 11, 30), now: now), .startDay)
    }

    func testLapsedSubscriberIsAskedForAPlanAgain() {
        let now = date(2026, 11, 23)
        XCTAssertEqual(OnboardingAccessRouting.route(hasResolvedEntitlements: true, legacy: false, purchased: true,
                                                     expiration: date(2026, 11, 20), now: now), .freeWeek)
        XCTAssertEqual(OnboardingAccessRouting.route(hasResolvedEntitlements: true, legacy: false, purchased: false,
                                                     expiration: nil, now: now), .freeWeek)
    }

    func testRequiredPaywallAfterOnboardingKeepsItsRules() {
        // Someone who finished onboarding before this change with no access still gets the required paywall;
        // a purchase made inside onboarding, a lapsed subscriber and the legacy cohort don't.
        XCTAssertTrue(TrackingAccessPolicy.requiresPaywall(legacy: false, purchasedBefore: false, purchased: false))
        XCTAssertFalse(TrackingAccessPolicy.requiresPaywall(legacy: false, purchasedBefore: true, purchased: true))
        XCTAssertFalse(TrackingAccessPolicy.requiresPaywall(legacy: false, purchasedBefore: true, purchased: false))
        XCTAssertFalse(TrackingAccessPolicy.requiresPaywall(legacy: true, purchasedBefore: false, purchased: false))
    }

    // MARK: Lead card

    func testLeadFollowsNotesRoutesCountriesDays() {
        XCTAssertEqual(OnboardingPlanLead.intent(for: [.days, .countries, .routes, .notes]), .notes)
        XCTAssertEqual(OnboardingPlanLead.intent(for: [.days, .countries, .routes]), .routes)
        XCTAssertEqual(OnboardingPlanLead.intent(for: [.days, .countries]), .countries)
        XCTAssertEqual(OnboardingPlanLead.intent(for: [.days]), .days)
        XCTAssertEqual(OnboardingPlanLead.intent(for: [.notes, .days]), .notes)
        XCTAssertEqual(OnboardingPlanLead.intent(for: []), .days)
    }

    // MARK: Plans

    func testPlanChoicesMapToTheExistingProducts() {
        XCTAssertEqual(OnboardingPlanChoice.yearly.option(family: false), .individualYearly)
        XCTAssertEqual(OnboardingPlanChoice.weekly.option(family: false), .individualWeekly)
        XCTAssertEqual(OnboardingPlanChoice.lifetime.option(family: false), .individual)
        XCTAssertEqual(OnboardingPlanChoice.yearly.option(family: true), .familyYearly)
        XCTAssertEqual(OnboardingPlanChoice.weekly.option(family: true), .familyWeekly)
        XCTAssertEqual(OnboardingPlanChoice.lifetime.option(family: true), .family)
    }

    // MARK: Timeline

    private let nine = (hour: 21, minute: 0)

    func testTrialTimelineUsesRealPlaceDaysAndDates() {
        let now = date(2026, 11, 23, 14, 5)
        let entries = OnboardingTrialTimeline.entries(
            offer: .trial(.year, displayPrice: "$29.99"),
            today: .init(placeName: "Café", earlierPhotoDays: 75, canRecord: true),
            recapTime: nine, now: now, calendar: calendar, locale: locale, bundle: english)
        XCTAssertEqual(entries.map(\.kind), [.today, .recap, .reminder, .renewal])
        XCTAssertEqual(entries[0].text, "Café is stamped and 75 earlier days are in from Photos. Recording starts now.")
        XCTAssertEqual(entries[1].text, "A short recap of your day: where you went and how far.")
        XCTAssertEqual(entries[1].when.replacingOccurrences(of: "\u{202F}", with: " "), "Every evening · 9:00 PM")
        XCTAssertEqual(entries[2].when, "Day 5 · Nov 28")
        XCTAssertEqual(entries[3].when, "Day 7 · Nov 30")
        XCTAssertEqual(entries[3].text, "Yearly plan begins: $29.99 a year. Cancel before then and pay nothing.")
        XCTAssertEqual(entries[3].accessibilityLabel, "Day 7 · Nov 30, Yearly plan begins: $29.99 a year. Cancel before then and pay nothing.")
    }

    func testTimelineDatesCrossMonthEnd() {
        let now = date(2026, 11, 28, 9)
        let entries = OnboardingTrialTimeline.entries(
            offer: .trial(.week, displayPrice: "$2.99"), today: .init(canRecord: true),
            recapTime: nine, now: now, calendar: calendar, locale: locale, bundle: english)
        XCTAssertEqual(entries[2].when, "Day 5 · Dec 3")
        XCTAssertEqual(entries[3].when, "Day 7 · Dec 5")
        XCTAssertEqual(entries[3].text, "Weekly plan begins: $2.99 a week. Cancel before then and pay nothing.")
    }

    func testRecapEntryFollowsTheConfiguredTimeAndMakesNoTonightPromise() {
        let now = date(2026, 11, 23, 22)
        let entries = OnboardingTrialTimeline.entries(
            offer: .trial(.year, displayPrice: "$29.99"), today: .init(canRecord: true),
            recapTime: (22, 30), now: now, calendar: calendar, locale: locale, bundle: english)
        XCTAssertEqual(entries[1].when.replacingOccurrences(of: "\u{202F}", with: " "), "Every evening · 10:30 PM")
        for entry in entries {
            XCTAssertFalse(entry.text.localizedCaseInsensitiveContains("page one"), entry.text)
            XCTAssertFalse(entry.when.localizedCaseInsensitiveContains("tonight"), entry.when)
        }
    }

    func testMissingTodayValuesAreDroppedNotInvented() {
        func text(_ today: OnboardingTrialTimeline.Today) -> String {
            OnboardingTrialTimeline.todayText(today, bundle: english)
        }
        XCTAssertEqual(text(.init(placeName: "Home", earlierPhotoDays: nil, canRecord: true)), "Home is stamped. Recording starts now.")
        XCTAssertEqual(text(.init(placeName: "Home", earlierPhotoDays: 0, canRecord: true)), "Home is stamped. Recording starts now.")
        XCTAssertEqual(text(.init(placeName: "Home", earlierPhotoDays: 1, canRecord: true)),
                       "Home is stamped and 1 earlier day is in from Photos. Recording starts now.")
        XCTAssertEqual(text(.init(placeName: nil, earlierPhotoDays: 12, canRecord: true)),
                       "12 earlier days are in from Photos. Recording starts now.")
        XCTAssertEqual(text(.init(placeName: "  ", earlierPhotoDays: nil, canRecord: true)), "Recording starts now.")
        XCTAssertEqual(text(.init(placeName: nil, earlierPhotoDays: nil, canRecord: false)), "Recording starts once location is allowed.")
    }

    func testEarlierPhotoDaysExcludeTodayAndNeedPhotos() {
        XCTAssertNil(OnboardingTrialTimeline.earlierPhotoDays(in: nil))
        XCTAssertEqual(OnboardingTrialTimeline.earlierPhotoDays(in: [0, 3, 0, 1, 5]), 2)
        XCTAssertEqual(OnboardingTrialTimeline.earlierPhotoDays(in: [0, 0, 4]), 0)
    }

    func testLifetimeAndNonTrialTimelinesMakeNoFreeClaims() {
        let now = date(2026, 11, 23, 14)
        let lifetime = OnboardingTrialTimeline.entries(
            offer: .lifetime(displayPrice: "$79.99"), today: .init(canRecord: true),
            recapTime: nine, now: now, calendar: calendar, locale: locale, bundle: english)
        XCTAssertEqual(lifetime.map(\.kind), [.today, .recap, .lifetime])
        XCTAssertEqual(lifetime[2].text, "One payment of $79.99. No renewals, no reminders.")

        let subscribe = OnboardingTrialTimeline.entries(
            offer: .subscription(.year, displayPrice: "$29.99"), today: .init(canRecord: true),
            recapTime: nine, now: now, calendar: calendar, locale: locale, bundle: english)
        XCTAssertEqual(subscribe.map(\.kind), [.today, .recap, .renewal])
        XCTAssertEqual(subscribe[2].text, "$29.99 a year, renewing until you cancel in Apple settings.")
        for entry in lifetime + subscribe {
            XCTAssertFalse(entry.text.localizedCaseInsensitiveContains("free"), entry.text)
            XCTAssertFalse(entry.text.contains("pay nothing"), entry.text)
        }
    }

    // MARK: Copy

    func testTrialEligibleCopyOffersTheFreeWeek() {
        let now = date(2026, 11, 23, 14)
        XCTAssertEqual(OnboardingFreeWeekCopy.primaryTitle(for: .individualYearly, displayPrice: "$29.99", hasTrial: true, bundle: english),
                       "Start my free week")
        XCTAssertEqual(OnboardingFreeWeekCopy.eyebrow(for: .trial(.year, displayPrice: "$29.99"), bundle: english), "YOUR FREE WEEK")
        let fine = OnboardingFreeWeekCopy.finePrint(for: .individualYearly, displayPrice: "$29.99", hasTrial: true, now: now,
                                                    calendar: calendar, locale: locale, bundle: english)
        XCTAssertEqual(fine, "Free until Nov 30. 7 days free, then $29.99 per year, automatically renewing until canceled. "
                       + "Cancel in Apple subscription settings at least 24 hours before renewal.")
        XCTAssertEqual(OnboardingFreeWeekCopy.planTag(for: .individualWeekly, hasTrial: true, bundle: english), "7 days free")
    }

    func testIneligibleCopySaysSubscribeWithoutFreeClaims() {
        let now = date(2026, 11, 23, 14)
        XCTAssertEqual(OnboardingFreeWeekCopy.primaryTitle(for: .individualYearly, displayPrice: "$29.99", hasTrial: false, bundle: english),
                       "Subscribe")
        XCTAssertEqual(OnboardingFreeWeekCopy.eyebrow(for: .subscription(.year, displayPrice: "$29.99"), bundle: english), "YOUR PLAN")
        XCTAssertEqual(OnboardingFreeWeekCopy.headline(for: .subscription(.year, displayPrice: nil), bundle: english), "Recording starts\nnow.")
        let fine = OnboardingFreeWeekCopy.finePrint(for: .familyWeekly, displayPrice: "$4.99", hasTrial: false, now: now,
                                                    calendar: calendar, locale: locale, bundle: english)
        XCTAssertEqual(fine, "$4.99 per week, automatically renewing until canceled. "
                       + "Cancel in Apple subscription settings at least 24 hours before renewal.")
        XCTAssertFalse(fine.localizedCaseInsensitiveContains("free"))
        XCTAssertNil(OnboardingFreeWeekCopy.planTag(for: .individualYearly, hasTrial: false, bundle: english))
        XCTAssertEqual(OnboardingOffer(option: .individualYearly, displayPrice: "$29.99", hasTrial: false),
                       .subscription(.year, displayPrice: "$29.99"))
    }

    func testLifetimeCopyNamesThePriceAndNoRenewal() {
        XCTAssertEqual(OnboardingFreeWeekCopy.primaryTitle(for: .individual, displayPrice: "$79.99", hasTrial: false, bundle: english),
                       "Buy Lifetime · $79.99")
        XCTAssertEqual(OnboardingFreeWeekCopy.primaryTitle(for: .family, displayPrice: nil, hasTrial: false, bundle: english),
                       "Buy Lifetime")
        XCTAssertEqual(OnboardingFreeWeekCopy.finePrint(for: .individual, displayPrice: "$79.99", hasTrial: true,
                                                        now: Date(), bundle: english),
                       "$79.99 charged once to your Apple Account. No subscription.")
        // A lifetime product never reports a trial, whatever eligibility says.
        XCTAssertEqual(OnboardingOffer(option: .individual, displayPrice: "$79.99", hasTrial: true), .lifetime(displayPrice: "$79.99"))
    }

    func testNewOnboardingCopyIsTranslatedInEveryShippedLocale() throws {
        let keys = ["Start my free week", "All plans and Family", "Recording starts\nnow, on us.",
                    "A short recap of your day: where you went and how far.", "Every evening · %@", "Evening recap",
                    "We remind you, two days before you’re charged.", "You’re in.", "Just me",
                    "Yearly plan begins: %@ a year. Cancel before then and pay nothing.", "Buy Lifetime · %@"]
        for locale in ["ar", "bn", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans"] {
            let url = Bundle.main.bundleURL.appendingPathComponent("\(locale).lproj")
            let bundle = try XCTUnwrap(Bundle(url: url), "Missing compiled locale: \(locale)")
            for key in keys {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: nil, table: nil), key, "\(locale): \(key)")
            }
            let title = OnboardingFreeWeekCopy.primaryTitle(for: .individual, displayPrice: "9,99 €", hasTrial: false, bundle: bundle)
            XCTAssertTrue(title.contains("9,99 €"), "\(locale): \(title)")
        }
    }

    // MARK: Checkout and notifications

    private final class Recorder {
        var purchases: [IsoMePurchaseOption] = []
        var trialReminders = 0
        var recapChoices: [Bool] = []
    }

    private func makeCheckout(outcome: StorePurchaseOutcome, restore: StoreRestoreOutcome = .notFound,
                              recorder: Recorder) -> OnboardingCheckout {
        OnboardingCheckout(
            purchase: { option in
                recorder.purchases.append(option)
                return outcome
            },
            restore: { restore },
            expiration: { Date(timeIntervalSince1970: 1_800_000_000) },
            scheduleTrialReminder: { recorder.trialReminders += 1 },
            applyRecap: { recorder.recapChoices.append($0) }
        )
    }

    func testNothingIsScheduledBeforeAPurchaseSucceeds() async {
        for outcome in [StorePurchaseOutcome.cancelled, .pending, .failed(.storeError), .failed(.productUnavailable)] {
            let recorder = Recorder()
            let checkout = makeCheckout(outcome: outcome, recorder: recorder)
            let earlyFinish = await checkout.finish(recapEnabled: true)
            XCTAssertFalse(earlyFinish)
            await checkout.buy(.individualYearly, startsTrial: true)
            XCTAssertEqual(checkout.phase, .choosing, "\(outcome)")
            XCTAssertEqual(checkout.lastOutcome, outcome)
            let finished = await checkout.finish(recapEnabled: true)
            XCTAssertFalse(finished, "\(outcome)")
            XCTAssertEqual(recorder.purchases, [.individualYearly])
            XCTAssertEqual(recorder.trialReminders, 0, "\(outcome)")
            XCTAssertTrue(recorder.recapChoices.isEmpty, "\(outcome)")
        }
    }

    func testTrialStartSchedulesOnlyOnTheDoneScreen() async {
        let recorder = Recorder()
        let checkout = makeCheckout(outcome: .succeeded, recorder: recorder)
        await checkout.buy(.individualYearly, startsTrial: true)
        XCTAssertEqual(checkout.phase, .done(.trial(ends: Date(timeIntervalSince1970: 1_800_000_000))))
        // The purchase itself never asks for notifications.
        XCTAssertEqual(recorder.trialReminders, 0)
        XCTAssertTrue(recorder.recapChoices.isEmpty)

        let finished = await checkout.finish(recapEnabled: true)
        XCTAssertTrue(finished)
        XCTAssertEqual(recorder.trialReminders, 1)
        XCTAssertEqual(recorder.recapChoices, [true])

        // Finishing twice schedules nothing more.
        await checkout.finish(recapEnabled: true)
        XCTAssertEqual(recorder.trialReminders, 1)
        XCTAssertEqual(recorder.recapChoices.count, 1)
    }

    func testRecapToggleOffTurnsTheRecapOff() async {
        let recorder = Recorder()
        let checkout = makeCheckout(outcome: .succeeded, recorder: recorder)
        await checkout.buy(.individual, startsTrial: false)
        XCTAssertEqual(checkout.phase, .done(.lifetime))
        await checkout.finish(recapEnabled: false)
        // Off is applied too (frequency Off), so nothing is left scheduled from an earlier choice.
        XCTAssertEqual(recorder.recapChoices, [false])
        // No trial, so no trial reminder either.
        XCTAssertEqual(recorder.trialReminders, 0)
    }

    func testSubscriptionWithoutTrialAndRestoreBothReachTheDoneScreen() async {
        let recorder = Recorder()
        let subscription = makeCheckout(outcome: .succeeded, recorder: recorder)
        await subscription.buy(.familyWeekly, startsTrial: false)
        XCTAssertEqual(subscription.phase, .done(.subscription))

        let restored = makeCheckout(outcome: .cancelled, restore: .restored, recorder: recorder)
        await restored.restorePurchases()
        XCTAssertEqual(restored.phase, .done(.restored))

        let notFound = makeCheckout(outcome: .cancelled, restore: .notFound, recorder: recorder)
        await notFound.restorePurchases()
        XCTAssertEqual(notFound.phase, .choosing)
        let finished = await notFound.finish(recapEnabled: true)
        XCTAssertFalse(finished)
    }
}
