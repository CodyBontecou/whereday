import Foundation
import ExportAutomationKit

let formatter = ISO8601DateFormatter()
let zones = ["America/Los_Angeles", "America/New_York", "America/Puerto_Rico", "Europe/Berlin", "Australia/Lord_Howe", "Asia/Kathmandu", "Pacific/Apia"]
let days = ["2026-03-08", "2026-11-01", "2026-03-29", "2026-10-25", "2026-04-05", "2026-10-04", "2026-05-04"]
print("zone\tnow\thour\tminute\tinterval\tnext\tlatest")
for zone in zones {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: zone)!
    for day in days {
        let parts = day.split(separator: "-").map { Int($0)! }
        for (hour, minute) in [(0, 0), (1, 30), (2, 30), (3, 30), (12, 30), (23, 30)] {
            let now = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: hour, minute: minute))!
            for (anchorHour, anchorMinute) in [(0, 0), (1, 30), (2, 30), (9, 0)] {
                for interval in [0, 1, 3, 9, 23] {
                    let schedule = AutomationSchedule(isEnabled: true, preferredHour: anchorHour, preferredMinute: anchorMinute, timeZoneIdentifier: zone)
                    let next = interval == 0
                        ? AutomationScheduleDateMath.calculateNextRunDate(schedule: schedule, now: now, calendar: calendar)!
                        : IntervalExportScheduleDateMath.nextOccurrence(anchorHour: anchorHour, anchorMinute: anchorMinute, intervalHours: interval, after: now, calendar: calendar)!
                    let latest = interval == 0
                        ? AutomationScheduleDateMath.latestScheduledOccurrenceDate(schedule: schedule, now: now, calendar: calendar)!
                        : IntervalExportScheduleDateMath.latestOccurrence(anchorHour: anchorHour, anchorMinute: anchorMinute, intervalHours: interval, at: now, calendar: calendar)!
                    print([zone, formatter.string(from: now), String(anchorHour), String(anchorMinute), String(interval), formatter.string(from: next), formatter.string(from: latest)].joined(separator: "\t"))
                }
            }
        }
    }
}
