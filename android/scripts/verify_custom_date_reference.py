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

def fragment():
    source = (ROOT / 'IsoMe/Views/MapView.swift').read_text()
    return source[source.index('struct DateRangeFilterSheet: View {'):source.index('\nstruct VisitQuickView: View {')]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check-source', action='store_true')
    mode.add_argument('--prepare', type=Path, help='New output directory for the standalone XcodeGen project')
    args = parser.parse_args()
    provenance = json.loads((ROOT / 'android/app/src/test/resources/ios-custom-date-edits.json').read_text())['provenance']
    sheet = fragment()
    if hashlib.sha256(sheet.encode()).hexdigest() != provenance['sheetFragmentSha256']:
        raise SystemExit('iOS date sheet changed; rerun native reference acceptance before updating fixtures')
    for name, digest in provenance['harness'].items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise SystemExit(f'Native harness changed: {name}; rerun reference acceptance')
    if args.prepare:
        output = args.prepare.resolve()
        output.mkdir(parents=True, exist_ok=False)
        source = ROOT / 'android/parity/ios-custom-dates'
        shutil.copytree(source / 'App', output / 'App')
        shutil.copytree(source / 'Tests', output / 'Tests')
        shutil.copy2(source / 'project.yml', output / 'project.yml')
        (output / 'App/DateRangeFilterSheet.swift').write_text('import SwiftUI\n' + sheet + '\n')
        print(f'Prepared {output}; generate with xcodegen and run DateParity tests on a dedicated simulator')
    else:
        print('PASS: selected native iOS custom-date sheet and reference harness provenance')

if __name__ == '__main__':
    main()
