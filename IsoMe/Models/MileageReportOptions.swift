import Foundation

struct MileageReportOptions {
    /// Include trips starting in [startsAt, endsAt); cross-boundary trips are not prorated.
    let startsAt: Date
    let endsAt: Date
    var vehicleID: UUID?
    var classifications: Set<TripClassification> = Set(TripClassification.allCases)
    var reviewedOnly: Bool = false

    init(startsAt: Date, endsAt: Date) throws {
        guard startsAt.timeIntervalSince1970.isFinite, endsAt.timeIntervalSince1970.isFinite,
              startsAt < endsAt else { throw MileageValidationError.invalidDateRange }
        self.startsAt = startsAt
        self.endsAt = endsAt
    }

    /// Explicit time zone avoids silently using the machine's current zone for tax-year edges.
    static func taxYear(_ year: Int, timeZone: TimeZone) throws -> MileageReportOptions {
        guard (1...9998).contains(year) else { throw MileageValidationError.invalidDateRange }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        guard let start = calendar.date(from: DateComponents(year: year, month: 1, day: 1)),
              let end = calendar.date(from: DateComponents(year: year + 1, month: 1, day: 1)) else {
            throw MileageValidationError.invalidDateRange
        }
        return try MileageReportOptions(startsAt: start, endsAt: end)
    }
}
