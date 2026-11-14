import Foundation

/// Pure renderers of an already-built report. No calculation, persistence, purchase decision,
/// file IO, clock, current locale/timezone or generated identifiers. Callers must supply the
/// same explicit zone used for their report options and rate schedule; Report has no zone.
/// This utility is NOT an entitlement boundary; any future export UI must use existing gating.
enum MileageReportExporter {
    enum ExportError: Error, Equatable {
        case invalidDate(UUID)
        case invalidOdometer(UUID)
        case invalidDecimal
        case unsupportedCSVControl
    }

    private static let format = "whereday-mileage-report"
    private static let version = 1
    // Issue #61 enumerates 19 minimum fields. Keep them first/in order, then typed extensions.
    private static let columns = [
        "trip_id", "date", "start_time", "end_time", "origin_name", "origin_address",
        "destination_name", "destination_address", "classification", "business_purpose", "vehicle",
        "distance_miles", "rate_per_mile_usd", "deduction_usd", "review_status", "source", "notes",
        "start_odometer_miles", "end_odometer_miles", "row_type", "vehicle_id", "trip_time_zone",
        "rate_source", "rate_is_custom_override", "metadata_key", "metadata_value"
    ]

    /// UTF-8, no BOM, every cell quoted, CRLF record endings, doubled quotes. Every record has
    /// 26 cells; consumers MUST filter row_type=trip. Metadata/summary records are not trips.
    /// Text beginning with a control, or = + - @ after whitespace/control/format characters,
    /// gets an ASCII apostrophe prefix. Other control characters (except tab/CR/LF) are rejected.
    /// This is common-spreadsheet defensive presentation, not lossless
    /// text interchange; JSON retains the original text. Never use CSV as a backup codec.
    static func csv(_ report: MileageReportService.Report, timeZone: TimeZone) throws -> Data {
        let rows = try projectedRows(report, timeZone: timeZone)
        var records: [[String]] = [columns]
        func append(_ values: [String: String]) {
            records.append(columns.map { values[$0] ?? "" })
        }
        var metadataValues = metadata(report, timeZone: timeZone)
        metadataValues["format"] = format
        metadataValues["schema_version"] = String(version)
        metadataValues["csv_formula_policy"] = "apostrophe-prefix-v1"
        for key in metadataValues.keys.sorted() {
            append(["row_type": "metadata", "metadata_key": key, "metadata_value": metadataValues[key]!])
        }
        for row in rows {
            var values = ["row_type": "trip"]
            for key in columns {
                if let value = row[key] as? String { values[key] = value }
                else if let value = row[key] as? Bool { values[key] = value ? "true" : "false" }
                // Explicit JSON null / inapplicable cells become empty, never a fabricated zero.
            }
            append(values)
        }
        for classification in TripClassification.allCases {
            append(["row_type": "summary", "metadata_key": "miles." + classification.rawValue,
                    "metadata_value": try decimal(report.milesByClassification[classification, default: 0])])
        }
        let summaries: [(String, String)] = [
            ("reviewed_business_trips", String(report.reviewedBusinessTripCount)),
            ("business_trips_missing_purpose", String(report.businessTripsMissingPurpose)),
            ("business_trips_missing_rate", String(report.businessTripsMissingRate)),
            ("known_estimated_deduction_usd", try decimal(report.knownEstimatedDeductionUSD)),
            ("is_estimate_complete", report.isEstimateComplete ? "true" : "false")
        ]
        for (key, value) in summaries {
            append(["row_type": "summary", "metadata_key": key, "metadata_value": value])
        }
        for vehicle in report.totalsByVehicle {
            // In vehicle_summary only, vehicle is a JSON string array preserving every name
            // snapshot without an ambiguous delimiter. ID and miles remain separate cells.
            let names = try JSONSerialization.data(withJSONObject: vehicle.nameSnapshots, options: [.withoutEscapingSlashes])
            append(["row_type": "vehicle_summary", "vehicle_id": vehicle.vehicleID?.uuidString.lowercased() ?? "",
                    "vehicle": String(decoding: names, as: UTF8.self), "distance_miles": try decimal(vehicle.miles)])
        }
        let rendered = try records.map { try $0.map(quotedCell).joined(separator: ",") }
        return Data((rendered.joined(separator: "\r\n") + "\r\n").utf8)
    }

    /// Compact UTF-8 JSON, recursively sorted object keys, schema_version=1. Decimal-valued
    /// fields are base-10 STRINGS, not binary64 or JSON numbers; missing optionals are explicit
    /// null. Integers/booleans remain JSON numbers/booleans. No generated_at/current-time field.
    static func json(_ report: MileageReportService.Report, timeZone: TimeZone) throws -> Data {
        var classifications: [String: String] = [:]
        for classification in TripClassification.allCases {
            classifications[classification.rawValue] = try decimal(report.milesByClassification[classification, default: 0])
        }
        let vehicles: [[String: Any]] = try report.totalsByVehicle.map { vehicle in
            ["vehicle_id": nullable(vehicle.vehicleID?.uuidString.lowercased()),
             "name_snapshots": vehicle.nameSnapshots, "distance_miles": try decimal(vehicle.miles)]
        }
        let summary: [String: Any] = [
            "miles_by_classification": classifications,
            "reviewed_business_trips": report.reviewedBusinessTripCount,
            "business_trips_missing_purpose": report.businessTripsMissingPurpose,
            "business_trips_missing_rate": report.businessTripsMissingRate,
            "known_estimated_deduction_usd": try decimal(report.knownEstimatedDeductionUSD),
            "is_estimate_complete": report.isEstimateComplete,
            "totals_by_vehicle": vehicles
        ]
        let document: [String: Any] = [
            "format": format, "schema_version": version,
            "metadata": metadata(report, timeZone: timeZone), "summary": summary,
            "rows": try projectedRows(report, timeZone: timeZone)
        ]
        return try JSONSerialization.data(withJSONObject: document, options: [.sortedKeys, .withoutEscapingSlashes])
    }

