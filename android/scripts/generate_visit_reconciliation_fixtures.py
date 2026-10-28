#!/usr/bin/env python3
"""Run the actual Swift duplicate-stop methods with synthetic model adapters."""
from pathlib import Path
import argparse, hashlib, json, subprocess, tempfile
root=Path(__file__).resolve().parents[2]
source=root/'IsoMe/Services/LocationManager.swift'
model=root/'IsoMe/Models/Visit.swift'
harness=root/'android/scripts/fixtures/VisitReconciliationFixtures.swift'
output=root/'android/domain/src/test/resources/ios-visit-reconciliation.json'
asset=root/'android/data/src/androidTest/assets/ios-visit-reconciliation.json'
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [source,model,harness,Path(__file__).resolve()]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance or output.read_bytes()!=asset.read_bytes():
        raise SystemExit('Visit reconciliation sources changed; regenerate on macOS')
    print('PASS: visit reconciliation fixture source hashes match');raise SystemExit(0)
text=source.read_text()
def method(name):
    start=text.index('    private func '+name+'(')
    end=text.index('\n    private func ',start+1)
    return text[start:end].replace('private func ','func ',1)
constants='\n'.join(line for line in text.splitlines() if 'private let ' in line and any(x in line for x in ['visitArrivalMatchTolerance:','duplicateVisitMergeThresholdMeters:','duplicateVisitMergeGap:']))
selected='import Foundation\nimport CoreLocation\nclass Reference {\n'+constants+'\n'+''.join(method(x) for x in ['shouldMergeDuplicateVisits','timeGapBetween','mergeVisit','inferredDepartureDate'])+'\n}\n'
with tempfile.TemporaryDirectory(prefix='whereday-reconciliation-') as directory:
    work=Path(directory);(work/'Reference.swift').write_text(selected);(work/'main.swift').write_bytes(harness.read_bytes())
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'Reference.swift'),str(work/'main.swift'),'-o',str(work/'fixtures')],check=True)
    cases=json.loads(subprocess.run([str(work/'fixtures')],capture_output=True,text=True,check=True).stdout)
    output.write_text(json.dumps({'provenance':provenance,'cases':cases},ensure_ascii=False,indent=2)+'\n')
    asset.write_bytes(output.read_bytes())
print(f'Generated {len(cases)} actual Swift visit reconciliation cases')
