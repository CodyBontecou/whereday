"""Only new progress copy; never regenerate previously accepted catalog entries."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
KEYS = ("Scanning files…", "Parsing files…", "Checking duplicates…", "Saving import…")
LOCALES = {"ar", "bn", "en", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans"}


class ImportProgressLocalizationTests(unittest.TestCase):
    def test_actual_progress_title_inventory(self):
        source = (ROOT / "IsoMe/Views/ImportPreviewView.swift").read_text()
        block = source.split("private var progressTitle:", 1)[1].split("/// Counts are staged", 1)[0]
        self.assertEqual(set(re.findall(r'return "([^"]+)"', block)), set(KEYS))

    def test_all_titles_have_real_values_in_every_supported_locale(self):
        catalog = json.loads((ROOT / "IsoMe/Localizable.xcstrings").read_text())["strings"]
        for key in KEYS:
            self.assertEqual(set(catalog[key]["localizations"]), LOCALES)
            for locale, translation in catalog[key]["localizations"].items():
                with self.subTest(key=key, locale=locale):
                    unit = translation["stringUnit"]
                    self.assertEqual(unit["state"], "translated")
                    self.assertTrue(unit["value"].strip())
                    self.assertTrue(unit["value"].endswith("…"))
                    self.assertNotIn("%", unit["value"])
                    if locale == "en":
                        self.assertEqual(unit["value"], key)
                    else:
                        self.assertNotEqual(unit["value"], key)


if __name__ == "__main__":
    unittest.main()
