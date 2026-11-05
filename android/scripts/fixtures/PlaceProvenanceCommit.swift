import Foundation
import SwiftData
import Darwin

private struct AcceptanceFailure: Error, CustomStringConvertible {
    let description: String
}

@main
private struct PlaceProvenanceAcceptance {
    @MainActor
    static func main() async {
        do {try await verify()} catch {
            FileHandle.standardError.write(Data("FAIL: \(error)\n".utf8))
            exit(EXIT_FAILURE)
        }
    }

    @MainActor
    private static func verify() async throws {
        let input = URL(fileURLWithPath: CommandLine.arguments[1])
        let cases = try JSONSerialization.jsonObject(with: Data(contentsOf: input)) as! [[String: Any]]
        guard cases.count == 12 else { throw AcceptanceFailure(description: "Expected twelve distinct Android exports") }
        var accepted: [String] = []
        for item in cases {
            let name = item["name"] as! String
            let expected = item["expected"] as! [String: Any]
            let data = Data((item["text"] as! String).utf8)
            let container = try ModelContainer(for: Visit.self, LocationPoint.self,
                configurations: ModelConfiguration(isStoredInMemoryOnly: true))
            let service = TimelineImportService(container: container)
            let fileName = "synthetic.\(item["format"] as! String)"
            let preview = try await service.preview(data: data, fileName: fileName)
            try require(preview.newVisitCount == 1 && preview.newPointCount == 0, "\(name): invalid preview")
            try require(try ModelContext(container).fetchCount(FetchDescriptor<Visit>()) == 0, "\(name): preview wrote history")
            let committed = try await service.commit(preview, options: ImportOptions())
            try require(committed.visitCount == 1 && committed.pointCount == 0, "\(name): invalid commit")
            let context = ModelContext(container)
            let values = try context.fetch(FetchDescriptor<Visit>())
            try require(values.count == 1, "\(name): expected one stored visit")
            let visit = values[0]
            // iOS keeps a place source it recognizes and stores any other one (such as Android's
            // geoapify) as import, the same policy checked for every source below.
            let placeSource = (expected["placeSource"] as? String).map { VisitPlaceSource(rawValue: $0)?.rawValue ?? "import" }
            try equal(visit.placeSourceRaw, placeSource, "\(name): stored placeSource")
            try equal(visit.placeSource?.rawValue, placeSource, "\(name): recognized placeSource")
            try equal(visit.placeDistanceMeters, number(expected, "placeDistanceMeters"), "\(name): distance")
            try equal(visit.latitude, number(expected, "latitude"), "\(name): latitude")
            try equal(visit.longitude, number(expected, "longitude"), "\(name): longitude")
            try equal(visit.locationName, expected["name"] as? String, "\(name): name")
            try equal(visit.address, expected["address"] as? String, "\(name): address")
            try equal(visit.notes, expected["notes"] as? String, "\(name): notes")
            try equal(visit.sourceRaw, expected["source"] as? String, "\(name): source")
            try equal(visit.confirmationStatusRaw, expected["confirmation"] as? String, "\(name): confirmation")
            for (key, actual) in [("arrivedAt", Optional(visit.arrivedAt)), ("departedAt", visit.departedAt), ("confirmedAt", visit.confirmedAt), ("updatedAt", visit.updatedAt)] {
                try equal(actual, date(expected[key] as? String), "\(name): \(key)")
            }
            for (key, actual) in [("originalLatitude", visit.originalLatitude), ("originalLongitude", visit.originalLongitude), ("detectedLatitude", visit.detectedLatitude), ("detectedLongitude", visit.detectedLongitude)] {
                try equal(actual, number(expected, key), "\(name): \(key)")
            }
            for (key, actual) in [("originalName", visit.originalLocationName), ("originalAddress", visit.originalAddress), ("detectedName", visit.detectedLocationName), ("detectedAddress", visit.detectedAddress)] {
                try equal(actual, expected[key] as? String, "\(name): \(key)")
            }
            try require(visit.geocodingCompleted, "\(name): imported visit requested geocoding")
            let again = try await service.preview(data: data, fileName: fileName)
            try require(again.newVisitCount == 0 && again.duplicateVisitCount == 1, "\(name): duplicate preview mismatch")
            let repeated = try await service.commit(again, options: ImportOptions())
            try require(repeated.visitCount == 0 && repeated.duplicateVisitCount == 1, "\(name): duplicate commit mismatch")
            try require(try ModelContext(container).fetchCount(FetchDescriptor<Visit>()) == 1, "\(name): duplicate stored")
            accepted.append(name)
        }
        try require(Set(accepted).count == 12, "Duplicate acceptance case names")
        for raw in ["coreLocationGeocode", "appleMaps", "userEntered", "import"] {
            let value = VisitPlaceSource(rawValue: raw)
            try require(value != nil, "Missing recognized place source \(raw)")
            let decoded = try JSONDecoder().decode(VisitPlaceSource.self, from: JSONEncoder().encode(value!))
            try require(decoded.rawValue == raw, "Changed source wire key \(raw)")
        }
        try require(VisitPlaceSource(rawValue: "future-provider") == nil, "Unknown source policy changed")
        var policies: [String: String] = [:]
        let jsonCase = cases.first {($0["name"] as? String) == "manual-list-json"}!
        for raw in ["coreLocationGeocode", "appleMaps", "geoapify", "userEntered", "import", "future-provider"] {
            let data = Data((jsonCase["text"] as! String).replacingOccurrences(of: "geoapify", with: raw).utf8)
            let container = try ModelContainer(for: Visit.self, LocationPoint.self,
                configurations: ModelConfiguration(isStoredInMemoryOnly: true))
            let service = TimelineImportService(container: container)
            let preview = try await service.preview(data: data, fileName: "synthetic.json")
            _ = try await service.commit(preview, options: ImportOptions())
            let rows = try ModelContext(container).fetch(FetchDescriptor<Visit>())
            let expected = VisitPlaceSource(rawValue: raw)?.rawValue ?? "import"
            try require(rows.count == 1 && rows[0].placeSourceRaw == expected, "Source policy changed for \(raw)")
            policies[raw] = expected
        }
        let result: [String: Any] = ["accepted": accepted, "count": accepted.count,
            "store": "isolated in-memory SwiftData", "legacyWireKeys": "verified", "sourcePolicies": policies]
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]))
    }

    private static func require(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
        if try !condition() { throw AcceptanceFailure(description: message) }
    }
    private static func number(_ expected: [String: Any], _ key: String) -> Double? { (expected[key] as? NSNumber)?.doubleValue }
    private static func date(_ value: String?) -> Date? {
        guard let value else { return nil }
        return ISO8601DateFormatter().date(from: value)
    }
    private static func equal<T: Equatable>(_ actual: T?, _ expected: T?, _ field: String) throws {
        try require(actual == expected, "\(field): got \(String(describing: actual)), expected \(String(describing: expected))")
    }
}
