import Combine
import Foundation
import SwiftData
import UIKit
import UserNotifications

/// Keeps exactly one pending evening recap request: the next occurrence, with that occurrence's real counts.
///
/// An iOS local notification's text is fixed when it's scheduled, so the request is rebuilt (or removed when a
/// skip rule applies) whenever something could change it: a visit or route point is saved (debounced), the app
/// launches or comes to the foreground, a setting changes, the day or time zone changes, StoreKit access changes,
/// and the daily-export background refresh runs (`DailyExportScheduler`, only when scheduled exports are on;
/// recaps add no background mode of their own). After an occurrence fires or passes, the next rebuild schedules
/// the following one.
///
/// Limitation: the counts are as of the last rebuild. Background location wakes keep them close, but if the app
/// gets no wake at all between the last update and the fire time, the recap can be slightly stale (it misses
/// what happened after that last wake), and a skip rule that became true afterwards can't remove it.
///
/// Privacy: everything is read and written on the phone. Place names appear in the text only when
/// Show place names is on.
@MainActor
final class RecapReminderScheduler: ObservableObject {
    static let shared = RecapReminderScheduler()
    static let requestIdentifier = "com.bontecou.isome.recap"
    static let userInfoKindKey = "recapKind"

    /// What the next occurrence looks like, for Settings › Reminders.
    struct Plan: Equatable {
        let fire: Date
        let summary: RecapSummary
        let skipReason: RecapSkipReason?
    }

    enum DataChange { case visit, points }

    @Published private(set) var settings: RecapReminderSettings
    @Published private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined
    @Published private(set) var plan: Plan?

    private let defaults: UserDefaults
    private let center: UNUserNotificationCenter
    private var reader: RecapDataReader?
    private var started = false
    private var pendingRefresh: Task<Void, Never>?
    private var pendingDeadline: Date?
    private var lastRefreshAt: Date?
    private var refreshGeneration = 0
    private var observers: [NSObjectProtocol] = []
    private var storeObserver: AnyCancellable?

    /// Visits are rare and change the text most; route points arrive constantly, so they rebuild at most this often.
    private static let visitDebounce: TimeInterval = 3
    private static let pointThrottle: TimeInterval = 10 * 60

    init(defaults: UserDefaults = .standard, center: UNUserNotificationCenter = .current()) {
        self.defaults = defaults
        self.center = center
        self.settings = RecapReminderSettings.load(defaults: defaults)
    }

    // MARK: - Wiring

