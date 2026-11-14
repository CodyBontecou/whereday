"""Regression coverage for newly extracted main catalog copy, not owner translation review."""
import json
import unittest
from pathlib import Path

import localization_catalog as catalog

KEYS = json.loads((Path(__file__).parent / "fixtures/new-main-catalog-keys.json").read_text())


class NewMainCatalogLocalizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.strings = json.loads((catalog.ROOT / "IsoMe/Localizable.xcstrings").read_text())["strings"]

    def value(self, key, locale):
        return self.strings.get(key, {}).get("localizations", {}).get(locale, {}).get("stringUnit", {}).get("value")

    def test_all_new_keys_have_real_values_in_every_supported_locale(self):
        self.assertEqual(len(KEYS), 36)
        missing = [(key, locale) for key in KEYS for locale in catalog.LOCALES if not (self.value(key, locale) or "").strip()]
        self.assertEqual(missing, [], f"{len(missing)} missing values: {missing}")

    def test_every_translation_preserves_the_actual_source_format_arguments(self):
        failures = []
        for key in KEYS:
            _, sources = catalog.source_values(key, self.strings[key])
            for locale in catalog.LOCALES:
                value = self.value(key, locale)
                if value is None or catalog.placeholders(value) != catalog.placeholders(sources[0]):
                    failures.append((key, locale))
                elif locale != "en" and value == sources[0]:
                    failures.append((key, locale, "unexpected English fallback"))
        self.assertEqual(failures, [])

    def test_import_errors_retain_the_exact_external_field_names(self):
        for key, field in [("Row %lld: invalid departed_at date", "departed_at"), ("Visit at index %lld: invalid departedAt date", "departedAt")]:
            for locale in catalog.LOCALES:
                with self.subTest(key=key, locale=locale):
                    self.assertIn(field, self.value(key, locale) or "")

    def test_recording_copy_does_not_become_audio_video_or_reward_points(self):
        keys = [key for key in KEYS if "recording" in key.lower() or key.startswith("Start or stop on either device")]
        self.assertEqual(len(keys), 5)
        for key in keys:
            for locale, forbidden in [("ja", ("録音", "録画", "貯め")), ("zh-Hans", ("录音", "录像", "积分"))]:
                with self.subTest(key=key, locale=locale):
                    value = self.value(key, locale)
                    self.assertIsNotNone(value)
                    for word in forbidden:
                        self.assertNotIn(word, value)

    def test_the_app_name_is_not_translated_or_corrupted(self):
        for key in [key for key in KEYS if "Whereday" in key]:
            for locale in catalog.LOCALES:
                with self.subTest(key=key, locale=locale):
                    self.assertIn("Whereday", self.value(key, locale) or "")


class NewAuxiliaryCatalogLocalizationTests(unittest.TestCase):
    TARGETS = {
        "IsoMe/InfoPlist.xcstrings": ("NSLocalNetworkUsageDescription", "NSMicrophoneUsageDescription"),
        "IsoMe/AppShortcuts.xcstrings": ("Check in with ${applicationName}",),
        "IsoMeWatch/Localizable.xcstrings": ("No shared recording yet", "Open this app and check location permission and recording access", "Recording controls synced", "Waiting for the other device"),
    }

    def test_auxiliary_keys_cover_every_locale_and_preserve_source_arguments(self):
        failures = []
        for relative, keys in self.TARGETS.items():
            strings = json.loads((catalog.ROOT / relative).read_text())["strings"]
            for key in keys:
                entry = strings[key]
                kind, source = catalog.source_values(key, entry)
                for locale in catalog.LOCALES:
                    values = catalog.locale_values(entry, locale, kind)
                    if not values or not values[0].strip() or catalog.placeholders(values[0]) != catalog.placeholders(source[0]):
                        failures.append((relative, key, locale))
        self.assertEqual(failures, [])

    def test_compiled_manifest_covers_exactly_the_new_catalog_defect(self):
        manifest = json.loads((catalog.ROOT / "scripts/fixtures/new-catalog-resource-checks.json").read_text())
        checks = manifest["checks"]
        expected = {("IsoMe/Localizable.xcstrings", key) for key in KEYS}
        expected.update((relative, key) for relative, keys in self.TARGETS.items() for key in keys)
        self.assertEqual({(check["catalog"], check["key"]) for check in checks}, expected)
        self.assertEqual(len(checks), len(expected))
        self.assertEqual(sum(len(check["locales"]) for check in checks), 465)
        for check in checks:
            self.assertEqual(len(check["locales"]), len(set(check["locales"])))
            self.assertTrue(set(check["locales"]).issubset(catalog.LOCALES))

    def test_siri_application_slot_and_development_capture_brand_are_literal(self):
        shortcuts = json.loads((catalog.ROOT / "IsoMe/AppShortcuts.xcstrings").read_text())["strings"]
        info = json.loads((catalog.ROOT / "IsoMe/InfoPlist.xcstrings").read_text())["strings"]
        for locale in catalog.LOCALES:
            with self.subTest(locale=locale):
                entry = shortcuts["Check in with ${applicationName}"]
                kind, _ = catalog.source_values("Check in with ${applicationName}", entry)
                phrases = catalog.locale_values(entry, locale, kind)
                self.assertIsNotNone(phrases)
                self.assertEqual(len(phrases), 1)
                self.assertEqual(phrases[0].count("${applicationName}"), 1)
                for key in ("NSLocalNetworkUsageDescription", "NSMicrophoneUsageDescription"):
                    value = info[key].get("localizations", {}).get(locale, {}).get("stringUnit", {}).get("value", "")
                    self.assertIn("Scrawl", value)


if __name__ == "__main__":
    unittest.main()
