#!/usr/bin/env python3
"""Execute the selected current iOS manual-overlap predicate with Foundation dates."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]
source=(ROOT/'IsoMe/Services/ManualVisitStore.swift').read_text()
fragment=source[source.index('                let candidateEnd = candidate.departedAt ?? Date.distantFuture'):source.index('\n            }\n            if let overlap')]
# Times are milliseconds relative to Unix epoch. None denotes the native open bound.
cases=[
 [0,None,-2000,-1], [0,None,-2000,0], [0,None,-2000,1],
 [0,None,0,None], [0,None,1000,None], [0,None,1000,1000],
 [0,1000,-1000,0], [0,1000,1000,2000], [0,1000,0,1000],
 [0,1000,100,900], [0,1000,-100,1100], [0,0,0,0],
 [0,0,0,None], [0,1000,0,0], [0,1000,500,500],
 [-1500,-500,-1000,0], [-1500,None,-2000,-1500],
 [0,None,64092211200000,64092211200001],
]
harness='''import Foundation
struct Candidate { let arrivedAt:Date;let departedAt:Date? }
func overlaps(_ candidate:Candidate,_ arrivedAt:Date,_ departedAt:Date?)->Bool {
 let end=departedAt ?? Date.distantFuture
'''+fragment+'''
}
let raw=try JSONSerialization.jsonObject(with:Data(CommandLine.arguments[1].utf8)) as! [[Any]]
func date(_ value:Any)->Date? { (value as? NSNumber).map {Date(timeIntervalSince1970:$0.doubleValue/1000)} }
let cases=raw.map {input -> [String:Any] in
 let overlap=overlaps(Candidate(arrivedAt:date(input[2])!,departedAt:date(input[3])),date(input[0])!,date(input[1]))
 return ["from":input[0],"through":input[1],"candidateFrom":input[2],"candidateThrough":input[3],"overlap":overlap]
}
let result:[String:Any] = ["futureMillis":Int64(Date.distantFuture.timeIntervalSince1970*1000),"cases":cases]
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:result,options:[.sortedKeys]))
'''
provenance={key:hashlib.sha256(value).hexdigest() for key,value in {
 'predicate':fragment.encode(),'inputs':json.dumps(cases).encode(),'harness':harness.encode(),
 'generator':Path(__file__).read_bytes()}.items()}
output=ROOT/'android/data/src/androidTest/assets/ios-manual-visit-overlap.json'
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
 if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('iOS manual-overlap reference changed; regenerate and rerun native acceptance')
 print('PASS: selected actual Swift manual-overlap predicate provenance');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-manual-overlap-') as folder:
 work=Path(folder);(work/'main.swift').write_text(harness)
 subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'main.swift'),'-o',str(work/'reference')],check=True)
 result=subprocess.run([str(work/'reference'),json.dumps(cases)],text=True,capture_output=True,check=True)
 result=json.loads(result.stdout)
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps({'provenance':provenance,**result},indent=2)+'\n')
print(f'Generated {len(result["cases"])} actual Swift manual-overlap references')
