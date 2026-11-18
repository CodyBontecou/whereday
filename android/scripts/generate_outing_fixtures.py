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
travel=root/'Shared/DistanceFormatter.swift'
harness=root/'android/scripts/fixtures/OutingFixtures.swift'
inputs=root/'android/scripts/fixtures/outing-inputs.json'
output=root/'android/export/src/test/resources/ios-outings.json'
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [export,session,point,travel,harness,inputs]}
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

e=export.read_text();s=session.read_text();p=point.read_text();t=travel.read_text()
# Keep actual model behavior without SwiftData persistence, UI-only summary properties or previews.
recording=declaration(s,'final class RecordingSession')
summary=s[s.index('struct RecordingSessionSummary:'):s.index('    var formattedTimeRange:')]+ '\n}\n'
location=p[p.index('final class LocationPoint'):p.index('    var accessibilityTimestamp:')]+ '\n}\n'
builder='enum RecordingSessionBuilder {\n'+declaration(s,'static func totalDistance(')+'\n}\n'
# Travel-mode types the session and summary reference (stored mode, default, moving-speed inference).
travel_modes='\n'.join(declaration(s,marker) for marker in ['enum TravelModeSource:','enum TravelModePreference:','struct RouteMetrics {','final class InferredTravelModeCache'])+'\n'
parts=[declaration(e,marker) for marker in ['struct ExportableOuting:', 'struct OutingsExportData:',
    'private static let iso8601Formatter:', 'static func exportOutingsToJSON(', 'static func exportOutingsToCSV(',
    'private static func exportableOuting(', 'private static func outingVisits(', 'private static func outingNotes(', 'static func travelModeSource(',
    'private static func escapeCSVField(']]
# The formatter is an immediately invoked closure in the original declaration.
parts[2]+='()'
selected='import Foundation\nimport CoreLocation\n'+declaration(t,'enum TravelMode:')+'\n'+recording+'\n'+location+'\n'+summary+'\n'+builder+'\n'+travel_modes
# Only arrival participates in these two renderers; no personal or persisted Visit is loaded.
# Coordinates and name exist only so the outing title helpers compile; the renderers never read them.
selected+='struct Visit {let arrivedAt: Date;var latitude: Double=0;var longitude: Double=0;var displayName: String=""}\nstruct ExportService {\n'+'\n'.join(parts)+'\n}\n'
with tempfile.TemporaryDirectory(prefix='whereday-outings-') as directory:
    work=Path(directory);source=work/'Selected.swift';main=work/'main.swift';binary=work/'fixtures'
    source.write_text(selected);main.write_bytes(harness.read_bytes())
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(source),str(main),'-o',str(binary)],check=True)
    cases=json.loads(subprocess.run([str(binary),str(inputs)],capture_output=True,text=True,check=True).stdout)
# iOS-only (PARITY_RECONCILIATION.md, November 18): Android has no travel mode, so the trailing CSV
# columns and JSON fields are removed from the actual Swift output before Android compares against it.
# Every step is checked; any other shape (moved columns, quoted values) stops generation instead.
import csv,io
TRAVEL_COLUMNS=['travel_mode','travel_mode_source']
def without_travel_columns(text):
    rows=list(csv.reader(io.StringIO(text,newline='')))
    if rows[0][-2:]!=TRAVEL_COLUMNS or any(name in rows[0][:-2] for name in TRAVEL_COLUMNS):
        raise SystemExit(f'Unexpected outing CSV header: {rows[0]}')
    header=','.join(TRAVEL_COLUMNS)
    stripped=text.replace(','+header+'\n','\n',1);position=stripped.index('\n')+1;kept=[stripped[:position]]
    for row in rows[1:]:
        suffix=','+','.join(row[-2:])+'\n'
        end=stripped.find(suffix,position)
        if any(ch in field for field in row[-2:] for ch in ',"\n') or end<0:
            raise SystemExit(f'Unexpected outing CSV travel fields: {row[-2:]}')
        kept.append(stripped[position:end]+'\n');position=end+len(suffix)
    result=''.join(kept)
    if position!=len(stripped) or list(csv.reader(io.StringIO(result,newline='')))!=[row[:-2] for row in rows]:
        raise SystemExit('Outing CSV travel columns could not be removed exactly')
    return result
for c in cases:
    for key in ['expectedCSV','expectedPairCSV']:
        c[key]=without_travel_columns(c[key])
    for key in ['expectedJSON','expectedPairJSON']:
        for outing in c[key]['outings']:
            if set(outing).isdisjoint({'travelMode','travelModeSource'}) or 'travelModeSource' not in outing:
                raise SystemExit(f'Unexpected outing JSON travel fields: {sorted(outing)}')
            outing.pop('travelMode',None);outing.pop('travelModeSource')
    output.write_text(json.dumps({'provenance':provenance,'cases':cases},ensure_ascii=False,indent=2)+'\n')
print(f'Generated {len(cases)} actual iOS outing renderer fixtures')
