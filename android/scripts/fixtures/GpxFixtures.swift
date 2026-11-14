import Foundation

// Synthetic transport models only. Renderers and Visit duration calculation are extracted verbatim.
struct RawValue {let rawValue: String}
struct Visit {
    let latitude: Double, longitude: Double
    let arrivedAt: Date, departedAt: Date?
    let exportLocationName: String?, address: String?, notes: String?
    let source: RawValue, confirmationStatus: RawValue
    let confirmedAt: Date?, updatedAt: Date?
    let originalLatitude: Double?, originalLongitude: Double?, originalLocationName: String?, originalAddress: String?
    let detectedLatitude: Double?, detectedLongitude: Double?, detectedLocationName: String?, detectedAddress: String?
    let placeSource: RawValue?, placeDistanceMeters: Double?
    // ACTUAL_VISIT_DURATION
}
struct LocationPoint {
    let latitude: Double, longitude: Double
    let timestamp: Date
    let horizontalAccuracy: Double, altitude: Double?, speed: Double?
    let isOutlier: Bool
}
struct ExportOptions {
    var includeVisitCoordinates = true, includeVisitDuration = true, includeVisitLocationName = true
    var includeVisitAddress = true, includeVisitNotes = true
    var includePointAltitude = true, includePointSpeed = true, includePointAccuracy = true, includePointOutlierFlag = true
}
let iso = ISO8601DateFormatter()
iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
func date(_ value: Any?) -> Date? { (value as? String).flatMap { iso.date(from: $0) } }
func number(_ row: [String: Any], _ key: String) -> Double? { (row[key] as? NSNumber)?.doubleValue }
let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let cases = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
let output = cases.map { input -> [String: Any] in
    var result = input
    let flags = input["options"] as! [String: Bool]
    var options = ExportOptions()
    options.includeVisitCoordinates = flags["visitCoordinates"]!
    options.includeVisitDuration = flags["visitDuration"]!
    options.includeVisitLocationName = flags["visitName"]!
    options.includeVisitAddress = flags["visitAddress"]!
    options.includeVisitNotes = flags["visitNotes"]!
    options.includePointAltitude = flags["pointAltitude"]!
    options.includePointSpeed = flags["pointSpeed"]!
    options.includePointAccuracy = flags["pointAccuracy"]!
    options.includePointOutlierFlag = flags["pointOutlier"]!
    let visits = (input["visits"] as! [[String: Any]]).map { row in
        Visit(latitude: number(row, "latitude")!, longitude: number(row, "longitude")!,
              arrivedAt: date(row["arrivedAt"])!, departedAt: date(row["departedAt"]),
              exportLocationName: row["name"] as? String, address: row["address"] as? String, notes: row["notes"] as? String,
              source: RawValue(rawValue: row["source"] as? String ?? "automatic"), confirmationStatus: RawValue(rawValue: row["confirmation"] as? String ?? "unconfirmed"),
              confirmedAt: date(row["confirmedAt"]), updatedAt: date(row["updatedAt"]),
              originalLatitude: number(row,"originalLatitude"), originalLongitude: number(row,"originalLongitude"), originalLocationName: row["originalName"] as? String, originalAddress: row["originalAddress"] as? String,
              detectedLatitude: number(row,"detectedLatitude"), detectedLongitude: number(row,"detectedLongitude"), detectedLocationName: row["detectedName"] as? String, detectedAddress: row["detectedAddress"] as? String,
              placeSource: (row["placeSource"] as? String).map {RawValue(rawValue:$0)}, placeDistanceMeters: number(row,"placeDistanceMeters"))
    }
    let points = (input["points"] as! [[String: Any]]).map { row in
        LocationPoint(latitude: number(row, "latitude")!, longitude: number(row, "longitude")!,
                      timestamp: date(row["time"])!, horizontalAccuracy: number(row, "accuracy")!,
                      altitude: number(row, "altitude"), speed: number(row, "speed"), isOutlier: row["outlier"] as! Bool)
    }
    let rendered: Data
    switch input["kind"] as! String {
    case "visits": rendered = ExportService.exportVisitsToGPX(visits: visits, options: options)
    case "points": rendered = ExportService.exportLocationPointsToGPX(points: points, options: options)
    default: rendered = ExportService.exportCombinedToGPX(visits: visits, points: points, options: options)
    }
    result["text"] = String(data: rendered, encoding: .utf8)!
    return result
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]))
