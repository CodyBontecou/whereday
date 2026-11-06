#!/usr/bin/env python3
"""Verify native iOS custom-date reference provenance; optionally prepare its isolated UI harness.

This check does not execute UIKit or certify the Android or iOS release matrix.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[2]
MAP_VIEW = 'IsoMe/Views/MapView.swift'
DESIGN = 'IsoMe/TEDesign.swift'
# MapView.swift: the range sheet, its presets and layer-help values, and the layer rows plus MapRangeCalendar.
FRAGMENTS = {
    'DateRangeFilterSheet': ('struct DateRangeFilterSheet: View {', '\n// MARK: - Quick Filter Bar'),
    'MapDatePreset': ('enum MapDatePreset: CaseIterable, Hashable {', '\nstruct FitMenuButton: View {'),
    'MapRangeCalendar': ('struct MapLayerSettings {', '\n// MARK: - Route Replay Helpers'),
}
# The paper and ink tokens and the toggle style those views draw with.
DESIGN_FRAGMENTS = {
    'TE': ('enum TE {', '\n// MARK: - Reusable Components'),
    'TEToggleStyle': ('struct TEToggleStyle: ToggleStyle {', '\nstruct TESectionFooter: View {'),
}

def cut(path, bounds):
    source = (ROOT / path).read_text()
    result = {}
    for name, (start, end) in bounds.items():
        index = source.index(start)
        result[name] = source[index:source.index(end, index)]
    return result

def fragments():
    return cut(MAP_VIEW, FRAGMENTS) | cut(DESIGN, DESIGN_FRAGMENTS)

def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check-source', action='store_true')
    mode.add_argument('--prepare', type=Path, help='New output directory for the standalone XcodeGen project')
    mode.add_argument('--print-digests', action='store_true', help='Print current source and harness digests')
    args = parser.parse_args()
    sheet = fragments()
    harness = sorted(str(path.relative_to(ROOT)) for path in (ROOT / 'android/parity/ios-custom-dates').rglob('*')
                     if path.is_file() and path.name != 'README.md')
    if args.print_digests:
        print(json.dumps({'sourceFragments': {name: digest(text) for name, text in sheet.items()},
                          'harness': {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in harness}}, indent=2))
        return
    provenance = json.loads((ROOT / 'android/app/src/test/resources/ios-custom-date-edits.json').read_text())['provenance']
    changes = [name for name, text in sheet.items() if digest(text) != provenance.get('sourceFragments', {}).get(name)]
    if sorted(provenance['harness']) != harness:
        changes.append('harness file list')
    changes += [name for name, value in provenance['harness'].items()
                if not (ROOT / name).is_file() or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != value]
    if changes and not args.prepare:
        raise SystemExit(f'iOS date sheet or native harness changed: {", ".join(changes)}; rerun native reference acceptance before updating fixtures')
    if args.prepare:
        output = args.prepare.resolve()
        output.mkdir(parents=True, exist_ok=False)
        source = ROOT / 'android/parity/ios-custom-dates'
        shutil.copytree(source / 'App', output / 'App')
        shutil.copytree(source / 'Tests', output / 'Tests')
        shutil.copy2(source / 'project.yml', output / 'project.yml')
        (output / 'App/DateRangeFilterSheet.swift').write_text('import SwiftUI\nimport UIKit\n\n' + '\n'.join(sheet.values()) + '\n')
        if changes:
            print(f'Changed since the recorded reference: {", ".join(changes)}; run acceptance, then update provenance')
        print(f'Prepared {output}; generate with xcodegen and run DateParity tests on a dedicated simulator')
    else:
        print('PASS: selected native iOS custom-date sheet and reference harness provenance')

if __name__ == '__main__':
    main()
