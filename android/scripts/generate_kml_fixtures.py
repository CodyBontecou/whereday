#!/usr/bin/env python3
"""Compile the current iOS KML renderer; normalize only the wall-clock metadata timestamp."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = root / 'IsoMe/Utilities/ExportService.swift'
visit = root / 'IsoMe/Models/Visit.swift'
harness = root / 'android/scripts/fixtures/KmlFixtures.swift'
inputs = root / 'android/scripts/fixtures/kml-inputs.json'
output = root / 'android/export/src/test/resources/ios-kml.json'
paths = [source, visit, harness, inputs, Path(__file__).resolve()]
provenance = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check-source', action='store_true')
args = parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance'] != provenance:
        raise SystemExit('KML fixture sources changed; regenerate on macOS')
    print('PASS: KML fixture source hashes match')
    raise SystemExit(0)
text = source.read_text()
start = text.index('extension ExportService {\n    /// New <trkseg>')
end = text.index('// MARK: - GeoJSON', start)
formatter_start = text.index('    private static let iso8601Formatter:')
formatter_end = text.index('\n    // MARK:', formatter_start)
v = visit.read_text()
duration = v[v.index('    var durationMinutes: Double? {'):v.index('\n    var formattedDuration:')]
audit = text[text.index('struct VisitAuditMetadata:'):text.index('\nstruct ExportService {')]
record = text[text.index('    struct ExportableVisit:'):text.index('\n    struct ExportData:')]
functions = text[text.index('    private static func exportableVisit('):text.index('    static func exportToJSON(')]
selected = 'import Foundation\n' + audit + '\nstruct ExportService {\n' + text[formatter_start:formatter_end] + '\n' + record + '\n' + functions + '\n}\n' + text[start:end]
with tempfile.TemporaryDirectory(prefix='whereday-kml-fixtures-') as directory:
    work = Path(directory)
    (work / 'Selected.swift').write_text(selected)
    (work / 'main.swift').write_text(harness.read_text().replace('// ACTUAL_VISIT_DURATION', duration))
    binary = work / 'fixtures'
    subprocess.run(['swiftc', '-module-cache-path', str(work / 'modules'), str(work / 'Selected.swift'), str(work / 'main.swift'), '-o', str(binary)], check=True)
    cases = json.loads(subprocess.run([str(binary), str(inputs)], check=True, capture_output=True, text=True).stdout)
# Keep original transport numbers, especially -0.0: JSONSerialization emits -0, which
# a subsequent integer JSON decode otherwise silently turns into positive zero.
originals = json.loads(inputs.read_text())
if len(originals) != len(cases) or [c['name'] for c in originals] != [c['name'] for c in cases]:
    raise SystemExit('Swift fixture identity mismatch')
cases = [dict(original, text=rendered['text']) for original, rendered in zip(originals, cases)]
for case in cases:
    case['text'], count = re.subn(r'(<Data name="exportDate"><value>).*?(</value>)' , lambda m: m[1] + '2026-10-28T12:00:00.000Z' + m[2], case['text'], count=1, flags=re.S)
    if count != 1:
        raise SystemExit('Expected exactly one generated metadata time')
output.write_text(json.dumps({'provenance': provenance, 'normalization': 'Only Document/ExtendedData exportDate uses a fixed generatedAt; record timestamps and all other content are actual Swift output.', 'cases': cases}, ensure_ascii=False, indent=2) + '\n')
print(f'Generated {len(cases)} actual iOS KML fixtures')
