#!/usr/bin/env swift
// Compiled-resource check, not a substitute for localized UI/device acceptance.
import Foundation

func fail(_ message: String) -> Never {
    fputs("FAIL: \(message)\n", stderr)
    exit(1)
}
guard CommandLine.arguments.count == 2 else { fail("usage: swift scripts/check_smart_start_localization.swift /path/to/IsoMe.app") }
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let app = URL(fileURLWithPath: CommandLine.arguments[1])
guard let bundle = Bundle(url: app) else { fail("app bundle missing") }
let source = try String(contentsOf: root.appendingPathComponent("IsoMe/Views/SmartStartSettingsView.swift"), encoding: .utf8)
let regex = try NSRegularExpression(pattern: #""([^"\n]+)""#)
let nsSource = source as NSString
var keys = Set(regex.matches(in: source, range: NSRange(location: 0, length: nsSource.length)).map {
    nsSource.substring(with: $0.range(at: 1))
}.filter { !$0.hasPrefix("smart-start-") })
keys.formUnion([
    "Tracking Source", "Shortcut", "CarPlay automation", "Workout automation", "Source",
    "Begin recording your route in Whereday. Optionally identify a CarPlay or workout automation; this does not enable Smart Start.",
    "Tracking could not start. Open Whereday to check your access and recording status."
])
let document = try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("IsoMe/Localizable.xcstrings"))) as! [String: Any]
let strings = document["strings"] as! [String: [String: Any]]
let locales = ["ar", "bn", "en", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans"]
var checked = 0
for locale in locales {
    guard bundle.localizations.contains(locale),
          let path = bundle.path(forResource: locale, ofType: "lproj"),
          let localized = Bundle(path: path) else { fail("missing compiled language \(locale)") }
    for key in keys.sorted() {
        guard let translations = strings[key]?["localizations"] as? [String: [String: Any]],
              let unit = translations[locale]?["stringUnit"] as? [String: String],
              let expected = unit["value"], !expected.isEmpty else { fail("missing source translation \(locale):\(key)") }
        let actual = localized.localizedString(forKey: key, value: "__MISSING_SMART_START__", table: "Localizable")
        guard actual == expected else { fail("compiled lookup mismatch \(locale):\(key): \(actual)") }
        checked += 1
    }
}
print("PASS: \(checked) actual compiled Bundle lookups (\(keys.count) keys × \(locales.count) locales)")
