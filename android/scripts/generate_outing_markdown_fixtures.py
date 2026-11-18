#!/usr/bin/env python3
"""Compile actual Swift outing Markdown pages/tables and model summary helpers."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,tempfile
root=Path(__file__).resolve().parents[2]
export=root/'IsoMe/Utilities/ExportService.swift';session=root/'IsoMe/Models/RecordingSession.swift';point=root/'IsoMe/Models/LocationPoint.swift';visit=root/'IsoMe/Models/Visit.swift';travel=root/'Shared/DistanceFormatter.swift'
harness=root/'android/scripts/fixtures/OutingMarkdownFixtures.swift';inputs=root/'android/scripts/fixtures/outing-markdown-inputs.json';output=root/'android/export/src/test/resources/ios-outing-markdown.json'
native_output=root/'android/platform/src/androidTest/assets/ios-outing-markdown.json'
def native_cases(cases):return [c for c in cases if c['name']=='rich-default-single' or (c['name'].startswith('locale-') and c['shape']=='single')]
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [export,session,point,visit,travel,harness,inputs,Path(__file__).resolve()]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('Outing Markdown references are stale; regenerate on macOS')
    if json.loads(native_output.read_text())!=native_cases(json.loads(output.read_text())['cases']):raise SystemExit('Native outing Markdown fixtures are stale')
    print('PASS: outing Markdown source/model/harness/input and native fixture provenance');raise SystemExit(0)
def declaration(source,marker):
    start=source.index(marker);brace=source.index('{',start);end=brace+1;depth=1
    while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
e=export.read_text();s=session.read_text();p=point.read_text();v=visit.read_text();t=travel.read_text()
recording=declaration(s,'final class RecordingSession')
summary=s[s.index('struct RecordingSessionSummary:'):s.index('    var formattedTimeRange:')]+'\n}\n'
location=p[p.index('final class LocationPoint'):p.index('    var accessibilityTimestamp:')]+'\n}\n'
builder='enum RecordingSessionBuilder {\n'+declaration(s,'static func totalDistance(')+'\n}\n'
# Travel-mode types the session and summary reference (stored mode, default, moving-speed inference).
travel_modes='\n'.join(declaration(s,marker) for marker in ['enum TravelModeSource:','enum TravelModePreference:','struct RouteMetrics {','final class InferredTravelModeCache'])+'\n'
parts=[declaration(e,marker) for marker in ['private static let iso8601Formatter:', 'struct ExportableVisit:', 'private static func exportableVisit(', 'private static func auditMetadata(', 'private static func escapeMarkdownTableCell(', 'private static func formattedDateReadable(', 'static func exportOutingsToMarkdown(', 'private static func outingMarkdownPage(', 'private static func outingVisits(', 'private static func outingNotes(', 'static func travelModeSource(', 'private static func outingVisitsMarkdown(', 'private static func outingPointsMarkdown(', 'private static func formattedDateTime(', 'private static func yamlString(', 'private static func yamlStringArray(', 'private static func yamlNumber(', 'private static func yamlOptionalNumber(', 'private static func yamlQuoted(']]
parts[0]+='()'
audit=e[e.index('struct VisitAuditMetadata:'):e.index('\nstruct ExportService {')]
display=v[v.index('    var automaticDisplayName:'):v.index('    var hasCustomName:')]
duration=declaration(v,'var durationMinutes:')
selected='import Foundation\nimport CoreLocation\n'+declaration(t,'enum TravelMode:')+'\n'+recording+'\n'+location+'\n'+summary+'\n'+builder+'\n'+travel_modes+audit+'\nstruct RecordingSessionFormatter {\n'+declaration(s,'static func duration(')+'\n}\nstruct ExportService {\n'+'\n'.join(parts)+'\n}\n'
with tempfile.TemporaryDirectory(prefix='whereday-outing-markdown-') as directory:
    work=Path(directory);(work/'Selected.swift').write_text(selected);(work/'main.swift').write_text(harness.read_text().replace('// ACTUAL_VISIT_DISPLAY',display).replace('// ACTUAL_VISIT_DURATION',duration))
    binary=work/'fixtures';subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'Selected.swift'),str(work/'main.swift'),'-o',str(binary)],check=True)
    originals=json.loads(inputs.read_text());by_name={}
    context=lambda c:(c.get('zone','America/Puerto_Rico'),c.get('locale','en_US'))
    for zone,locale in dict.fromkeys(context(c) for c in originals):
        subset=work/'inputs.json';subset.write_text(json.dumps([c for c in originals if context(c)==(zone,locale)]))
        for c in json.loads(subprocess.run([str(binary),str(subset),'-AppleLocale',locale],check=True,capture_output=True,text=True).stdout):by_name[c['name']]=c
    cases=[by_name[c['name']] for c in originals]
def without_travel_mode(md):
    # iOS-only (PARITY_RECONCILIATION.md, November 18): travel mode front matter, the page's Mode row
    # and the bulk table's Mode column. Android has no travel mode, so its renderer omits them.
    # Each removal is counted against what Swift must have written; anything else stops generation.
    md,front=re.subn(r'(?m)^travel_mode(?:_source)?: .*\n','',md)
    md,rows=re.subn(r'(?m)^\| Mode \| .* \|\n','',md)
    lines=md.split('\n');table=False;tables=0
    for i,line in enumerate(lines):
        start=line.startswith('| Start | End | Title | Mode |')
        tables+=start;table=start or (table and line.startswith('|'))
        if not table: continue
        if line.startswith('|-'): cells=line.split('|');lines[i]='|'.join(cells[:4]+cells[5:])
        else:
            cells=line[2:-2].split(' | ')
            if len(cells)!=9: raise SystemExit(f'Unexpected outing Markdown table row: {line}')
            lines[i]='| '+' | '.join(cells[:3]+cells[4:])+' |'
    pages=md.count('\ntype: "outing"\n')
    if front!=2*pages or rows>pages or tables!=('# Whereday Outings Export' in md):
        raise SystemExit(f'Unexpected outing Markdown travel output: front={front} rows={rows} tables={tables} pages={pages}')
    md='\n'.join(lines)
    if 'travel_mode' in md or '| Mode |' in md: raise SystemExit('Outing Markdown travel output left behind')
    return md
for c in cases:c['expected']=without_travel_mode(re.sub(r'(?m)^\*\*Export Date:\*\* .*$', '**Export Date:** <GENERATED>',c['expected']))
output.write_text(json.dumps({'provenance':provenance,'normalization':'Generated bulk export dates are normalized and iOS-only travel mode output is removed; all other outing data and locale display remain.','cases':cases},ensure_ascii=False,indent=2)+'\n')
native_output.parent.mkdir(parents=True,exist_ok=True)
native_output.write_text(json.dumps(native_cases(cases),ensure_ascii=False,indent=2)+'\n')
print('Generated',len(cases),'actual Swift outing Markdown fixtures and',len(native_cases(cases)),'native real-file references')
