#!/usr/bin/env python3
"""Measure actual LocationPoint/Core Location distance and route totals on macOS."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile
root=Path(__file__).resolve().parents[2]
point=root/'IsoMe/Models/LocationPoint.swift';session=root/'IsoMe/Models/RecordingSession.swift';harness=root/'android/scripts/fixtures/DistanceFixtures.swift';inputs=root/'android/scripts/fixtures/distance-inputs.json';output=root/'android/domain/src/test/resources/ios-distances.json'
native_output=root/'android/data/src/androidTest/assets/ios-distance-routes.json'
def native_cases(cases):return [c for c in cases if c['name'].startswith('walking-route-')]
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [point,session,harness,inputs,Path(__file__).resolve()]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('Distance references are stale; regenerate on macOS')
    if json.loads(native_output.read_text())!=native_cases(json.loads(output.read_text())['cases']):raise SystemExit('Native distance route references are stale')
    print('PASS: Core Location distance and native route fixture provenance');raise SystemExit(0)
def declaration(source,marker):
    start=source.index(marker);brace=source.index('{',start);end=brace+1;depth=1
    while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
source=harness.read_text().replace('// ACTUAL_DISTANCE',declaration(point.read_text(),'func distance(to other:')).replace('// ACTUAL_TOTAL_DISTANCE',declaration(session.read_text(),'static func totalDistance('))
originals=json.loads(inputs.read_text());cases=[]
with tempfile.TemporaryDirectory(prefix='whereday-distance-') as directory:
    work=Path(directory);(work/'main.swift').write_text(source);binary=work/'fixtures'
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'main.swift'),'-o',str(binary)],check=True)
    # Core Location's local curvature cache can depend on earlier calls. Isolate each
    # pair/ordered route in a fresh process, preserving call order within route totals.
    for row in originals:
        path=work/'input.json';path.write_text(json.dumps([row]));cases.extend(json.loads(subprocess.run([str(binary),str(path)],check=True,capture_output=True,text=True).stdout))
    platform=subprocess.run(['sw_vers'],capture_output=True,text=True,check=True).stdout.strip()
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps({'provenance':provenance,'platform':platform,'contract':'Fresh-process pairs and ordered routes. Conditioned comparisons use explicit tolerances; singular cases retain source results but require separate acceptance.','cases':cases},ensure_ascii=False,indent=2)+'\n')
native_output.parent.mkdir(parents=True,exist_ok=True)
native_output.write_text(json.dumps(native_cases(cases),indent=2)+'\n')
print('Generated',len(cases),'actual Core Location distance references and',len(native_cases(cases)),'native routes')
