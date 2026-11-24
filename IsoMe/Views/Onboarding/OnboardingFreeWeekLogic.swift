import Foundation

// Pure logic for the last onboarding step, "Start my free week", kept out of the views so it can be unit tested.
// Prices, periods and trial eligibility always come from StoreKit; nothing here invents a price.

// MARK: - Who pays

/// What the last onboarding step offers.
enum OnboardingFinalStepRoute: Equatable {
    /// Purchases are still being read. A short wait, never a price someone may already have paid.
    case checking
    /// The person can already record (legacy cohort, purchased or restored): no payment, "Start my day".
    case startDay
    /// The person needs a plan: the free week, or Subscribe when StoreKit says they aren't trial-eligible.
    case freeWeek
}

enum OnboardingAccessRouting {
    /// Uses the same access rule as tracking itself, so onboarding never asks someone to pay for what they have.
    /// The legacy cohort is known before StoreKit answers, so it never waits.
    static func route(hasResolvedEntitlements: Bool, legacy: Bool, purchased: Bool,
                      expiration: Date?, now: Date = Date()) -> OnboardingFinalStepRoute {
        if TrackingAccessPolicy.canTrack(legacy: legacy, purchased: purchased, expiration: expiration, now: now) {
            return .startDay
        }
        return hasResolvedEntitlements ? .freeWeek : .checking
    }
}

// MARK: - Plans

/// The three plans on the free-week step; the Just me / Family switch picks the product.
enum OnboardingPlanChoice: String, CaseIterable, Identifiable {
    case yearly, weekly, lifetime

    var id: String { rawValue }

    func option(family: Bool) -> IsoMePurchaseOption {
        switch self {
        case .yearly: family ? .familyYearly : .individualYearly
        case .weekly: family ? .familyWeekly : .individualWeekly
        case .lifetime: family ? .family : .individual
        }
    }
}

/// The benefit the All plans sheet leads with, picked from the onboarding intents.
enum OnboardingPlanLead {
    /// With several intents, the most specific one wins.
    static let priority: [OnboardingIntent] = [.notes, .routes, .countries, .days]

    static func intent(for intents: Set<OnboardingIntent>) -> OnboardingIntent {
        priority.first(where: intents.contains) ?? .days
    }
}

// MARK: - Copy

/// What the selected plan is, as StoreKit reports it.
enum OnboardingOffer: Equatable {
    enum Period: Equatable { case week, year }

    /// An eligible seven-day free trial, then the subscription.
    case trial(Period, displayPrice: String?)
    /// A subscription without a free trial (already used, or not offered).
    case subscription(Period, displayPrice: String?)
    case lifetime(displayPrice: String?)

    init(option: IsoMePurchaseOption, displayPrice: String?, hasTrial: Bool) {
        switch option {
        case .individualWeekly, .familyWeekly:
            self = hasTrial ? .trial(.week, displayPrice: displayPrice) : .subscription(.week, displayPrice: displayPrice)
        case .individualYearly, .familyYearly:
            self = hasTrial ? .trial(.year, displayPrice: displayPrice) : .subscription(.year, displayPrice: displayPrice)
        case .individual, .family, .familyUpgrade:
            self = .lifetime(displayPrice: displayPrice)
        }
    }

    var isTrial: Bool {
        if case .trial = self { return true }
        return false
    }
}

/// Complete localized sentences, so translators choose grammar and where the price goes.
/// StoreKit's localized price is inserted verbatim.
enum OnboardingFreeWeekCopy {
    /// The free week is seven days, the only trial StoreKit marks as eligible.
    static let trialDays = 7
    /// The trial reminder fires two days before the trial ends.
    static let reminderDay = 5

    static func trialEnd(from now: Date, calendar: Calendar = .current) -> Date {
        calendar.date(byAdding: .day, value: trialDays, to: now) ?? now.addingTimeInterval(TimeInterval(trialDays * 86_400))
    }

    static func reminderDate(from now: Date, calendar: Calendar = .current) -> Date {
        calendar.date(byAdding: .day, value: reminderDay, to: now) ?? now.addingTimeInterval(TimeInterval(reminderDay * 86_400))
    }

