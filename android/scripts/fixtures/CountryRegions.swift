import Foundation

@main
struct CountryRegionFixtures {
    __PICKER_HELPERS__

    @MainActor static func main() throws {
        let all = __COUNTRY_CODES__.sorted()
        let search = ""
        let eligible: [(code: String, name: String)] = { __ELIGIBLE_BODY__ }()
        let selections: [(code: String, name: String)] = { __PICKER_BODY__ }()
        let picker = selections.map(\.code).sorted()
        precondition(Set(picker) == Set(eligible.map(\.code)))
        let now = ISO8601DateFormatter().date(from: "2026-10-29T12:00:00Z")!
        let rawCodes = all + all.map { $0.lowercased() } + ["", "ZZ", "zz", "UK", "us ", " US", "U", "USA", "ZZZ", "uſ", "ıo", "ß"]
        var cases: [[String: Any]] = []
        for code in rawCodes {
            let visit = Visit(latitude: 0, longitude: 0, arrivedAt: now)
            visit.countryCode = code
            visit.countryTimeZoneIdentifier = "UTC"
            let point = LocationPoint(latitude: 0, longitude: 0, timestamp: now, horizontalAccuracy: 5)
            point.countryCode = code
            point.countryTimeZoneIdentifier = "UTC"
            let stay = CountryStay(countryCode: code, firstDay: "2026-10-29", lastDay: "2026-10-29")
            let combined = CountryDayCalculator.summaries(visits: [visit], points: [point], stays: [stay], now: now)
            let recorded = CountryDayCalculator.summaries(visits: [visit], points: [point], stays: [], now: now)
            cases.append(["raw": code, "combinedCsv": CountryDayCalculator.csv(combined), "recordedCsv": CountryDayCalculator.csv(recorded)])
        }
        var names: [String: [String: String]] = [:]
        for language in ["en", "es"] {
            let locale = Locale(identifier: language)
            names[language] = Dictionary(uniqueKeysWithValues: picker.map { ($0, locale.localizedString(forRegionCode: $0) ?? $0) })
        }
        let result: [String: Any] = ["all": all, "picker": picker, "names": names, "cases": cases,
                                   "runtime": ["os": ProcessInfo.processInfo.operatingSystemVersionString, "kind": "macOS Foundation reference"]]
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]))
    }
}
