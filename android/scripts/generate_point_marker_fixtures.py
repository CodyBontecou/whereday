#!/usr/bin/env python3
"""Execute the current Swift viewport GPS marker sampler, without rewriting its algorithm."""
from pathlib import Path
import argparse, hashlib, json, subprocess, tempfile

root=Path(__file__).resolve().parents[2]
source=(root/'IsoMe/Views/MapView.swift').read_text()
algorithm=source[source.index('@MainActor\nenum MapPointMarkerSampler {'):].strip()
assert algorithm.endswith('}')
harness='''import Foundation
import MapKit
struct LocationPoint {
    let id:UUID
    let latitude:Double
    let longitude:Double
    let ordinal:Int
    var coordinate:CLLocationCoordinate2D {CLLocationCoordinate2D(latitude:latitude,longitude:longitude)}
}
'''+algorithm+'''
func number(_ value:Any) -> Double {
    if let value=value as? NSNumber {return value.doubleValue}
    switch value as! String {case "nan":return .nan;case "inf":return .infinity;default:return -.infinity}
}
try MainActor.assumeIsolated {
let inputs=try JSONSerialization.jsonObject(with:FileHandle.standardInput.readDataToEndOfFile()) as! [[String:Any]]
let width=MKMapRect.world.width
var results:[[String:Any]]=[]
for item in inputs {
    let points=(item["points"] as! [[Any]]).enumerated().map {index,row in
        LocationPoint(id:UUID(uuidString:String(format:"00000000-0000-0000-0000-%012d",row[0] as! Int))!,latitude:number(row[1]),longitude:number(row[2]),ordinal:index)
    }
    let rect=(item["rect"] as? [Any]).map {MKMapRect(x:number($0[0])*width,y:number($0[1])*width,width:number($0[2])*width,height:number($0[3])*width)}
    let viewport=item["viewport"] as! [Any]
    let selected=(item["selected"] as? Int).map {UUID(uuidString:String(format:"00000000-0000-0000-0000-%012d",$0))!}
    let sampled=MapPointMarkerSampler.sample(points,in:rect,viewportSize:CGSize(width:number(viewport[0]),height:number(viewport[1])),selectedPointID:selected)
    results.append(["name":item["name"]!,"indices":sampled.map(\\.ordinal)])
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys]))
}
'''
points=[[1,0,0],[2,0,1],[3,0,2],[4,50,-170],[5,-50,170]]
cases=[]
def add(name,pts=points,rect=(0,0,1,1),viewport=(390,300),selected=None):
    cases.append(dict(name=name,points=pts,rect=rect,viewport=viewport,selected=selected))
add('first-in-cell');add('selected-replaces-cell',selected=3);add('selected-missing',selected=99)
add('unknown-viewport',rect=None);add('null-map-rect',rect=('inf','inf',0,0))
for label,rect in [('zero-width',(0,0,0,1)),('negative-height',(0,0,1,-1)),('nan-origin',('nan',0,1,1)),('infinite-width',(0,0,'inf',1))]:add(label,rect=rect)
for label,size in [('zero-size',(0,300)),('negative-size',(390,-1)),('nan-size',('nan',300)),('infinite-size',(390,'inf'))]:add(label,viewport=size)
add('invalid-coordinates',[[1,'nan',0],[2,0,'inf'],[3,91,0],[4,0,181],[5,0,0]])
add('date-line',[[1,0,177],[2,0,-177],[3,0,0],[4,0,-170]],rect=(.95,.4,.1,.2),viewport=(480,240))
add('date-line-selected',[[1,0,177],[2,0,-177],[3,0,-176.9]],rect=(.95,.4,.1,.2),viewport=(96,48),selected=3)
add('wrapped-negative-origin',rect=(-.1,0,.3,1));add('more-than-world',rect=(.1,0,2,1))
add('world-edge-alias',[[1,0,-180],[2,0,180]],selected=2)
add('north-south-outside',[[1,80,0],[2,-80,0],[3,0,0]],rect=(0,.25,1,.5))
add('tiny-grid',viewport=(1,1));add('selected-offscreen',selected=4,rect=(.49,.49,.02,.02))
mesh=[[i+1,-75+(i//40)*5,-175+(i%40)*9] for i in range(1200)]
for name,size in [('phone',(390,300)),('tablet',(1800,1200)),('ultrawide',(1000000,48)),('tall',(48,1000000))]:add(name,mesh,viewport=size)
add('stable-route-order',list(reversed(mesh)),viewport=(1800,1200),selected=678)
output=root/'android/domain/src/test/resources/ios-point-markers.json'
provenance={k:hashlib.sha256(v).hexdigest() for k,v in [('algorithm',algorithm.encode()),('harness',harness.encode()),('generator',Path(__file__).read_bytes())]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    previous=json.loads(output.read_text())
    if previous['provenance']!=provenance or previous['cases']!=json.loads(json.dumps(cases)):raise SystemExit('Swift point marker rules or cases changed; regenerate on macOS')
    print('PASS: actual Swift viewport point marker provenance');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-point-markers-') as folder:
    work=Path(folder);(work/'main.swift').write_text(harness)
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'main.swift'),'-o',str(work/'fixtures')],check=True)
    run=subprocess.run([str(work/'fixtures')],input=json.dumps(cases),capture_output=True,text=True,check=True)
    expected=json.loads(run.stdout)
    output.write_text(json.dumps(dict(provenance=provenance,cases=cases,expected=expected),indent=2)+'\n')
print(f'Generated {len(cases)} viewport marker cases from actual Swift')
