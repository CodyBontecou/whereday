"""Targeted inference/folder/completion copy, without refreshing accepted values."""
import json
from pathlib import Path
import re
import unittest
import localization_catalog as catalog

ROOT = Path(__file__).resolve().parents[1]
SCOPE = json.loads((ROOT / "scripts/fixtures/google-import-copy-keys.json").read_text())
KEYS = SCOPE["existingMissingKeys"] + SCOPE["newKeys"]


class GoogleImportLocalizationTests(unittest.TestCase):
    def test_inventory_includes_every_actual_folder_failure_and_new_ui_boundary(self):
        source = (ROOT / "IsoMe/Utilities/GoogleFolderDiscovery.swift").read_text()
        errors = set(re.findall(r'String\(localized: "([^"]+)"\)', source))
        self.assertEqual(len(errors), 6)
        self.assertTrue(errors.issubset(KEYS))
        self.assertEqual(len(KEYS), len(set(KEYS)))
        self.assertEqual(len(KEYS), 29)
        view = (ROOT / "IsoMe/Views/ImportPreviewView.swift").read_text()
        self.assertIn('String(localized: "Added to Atlas: \\(span).")', view)
        self.assertNotIn('String(localized: "\\(span) of days now show in Atlas.")', view)
        self.assertIn('Text("Google JSON files: \\(folder.googleFileCount.formatted())")', view)
        self.assertIn('Text("Excluded entries: \\(folder.excludedEntryCount.formatted())")', view)

    def test_all_targeted_values_exist_with_original_placeholder_identity(self):
        strings = json.loads((ROOT / "IsoMe/Localizable.xcstrings").read_text())["strings"]
        for key in KEYS:
            for locale in catalog.LOCALES:
                with self.subTest(key=key, locale=locale):
                    entry = strings.get(key, {}).get("localizations", {}).get(locale, {})
                    unit = entry.get("stringUnit", {})
                    self.assertEqual(unit.get("state"), "translated")
                    value = unit.get("value", "")
                    self.assertTrue(value.strip())
                    self.assertEqual(catalog.placeholders(value), catalog.placeholders(key))
                    if locale == "en":
                        self.assertEqual(value, key)

    def test_rtl_caption_override_is_scoped_and_keeps_ltr_typography(self):
        view = (ROOT / "IsoMe/Views/ImportPreviewView.swift").read_text()
        self.assertIn('layoutDirection == .rightToLeft ? .system(style) : TE.mono(style)', view)
        self.assertIn('layoutDirection == .rightToLeft ? 0 : spacing', view)
        self.assertEqual(view.count('TE.mono('), 1)
        self.assertTrue(all('captionTracking(' in call for call in re.findall(r'\.tracking\([^\n]+', view)))

    def test_reviewed_domain_unit_and_atomicity_corrections(self):
        strings = json.loads((ROOT / "IsoMe/Localizable.xcstrings").read_text())["strings"]
        def value(key, locale):
            return strings[key]["localizations"][locale]["stringUnit"]["value"]
        self.assertTrue(value("Radius: %lld m", "pt-BR").endswith(" m"))
        for locale, term in {"es": "vista previa", "fr": "aperçu", "ja": "プレビュー", "pl": "podglądu", "ru": "предпросмотра", "zh-Hans": "预览"}.items():
            self.assertIn(term, value("Updating review…", locale))
        for key in KEYS:
            for locale in ("es", "ja", "pl", "hi"):
                text = value(key, locale)
                for wrong in ("salvó", "救われ", "uratowane", "बचाया"):
                    self.assertNotIn(wrong, text, (key, locale))
        atomic = "Excluded entries are not imported. If any Google file cannot be checked, the entire folder is rejected."
        self.assertIn("choć jednego", value(atomic, "pl"))
        self.assertIn("一个", value(atomic, "zh-Hans"))
        self.assertIn("地图集", value("Added to Atlas: %@.", "zh-Hans"))


if __name__ == "__main__":
    unittest.main()
