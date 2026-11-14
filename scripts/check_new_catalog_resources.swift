#!/usr/bin/env swift
// Run from the repo root after building: swift scripts/check_new_catalog_resources.swift /path/to/IsoMe.app
// Checks only newly filled catalog values, not spoken UI, Siri activation or Watch runtime behavior.
import Foundation
import Darwin

func fail(_ message: String) -> Never {
    fputs("FAIL: \(message)\n", stderr)
    exit(1)
}
struct Check: Decodable {
    let catalog: String
    let key: String
    let kind: String
    let locales: [String]
    let bundle: String
    let table: String
}
struct Manifest: Decodable { let checks: [Check] }
guard CommandLine.arguments.count == 2 else { fail("Pass a built IsoMe.app path; run from the repository root") }
let app = URL(fileURLWithPath: CommandLine.arguments[1])
let manifestURL = URL(fileURLWithPath: "scripts/fixtures/new-catalog-resource-checks.json")
let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: manifestURL))
guard manifest.checks.count == 43 else { fail("Expected the original 43 new keys") }
var documents: [String: [String: Any]] = [:]
var checked = 0
for check in manifest.checks {
    if documents[check.catalog] == nil {
        let data = try Data(contentsOf: URL(fileURLWithPath: check.catalog))
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let strings = document["strings"] as? [String: Any] else { fail("Invalid source catalog \(check.catalog)") }
        documents[check.catalog] = strings
    }
    guard let entry = documents[check.catalog]?[check.key] as? [String: Any],
          let localizations = entry["localizations"] as? [String: Any] else { fail("Missing source key \(check.key)") }
    for locale in check.locales {
        guard let localization = localizations[locale] as? [String: Any] else { fail("Missing source locale \(locale):\(check.key)") }
        let expected: String
        let compiledKey: String
        if check.kind == "stringSet" {
            guard let set = localization["stringSet"] as? [String: Any],
                  let values = set["values"] as? [String], values.count == 1 else { fail("Expected the real one-phrase Siri stringSet") }
            expected = values[0]
            // Actual xcstringstool stringSet output, observed in the built AppShortcuts.strings.
            compiledKey = "#!SET#!_\(check.key)[0]"
        } else {
            guard let unit = localization["stringUnit"] as? [String: Any],
                  let value = unit["value"] as? String, !value.isEmpty else { fail("Missing source value \(locale):\(check.key)") }
            expected = value
            compiledKey = check.key
        }
        let bundleURL = app.appendingPathComponent(check.bundle).appendingPathComponent("\(locale).lproj")
        guard let bundle = Bundle(url: bundleURL) else { fail("Missing compiled locale bundle \(bundleURL.path)") }
        let sentinel = "__MISSING_NEW_CATALOG_RESOURCE__"
        let actual = bundle.localizedString(forKey: compiledKey, value: sentinel, table: check.table)
        guard actual != sentinel else { fail("Missing compiled resource \(check.catalog):\(locale):\(check.key)") }
        guard actual == expected else { fail("Compiled/source mismatch \(check.catalog):\(locale):\(check.key)") }
        checked += 1
    }
}
guard checked == 465 else { fail("Expected 465 newly filled values, checked \(checked)") }
print("PASS: \(checked) actual compiled resource lookups across iPhone, Siri, permission and embedded Watch catalogs")
