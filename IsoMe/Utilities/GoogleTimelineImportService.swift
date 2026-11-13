import Foundation
import CoreFoundation

/// Reads coordinates and times already present in Google's exports. Never looks
/// up places or invents timestamps for untimed waypoints.
enum GoogleTimelineImportService {
    static func parse(root: Any, format: ImportSourceFormat) throws -> ImportPayload {
        let object = root as? [String: Any]
        let key: String
        switch format {
        case .googleRaw: key = "locations"
        case .googleSemantic: key = "timelineObjects"
        case .googleDevice: key = "semanticSegments"
        default: throw ImportError.unsupportedFormat
        }
        guard let rows = (root as? [Any]) ?? (object?[key] as? [Any]) else {
            throw TimelineImportError.invalidGoogleArray
        }
        guard rows.count <= ImportPayload.maximumRecordCount else { throw TimelineImportError.tooManyRecords }
        var payload = ImportPayload(format: format)
        for (index, value) in rows.enumerated() {
            if index.isMultiple(of: 500) { try Task.checkCancellation() }
            guard let row = value as? [String: Any] else {
                payload.invalidRecordCount += 1
                if format == .googleRaw { payload.unorderableRawObservationCount += 1 }
                continue
            }
            switch format {
            case .googleRaw:
                let observedTime = milliseconds(row["timestampMs"]) ?? date(row["timestamp"])
                if let c = coordinate(row), let time = observedTime,
                   row["accuracy"] == nil || number(row["accuracy"]).map({ $0 >= 0 }) == true {
                    payload.points.append(ImportedLocationPoint(
                        latitude: c.latitude, longitude: c.longitude, timestamp: time,
                        altitude: number(row["altitude"]), speed: number(row["velocity"]).flatMap { $0 >= 0 ? $0 : nil },
                        horizontalAccuracy: number(row["accuracy"]) ?? -1, isOutlier: false
                    ))
                } else {
                    payload.invalidRecordCount += 1
                    if let observedTime { payload.rawInferenceBarrierDates.append(observedTime) }
                    else { payload.unorderableRawObservationCount += 1 }
                }
            case .googleSemantic:
                if let visit = row["placeVisit"] as? [String: Any] {
                    let location = visit["location"] as? [String: Any] ?? [:]
                    let duration = visit["duration"] as? [String: Any] ?? [:]
                    appendVisit(location: location,
                                start: date(duration["startTimestamp"]) ?? milliseconds(duration["startTimestampMs"]),
                                end: date(duration["endTimestamp"]) ?? milliseconds(duration["endTimestampMs"]), into: &payload)
                } else if let activity = row["activitySegment"] as? [String: Any] {
                    let duration = activity["duration"] as? [String: Any] ?? [:]
                    let start = date(duration["startTimestamp"]) ?? milliseconds(duration["startTimestampMs"])
                    let end = date(duration["endTimestamp"]) ?? milliseconds(duration["endTimestampMs"])
                    guard let start, let end, end >= start else { payload.invalidRecordCount += 1; continue }
                    appendPoint(location: activity["startLocation"], timestamp: start, into: &payload)
                    appendPoint(location: activity["endLocation"], timestamp: end, into: &payload)
                    if let path = activity["simplifiedRawPath"] as? [String: Any], let points = path["points"] as? [Any] {
                        try appendPath(points, start: start, end: end, into: &payload)
                    }
                    if let path = activity["waypointPath"] as? [String: Any], let points = path["waypoints"] as? [Any] {
                        try appendPath(points, start: start, end: end, into: &payload)
                    }
                } else { payload.unsupportedRecordCount += 1 }
            case .googleDevice:
                let start = date(row["startTime"]), end = date(row["endTime"])
                guard let start, let end, end >= start else { payload.invalidRecordCount += 1; continue }
                var supported = false
                if let visit = row["visit"] as? [String: Any] {
                    supported = true
                    let candidate = visit["topCandidate"] as? [String: Any] ?? [:]
                    var location = candidate["placeLocation"] as? [String: Any] ?? [:]
                    if let point = candidate["placeLocation"] as? String { location["latLng"] = point }
                    location["name"] = candidate["placeName"] ?? visit["name"]
                    location["address"] = candidate["address"] ?? visit["address"]
                    appendVisit(location: location, start: start, end: end, into: &payload)
                }
                if let activity = row["activity"] as? [String: Any] {
                    supported = true
                    appendPoint(location: activity["start"], timestamp: start, into: &payload)
                    appendPoint(location: activity["end"], timestamp: end, into: &payload)
                }
                if let points = row["timelinePath"] as? [Any] {
                    supported = true
                    try appendPath(points, start: start, end: end, into: &payload)
                }
                if !supported { payload.unsupportedRecordCount += 1 }
            default: break
            }
            guard payload.visits.count + payload.points.count <= ImportPayload.maximumRecordCount else {
                throw TimelineImportError.tooManyRecords
            }
        }
        return payload
    }