    private static func metadata(_ report: MileageReportService.Report, timeZone: TimeZone) -> [String: String] {
        ["disclaimer": report.disclaimer, "calendar": "gregorian", "time_zone": timeZone.identifier,
         "decimal_encoding": "base10-strings"]
    }

    /// Private wire projection only, not another mutable domain model or a calculator. Rows,
    /// sums, rates and eligibility come verbatim from MileageReportService.Report.
    private static func projectedRows(_ report: MileageReportService.Report, timeZone: TimeZone) throws -> [[String: Any]] {
        let civilDate = formatter("yyyy-MM-dd", timeZone: timeZone)
        let timestamp = formatter("yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX", timeZone: timeZone)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return try report.rows.map { row in
            let trip = row.trip
            guard let end = trip.endedAt else { throw ExportError.invalidDate(trip.id) }
            for instant in [trip.startedAt, end] {
                guard instant.timeIntervalSince1970.isFinite,
                      timeZone.secondsFromGMT(for: instant) % 60 == 0 else { throw ExportError.invalidDate(trip.id) }
                let components = calendar.dateComponents([.era, .year], from: instant)
                guard components.era == 1, let year = components.year, (1...9999).contains(year) else {
                    throw ExportError.invalidDate(trip.id)
                }
            }
            let values: [String: Any] = [
                "trip_id": trip.id.uuidString.lowercased(),
                "date": civilDate.string(from: trip.startedAt),
                "start_time": timestamp.string(from: trip.startedAt), "end_time": timestamp.string(from: end),
                "origin_name": nullable(trip.startName), "origin_address": nullable(trip.startAddress),
                "destination_name": nullable(trip.endName), "destination_address": nullable(trip.endAddress),
                "classification": trip.classification.rawValue, "business_purpose": trip.businessPurpose,
                "vehicle": nullable(trip.vehicleNameSnapshot), "vehicle_id": nullable(trip.vehicleID?.uuidString.lowercased()),
                "distance_miles": try decimal(row.miles),
                "rate_per_mile_usd": try optionalDecimal(row.rate?.usdPerMile),
                "deduction_usd": try optionalDecimal(row.estimatedDeductionUSD),
                "review_status": trip.reviewStatus.rawValue, "source": trip.sourceRaw, "notes": trip.notes,
                "start_odometer_miles": try odometer(trip.startOdometerMiles, tripID: trip.id),
                "end_odometer_miles": try odometer(trip.endOdometerMiles, tripID: trip.id),
                "trip_time_zone": trip.timezoneIdentifier,
                "rate_source": nullable(row.rate?.source),
                "rate_is_custom_override": row.rate.map { $0.isCustomOverride as Any } ?? NSNull()
            ]
            return values
        }
    }

    private static func formatter(_ pattern: String, timeZone: TimeZone) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = pattern
        formatter.isLenient = false
        return formatter
    }

    private static func nullable(_ text: String?) -> Any { text.map { $0 as Any } ?? NSNull() }

    private static func decimal(_ value: Decimal) throws -> String {
        guard !value.isNaN else { throw ExportError.invalidDecimal }
        var copy = value
        return NSDecimalString(&copy, Locale(identifier: "en_US_POSIX"))
    }

    private static func optionalDecimal(_ value: Decimal?) throws -> Any {
        guard let value else { return NSNull() }
        return try decimal(value)
    }

    private static func odometer(_ value: Double?, tripID: UUID) throws -> Any {
        guard let value else { return NSNull() }
        // The model stores Double. Export its shortest round-trip decimal spelling, not a
        // claim to recover precision absent from storage; reject nonrepresentable Decimals.
        guard value.isFinite, value >= 0,
              let converted = Decimal(string: String(value), locale: Locale(identifier: "en_US_POSIX")),
              !converted.isNaN else { throw ExportError.invalidOdometer(tripID) }
        let text = try decimal(converted)
        // Round-trip only this model Double (never money). Reject underflow/precision loss
        // rather than emit a plausible but changed odometer reading.
        guard Double(text) == value else { throw ExportError.invalidOdometer(tripID) }
        return text
    }

    private static func quotedCell(_ original: String) throws -> String {
        guard !original.unicodeScalars.contains(where: {
            $0.properties.generalCategory == .control && ![9, 10, 13].contains($0.value)
        }) else { throw ExportError.unsupportedCSVControl }
        let ignored = CharacterSet.whitespacesAndNewlines.union(.controlCharacters)
        let meaningful = original.unicodeScalars.first {
            !ignored.contains($0) && $0.properties.generalCategory != .format
        }
        let leadingControl = original.unicodeScalars.first.map { $0.properties.generalCategory == .control } ?? false
        let formula = meaningful.map { "=+-@".unicodeScalars.contains($0) } ?? false
        let protected = leadingControl || formula ? "'" + original : original
        return "\"" + protected.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
}
