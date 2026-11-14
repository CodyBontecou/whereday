"""Coverage checks for the illustrated onboarding's editorial headlines."""
import json
import unittest

import localization_catalog as catalog


HEADLINES = (
    "Every day\nhas a story.",
    "The shape\nof your day.",
    "Your places,\nautomatically.",
    "Your places.\nYour photographs.",
    "Ready for your\nnext chapter.",
)


FLOW_COPY = (
    "Back", "Next", "Get Started", "Step %lld of %lld", "Checking purchases…", "Loading...",
    "A life of places.\nYours to keep.", "Your Whereday plan",
    "Manage your purchase and Family Sharing.",
    "Track your days. Revisit your places.\nKeep your history private and on-device.",
    "Active purchase", "Manage subscription", "Add Apple Family Sharing to your lifetime purchase.",
    "%@, one-time purchase", "Who is this for?", "Individual", "Family",
    "Share with up to 5 family members using Apple Family Sharing", "Retry loading plans",
    "Restore Purchases", "Privacy", "Terms", "Done", "Unavailable",
    "Your existing records remain available to view and export if your subscription ends.",
    "Individual Lifetime", "Family Lifetime", "Family Lifetime Upgrade", "Individual Weekly",
    "Individual Yearly", "Family Weekly", "Family Yearly", "per week", "per year", "once",
    "7 days free", "One-time purchase", "Upgrade to Family", "Start 7-day free trial", "Subscribe", "Buy Lifetime",
    "Cancel in Apple subscription settings at least 24 hours before renewal.",
    "%@ charged once to your Apple Account. No subscription.",
    "%@ per week, automatically renewing until canceled.",
    "%@ per year, automatically renewing until canceled.",
    "7 days free, then %@ per week, automatically renewing until canceled.",
    "7 days free, then %@ per year, automatically renewing until canceled.",
    "Purchase options are unavailable. Please try again later.",
    "Failed to load purchase options: %@", "Family Upgrade requires an Individual Lifetime purchase.",
    "Product not available", "Purchase verification failed.", "Purchase is pending approval",
    "Purchase failed: %@", "Restore failed: %@",
    "No active purchase found. For a Family plan, check your Family Sharing settings.",
)


class OnboardingLocalizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.strings = json.loads((catalog.ROOT / "IsoMe/Localizable.xcstrings").read_text())["strings"]
        cls.source = (catalog.ROOT / "IsoMe/Views/ContentView.swift").read_text()

    def test_headline_keys_match_the_swiftui_source(self):
        for key in HEADLINES:
            with self.subTest(key=key):
                self.assertIn(json.dumps(key), self.source)

    def test_every_supported_locale_has_a_translated_headline(self):
        for key in HEADLINES:
            for locale in catalog.LOCALES:
                with self.subTest(key=key, locale=locale):
                    unit = self.strings[key]["localizations"][locale]["stringUnit"]
                    self.assertEqual(unit["state"], "translated")
                    self.assertTrue(unit["value"].strip())
                    if locale == "en":
                        self.assertEqual(unit["value"], key)
                    else:
                        self.assertNotEqual(unit["value"], key)

    def test_remaining_flow_copy_covers_every_locale_and_preserves_placeholders(self):
        for key in FLOW_COPY:
            for locale in catalog.LOCALES:
                with self.subTest(key=key, locale=locale):
                    unit = self.strings[key]["localizations"][locale]["stringUnit"]
                    self.assertEqual(unit["state"], "translated")
                    self.assertTrue(unit["value"].strip())
                    self.assertEqual(catalog.placeholders(unit["value"]), catalog.placeholders(key))
                    if locale == "en":
                        self.assertEqual(unit["value"], key)
                    elif (locale, key) not in {("es", "Individual"), ("pt-BR", "Individual")}:
                        # This plan label is naturally identical in these languages.
                        self.assertNotEqual(unit["value"], key)

    def test_headlines_keep_a_deliberate_language_specific_line_break(self):
        for key in HEADLINES:
            for locale in catalog.LOCALES:
                with self.subTest(key=key, locale=locale):
                    value = self.strings[key]["localizations"][locale]["stringUnit"]["value"]
                    self.assertEqual(value.count("\n"), 1)
                    self.assertTrue(all(line == line.strip() and line for line in value.split("\n")))
                    self.assertNotIn("\\n", value)


if __name__ == "__main__":
    unittest.main()
