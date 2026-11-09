import json
import unittest
import generate_country_regions as regions

class CountryRegionReferenceTests(unittest.TestCase):
    def setUp(self):
        self.frozen={path:path.read_bytes() for path in regions.SOURCES}
        self.view=regions.SOURCES[3]
    def changed(self,before,after):
        result=dict(self.frozen)
        text=result[self.view].decode()
        self.assertIn(before,text)
        result[self.view]=text.replace(before,after,1).encode()
        return result
    def test_unrelated_view_styling_does_not_change_country_logic_fingerprints(self):
        changed=dict(self.frozen)
        changed[self.view]+=b'\n// Unrelated view styling edit\nprivate let fixturePaperOpacity = 0.9\n'
        self.assertEqual(regions.fingerprints(self.frozen),regions.fingerprints(changed))
    def test_country_eligibility_filter_change_is_detected(self):
        changed=self.changed('$0.identifier.count == 2','$0.identifier.count == 3')
        self.assertNotEqual(regions.fingerprints(self.frozen),regions.fingerprints(changed))
    def test_search_predicate_change_is_detected_even_with_same_empty_query_membership(self):
        changed=self.changed('localizedStandardContains(query)','hasPrefix(query)')
        self.assertNotEqual(regions.fingerprints(self.frozen),regions.fingerprints(changed))
    def test_model_changes_remain_fully_fingerprinted(self):
        changed=dict(self.frozen);changed[regions.SOURCES[0]]+=b'\n// changed model source\n'
        self.assertNotEqual(regions.fingerprints(self.frozen),regions.fingerprints(changed))
    def test_missing_selection_declaration_requires_review(self):
        changed=self.changed('private var countries:','private var regions:')
        with self.assertRaises(ValueError):regions.fingerprints(changed)
    def test_generated_policy_rejects_a_picker_missing_a_foundation_region(self):
        snapshot=json.loads(regions.OUTPUT.read_text())
        snapshot['picker'].remove('XK')
        with self.assertRaises(AssertionError):regions.kotlin(snapshot)

if __name__=='__main__':unittest.main()
