import Foundation
import SwiftData

/// Manual stays use civil dates, so changing the phone's time zone cannot shift them.
@Model
final class CountryStay {
    var id: UUID
    var countryCode: String
    var firstDay: String
    var lastDay: String
    var note: String?

    init(countryCode: String, firstDay: String, lastDay: String, note: String? = nil) {
        id = UUID()
        self.note = note
        self.countryCode = countryCode.uppercased()
        self.firstDay = firstDay
        self.lastDay = lastDay
    }
}

struct CountryPresence: Equatable {
    let countryCode: String
    let day: String
    var recorded: Bool
    var manual: Bool
}

struct CountryDaySummary: Identifiable {
    let countryCode: String
    let days: [CountryPresence]
    var id: String { countryCode }
    var name: String { Locale.current.localizedString(forRegionCode: countryCode) ?? countryCode }
}

enum CountryDayCalculator {
    private static let countryCodes = Set(Locale.Region.isoRegions.map(\.identifier))
    /// All date arithmetic uses Gregorian civil dates, independent of device calendar settings.
    static func dayIdentifier(_ date: Date, timeZone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    static func date(for day: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.isLenient = false
        guard let date = formatter.date(from: day),
              dayIdentifier(date, timeZone: formatter.timeZone) == day else { return nil }
        return date
    }

    static func inclusiveDays(from first: String, through last: String) -> [String] {
        guard let start = date(for: first), let end = date(for: last), start <= end else { return [] }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var result: [String] = []
        var cursor = start
        while cursor <= end {
            result.append(dayIdentifier(cursor, timeZone: calendar.timeZone))
            guard let next = calendar.date(byAdding: .day, value: 1, to: cursor) else { break }
            cursor = next
        }
        return result
    }

    static func recordedDays(start: Date, end: Date?, timeZoneIdentifier: String?, now: Date) -> [String] {
        // Never infer an ongoing stay from an unclosed, potentially stale visit.
        guard start <= now, let identifier = timeZoneIdentifier,
              let zone = TimeZone(identifier: identifier) else { return [] }
        guard let end else { return [dayIdentifier(start, timeZone: zone)] }
        let lastInstant: Date
        if end > start {
            // Intervals are half-open: departing at midnight doesn't imply presence the next day.
            lastInstant = min(end.addingTimeInterval(-0.001), now)
        } else {
            lastInstant = start
        }
        return inclusiveDays(from: dayIdentifier(start, timeZone: zone),
                             through: dayIdentifier(lastInstant, timeZone: zone))
    }

    static func summaries(visits: [Visit], points: [LocationPoint], stays: [CountryStay],
                          firstDay: String? = nil, lastDay: String? = nil, now: Date = Date()) -> [CountryDaySummary] {
        var presence: [String: [String: CountryPresence]] = [:]
        func add(code: String?, days: [String], manual: Bool) {
            guard let code = code?.uppercased(), countryCodes.contains(code) else { return }
            for day in days {
                guard firstDay.map({ day >= $0 }) ?? true,
                      lastDay.map({ day <= $0 }) ?? true else { continue }
                var item = presence[code]?[day] ?? CountryPresence(countryCode: code, day: day, recorded: false, manual: false)
                item.recorded = item.recorded || !manual
                item.manual = item.manual || manual
                presence[code, default: [:]][day] = item
            }
        }
        for visit in visits {
            add(code: visit.countryCode,
                days: recordedDays(start: visit.arrivedAt, end: visit.countryDepartureIsInferred == false ? visit.departedAt : nil,
                                   timeZoneIdentifier: visit.countryTimeZoneIdentifier, now: now), manual: false)
        }
        for point in points where !point.isOutlier {
            add(code: point.countryCode,
                days: recordedDays(start: point.timestamp, end: nil,
                                   timeZoneIdentifier: point.countryTimeZoneIdentifier, now: now), manual: false)
        }
        for stay in stays {
            add(code: stay.countryCode, days: inclusiveDays(from: stay.firstDay, through: stay.lastDay), manual: true)
        }
        return presence.map { code, days in
            CountryDaySummary(countryCode: code, days: days.values.sorted { $0.day > $1.day })
        }.sorted {
            if $0.days.count != $1.days.count { return $0.days.count > $1.days.count }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    static func csv(_ summaries: [CountryDaySummary]) -> String {
        let rows = summaries.sorted { $0.countryCode < $1.countryCode }.flatMap { summary in
            summary.days.sorted { $0.day < $1.day }.map { presence in
                let source = presence.recorded && presence.manual ? "recorded+manual" : presence.manual ? "manual" : "recorded"
                return "\(presence.countryCode),\(presence.day),\(source)"
            }
        }
        return (["country_code,local_date,source"] + rows).joined(separator: "\r\n") + "\r\n"
    }
}