    /// Called at launch, including background launches for location events.
    func start(container: ModelContainer?) {
        guard !started else { return }
        started = true
        if let container { reader = RecapDataReader(modelContainer: container) }
        let names: [Notification.Name] = [
            UIApplication.significantTimeChangeNotification,
            .NSSystemTimeZoneDidChange,
            .NSCalendarDayChanged
        ]
        for name in names {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor in RecapReminderScheduler.shared.setNeedsRefresh(after: 1) }
            })
        }
        // Manual visits (Shortcuts, links, the map) and Apple Watch imports change the day too.
        for name in [Notification.Name.manualVisitsChanged, .watchLocationDataImported] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor in RecapReminderScheduler.shared.noteDataChanged(.visit) }
            })
        }
        storeObserver = StoreManager.shared.objectWillChange.sink { _ in
            Task { @MainActor in RecapReminderScheduler.shared.setNeedsRefresh(after: 2) }
        }
        Task {
            await migrateLegacyNightlyIfNeeded()
            await refresh()
        }
    }

    /// One-time: the old one-shot "Page one" request becomes Every evening and is removed.
    func migrateLegacyNightlyIfNeeded() async {
        guard !defaults.bool(forKey: RecapReminderSettings.Key.legacyMigrated) else { return }
        let pending = await center.pendingNotificationRequests()
        let legacyPending = pending.contains { $0.identifier == RecapReminderMigration.legacyNotificationIdentifier }
        let stored = defaults.string(forKey: RecapReminderSettings.Key.frequency).flatMap(RecapFrequency.init(rawValue:))
        if let frequency = RecapReminderMigration.migratedFrequency(stored: stored, legacyRequestPending: legacyPending,
                                                                    legacyToggleOn: false) {
            var updated = settings
            updated.frequency = frequency
            apply(updated)
            LogManager.shared.info("Recap: the one-time first-page reminder became Every evening", category: .general)
        }
        center.removePendingNotificationRequests(withIdentifiers: [RecapReminderMigration.legacyNotificationIdentifier])
        defaults.set(true, forKey: RecapReminderSettings.Key.legacyMigrated)
    }

    // MARK: - Inputs

    func update(_ change: (inout RecapReminderSettings) -> Void) {
        var updated = settings
        change(&updated)
        guard updated != settings else { return }
        let wasOff = !settings.frequency.isOn
        apply(updated)
        Task {
            // Choosing a recap is the moment to ask, and only when the system hasn't asked yet.
            if wasOff, updated.frequency.isOn { await requestAuthorizationIfUndetermined() }
            await refresh()
        }
    }

    /// The onboarding "Evening recap" toggle, applied when "Open today's map" / "Start my day" is tapped.
    /// That evening's opens don't skip the first recap, which the toggle promises for tonight.
    func applyOnboardingChoice(enabled: Bool, now: Date = Date()) async {
        var updated = settings
        updated.frequency = RecapFrequency.onboardingToggle(enabled)
        apply(updated)
        if enabled {
            defaults.set(Self.dayKey(now), forKey: RecapReminderSettings.Key.firstEveningDay)
            await requestAuthorizationIfUndetermined()
        }
        await refresh(now: now)
    }

    func noteDataChanged(_ change: DataChange) {
        guard settings.frequency.isOn else { return }
        switch change {
        case .visit:
            setNeedsRefresh(after: Self.visitDebounce)
        case .points:
            let since = lastRefreshAt.map { Date().timeIntervalSince($0) } ?? .infinity
            setNeedsRefresh(after: since >= Self.pointThrottle ? Self.visitDebounce : Self.pointThrottle - since)
        }
    }

    /// Opening the app counts as seeing the day only after onboarding.
    func noteForeground(now: Date = Date()) {
        if defaults.bool(forKey: "hasCompletedOnboarding") {
            defaults.set(now, forKey: RecapReminderSettings.Key.lastActiveAt)
        }
        Task {
            await refreshAuthorizationStatus()
            await refresh()
        }
    }

    func noteBackground(now: Date = Date()) {
        guard defaults.bool(forKey: "hasCompletedOnboarding") else { return }
        defaults.set(now, forKey: RecapReminderSettings.Key.lastBackgroundAt)
        setNeedsRefresh(after: 0)
    }

    /// Coalesces rebuilds; an earlier deadline wins.
    func setNeedsRefresh(after delay: TimeInterval) {
        let deadline = Date().addingTimeInterval(max(0, delay))
        if let pendingDeadline, pendingDeadline <= deadline, pendingRefresh != nil { return }
        pendingRefresh?.cancel()
        pendingDeadline = deadline
        pendingRefresh = Task { [weak self] in
            if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
            guard !Task.isCancelled, let self else { return }
            self.pendingRefresh = nil
            self.pendingDeadline = nil
            await self.refresh()
        }
    }

    func requestAuthorizationIfUndetermined() async {
        let current = await center.notificationSettings().authorizationStatus
        if current == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
        }
        await refreshAuthorizationStatus()
    }

    func refreshAuthorizationStatus() async {
        authorizationStatus = await center.notificationSettings().authorizationStatus
    }

    func resetForClearedData() {
        plan = nil
        center.removePendingNotificationRequests(withIdentifiers: [Self.requestIdentifier])
        setNeedsRefresh(after: 1)
    }

    // MARK: - Rebuild

    /// Recomputes the next occurrence and replaces, or removes, the pending request.
    func refresh(now: Date = Date()) async {
        refreshGeneration += 1
        let generation = refreshGeneration
        lastRefreshAt = now
        let backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Evening recap")
        defer { if backgroundTask != .invalid { UIApplication.shared.endBackgroundTask(backgroundTask) } }

        let settings = settings
        let calendar = Calendar.autoupdatingCurrent
        guard settings.frequency.isOn, let fire = RecapSchedule.nextFire(after: now, settings: settings, calendar: calendar) else {
            plan = nil
            removePending(reason: "off")
            return
        }
        let store = StoreManager.shared
        // Until StoreKit answers, a subscriber looks lapsed; leave the pending request as it is.
        guard store.hasResolvedEntitlements else { return }

        let kind = settings.frequency.periodKind
        let period = RecapSchedule.period(endingAt: fire, kind: kind, calendar: calendar)
        let trackingNow = LocationManager.shared?.isTrackingEnabled ?? defaults.bool(forKey: "isTrackingEnabled")
        guard let summary = await reader?.summary(kind: kind, period: period, now: now, trackingEnabledNow: trackingNow,
                                                   calendar: calendar) else { return }
        guard generation == refreshGeneration else { return }

        let foreground = [RecapReminderSettings.Key.lastActiveAt, RecapReminderSettings.Key.lastBackgroundAt]
            .compactMap { defaults.object(forKey: $0) as? Date }
        let exemptDay = defaults.string(forKey: RecapReminderSettings.Key.firstEveningDay).flatMap { Self.date(fromDayKey: $0) }
        let reason = RecapSkipRules.reason(summary: summary, canTrack: store.canTrack, skipQuietDays: settings.skipQuietDays,
                                           fire: fire, foregroundDates: foreground, exemptDay: exemptDay, calendar: calendar)
        plan = Plan(fire: fire, summary: summary, skipReason: reason)

        if let reason {
            removePending(reason: reason.rawValue, fire: fire)
            return
        }
        let content = RecapContentBuilder.content(for: summary, showPlaceNames: settings.showPlaceNames,
                                                  usesMetric: Self.usesMetric(defaults))
        await schedule(content, kind: kind, at: fire, calendar: calendar)
    }

    private func schedule(_ recap: RecapContent, kind: RecapPeriodKind, at fire: Date, calendar: Calendar) async {
        await refreshAuthorizationStatus()
        guard [.authorized, .provisional, .ephemeral].contains(authorizationStatus) else {
            removePending(reason: "notifications not allowed", fire: fire)
            return
        }
        let content = UNMutableNotificationContent()
        content.title = recap.title
        content.body = recap.body
        content.sound = .default
        content.threadIdentifier = Self.requestIdentifier
        content.userInfo = [Self.userInfoKindKey: kind.rawValue]
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fire)
        let request = UNNotificationRequest(identifier: Self.requestIdentifier, content: content,
                                            trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
        do {
            // Adding with the same identifier replaces the pending request: there is never more than one.
            try await center.add(request)
            LogManager.shared.info("Recap scheduled for \(Self.logDate(fire))", category: .general)
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--log-recap-schedule") {
                await logPendingRequest()
            }
            #endif
        } catch {
            LogManager.shared.warning("Couldn't schedule the evening recap · \(error.localizedDescription)", category: .general)
        }
    }

    private func removePending(reason: String, fire: Date? = nil) {
        center.removePendingNotificationRequests(withIdentifiers: [Self.requestIdentifier])
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--log-recap-schedule") {
            LogManager.shared.info("Recap debug · no pending request (\(reason))\(fire.map { " for \(Self.logDate($0))" } ?? "")",
                                   category: .general)
        }
        #endif
    }

    #if DEBUG
    /// QA evidence (`--log-recap-schedule`): what iOS actually holds, read back from the notification center.
    private func logPendingRequest() async {
        let pending = await center.pendingNotificationRequests().filter { $0.identifier == Self.requestIdentifier }
        for request in pending {
            let trigger = request.trigger as? UNCalendarNotificationTrigger
            let fire = trigger?.nextTriggerDate().map(Self.logDate) ?? "?"
            LogManager.shared.info("Recap debug · pending \(pending.count) · fires \(fire) · \(request.content.title): \(request.content.body)",
                                   category: .general)
        }
    }
    #endif

    // MARK: - Helpers

    static func usesMetric(_ defaults: UserDefaults) -> Bool {
        defaults.object(forKey: "usesMetricDistanceUnits") == nil ? true : defaults.bool(forKey: "usesMetricDistanceUnits")
    }

    private func apply(_ updated: RecapReminderSettings) {
        settings = updated
        updated.save(defaults: defaults)
    }

    static func dayKey(_ date: Date, calendar: Calendar = .autoupdatingCurrent) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    static func date(fromDayKey key: String, calendar: Calendar = .autoupdatingCurrent) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12))
    }

    private static func logDate(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day().hour().minute())
    }
}

