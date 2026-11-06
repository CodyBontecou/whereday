#!/usr/bin/env python3
"""Compile the complete current iOS history importer against synthetic compatibility inputs."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[2]
source = root / 'IsoMe/Utilities/ImportService.swift'
formats = root / 'IsoMe/Utilities/ExportService.swift'
harness = root / 'android/scripts/fixtures/HistoryImportFixtures.swift'
inputs = root / 'android/scripts/fixtures/history-import-inputs.json'
output = root / 'android/export/src/test/resources/ios-history-import.json'
provenance = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in [source, formats, harness, inputs, Path(__file__).resolve()]}
parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group()
mode.add_argument('--check-source', action='store_true')
mode.add_argument('--verify-android', type=Path, help='Verify build-only Android JSON/CSV/Markdown exports with the actual Swift importer')
args = parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance'] != provenance:
        raise SystemExit('History import sources or fixture inputs changed; regenerate on macOS')
    print('PASS: history import fixture source hashes match')
    raise SystemExit(0)
# Only the format enum is extracted; compile ImportService.swift in its entirety.
text = formats.read_text()
format_enum = text[text.index('enum ExportFormat:'):text.index('\nstruct ExportService {')]
with tempfile.TemporaryDirectory(prefix='whereday-history-import-') as directory:
    work = Path(directory)
    (work / 'Formats.swift').write_text('import Foundation\n' + format_enum)
    (work / 'main.swift').write_bytes(harness.read_bytes())
    binary = work / 'fixtures'
    subprocess.run(['swiftc', '-module-cache-path', str(work / 'modules'),
                    str(work / 'Formats.swift'), str(source), str(work / 'main.swift'),
                    '-o', str(binary)], check=True)
    fixture_input = args.verify_android or inputs
    cases = json.loads(subprocess.run([str(binary), str(fixture_input.resolve())], check=True,
                                      capture_output=True, text=True).stdout)
    if args.verify_android:
        def normalize(value):
            if isinstance(value, list):
                return [normalize(v) for v in value]
            if isinstance(value, dict):
                return {k: (datetime.fromisoformat(v.replace('Z', '+00:00')).astimezone(timezone.utc).isoformat()
                            if v is not None and k in {'arrivedAt', 'departedAt', 'confirmedAt', 'updatedAt', 'timestamp'}
                            else normalize(v)) for k, v in value.items()}
            return value
        visit_variants = ['default', 'without-name', 'without-address', 'without-notes', 'without-duration', 'without-optional-fields']
        point_variants = [f'fields-{mask:02d}' for mask in range(16)]
        required = {f'android-{renderer}-{subject}-{variant}-{format}'
                    for renderer in ['list', 'streaming'] for format in ['json', 'csv', 'markdown']
                    for subject, variants in [('visits', visit_variants), ('points', point_variants), ('all', ['default', 'only-visits', 'only-points'])] for variant in variants}
        if len(cases) != 150 or {case['name'] for case in cases} != required:
            raise SystemExit('Expected 150 distinct current Android renderer/field-choice exports')
        for case in cases:
            if 'expectedError' in case:
                raise SystemExit(f"FAIL: {case['name']}: {case['expectedError']}")
            if case.get('entryPoint') != 'unified':
                raise SystemExit('Acceptance files must use the app importFile entry point')
            if case['subject'] == 'all':
                actual = {'visits': case['expectedVisits'], 'points': case['expectedPoints']}
            else:
                key = 'expectedVisits' if case['subject'] == 'visits' else 'expectedPoints'
                other = 'expectedPoints' if case['subject'] == 'visits' else 'expectedVisits'
                if case[other]:
                    raise SystemExit(f"FAIL: {case['name']}: unexpected second subject")
                actual = case[key]
            if normalize(case['androidExpected']) != normalize(actual):
                raise SystemExit(f"FAIL: {case['name']}: Swift imported values differ from Android values")
            print(f"PASS: {case['name']} accepted by actual Swift importer")
        report = args.verify_android.parent / 'swift-acceptance.json'
        android_sources = [root / 'android/export/src/main/kotlin/com/bontecou/whereday/export' / name
                           for name in ['HistoryExporter.kt', 'StreamingHistoryExporter.kt', 'HistoryImporter.kt', 'StreamingHistoryReader.kt', 'ImportRecordLimits.kt', 'CsvReader.kt', 'MarkdownHistoryReader.kt', 'ExportOptions.kt']]
        android_sources += [root / 'android/domain/src/main/kotlin/com/bontecou/whereday/domain/Models.kt',
                            root / 'android/export/src/test/kotlin/com/bontecou/whereday/export/HistoryExportInteropTest.kt']
        android_provenance = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in android_sources}
        report.write_text(json.dumps({'provenance': provenance, 'androidProvenance': android_provenance,
                                     'inputSHA256': hashlib.sha256(args.verify_android.read_bytes()).hexdigest(),
                                     'cases': cases}, ensure_ascii=False, indent=2) + '\n')
    else:
        output.write_text(json.dumps({'provenance': provenance, 'cases': cases},
                                     ensure_ascii=False, indent=2) + '\n')
        print(f'Generated {len(cases)} actual iOS history import fixtures')
