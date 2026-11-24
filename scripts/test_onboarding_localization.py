"""Coverage checks for the onboarding's editorial headlines (the "Page One" first-run flow)."""
import json
import re
import unittest

import localization_catalog as catalog


HEADLINES = (
    "Every day\nis a page.",
    "What should\nit remember for you?",
    "Let it write while\nit’s in your pocket.",
    "Check without\nopening it.",
    "Your history,\nyour files.",
    "Recording starts\nnow.",
)

# The free-week step's headlines are chosen in OnboardingFreeWeekLogic.swift.
FREE_WEEK_HEADLINES = (
    "Recording starts\nnow, on us.",
    "Recording starts\nnow.",
    "Recording starts\nnow, for good.",
)


FLOW_COPY = (
    "You’re in.", "Evening recap", "Every evening · %@",
    "Every night at %@, starting tonight. Change it in Settings › Reminders.",
    "Every night at %@, starting tomorrow. Change it in Settings › Reminders.",
    "A short recap of your day: where you went and how far.",
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
        cls.source = (catalog.ROOT / "IsoMe/Views/Onboarding/OnboardingView.swift").read_text()
        cls.free_week_source = (catalog.ROOT / "IsoMe/Views/Onboarding/OnboardingFreeWeekLogic.swift").read_text()

    def test_headline_keys_match_the_swiftui_source(self):
        for key in HEADLINES:
            with self.subTest(key=key):
                self.assertIn(json.dumps(key, ensure_ascii=False), self.source)

    def test_free_week_headline_keys_match_the_swift_source(self):
        for key in FREE_WEEK_HEADLINES:
            with self.subTest(key=key):
                self.assertIn(json.dumps(key, ensure_ascii=False), self.free_week_source)

    def test_every_supported_locale_has_a_translated_headline(self):
        for key in HEADLINES + FREE_WEEK_HEADLINES:
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

    def test_cover_button_reads_start_my_journal_in_every_locale(self):
        self.assertIn('case .cover: return "Start my journal"', self.source)
        self.assertNotIn("Write page one", self.source)
        self.assertNotIn("Write page one", self.strings)
        for locale in catalog.LOCALES:
            with self.subTest(locale=locale):
                unit = self.strings["Start my journal"]["localizations"][locale]["stringUnit"]
                self.assertEqual(unit["state"], "translated")
                self.assertTrue(unit["value"].strip())
                if locale == "en":
                    self.assertEqual(unit["value"], "Start my journal")
                else:
                    self.assertNotEqual(unit["value"], "Start my journal")
                    self.assertNotRegex(unit["value"].lower(), r"page|página|page un|страниц|ページ|页")

    def test_headlines_keep_a_deliberate_language_specific_line_break(self):
        for key in HEADLINES + FREE_WEEK_HEADLINES:
            for locale in catalog.LOCALES:
                with self.subTest(key=key, locale=locale):
                    value = self.strings[key]["localizations"][locale]["stringUnit"]["value"]
                    self.assertEqual(value.count("\n"), 1)
                    self.assertTrue(all(line == line.strip() and line for line in value.split("\n")))
                    self.assertNotIn("\\n", value)

    def test_no_copy_still_promises_tonights_page(self):
        # The evening recap replaced the one-shot "page one" notification; the cover's metaphor stays.
        self.assertIn("Every day\nis a page.", self.strings)
        promises = re.compile(r"page one (arrives|closes|is (open|written|ready))|tonight[’']s page|today is page one|"
                              r"closes tonight|your first page", re.IGNORECASE)
        sources = [self.source, self.free_week_source,
                   (catalog.ROOT / "IsoMe/Views/RemindersSettingsView.swift").read_text(),
                   (catalog.ROOT / "IsoMe/Services/RecapReminders.swift").read_text()]
        for key, entry in self.strings.items():
            if entry.get("extractionState") == "stale":
                continue
            with self.subTest(key=key):
                self.assertIsNone(promises.search(key))
        for source in sources:
            # Comments may describe the legacy notification; string literals may not promise it.
            literals = re.findall(r'"(?:[^"\\\n]|\\.)*"', "\n".join(
                line for line in source.splitlines() if not line.lstrip().startswith("//")))
            for literal in literals:
                with self.subTest(literal=literal):
                    self.assertIsNone(promises.search(literal))


if __name__ == "__main__":
    unittest.main()