// MARK: - Reading the period

/// Reads one period off the main thread and returns plain values; models never leave this actor.
@ModelActor
actor RecapDataReader {
    func summary(kind: RecapPeriodKind, period: DateInterval, now: Date, trackingEnabledNow: Bool,
                 calendar: Calendar) -> RecapSummary? {
        let start = period.start
        let end = period.end
        do {
            // Every stay that overlaps the period, as Today counts them (a night at home that began yesterday too).
            var visitQuery = FetchDescriptor<Visit>(predicate: #Predicate {
                $0.arrivedAt < end && ($0.departedAt ?? end) > start
            })
            visitQuery.sortBy = [SortDescriptor(\.arrivedAt)]
            let visits = try modelContext.fetch(visitQuery)

            var pointQuery = FetchDescriptor<LocationPoint>(predicate: #Predicate { $0.timestamp >= start && $0.timestamp < end })
            pointQuery.sortBy = [SortDescriptor(\.timestamp)]
            let points = try modelContext.fetch(pointQuery)

            let earlierQuery = FetchDescriptor<Visit>(predicate: #Predicate { $0.arrivedAt < start })
            let earlier = visits.isEmpty ? [] : try modelContext.fetch(earlierQuery)

            let sessionQuery = FetchDescriptor<RecordingSession>(predicate: #Predicate {
                $0.startedAt < end && ($0.endedAt ?? end) > start
            })
            let sessionCount = try modelContext.fetchCount(sessionQuery)
            let trackingWasOn = !points.isEmpty || sessionCount > 0 || (trackingEnabledNow && period.contains(now))

            var countryCount: Int?
            if kind == .week {
                let stays = try modelContext.fetch(FetchDescriptor<CountryStay>())
                let first = CountryDayCalculator.dayIdentifier(start, timeZone: calendar.timeZone)
                let last = CountryDayCalculator.dayIdentifier(end.addingTimeInterval(-1), timeZone: calendar.timeZone)
                let countries = CountryDayCalculator.summaries(visits: visits, points: points, stays: stays,
                                                              firstDay: first, lastDay: last, now: now)
                countryCount = countries.isEmpty ? nil : countries.count
            }

            // Today's Home: the first saved place of kind Home, in the view model's name order.
            var placeQuery = FetchDescriptor<SavedPlace>()
            placeQuery.sortBy = [SortDescriptor(\.name, order: .forward)]
            let home = try modelContext.fetch(placeQuery).first { $0.kind == .home }

            return RecapAggregation.summary(
                kind: kind,
                visits: visits.enumerated().map { DayStayVisit($1, index: $0) },
                earlierVisits: earlier.enumerated().map { DayStayVisit($1, index: $0) },
                points: points.map { RecapPoint(latitude: $0.latitude, longitude: $0.longitude, timestamp: $0.timestamp, isOutlier: $0.isOutlier) },
                home: home.map(DayStayHome.init),
                countryCount: countryCount,
                trackingWasOn: trackingWasOn,
                period: period,
                now: now,
                calendar: calendar
            )
        } catch {
            return nil
        }
    }
}

// MARK: - Opening the app from a recap

/// A tapped recap: the map opens on Today, or on the last 7 days for the weekly recap.
@MainActor
final class RecapOpenRequest: ObservableObject {
    static let shared = RecapOpenRequest()
    @Published private(set) var pending: RecapPeriodKind?
    @Published private(set) var revision = 0

    func open(_ kind: RecapPeriodKind) {
        pending = kind
        revision += 1
    }

    func consume() -> RecapPeriodKind? {
        defer { pending = nil }
        return pending
    }
}