    static func shortDate(_ date: Date, calendar: Calendar = .current, locale: Locale = .autoupdatingCurrent) -> String {
        var style = Date.FormatStyle(date: .omitted, time: .omitted, locale: locale, calendar: calendar, timeZone: calendar.timeZone)
        style = style.month(.abbreviated).day()
        return date.formatted(style)
    }

    static func eyebrow(for offer: OnboardingOffer, bundle: Bundle = .main) -> String {
        offer.isTrial ? String(localized: "YOUR FREE WEEK", bundle: bundle) : String(localized: "YOUR PLAN", bundle: bundle)
    }

    static func headline(for offer: OnboardingOffer, bundle: Bundle = .main) -> String {
        switch offer {
        case .trial: String(localized: "Recording starts\nnow, on us.", bundle: bundle)
        case .subscription: String(localized: "Recording starts\nnow.", bundle: bundle)
        case .lifetime: String(localized: "Recording starts\nnow, for good.", bundle: bundle)
        }
    }

    /// "Start my free week" only while StoreKit reports an eligible free trial; otherwise PaywallCopy's Subscribe.
    static func primaryTitle(for option: IsoMePurchaseOption, displayPrice: String?, hasTrial: Bool,
                             bundle: Bundle = .main) -> String {
        if !option.isSubscription, option != .familyUpgrade, let displayPrice {
            return String(localized: "Buy Lifetime · \(displayPrice)", bundle: bundle)
        }
        if option.isSubscription && hasTrial { return String(localized: "Start my free week", bundle: bundle) }
        return PaywallCopy.purchaseButtonTitle(for: option, hasTrial: hasTrial, bundle: bundle)
    }

    /// The disclosure next to the purchase button: price, period, auto-renewal and how to cancel.
    static func finePrint(for option: IsoMePurchaseOption, displayPrice: String, hasTrial: Bool, now: Date,
                          calendar: Calendar = .current, locale: Locale = .autoupdatingCurrent,
                          bundle: Bundle = .main) -> String {
        let disclosure = PaywallCopy.disclosure(for: option, displayPrice: displayPrice, hasTrial: hasTrial, bundle: bundle)
        guard option.isSubscription else { return disclosure }
        let cancel = String(localized: "Cancel in Apple subscription settings at least 24 hours before renewal.", bundle: bundle)
        guard hasTrial else { return "\(disclosure) \(cancel)" }
        let end = shortDate(trialEnd(from: now, calendar: calendar), calendar: calendar, locale: locale)
        let free = String(localized: "Free until \(end).", bundle: bundle)
        return "\(free) \(disclosure) \(cancel)"
    }

    /// The small tag on an inline plan card.
    static func planTag(for option: IsoMePurchaseOption, hasTrial: Bool, bundle: Bundle = .main) -> String? {
        if option.isSubscription { return hasTrial ? String(localized: "7 days free", bundle: bundle) : nil }
        return String(localized: "Pay once", bundle: bundle)
    }
}

// MARK: - Timeline

/// One stop on the free-week timeline.
struct OnboardingTimelineEntry: Equatable, Identifiable {
    enum Kind: String { case today, recap, reminder, renewal, lifetime }

    let kind: Kind
    /// Shown in small capitals; VoiceOver reads it as written.
    let when: String
    let text: String

    var id: String { kind.rawValue }
    var accessibilityLabel: String { "\(when), \(text)" }
}

enum OnboardingTrialTimeline {
    /// What onboarding really did; nil or zero values drop their clause instead of being invented.
    struct Today: Equatable {
        var placeName: String?
        /// Days before today with geotagged photos, counted only when photo matching is on.
        var earlierPhotoDays: Int?
        var canRecord: Bool
    }