    private static func appendVisit(location: [String: Any], start: Date?, end: Date?, into payload: inout ImportPayload) {
        guard let c = coordinate(location), let start, let end, end >= start else {
            payload.invalidRecordCount += 1; return
        }
        payload.visits.append(ImportedVisit(
            latitude: c.latitude, longitude: c.longitude, arrivedAt: start, departedAt: end,
            locationName: location["name"] as? String, address: location["address"] as? String, notes: nil,
            sourceRaw: "imported", placeSourceRaw: "import"
        ))
    }

    private static func appendPoint(location: Any?, timestamp: Date?, into payload: inout ImportPayload) {
        guard let location else { return }
        let dictionary = (location as? [String: Any]) ?? ["latLng": location]
        guard let c = coordinate(dictionary), let timestamp else { payload.invalidRecordCount += 1; return }
        // Absent accuracy is unmeasured; an explicitly invalid value is not an unknown fix.
        let accuracy: Double
        if let supplied = dictionary["accuracy"] {
            guard let measured = number(supplied), measured >= 0 else { payload.invalidRecordCount += 1; return }
            accuracy = measured
        } else { accuracy = -1 }
        payload.points.append(ImportedLocationPoint(
            latitude: c.latitude, longitude: c.longitude, timestamp: timestamp,
            altitude: number(dictionary["altitude"]), speed: nil,
            horizontalAccuracy: accuracy, isOutlier: false
        ))
    }

    private static func appendPath(_ points: [Any], start: Date?, end: Date?, into payload: inout ImportPayload) throws {
        guard points.count <= ImportPayload.maximumRecordCount else { throw TimelineImportError.tooManyRecords }
        for (index, value) in points.enumerated() {
            if index.isMultiple(of: 500) { try Task.checkCancellation() }
            guard let point = value as? [String: Any] else { payload.invalidRecordCount += 1; continue }
            var timestamp = date(point["time"]) ?? date(point["timestamp"]) ?? milliseconds(point["timestampMs"])
            if timestamp == nil, let offset = number(point["durationMinutesOffsetFromStartTime"]), offset >= 0, let start {
                timestamp = start.addingTimeInterval(offset * 60)
            }
            guard let timestamp, start.map({ timestamp >= $0 }) ?? true, end.map({ timestamp <= $0 }) ?? true else {
                payload.invalidRecordCount += 1; continue
            }
            appendPoint(location: point, timestamp: timestamp, into: &payload)
            guard payload.visits.count + payload.points.count <= ImportPayload.maximumRecordCount else {
                throw TimelineImportError.tooManyRecords
            }
        }
    }

    static func coordinate(_ object: [String: Any]) -> (latitude: Double, longitude: Double)? {
        let latitude: Double, longitude: Double
        if let lat = number(object["latitudeE7"] ?? object["latE7"]), let lon = number(object["longitudeE7"] ?? object["lngE7"]) {
            latitude = lat / 10_000_000; longitude = lon / 10_000_000
        } else if let string = (object["latLng"] ?? object["point"]) as? String {
            let parts = string.replacingOccurrences(of: "geo:", with: "")
                .replacingOccurrences(of: "\u{00B0}", with: "").split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count == 2,
                  let lat = Double(parts[0].trimmingCharacters(in: .whitespacesAndNewlines)),
                  let lon = Double(parts[1].trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
            latitude = lat; longitude = lon
        } else { return nil }
        guard latitude.isFinite, longitude.isFinite, (-90...90).contains(latitude), (-180...180).contains(longitude) else { return nil }
        return (latitude, longitude)
    }

    private static func number(_ value: Any?) -> Double? {
        if let value = value as? NSNumber {
            guard CFGetTypeID(value) != CFBooleanGetTypeID() else { return nil }
            return value.doubleValue.isFinite ? value.doubleValue : nil
        }
        guard let value = value as? String, let number = Double(value), number.isFinite else { return nil }
        return number
    }

    private static func milliseconds(_ value: Any?) -> Date? {
        guard let milliseconds = number(value) else { return nil }
        let result = Date(timeIntervalSince1970: milliseconds / 1_000)
        return validDate(result) ? result : nil
    }

    private static func date(_ value: Any?) -> Date? {
        guard let text = value as? String else { return nil }
        let result = (try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(text))
            ?? (try? Date.ISO8601FormatStyle().parse(text))
        return result.flatMap { validDate($0) ? $0 : nil }
    }

    static func validDate(_ date: Date) -> Bool {
        date.timeIntervalSince1970.isFinite && date >= Date.distantPast && date <= Date.distantFuture
    }
}
