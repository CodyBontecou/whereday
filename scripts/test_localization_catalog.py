"""Regression checks for coverage auditing and preservation of plural catalogs."""
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import localization_catalog as catalog


class LocalizationCatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.entry = {
            "localizations": {
                "en": {"stringUnit": {"state": "translated", "value": "%lld photos"}},
                "pl": {"variations": {"plural": {
                    form: {"stringUnit": {"state": "translated", "value": value}}
                    for form, value in zip(
                        ("one", "few", "many", "other"),
                        ("%lld zdjęcie", "%lld zdjęcia", "%lld zdjęć", "%lld zdjęcia"),
                    )
                }}},
            }
        }

    def audit(self):
        (self.root / "Localizable.xcstrings").write_text(json.dumps({"strings": {"%lld photos": self.entry}}))
        with patch.object(catalog, "ROOT", self.root), patch.object(catalog, "CATALOGS", (Path("Localizable.xcstrings"),)):
            return catalog.audit(("pl",))

    def test_complete_polish_plural_passes(self):
        self.assertEqual(self.audit(), 0)

    def test_missing_few_form_fails(self):
        del self.entry["localizations"]["pl"]["variations"]["plural"]["few"]
        self.assertEqual(self.audit(), 1)

    def test_lost_placeholder_in_nondefault_form_fails(self):
        self.entry["localizations"]["pl"]["variations"]["plural"]["few"]["stringUnit"]["value"] = "zdjęcia"
        self.assertEqual(self.audit(), 1)

    def test_fill_preserves_english_plural(self):
        english = {"variations": {"plural": {
            form: {"stringUnit": {"state": "translated", "value": value}}
            for form, value in (("one", "%lld photo"), ("other", "%lld photos"))
        }}}
        self.entry["localizations"]["en"] = english
        path = self.root / "Localizable.xcstrings"
        path.write_text(json.dumps({"strings": {"%lld photos": self.entry}}))
        with patch.object(catalog, "ROOT", self.root), patch.object(catalog, "CATALOGS", (path.relative_to(self.root),)), patch.object(catalog, "LOCALES", ("en",)), patch.object(catalog, "GOOGLE_LOCALES", {}), patch.object(catalog, "audit", return_value=0):
            self.assertEqual(catalog.fill(), 0)
        self.assertEqual(json.loads(path.read_text())["strings"]["%lld photos"]["localizations"]["en"], english)

    def test_extraction_requires_all_four_targets_and_detects_unknown_keys(self):
        targets = {"IsoMe": "IsoMe", "IsoMeWatch": "IsoMeWatch", "IsoMeWidgetExtension": "IsoMeWidget", "IsoMeWatchWidgetExtension": "IsoMeWatchWidget"}
        with patch.object(catalog, "ROOT", self.root):
            # A wrong or empty build path must never produce a coverage pass.
            self.assertEqual(catalog.audit_extracted(self.root / "missing"), 1)
            for target, folder in targets.items():
                path = self.root / folder / "Localizable.xcstrings"
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps({"strings": {"Save": {}}}))
                extracted = self.root / "build" / (target + ".build") / "Objects-normal" / "arm64" / "View.stringsdata"
                extracted.parent.mkdir(parents=True)
                extracted.write_text(json.dumps({"tables": {"Localizable": [{"key": "Save"}]}}))
            self.assertEqual(catalog.audit_extracted(self.root / "build"), 0)
            extracted.write_text(json.dumps({"tables": {"Localizable": [{"key": "Missing shared string"}]}}))
            self.assertEqual(catalog.audit_extracted(self.root / "build"), 1)

    def run(self, result=None):
        # Expected failures print diagnostics; keep the test output readable.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return super().run(result)


if __name__ == "__main__":
    unittest.main()