    /// - Parameter recapTime: the evening recap's hour and minute (Settings › Reminders; 9 PM by default).
    static func entries(offer: OnboardingOffer, today: Today, recapTime: (hour: Int, minute: Int), now: Date,
                        calendar: Calendar = .current, locale: Locale = .autoupdatingCurrent,
                        bundle: Bundle = .main) -> [OnboardingTimelineEntry] {
        let recapDate = calendar.date(bySettingHour: recapTime.hour, minute: recapTime.minute, second: 0, of: now) ?? now
        let time = ClockTimeFormatter.string(from: recapDate, locale: locale, timeZone: calendar.timeZone)
        var entries = [
            OnboardingTimelineEntry(kind: .today, when: String(localized: "Today", bundle: bundle),
                                    text: todayText(today, bundle: bundle)),
            OnboardingTimelineEntry(kind: .recap,
                                    when: String(localized: "Every evening · \(time)", bundle: bundle),
                                    text: String(localized: "A short recap of your day: where you went and how far.", bundle: bundle))
        ]
        switch offer {
        case let .trial(period, price):
            let reminder = OnboardingFreeWeekCopy.shortDate(OnboardingFreeWeekCopy.reminderDate(from: now, calendar: calendar),
                                                            calendar: calendar, locale: locale)
            let end = OnboardingFreeWeekCopy.shortDate(OnboardingFreeWeekCopy.trialEnd(from: now, calendar: calendar),
                                                       calendar: calendar, locale: locale)
            entries.append(OnboardingTimelineEntry(
                kind: .reminder, when: String(localized: "Day 5 · \(reminder)", bundle: bundle),
                text: String(localized: "We remind you, two days before you’re charged.", bundle: bundle)))
            entries.append(OnboardingTimelineEntry(
                kind: .renewal, when: String(localized: "Day 7 · \(end)", bundle: bundle),
                text: trialRenewalText(period: period, price: price, bundle: bundle)))
        case let .subscription(period, price):
            entries.append(OnboardingTimelineEntry(
                kind: .renewal,
                when: period == .year ? String(localized: "Every year", bundle: bundle) : String(localized: "Every week", bundle: bundle),
                text: subscriptionText(period: period, price: price, bundle: bundle)))
        case let .lifetime(price):
            entries.append(OnboardingTimelineEntry(
                kind: .lifetime, when: String(localized: "For good", bundle: bundle),
                text: price.map { String(localized: "One payment of \($0). No renewals, no reminders.", bundle: bundle) }
                    ?? String(localized: "One payment. No renewals, no reminders.", bundle: bundle)))
        }
        return entries
    }

    static func todayText(_ today: Today, bundle: Bundle = .main) -> String {
        let place = today.placeName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let days = today.earlierPhotoDays.flatMap { $0 > 0 ? $0 : nil }
        let stamped: String?
        switch (place, days) {
        case let (place?, 1?): stamped = String(localized: "\(place) is stamped and 1 earlier day is in from Photos.", bundle: bundle)
        case let (place?, days?): stamped = String(localized: "\(place) is stamped and \(days) earlier days are in from Photos.", bundle: bundle)
        case let (place?, nil): stamped = String(localized: "\(place) is stamped.", bundle: bundle)
        case (nil, 1?): stamped = String(localized: "1 earlier day is in from Photos.", bundle: bundle)
        case let (nil, days?): stamped = String(localized: "\(days) earlier days are in from Photos.", bundle: bundle)
        case (nil, nil): stamped = nil
        }
        let recording = today.canRecord
            ? String(localized: "Recording starts now.", bundle: bundle)
            : String(localized: "Recording starts once location is allowed.", bundle: bundle)
        return [stamped, recording].compactMap { $0 }.joined(separator: " ")
    }

    private static func trialRenewalText(period: OnboardingOffer.Period, price: String?, bundle: Bundle) -> String {
        switch (period, price) {
        case let (.year, price?): String(localized: "Yearly plan begins: \(price) a year. Cancel before then and pay nothing.", bundle: bundle)
        case let (.week, price?): String(localized: "Weekly plan begins: \(price) a week. Cancel before then and pay nothing.", bundle: bundle)
        case (_, nil): String(localized: "Your plan begins. Cancel before then and pay nothing.", bundle: bundle)
        }
    }

