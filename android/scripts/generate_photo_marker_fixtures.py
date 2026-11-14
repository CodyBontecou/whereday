#!/usr/bin/env python3
"""Execute the current Swift marker-mode and screen-space dot targeting rules."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile

root=Path(__file__).resolve().parents[2]
source=(root/'IsoMe/Views/PhotoMomentViews.swift').read_text()
start=source.index('enum PhotoMapMarkerStyle: Equatable {')
end=source.index('\nstruct PhotoMapDot: View {',start)
algorithm=source[start:end]
harness='''import Foundation
import CoreGraphics
'''+algorithm+'''
var styles:[[String:Any]]=[]
for images in [false,true] {for dots in [false,true] {
 styles.append(["images":images,"dots":dots,"style":String(describing:PhotoMapMarkerStyle.resolve(showsImages:images,showsDots:dots))])
}}
var hits:[[String:Any]]=[]
func add(_ name:String,_ point:CGPoint,_ centers:[CGPoint?]) {
 hits.append(["name":name,"point":[point.x,point.y],"centers":centers.map {p -> Any in p.map {[$0.x,$0.y]} ?? NSNull()},
              "index":PhotoMapDotHitTesting.targetIndex(at:point,centers:centers) as Any? ?? NSNull()])
}
add("empty",.zero,[])
add("unprojectable",.zero,[nil])
add("center",CGPoint(x:100,y:100),[CGPoint(x:100,y:100)])
add("inclusive-edge",CGPoint(x:122,y:100),[CGPoint(x:100,y:100)])
add("outside-edge",CGPoint(x:122.001,y:100),[CGPoint(x:100,y:100)])
add("inclusive-corner",CGPoint(x:122,y:122),[CGPoint(x:100,y:100)])
add("outside-vertical",CGPoint(x:100,y:122.001),[CGPoint(x:100,y:100)])
add("nearest-overlap",CGPoint(x:119,y:100),[CGPoint(x:100,y:100),CGPoint(x:120,y:100)])
add("nearest-reversed",CGPoint(x:119,y:100),[CGPoint(x:120,y:100),CGPoint(x:100,y:100)])
add("stable-tie",CGPoint(x:110,y:100),[CGPoint(x:100,y:100),CGPoint(x:120,y:100)])
add("coincident",CGPoint(x:100,y:100),[CGPoint(x:100,y:100),CGPoint(x:100,y:100)])
add("missing-first",CGPoint(x:100,y:100),[nil,CGPoint(x:100,y:100)])
add("translated",CGPoint(x:10119,y:-9900),[CGPoint(x:10100,y:-9900),CGPoint(x:10120,y:-9900)])
add("outside-all",CGPoint(x:200,y:200),[CGPoint(x:100,y:100),CGPoint(x:120,y:100)])
var locals:[[String:Any]]=[]
for pair in [(CGPoint(x:22,y:22),CGPoint(x:100,y:200)),(CGPoint.zero,CGPoint(x:-10,y:10)),(CGPoint(x:44,y:44),CGPoint(x:100,y:200))] {
 let point=PhotoMapDotHitTesting.mapPoint(localPoint:pair.0,markerCenter:pair.1)
 locals.append(["local":[pair.0.x,pair.0.y],"center":[pair.1.x,pair.1.y],"point":[point.x,point.y]])
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:["styles":styles,"hits":hits,"locals":locals],options:[.sortedKeys]))
'''
output=root/'android/domain/src/test/resources/ios-photo-markers.json'
provenance={'algorithm':hashlib.sha256(algorithm.encode()).hexdigest(),'harness':hashlib.sha256(harness.encode()).hexdigest(),
            'generator':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('Swift photo marker rules changed; regenerate on macOS')
    print('PASS: actual Swift photo marker style/hit-test provenance');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-photo-markers-') as folder:
    work=Path(folder);(work/'main.swift').write_text(harness)
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'main.swift'),'-o',str(work/'fixtures')],check=True)
    cases=json.loads(subprocess.run([str(work/'fixtures')],capture_output=True,text=True,check=True).stdout)
    output.write_text(json.dumps({'provenance':provenance,**cases},indent=2)+'\n')
print('Generated 4 styles, 14 hit targets and 3 local-to-map references from actual Swift')
