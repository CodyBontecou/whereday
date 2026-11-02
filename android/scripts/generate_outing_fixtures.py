#!/usr/bin/env python3
"""Compile current iOS outing summary/JSON/CSV code against synthetic model inputs."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
export=root/'IsoMe/Utilities/ExportService.swift'
session=root/'IsoMe/Models/RecordingSession.swift'
point=root/'IsoMe/Models/LocationPoint.swift'
harness=root/'android/scripts/fixtures/OutingFixtures.swift'
inputs=root/'android/scripts/fixtures/outing-inputs.json'
output=root/'android/export/src/test/resources/ios-outings.json'
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [export,session,point,harness,inputs]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance: raise SystemExit('Outing fixture sources changed; regenerate on macOS')
    print('PASS: outing fixture source hashes match')
    raise SystemExit(0)

def declaration(source,marker):
    start=source.index(marker);brace=source.index('{',start);end=brace+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]

e=export.read_text();s=session.read_text();p=point.read_text()
# Keep actual model behavior without SwiftData persistence, UI-only summary properties or previews.
recording=declaration(s,'final class RecordingSession')
summary=s[s.index('struct RecordingSessionSummary:'):s.index('    var formattedTimeRange:')]+ '\n}\n'
location=p[p.index('final class LocationPoint'):p.index('    var accessibilityTimestamp:')]+ '\n}\n'
builder='enum RecordingSessionBuilder {\n'+declaration(s,'static func totalDistance(')+'\n}\n'
parts=[declaration(e,marker) for marker in ['struct ExportableOuting:', 'struct OutingsExportData:',
    'private static let iso8601Formatter:', 'static func exportOutingsToJSON(', 'static func exportOutingsToCSV(',
    'private static func exportableOuting(', 'private static func outingVisits(', 'private static func outingNotes(',
    'private static func escapeCSVField(']]
# The formatter is an immediately invoked closure in the original declaration.
parts[2]+='()'
selected='import Foundation\nimport CoreLocation\n'+recording+'\n'+location+'\n'+summary+'\n'+builder+'\n'
# Only arrival participates in these two renderers; no personal or persisted Visit is loaded.
selected+='struct Visit {let arrivedAt: Date}\nstruct ExportService {\n'+'\n'.join(parts)+'\n}\n'
with tempfile.TemporaryDirectory(prefix='whereday-outings-') as directory:
    work=Path(directory);source=work/'Selected.swift';main=work/'main.swift';binary=work/'fixtures'
    source.write_text(selected);main.write_bytes(harness.read_bytes())
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(source),str(main),'-o',str(binary)],check=True)
    cases=json.loads(subprocess.run([str(binary),str(inputs)],capture_output=True,text=True,check=True).stdout)
    output.write_text(json.dumps({'provenance':provenance,'cases':cases},ensure_ascii=False,indent=2)+'\n')
print(f'Generated {len(cases)} actual iOS outing renderer fixtures')
