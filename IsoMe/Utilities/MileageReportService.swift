import Foundation

/// Immutable summary input. No point fetching, network access, or mutable model dependencies
/// in the report calculation itself. Snapshot creation belongs on the model context's actor.
struct MileageReportTrip {
    let id: UUID
    let startedAt: Date
    let endedAt: Date?
    let distanceMeters: Double
    let classification: TripClassification
    let reviewStatus: TripReviewStatus
    let businessPurpose: String
    let vehicleID: UUID?
    let vehicleNameSnapshot: String?
    let excludeFromReports: Bool

    init(trip: Trip) {
        id = trip.id
        startedAt = trip.startedAt
        endedAt = trip.endedAt
        distanceMeters = trip.effectiveDistanceMeters
        classification = trip.classification
        reviewStatus = trip.reviewStatus
        businessPurpose = trip.businessPurpose
        vehicleID = trip.vehicleID
        vehicleNameSnapshot = trip.vehicleNameSnapshot
        excludeFromReports = trip.excludeFromReports
    }
}

enum MileageReportService {
    static let disclaimer = "Mileage estimates are generated from GPS data and may differ from your vehicle odometer. Classifications, rates, and deduction estimates are for recordkeeping only and are not tax, legal, or accounting advice. Review every trip and consult a qualified tax professional. Tax rules and mileage rates can change."
    static let metersPerMile = Decimal(string: "1609.344")!

    enum CalculationError: Error, Equatable {
        case unrepresentableAggregate
    }

    struct Row {
        let trip: MileageReportTrip
        let miles: Decimal
        /// Only explicit business classifications receive an estimate; nil means unavailable.
        let rate: MileageRateProvider.Selection?
        let estimatedDeductionUSD: Decimal?
    }

    struct VehicleTotal {
        let vehicleID: UUID?
        /// Historical snapshots, not a lookup of the current mutable vehicle name.
        let nameSnapshots: [String]
        let miles: Decimal
    }

    struct Report {
        let disclaimer: String
        let rows: [Row]
        let milesByClassification: [TripClassification: Decimal]
        let totalsByVehicle: [VehicleTotal]
        let reviewedBusinessTripCount: Int
        let businessTripsMissingPurpose: Int
        let businessTripsMissingRate: Int
        /// Sum of individually rounded, known-rate business estimates. Never presented as a
        /// complete total when a rate is missing; check isEstimateComplete as well.
        let knownEstimatedDeductionUSD: Decimal
        let isEstimateComplete: Bool
    }

    static func build(trips: [MileageReportTrip], options: MileageReportOptions,
                      rates: MileageRateProvider) throws -> Report {
        var rows: [Row] = []
        var totals = Dictionary(uniqueKeysWithValues: TripClassification.allCases.map { ($0, Decimal.zero) })
        var vehicleMiles: [UUID?: Decimal] = [:]
        var vehicleNames: [UUID?: Set<String>] = [:]
        var seenIDs = Set<UUID>()
        var reviewed = 0
        var missingPurpose = 0
        var missingRate = 0
        var estimate = Decimal.zero
        for trip in trips where !trip.excludeFromReports {
            guard trip.startedAt.timeIntervalSince1970.isFinite else {
                throw MileageValidationError.invalidTrip(trip.id)
            }
        }
        for trip in trips.filter({ !$0.excludeFromReports }).sorted(by: {
            $0.startedAt == $1.startedAt ? $0.id.uuidString < $1.id.uuidString : $0.startedAt < $1.startedAt
        }) {
            guard !trip.excludeFromReports, trip.startedAt >= options.startsAt, trip.startedAt < options.endsAt,
                  options.vehicleID == nil || options.vehicleID == trip.vehicleID,
                  options.classifications.contains(trip.classification),
                  !options.reviewedOnly || trip.reviewStatus == .reviewed else { continue }
            // Active, malformed, or duplicate summaries must not silently inflate a report.
            guard trip.startedAt.timeIntervalSince1970.isFinite,
                  let end = trip.endedAt, end.timeIntervalSince1970.isFinite, end >= trip.startedAt,
                  trip.distanceMeters.isFinite, trip.distanceMeters >= 0,
                  seenIDs.insert(trip.id).inserted else { throw MileageValidationError.invalidTrip(trip.id) }
            guard let meters = Decimal(string: String(trip.distanceMeters), locale: Locale(identifier: "en_US_POSIX")),
                  !meters.isNaN else { throw MileageValidationError.invalidTrip(trip.id) }
            let miles = meters / metersPerMile
            let rate = trip.classification == .business ? rates.rate(on: trip.startedAt) : nil
            let deduction = rate.map { roundedUSD(miles * $0.usdPerMile) }
            guard !miles.isNaN, deduction?.isNaN != true else {
                throw MileageValidationError.invalidTrip(trip.id)
            }
            rows.append(Row(trip: trip, miles: miles, rate: rate, estimatedDeductionUSD: deduction))
            totals[trip.classification] = try adding(totals[trip.classification, default: 0], miles)
            vehicleMiles[trip.vehicleID] = try adding(vehicleMiles[trip.vehicleID, default: 0], miles)
            if let name = trip.vehicleNameSnapshot { vehicleNames[trip.vehicleID, default: []].insert(name) }
            if trip.classification == .business {
                if trip.reviewStatus == .reviewed { reviewed += 1 }
                if trip.businessPurpose.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { missingPurpose += 1 }
                if rate == nil { missingRate += 1 }
                estimate = try adding(estimate, deduction ?? 0, requiresExactResult: true)
            }
        }
        let vehicles = vehicleMiles.map { id, miles in
            VehicleTotal(vehicleID: id, nameSnapshots: (vehicleNames[id] ?? []).sorted(), miles: miles)
        }.sorted { ($0.vehicleID?.uuidString ?? "") < ($1.vehicleID?.uuidString ?? "") }
        return Report(disclaimer: disclaimer, rows: rows, milesByClassification: totals,
                      totalsByVehicle: vehicles, reviewedBusinessTripCount: reviewed,
                      businessTripsMissingPurpose: missingPurpose, businessTripsMissingRate: missingRate,
                      knownEstimatedDeductionUSD: estimate, isEstimateComplete: missingRate == 0)
    }

    /// Mileage quotients can repeat, so finite rounding at Decimal's precision is expected.
    /// Money rows are already rounded to cents: their sum must be exact, not silently lose a
    /// smaller row. Both paths reject overflow/underflow/invalid results, without a rate cap.
    private static func adding(_ lhs: Decimal, _ rhs: Decimal, requiresExactResult: Bool = false) throws -> Decimal {
        var lhs = lhs
        var rhs = rhs
        var result = Decimal.zero
        let status = NSDecimalAdd(&result, &lhs, &rhs, .plain)
        guard !result.isNaN,
              status == .noError || (!requiresExactResult && status == .lossOfPrecision) else {
            throw CalculationError.unrepresentableAggregate
        }
        return result
    }

    /// Round per trip, half up, so the visible rows sum exactly to the reported known estimate.
    private static func roundedUSD(_ value: Decimal) -> Decimal {
        var value = value
        var result = Decimal.zero
        NSDecimalRound(&result, &value, 2, .plain)
        return result
    }
}
