import Foundation
import CoreLocation

// Only the model/storage boundary is adapted. Matching, gap, merge and inferred departure
// bodies and constants are extracted verbatim from LocationManager.swift.
final class Visit {
    enum Source {case automatic,manual,imported}
    var source: Source = .automatic
    let id: UUID
    var latitude: Double = 18
    var longitude: Double = -66
    var arrivedAt: Date
    var departedAt: Date?
    var countryDepartureIsInferred: Bool? = false
    var customName: String?
    var locationName: String?
    var address: String?
    var geocodingCompleted = false
    var notes: String?
    enum ConfirmationStatus: String {case unconfirmed,confirmed,corrected}
    var confirmationStatus: ConfirmationStatus = .unconfirmed
    var isConfirmed: Bool { confirmationStatus == .confirmed || confirmationStatus == .corrected }
    var displayName: String {
        customName.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
            ?? locationName ?? address ?? "Unknown Location"
    }
    init(_ seconds: Double, _ end: Double?, id: UUID = UUID()) {
        self.id = id; arrivedAt = Date(timeIntervalSince1970: seconds)
        departedAt = end.map { Date(timeIntervalSince1970: $0) }
    }
    func json() -> [String: Any] {
        ["id": id.uuidString.lowercased(), "latitude": latitude, "longitude": longitude,
         "arrival": arrivedAt.timeIntervalSince1970, "departure": departedAt.map { $0.timeIntervalSince1970 } as Any? ?? NSNull(),
         "inferred": countryDepartureIsInferred ?? true,
         "name": locationName as Any? ?? NSNull(), "address": address as Any? ?? NSNull(), "notes": notes as Any? ?? NSNull(), "confirmation": confirmationStatus.rawValue]
    }
}
let reference = Reference(), now = Date(timeIntervalSince1970: 20000)
var cases = [[String: Any]]()
for (index, name) in ["overlap", "gap-30", "gap-over-30", "arrival-15", "arrival-over-15", "far", "open", "both-open", "same-id", "metadata", "same-notes", "empty-target-notes", "future-open", "dateline", "reviewed-different-places", "reviewed-same-place-folded", "one-reviewed"].enumerated() {
    let target = Visit(1000, 2000, id: UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", index * 2 + 1))!)
    let source = Visit(1500, 3000, id: name == "same-id" ? target.id : UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", index * 2 + 2))!)
    switch name {
    case "gap-30": source.arrivedAt = Date(timeIntervalSince1970: 3800); source.departedAt = Date(timeIntervalSince1970: 4000)
    case "gap-over-30": source.arrivedAt = Date(timeIntervalSince1970: 3801); source.departedAt = Date(timeIntervalSince1970: 4000)
    case "arrival-15", "arrival-over-15":
        target.departedAt = Date(timeIntervalSince1970: 1000)
        source.arrivedAt = Date(timeIntervalSince1970: name == "arrival-15" ? 1900 : 1901)
    case "far": source.latitude = 19
    case "open": source.departedAt = nil
    case "both-open": target.departedAt = nil; source.departedAt = nil
    case "metadata": target.locationName = "Corrected cafe"; target.notes = " first \n"; source.locationName = "Detected"; source.address = "Street"; source.notes = "second"; source.countryDepartureIsInferred = true
    case "same-notes": target.notes = " same "; source.notes = "same"
    case "empty-target-notes": target.notes = " \n"; source.notes = " source \n"
    case "future-open": target.arrivedAt = Date(timeIntervalSince1970: 25000); target.departedAt = nil; source.arrivedAt = Date(timeIntervalSince1970: 26000); source.departedAt = nil
    case "dateline": target.longitude = 179.9999; source.longitude = -179.9999
    // A split visit: both parts reviewed and named after neighbouring places.
    case "reviewed-different-places":
        target.locationName = "Trader Joe's"; target.confirmationStatus = .confirmed
        source.locationName = "Marshalls"; source.confirmationStatus = .corrected
    case "reviewed-same-place-folded":
        target.locationName = "Café Lumière"; target.confirmationStatus = .confirmed
        source.locationName = "cafe lumiere"; source.confirmationStatus = .corrected
    case "one-reviewed":
        target.locationName = "Trader Joe's"; target.confirmationStatus = .confirmed
        source.locationName = "Marshalls"
    default: break
    }
    let beforeTarget = target.json(), beforeSource = source.json()
    let distance = CLLocation(latitude: target.latitude, longitude: target.longitude).distance(from: CLLocation(latitude: source.latitude, longitude: source.longitude))
    let matches = reference.shouldMergeDuplicateVisits(target, source, referenceDate: now)
    reference.mergeVisit(source, into: target)
    cases.append(["name": name, "now": now.timeIntervalSince1970, "target": beforeTarget, "source": beforeSource,
                  "distance": distance, "matches": matches, "merged": target.json(),
                  "inferredDeparture": reference.inferredDepartureDate(for: source, in: [source, target], fallback: now).timeIntervalSince1970])
}
print(String(data: try JSONSerialization.data(withJSONObject: cases, options: [.sortedKeys]), encoding: .utf8)!)
