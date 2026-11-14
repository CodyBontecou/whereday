import Foundation

// Use an explicit locale and time zone for the legacy, metadata-free Markdown format.
UserDefaults.standard.set(["en_US"], forKey: "AppleLanguages")
UserDefaults.standard.set("en_US", forKey: "AppleLocale")
NSTimeZone.default = TimeZone(identifier: "America/Puerto_Rico")!
let formatter = ISO8601DateFormatter()
formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
func value(_ string: String?) -> Any { string.map { $0 as Any } ?? NSNull() }
func date(_ date: Date?) -> Any { value(date.map { formatter.string(from: $0) }) }
func number(_ number: Double?) -> Any { number.map { $0 as Any } ?? NSNull() }
func visit(_ visit: ImportedVisit) -> [String: Any] {
    ["latitude": visit.latitude, "longitude": visit.longitude,
     "arrivedAt": date(visit.arrivedAt), "departedAt": date(visit.departedAt),
     "locationName": value(visit.locationName), "address": value(visit.address), "notes": value(visit.notes),
     "customName": value(visit.customName), "source": value(visit.sourceRaw),
     "confirmationStatus": value(visit.confirmationStatusRaw),
     "confirmedAt": date(visit.confirmedAt), "updatedAt": date(visit.updatedAt),
     "originalLatitude": number(visit.originalLatitude), "originalLongitude": number(visit.originalLongitude),
     "originalLocationName": value(visit.originalLocationName), "originalAddress": value(visit.originalAddress),
     "detectedLatitude": number(visit.detectedLatitude), "detectedLongitude": number(visit.detectedLongitude),
     "detectedLocationName": value(visit.detectedLocationName), "detectedAddress": value(visit.detectedAddress),
     "placeSource": value(visit.placeSourceRaw), "placeDistanceMeters": number(visit.placeDistanceMeters)]
}
func point(_ point: ImportedLocationPoint) -> [String: Any] {
    ["latitude": point.latitude, "longitude": point.longitude, "timestamp": date(point.timestamp),
     "horizontalAccuracy": point.horizontalAccuracy, "altitude": number(point.altitude),
     "speed": number(point.speed), "isOutlier": point.isOutlier]
}
let inputs = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [[String: Any]]
let formats: [String: ExportFormat] = ["json": .json, "csv": .csv, "markdown": .markdown]
var results: [[String: Any]] = []
for input in inputs {
    var result = input
    let format = formats[input["format"] as! String]!
    let data = Data((input["text"] as! String).utf8)
    // Baseline cases use typed readers; Android-generated files exercise the public importFile entry point.
    do {
        if input["entryPoint"] as? String == "unified" {
            let imported = try ImportService.importFile(data: data, format: format)
            result["expectedVisits"] = imported.visits.map(visit)
            result["expectedPoints"] = imported.points.map(point)
        } else if input["subject"] as! String == "visits" {
            result["expectedVisits"] = try ImportService.importVisits(data: data, format: format).map(visit)
        } else {
            result["expectedPoints"] = try ImportService.importLocationPoints(data: data, format: format).map(point)
        }
    } catch {
        result["expectedError"] = error.localizedDescription
    }
    results.append(result)
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: results, options: [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes]))
