#!/usr/bin/env python3
"""Compile current Swift JSON/CSV/Markdown renderers against synthetic transport records."""
from pathlib import Path
import argparse, hashlib, json, re, subprocess, tempfile
root=Path(__file__).resolve().parents[2]
source=root/'IsoMe/Utilities/ExportService.swift';model=root/'IsoMe/Models/Visit.swift'
harness=root/'android/scripts/fixtures/HistoryTableFixtures.swift';inputs=root/'android/scripts/fixtures/history-table-inputs.json'
output=root/'android/export/src/test/resources/ios-history-tables.json'
native_output=root/'android/platform/src/androidTest/assets/ios-markdown-locales.json'
def native_cases(cases):return [c for c in cases if c['name'].startswith('markdown-locale-')]
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [source,model,harness,inputs,Path(__file__).resolve()]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('History renderer sources changed; regenerate on macOS')
    if json.loads(native_output.read_text()) != native_cases(json.loads(output.read_text())['cases']):raise SystemExit('Native Markdown locale references are stale')
    print('PASS: JSON/CSV/Markdown renderer and native locale fixture provenance');raise SystemExit(0)
s=source.read_text();v=model.read_text()
audit=s[s.index('struct VisitAuditMetadata:'):s.index('\nstruct ExportService {')]
formatter=s[s.index('    private static let iso8601Formatter:'):s.index('\n    // MARK: - JSON Export')]
visits=s[s.index('    struct ExportableVisit:'):s.index('    static func createTemporaryFile(')]
points=s[s.index('    struct ExportableLocationPoint:'):s.index('    @MainActor\n    static func shareLocationPoints(')]
combined=s[s.index('    struct CombinedExportData:'):s.index('    @MainActor\n    static func shareCombined(')]
duration=v[v.index('    var durationMinutes: Double? {'):v.index('\n    var formattedDuration:')]
selected='import Foundation\n'+audit+'\nstruct ExportService {\n'+formatter+visits+points+combined+'\n}\n'
with tempfile.TemporaryDirectory(prefix='whereday-history-renderers-') as directory:
    work=Path(directory);(work/'Selected.swift').write_text(selected);(work/'main.swift').write_text(harness.read_text().replace('// ACTUAL_VISIT_DURATION',duration))
    binary=work/'fixtures';subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'Selected.swift'),str(work/'main.swift'),'-o',str(binary)],check=True)
    # Foundation caches Calendar/TimeZone/Locale.current: each context requires a fresh process.
    originals=json.loads(inputs.read_text());by_name={}
    context=lambda c:(c.get('zone','America/Puerto_Rico'),c.get('locale','en_US'))
    for zone,locale in dict.fromkeys(context(c) for c in originals):
        selected=[c for c in originals if context(c)==(zone,locale)]
        selected_input=work/'context-input.json';selected_input.write_text(json.dumps(selected))
        for c in json.loads(subprocess.run([str(binary),str(selected_input),'-AppleLocale',locale],check=True,capture_output=True,text=True).stdout):by_name[c['name']]=c
    rendered=[by_name[c['name']] for c in originals]
originals=json.loads(inputs.read_text());assert [c['name'] for c in originals]==[c['name'] for c in rendered]
cases=[]
for original,rendered in zip(originals,rendered):
    case=dict(original,locale=rendered['locale'],zone=rendered['zone'])
    text=rendered['text']
    if case['format']=='json':
        payload=json.loads(text);payload['exportDate']='2026-10-28T12:00:00.000Z';case['expected']=payload
    elif case['format']=='csv':case['text']=re.sub(r'(?m)^# Generated: .*$', '# Generated: 2026-10-28T12:00:00.000Z',text)
    else:case['text']=re.sub(r'(?m)^\*\*Export Date:\*\* .*$', '**Export Date:** <GENERATED>',text)
    cases.append(case)
output.write_text(json.dumps({'provenance':provenance,'normalization':'Only generated dates and JSON object order/whitespace are normalized. Markdown retains actual locale/zone and display precision.','cases':cases},ensure_ascii=False,indent=2)+'\n')
native_output.parent.mkdir(parents=True,exist_ok=True)
native_output.write_text(json.dumps(native_cases(cases),ensure_ascii=False,indent=2)+'\n')
print('Generated',len(cases),'actual Swift history renderer fixtures and',len(native_cases(cases)),'native locale references')
