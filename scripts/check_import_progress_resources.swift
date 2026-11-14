import Foundation

// Check the actual compiled lproj values, not English fallback or source presence.
let keys = ["Scanning files…", "Parsing files…", "Checking duplicates…", "Saving import…"]
let locales = ["ar", "bn", "en", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans"]
func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("FAIL: \(message)\n".utf8))
    exit(1)
}
guard CommandLine.arguments.count == 2 else { fail("Usage: swift scripts/check_import_progress_resources.swift /path/to/IsoMe.app") }
let app = URL(fileURLWithPath: CommandLine.arguments[1])
let source = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("IsoMe/Localizable.xcstrings")
let catalog = try JSONSerialization.jsonObject(with: Data(contentsOf: source)) as! [String: Any]
let strings = catalog["strings"] as! [String: Any]
var checked = 0
for locale in locales {
    guard let bundle = Bundle(url: app.appendingPathComponent("\(locale).lproj")) else { fail("Missing compiled locale \(locale)") }
    for key in keys {
        guard let entry = strings[key] as? [String: Any],
              let localizations = entry["localizations"] as? [String: Any],
              let localization = localizations[locale] as? [String: Any],
              let unit = localization["stringUnit"] as? [String: String],
              let expected = unit["value"] else { fail("Missing expected source \(locale):\(key)") }
        let actual = bundle.localizedString(forKey: key, value: "__MISSING_IMPORT_PROGRESS__", table: "Localizable")
        guard actual == expected else { fail("Compiled mismatch \(locale):\(key): \(actual)") }
        checked += 1
    }
}
print("PASS: \(checked) actual compiled import-progress resource lookups")
