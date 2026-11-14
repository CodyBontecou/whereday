import unittest
from generate_localized_resources import generate, signature, validate, merge_defaults, split_generated


class LocalizedResourcesTest(unittest.TestCase):
    def test_reordered_numbered_arguments_preserve_identity(self):
        validate("%1$d points on %2$s", "%2$s: %1$d puntos")

    def test_wrong_type_missing_argument_and_swift_formats_fail(self):
        for target in ["%1$s puntos", "%1$d puntos", "%@ puntos"]:
            with self.assertRaises(ValueError):
                validate("%1$d points on %2$s", target)
        with self.assertRaises(ValueError):
            signature("%lld points")

    def test_plural_categories_are_explicit_and_each_format_is_checked(self):
        source = {"one": "%d point", "other": "%d points"}
        validate(source, {"one": "%d punto", "other": "%d puntos"})
        for target in [{"other": "%d puntos"}, {"one": "%d punto", "other": "%s puntos"}]:
            with self.assertRaises(ValueError):
                validate(source, target)

    def test_missing_keys_and_unreviewed_catalog_entries_fail(self):
        pack = {"locale": "es", "catalog": {"map": "Map"}, "translations": {}}
        catalog = {"strings": {"Map": {"localizations": {"es": {"stringUnit": {"state": "needs_review", "value": "Mapa"}}}}}}
        with self.assertRaises(ValueError):
            generate({"map": "Map"}, pack, catalog)
        with self.assertRaises(ValueError):
            generate({"map": "Map", "new": "New"}, pack, catalog)

    def test_xml_and_android_quotes_are_escaped(self):
        text = generate({"label": "Label"}, {"locale": "es", "catalog": {}, "translations": {"label": 'A & B "quote" it\'s'}}, {})
        self.assertIn("A &amp; B", text)
        self.assertIn('\\"quote\\"', text)
        self.assertIn("it\\'s", text)

    def test_module_outputs_preserve_ownership_and_plural_formats(self):
        modules = {"app": {"screen": "Screen"}, "platform": {"count": {"one": "%s point", "other": "%s points"}}}
        source = merge_defaults(modules)
        text = generate(source, {"locale": "es", "catalog": {}, "translations": {"screen": "Pantalla", "count": {"one": "%s punto", "other": "%s puntos"}}}, {})
        outputs = split_generated(text, modules)
        self.assertIn('name="screen"', outputs["app"])
        self.assertNotIn('name="count"', outputs["app"])
        self.assertIn('name="count"', outputs["platform"])
        self.assertNotIn('name="screen"', outputs["platform"])
        self.assertIn('%s puntos', outputs["platform"])

    def test_duplicate_and_unowned_resources_fail(self):
        with self.assertRaises(ValueError):
            merge_defaults({"app": {"duplicate": "A"}, "platform": {"duplicate": "B"}})
        with self.assertRaises(ValueError):
            split_generated('<resources><string name="missing">Text</string></resources>', {"app": {"known": "Known"}})


if __name__ == "__main__":
    unittest.main()
