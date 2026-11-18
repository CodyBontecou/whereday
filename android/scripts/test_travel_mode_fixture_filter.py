#!/usr/bin/env python3
"""The iOS-only travel-mode filter used by the outing fixture generators (runs on Linux CI)."""
from pathlib import Path
import json
import unittest

from travel_mode_fixture_filter import (TravelModeFilterError, without_travel_columns,
                                        without_travel_fields, without_travel_markdown)

ROOT = Path(__file__).resolve().parents[2]
SAMPLES = json.loads((ROOT / 'android/scripts/fixtures/travel-mode-filter-samples.json').read_text())['samples']
HEADER = 'id,title,notes,travel_mode,travel_mode_source\n'


class ActualSwiftOutputTest(unittest.TestCase):
    def test_captured_swift_output_filters_to_the_committed_android_references(self):
        outings = json.loads((ROOT / 'android/export/src/test/resources/ios-outings.json').read_text())['cases']
        markdown = json.loads((ROOT / 'android/export/src/test/resources/ios-outing-markdown.json').read_text())['cases']
        csv_references = {c[key] for c in outings for key in ('expectedCSV', 'expectedPairCSV')}
        json_references = [o for c in outings for key in ('expectedJSON', 'expectedPairJSON') for o in c[key]['outings']]
        markdown_references = {c['expected'] for c in markdown}
        for sample in SAMPLES:
            kind, swift, android = sample['kind'], sample['swift'], sample['android']
            with self.subTest(kind=kind):
                if kind == 'csv':
                    self.assertEqual(without_travel_columns(swift), android)
                    self.assertIn(android, csv_references)
                elif kind == 'json':
                    self.assertEqual(without_travel_fields(dict(swift)), android)
                    self.assertIn(android, json_references)
                else:
                    self.assertEqual(without_travel_markdown(swift), android)
                    self.assertIn(android, markdown_references)


class CsvTest(unittest.TestCase):
    def test_removes_only_the_trailing_travel_values_of_each_record(self):
        swift = HEADER + 'a,"Ride, ""fast""","two\nlines, here",walk,manual\nb,B,,e_bike2,default\nc,C,,,automatic\n'
        self.assertEqual(without_travel_columns(swift),
                         'id,title,notes\na,"Ride, ""fast""","two\nlines, here"\nb,B,\nc,C,\n')

    def test_keeps_every_existing_column_in_place(self):
        swift = HEADER + 'a,A,n,drive,manual\n'
        self.assertEqual(without_travel_columns(swift).split('\n')[0].split(','), ['id', 'title', 'notes'])

    def test_rejects_unexpected_shapes(self):
        for swift in [
            'id,travel_mode,travel_mode_source,later\na,walk,manual,x\n',   # a column after the travel columns
            'id,travel_mode,title,travel_mode_source\na,walk,A,manual\n',   # travel column moved
            'id,title\na,A\n',                                              # no travel columns
            HEADER + 'a,A,n,"walk,bike",manual\n',                          # quoted travel value
            HEADER + 'a,A,n,walk,"man\nual"\n',                             # multi-line travel value
        ]:
            with self.subTest(swift=swift), self.assertRaises(TravelModeFilterError):
                without_travel_columns(swift)


class JsonTest(unittest.TestCase):
    def test_removes_mode_and_source(self):
        self.assertEqual(without_travel_fields({'id': 'a', 'travelMode': 'bike', 'travelModeSource': 'manual'}), {'id': 'a'})
        self.assertEqual(without_travel_fields({'id': 'a', 'travelModeSource': 'automatic'}), {'id': 'a'})

    def test_requires_the_source_swift_always_writes(self):
        with self.assertRaises(TravelModeFilterError):
            without_travel_fields({'id': 'a', 'travelMode': 'bike'})


class MarkdownTest(unittest.TestCase):
    PAGE = ('---\ntype: "outing"\ntitle: "A"\nsource: "recorded"\ntravel_mode: "bike"\ntravel_mode_source: "manual"\n'
            'notes: null\n---\n\n# A\n\n| Field | Value |\n|------|-------|\n| Distance | 5 m |\n| Mode | Bike |\n'
            '| GPS points | 2 |\n')
    TABLE = ('# Whereday Outings Export\n\n| Start | End | Title | Mode | Duration | Distance | Points | Visits | Notes |\n'
             '|------|-----|-------|------|----------|----------|--------|--------|-------|\n'
             '| s | e | Walk \\| run | Walk | 1m | 5 m | 2 | 0 | - |\n')

    def test_removes_front_matter_mode_row_and_table_column(self):
        page = without_travel_markdown(self.PAGE)
        self.assertNotIn('travel_mode', page)
        self.assertNotIn('| Mode |', page)
        self.assertIn('| Distance | 5 m |\n| GPS points | 2 |', page)
        self.assertEqual(without_travel_markdown(self.TABLE).split('\n')[2:5], [
            '| Start | End | Title | Duration | Distance | Points | Visits | Notes |',
            '|------|-----|-------|----------|----------|--------|--------|-------|',
            '| s | e | Walk \\| run | 1m | 5 m | 2 | 0 | - |'])

    def test_rejects_unexpected_shapes(self):
        for md in [
            self.PAGE.replace('travel_mode_source: "manual"\n', ''),                     # front matter incomplete
            self.TABLE.replace('| - |\n', '| - | extra |\n'),                             # table row gained a cell
            self.TABLE.replace('| Mode | Duration', '| Duration | Mode'),                 # Mode column moved
            self.PAGE + '\n| Mode | Bike |\n',                                           # more Mode rows than pages
        ]:
            with self.subTest(md=md), self.assertRaises(TravelModeFilterError):
                without_travel_markdown(md)


if __name__ == '__main__':
    unittest.main()
