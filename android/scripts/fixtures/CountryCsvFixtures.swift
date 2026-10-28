import Foundation
let input = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let cases = try JSONSerialization.jsonObject(with: input) as! [[String: Any]]
var results: [[String: Any]] = []
for item in cases {
    let rows = item["days"] as! [[String: Any]]
    let days = rows.map { CountryPresence(countryCode: $0["country"] as! String, day: $0["day"] as! String, recorded: $0["recorded"] as! Bool, manual: $0["manual"] as! Bool) }
    let grouped = Dictionary(grouping: days, by: \.countryCode).map { CountryDaySummary(countryCode: $0.key, days: $0.value) }
    var result = item
    result["expected"] = CountryDayCalculator.csv(grouped)
    results.append(result)
}
let data = try JSONSerialization.data(withJSONObject: results, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
FileHandle.standardOutput.write(data)