    private static func subscriptionText(period: OnboardingOffer.Period, price: String?, bundle: Bundle) -> String {
        switch (period, price) {
        case let (.year, price?): String(localized: "\(price) a year, renewing until you cancel in Apple settings.", bundle: bundle)
        case let (.week, price?): String(localized: "\(price) a week, renewing until you cancel in Apple settings.", bundle: bundle)
        case (_, nil): String(localized: "Renews until you cancel in Apple settings.", bundle: bundle)
        }
    }

    /// Days before today that have geotagged photos; nil when photos weren't read.
    static func earlierPhotoDays(in counts: [Int]?) -> Int? {
        guard let counts, !counts.isEmpty else { return nil }
        return PhotoDayHistogram.daysFound(in: Array(counts.dropLast()))
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

// MARK: - Checkout

/// The purchase on the last step and what follows it. Notifications are asked for and scheduled only from `finish`,
/// and only after a purchase, trial start or restore succeeded, so nobody is asked about an empty day.
@MainActor
final class OnboardingCheckout: ObservableObject {
    enum Completion: Equatable {
        case trial(ends: Date?)
        case subscription
        case lifetime
        case restored
    }

    enum Phase: Equatable {
        case choosing
        case working
        case done(Completion)
    }

    @Published private(set) var phase: Phase = .choosing
    @Published private(set) var lastOutcome: StorePurchaseOutcome?

    private let purchase: (IsoMePurchaseOption) async -> StorePurchaseOutcome
    private let restore: () async -> StoreRestoreOutcome
    private let expiration: () -> Date?
    private let scheduleTrialReminder: () async -> Void
    /// Applies the "Evening recap" toggle (Every evening or Off) and, when on, asks for notification permission.
    private let applyRecap: (Bool) async -> Void
    private var hasFinished = false

    init(purchase: @escaping (IsoMePurchaseOption) async -> StorePurchaseOutcome,
         restore: @escaping () async -> StoreRestoreOutcome,
         expiration: @escaping () -> Date?,
         scheduleTrialReminder: @escaping () async -> Void,
         applyRecap: @escaping (Bool) async -> Void) {
        self.purchase = purchase
        self.restore = restore
        self.expiration = expiration
        self.scheduleTrialReminder = scheduleTrialReminder
        self.applyRecap = applyRecap
    }

    /// Real StoreKit purchases; the trial reminder waits for `finish`, with the evening recap.
    convenience init(store: StoreManager) {
        self.init(
            purchase: { [weak store] option in
                await store?.purchase(option, defersTrialReminder: true) ?? .failed(.storeError)
            },
            restore: { [weak store] in await store?.restorePurchases() ?? .failed },
            expiration: { [weak store] in store?.trackingExpiration },
            scheduleTrialReminder: { [weak store] in await store?.scheduleDeferredTrialReminder() },
            applyRecap: { enabled in await RecapReminderScheduler.shared.applyOnboardingChoice(enabled: enabled) }
        )
    }

    var isDone: Bool {
        if case .done = phase { return true }
        return false
    }

    /// Cancelled, pending and failed purchases leave the step as it was; StoreManager reports the message.
    func buy(_ option: IsoMePurchaseOption, startsTrial: Bool) async {
        guard phase == .choosing else { return }
        phase = .working
        let outcome = await purchase(option)
        lastOutcome = outcome
        guard outcome == .succeeded else {
            phase = .choosing
            return
        }
        if !option.isSubscription {
            phase = .done(.lifetime)
        } else if startsTrial {
            phase = .done(.trial(ends: expiration()))
        } else {
            phase = .done(.subscription)
        }
    }

    func restorePurchases() async {
        guard phase == .choosing else { return }
        phase = .working
        let outcome = await restore()
        phase = outcome == .restored ? .done(.restored) : .choosing
    }

    /// The "You're in" screen's action. Asks for notification permission and schedules only here,
    /// after a successful purchase. Returns whether anything was scheduled or attempted.
    @discardableResult
    func finish(recapEnabled: Bool) async -> Bool {
        guard case let .done(completion) = phase, !hasFinished else { return false }
        hasFinished = true
        if case .trial = completion { await scheduleTrialReminder() }
        await applyRecap(recapEnabled)
        return true
    }
}
