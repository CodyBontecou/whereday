import Foundation

/// Default initialization supplies NO rates. Callers explicitly provide a sourced
/// schedule/custom override, or select a named frozen catalog. Periods are absolute
/// half-open dates, allowing mid-year changes without guessing an unknown rate.
struct MileageRate: Equatable, Codable {
    let startsAt: Date
    let endsAt: Date
    let usdPerMile: Decimal
    let source: String
}

struct MileageRateProvider {
    private let rates: [MileageRate]
    private let customRate: Decimal?
    private let customSource: String?

    init(rates: [MileageRate] = [], customRate: Decimal? = nil, customSource: String? = nil) throws {
        for rate in rates {
            guard rate.startsAt.timeIntervalSince1970.isFinite, rate.endsAt.timeIntervalSince1970.isFinite,
                  rate.startsAt < rate.endsAt, Self.valid(rate.usdPerMile),
                  !rate.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw MileageValidationError.invalidRate
            }
        }
        let ordered = rates.sorted { $0.startsAt < $1.startsAt }
        for pair in zip(ordered, ordered.dropFirst()) where pair.0.endsAt > pair.1.startsAt {
            throw MileageValidationError.overlappingRates
        }
        if let customRate {
            guard Self.valid(customRate), let customSource,
                  !customSource.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw MileageValidationError.invalidRate
            }
        }
        self.rates = ordered
        self.customRate = customRate
        self.customSource = customSource
    }

    /// Explicit snapshot of published US IRS BUSINESS USD/mile rates, researched
    /// 2026-11-13. Both 2022 and 2026 split on July 1; no other years are inferred.
    /// Supply the SAME report timezone as MileageReportOptions: Gregorian civil
    /// midnights become absolute half-open intervals in that zone, never UTC by
    /// fallback. The caller chooses the report date instant; current reports use
    /// trip.startedAt for the whole trip (no proration), not its stored timezone.
    /// This catalog does not decide tax eligibility or provide accounting advice.
    /// An explicit custom override retains init's semantics, including applying
    /// outside the frozen years to finite lookup instants. Default init stays empty.
    static func frozenUSBusiness2022Through2026(timeZone: TimeZone,
                                               customRate: Decimal? = nil,
                                               customSource: String? = nil) throws -> MileageRateProvider {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = timeZone

        func boundary(_ year: Int, _ month: Int) throws -> Date {
            guard (1...9998).contains(year), (1...12).contains(month),
                  let date = calendar.date(from: DateComponents(year: year, month: month,
                                                               day: 1, hour: 0, minute: 0, second: 0)),
                  date.timeIntervalSince1970.isFinite else {
                throw MileageValidationError.invalidDateRange
            }
            // Calendar.date can normalize invalid/skipped civil times. Never
            // silently substitute another date/hour or a default timezone.
            let actual = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
            guard actual.year == year, actual.month == month, actual.day == 1,
                  actual.hour == 0, actual.minute == 0, actual.second == 0 else {
                throw MileageValidationError.invalidDateRange
            }
            return date
        }

        func period(_ startYear: Int, _ startMonth: Int, _ endYear: Int, _ endMonth: Int,
                    _ thousandths: Int, _ provenance: String, _ sources: [String]) throws -> MileageRate {
            guard thousandths >= 0, !provenance.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !sources.isEmpty, sources.allSatisfy({ text in
                      guard let url = URL(string: text) else { return false }
                      return url.scheme == "https" && url.host == "www.irs.gov" && url.absoluteString == text
                  }) else { throw MileageValidationError.invalidRate }
            return MileageRate(
                startsAt: try boundary(startYear, startMonth), endsAt: try boundary(endYear, endMonth),
                usdPerMile: Decimal(sign: .plus, exponent: -3, significand: Decimal(thousandths)),
                source: "Frozen US IRS BUSINESS 2022–2026; research snapshot 2026-11-13. "
                    + provenance + " Primary sources: " + sources.joined(separator: " ; "))
        }

        let table = "https://www.irs.gov/tax-professionals/standard-mileage-rates"
        let revision2022 = "https://www.irs.gov/irb/2022-26_IRB#ANN-2022-13"
        let midyear2022 = "https://www.irs.gov/newsroom/irs-increases-mileage-rate-for-remainder-of-2022"
        let revision2026 = "https://www.irs.gov/irb/2026-29_irb"
        let schedule = [
            try period(2022, 1, 2022, 7, 585,
                "2022-01-01 through 2022-06-30: IR-2021-251; both half-year endpoints explicitly in IR-2022-124/table; Announcement 2022-13 preserves the pre-July rate.",
                ["https://www.irs.gov/newsroom/irs-issues-standard-mileage-rates-for-2022", midyear2022, revision2022, table]),
            try period(2022, 7, 2023, 1, 625,
                "2022-07-01 through 2022-12-31: Announcement 2022-13 and IR-2022-124; explicit second-half period in table.",
                [revision2022, midyear2022, table]),
            try period(2023, 1, 2024, 1, 655,
                "2023-01-01 through 2023-12-31: Notice 2023-3 sets rate/start; annual endpoint from published IRS table, not a quoted December 31 sentence in the notice.",
                ["https://www.irs.gov/irb/2023-03_IRB#NOT-2023-3", table]),
            try period(2024, 1, 2025, 1, 670,
                "2024-01-01 through 2024-12-31: IR-2023-239 sets rate/start; annual endpoint from published IRS table.",
                ["https://www.irs.gov/newsroom/irs-issues-standard-mileage-rates-for-2024-mileage-rate-increases-to-67-cents-a-mile-up-1-point-5-cents-from-2023", table]),
            try period(2025, 1, 2026, 1, 700,
                "2025-01-01 through 2025-12-31: IR-2024-312 sets rate/start; annual endpoint from published IRS table.",
                ["https://www.irs.gov/newsroom/irs-increases-the-standard-mileage-rate-for-business-use-in-2025-key-rate-increases-3-cents-to-70-cents-per-mile", table]),
            try period(2026, 1, 2026, 7, 725,
                "2026-01-01 through 2026-06-30: IR-2025-128 initially announces 72.5 cents; Announcement 2026-11 preserves Notice 2026-10 before July 1; table explicitly limits this period.",
                ["https://www.irs.gov/newsroom/irs-sets-2026-business-standard-mileage-rate-at-725-cents-per-mile-up-25-cents", revision2026, table]),
            try period(2026, 7, 2027, 1, 760,
                "2026-07-01 through 2026-12-31: Announcement 2026-11 in Internal Revenue Bulletin 2026-29 revises Notice 2026-10; explicit second-half period in table.",
                [revision2026, table])
        ]
        return try MileageRateProvider(rates: schedule, customRate: customRate, customSource: customSource)
    }

    struct Selection: Equatable, Codable {
        let usdPerMile: Decimal
        let source: String
        let isCustomOverride: Bool
    }

    func rate(on date: Date) -> Selection? {
        guard date.timeIntervalSince1970.isFinite else { return nil }
        if let customRate, let customSource {
            return Selection(usdPerMile: customRate, source: customSource, isCustomOverride: true)
        }
        guard let rate = rates.first(where: { $0.startsAt <= date && date < $0.endsAt }) else { return nil }
        return Selection(usdPerMile: rate.usdPerMile, source: rate.source, isCustomOverride: false)
    }

    private static func valid(_ value: Decimal) -> Bool {
        let number = NSDecimalNumber(decimal: value).doubleValue
        return number.isFinite && number >= 0
    }
}
