import Foundation

// Check the actual compiled lproj values, not English fallback or source presence.
var keys = ["Scanning files…", "Parsing files…", "Checking duplicates…", "Saving import…"]
let locales = ["ar", "bn", "en", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans"]
func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("FAIL: \(message)\n".utf8))
    exit(1)
}
guard (2...3).contains(CommandLine.arguments.count) else { fail("Usage: swift scripts/check_import_progress_resources.swift /path/to/IsoMe.app [copy-key-inventory.json]") }
if CommandLine.arguments.count == 3 {
    let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
    guard let scope = try JSONSerialization.jsonObject(with: data) as? [String: [String]],
          let existing = scope["existingMissingKeys"], let added = scope["newKeys"] else { fail("Invalid copy-key inventory") }
    keys += existing + added
    guard Set(keys).count == keys.count else { fail("Duplicate resource-check keys") }
}
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
print("PASS: \(checked) actual compiled import-copy/progress resource lookups")
