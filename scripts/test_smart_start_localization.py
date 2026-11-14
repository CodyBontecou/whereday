"""Catalog coverage for the real Smart Start Settings and new Shortcut source UI."""
import json
import re
import unittest

import localization_catalog as catalog


SETTINGS = catalog.ROOT / "IsoMe/Views/SmartStartSettingsView.swift"
INTENTS = catalog.ROOT / "IsoMe/Services/IsoMeIntents.swift"
INTENT_COPY = (
    "Tracking Source", "Shortcut", "CarPlay automation", "Workout automation", "Source",
    "Begin recording your route in Whereday. Optionally identify a CarPlay or workout automation; this does not enable Smart Start.",
    "Tracking could not start. Open Whereday to check your access and recording status.",
)


def settings_copy():
    # Every literal in this small view is user-facing except stable accessibility IDs.
    # Dynamic Duration formatting uses Foundation's localized units, not catalog keys.
    return {
        value for value in re.findall(r'"([^"\n]+)"', SETTINGS.read_text())
        if not value.startswith("smart-start-")
    }


class SmartStartLocalizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.strings = json.loads((catalog.ROOT / "IsoMe/Localizable.xcstrings").read_text())["strings"]
        cls.keys = settings_copy() | set(INTENT_COPY)

    def test_inventory_tracks_actual_view_and_intent_literals(self):
        self.assertGreater(len(settings_copy()), 35)
        source = INTENTS.read_text()
        for key in INTENT_COPY:
            self.assertIn('"' + key + '"', source)

    def test_every_user_facing_key_is_cataloged(self):
        self.assertEqual([], sorted(self.keys - self.strings.keys()))

    def test_every_supported_locale_has_complete_values_and_matching_placeholders(self):
        failures = []
        for key in sorted(self.keys):
            for locale in catalog.LOCALES:
                unit = self.strings.get(key, {}).get("localizations", {}).get(locale, {}).get("stringUnit", {})
                value = unit.get("value", "")
                if unit.get("state") != "translated" or not value.strip():
                    failures.append((key, locale, "missing translation"))
                elif catalog.placeholders(key) != catalog.placeholders(value):
                    failures.append((key, locale, "placeholder mismatch"))
                elif locale == "en" and value != key:
                    failures.append((key, locale, "changed English contract"))
                elif locale != "en" and value == key and key != "Smart Start" and (locale, key) != ("fr", "Source"):
                    failures.append((key, locale, "unexpected English fallback"))
        self.assertEqual([], failures)

    def test_recipes_name_the_actual_localized_shortcut_actions(self):
        recipe = next(key for key in self.keys if key.startswith("In Shortcuts,"))
        for locale in catalog.LOCALES:
            with self.subTest(locale=locale):
                value = self.strings[recipe]["localizations"][locale]["stringUnit"]["value"]
                for action in ("Start Tracking", "Stop Tracking"):
                    title = self.strings[action]["localizations"][locale]["stringUnit"]["value"]
                    self.assertIn(title, value)

    def test_new_copy_does_not_confuse_route_recording_with_audio_or_force_quit_with_smoking(self):
        for key in self.keys:
            localizations = self.strings.get(key, {}).get("localizations", {})
            for locale, forbidden in (("ar", "التدخين"), ("ja", "録音"), ("zh-Hans", "录音")):
                value = localizations.get(locale, {}).get("stringUnit", {}).get("value", "")
                self.assertNotIn(forbidden, value)


if __name__ == "__main__":
    unittest.main()
