import Foundation
import ExportKit

let input = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let cases = try JSONSerialization.jsonObject(with: input) as! [[String: String]]
var results: [[String: Any]] = []
for entry in cases {
    var result: [String: Any] = entry
    let existing = entry["existing"]!
    let incoming = entry["incoming"]!
    let file = PlannedExportFile(id: "synthetic", role: .aggregate(formatID: entry["format"]!), relativePath: "synthetic.txt", content: incoming, estimatedByteCount: incoming.utf8.count)
    do {
        let merged: String
        switch entry["format"]! {
        case "csv": merged = try CSVAppendMergeStrategy().merge(existing: existing, new: incoming, file: file)
        case "markdown": merged = try MarkdownMergeStrategy().merge(existing: existing, new: incoming, file: file)
        default: merged = try JSONArrayMergeStrategy().merge(existing: existing, new: incoming, file: file)
        }
        result["expected"] = merged
    } catch {
        result["error"] = true
    }
    results.append(result)
}
let data = try JSONSerialization.data(withJSONObject: results, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
FileHandle.standardOutput.write(data)
