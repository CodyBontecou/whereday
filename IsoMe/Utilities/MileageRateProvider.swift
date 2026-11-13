import Foundation

/// No built-in IRS rates: callers must supply an explicitly sourced schedule or custom rate.
/// Periods are absolute, half-open dates, allowing mid-year changes without guessing a rate.
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
