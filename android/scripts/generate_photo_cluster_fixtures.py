#!/usr/bin/env python3
"""Extract the current iOS photo clustering algorithm and run synthetic coordinates."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile
root=Path(__file__).resolve().parents[2]
source=(root/'IsoMe/Views/PhotoMomentViews.swift').read_text()
start=source.index('enum PhotoMomentClusterBuilder {');end=source.index('\nstruct PhotoMomentMapMarker:',start)
algorithm=source[start:end]
model=source[source.index("struct PhotoMomentCluster: Identifiable {"):start]
harness='''import Foundation
import CoreLocation
struct PhotoMoment {
 let id:UUID;let assetLocalIdentifier:String;let takenAt:Date;let latitude:Double;let longitude:Double
 var coordinate:CLLocationCoordinate2D {CLLocationCoordinate2D(latitude:latitude,longitude:longitude)}
}
'''+model+algorithm+'''
var cases:[[String:Any]]=[]
func add(_ name:String,_ values:[[Double]],_ threshold:Double=35,_ maximum:Int=500) {
 let photos=values.enumerated().map {index,value in PhotoMoment(id:UUID(uuidString:String(format:"00000000-0000-0000-0000-%012d",index))!,assetLocalIdentifier:String(format:"photo-%03d",index),takenAt:Date(timeIntervalSince1970:value[0]),latitude:value[1],longitude:value[2])}
 let clusters=PhotoMomentClusterBuilder.placeClusters(for:photos,thresholdMeters:threshold)
 let annotations=PhotoMomentClusterBuilder.annotations(for:clusters,maximumCount:maximum)
 cases.append(["name":name,"threshold":threshold,"maximum":maximum,"annotations":annotations.map {["photos":$0.photos.map {$0.assetLocalIdentifier},"lat":$0.coordinate.latitude,"lon":$0.coordinate.longitude,"isArea":$0.isArea,"representative":$0.representativePhoto?.assetLocalIdentifier ?? "","places":$0.places.map {$0.photos.map {$0.assetLocalIdentifier}}] as [String:Any]},"photos":photos.map {["uri":$0.assetLocalIdentifier,"time":$0.takenAt.timeIntervalSince1970,"lat":$0.latitude,"lon":$0.longitude] as [String:Any]},"clusters":clusters.map {["photos":$0.photos.map { $0.assetLocalIdentifier },"lat":$0.coordinate.latitude,"lon":$0.coordinate.longitude] as [String:Any]}])
}
add("empty",[])
add("same-place-unsorted",[[20,48.8584,2.2945],[10,48.8584,2.2945]])
add("centroid-not-first",[[0,0,0],[1,0,0.00025],[2,0,0.00040],[3,0,0.00060]])
add("nearest-group",[[0,0,0],[1,0,0.0005],[2,0,0.0003]])
add("equal-times",[[0,18,-66],[0,18.0001,-66],[0,48,2],[0,48.0001,2]])
add("dateline-arithmetic-centroid",[[0,0,179.9999],[1,0,-179.9999],[2,0,179.9999]])
add("polar",[[0,89.9999,0],[1,89.9999,120],[2,89.9999,-120]])
add("exact-coordinate-only",[[0,48.0,2],[1,48,2],[2,48.000001,2]],0)
add("centroid-crosses-index-cell",(0..<150).map {[Double($0),18, -66+Double($0)*0.00001]})
add("many-distinct-places",(0..<1200).map {i -> [Double] in [Double(i),-60.0+Double(i/40)*4.0,-175.0+Double(i%40)*8.0]})
add("area-keeps-member-groups",[[0,0,0],[1,0,0.00001],[2,0,0.01],[3,0,0.01001],[4,48,2],[5,-48,-2]],35,2)
add("single-area-global",[[0,90,0],[1,-90,0],[2,0,180],[3,0,-180],[4,45,45]],35,1)
add("area-equal-times",[[0,20,20],[0,-20,-20],[0,20.01,20.01],[0,-20.01,-20.01]],35,2)
add("maximum-exactly",[[0,0,0],[1,1,1]],35,2)
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:cases,options:[.sortedKeys]))
'''
provenance={"model":hashlib.sha256(model.encode()).hexdigest(),"algorithm":hashlib.sha256(algorithm.encode()).hexdigest(),"harness":hashlib.sha256(harness.encode()).hexdigest()}
outputs=[root/'android/domain/src/test/resources/ios-photo-clusters.json',root/'android/data/src/androidTest/assets/ios-photo-clusters.json']
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
 for output in outputs:
  assert json.loads(output.read_text())['provenance']==provenance,'Swift photo clustering changed; regenerate on macOS'
 assert outputs[0].read_bytes()==outputs[1].read_bytes()
 print('PASS: actual Swift photo-cluster fixture provenance');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-photo-clusters-') as directory:
 work=Path(directory);(work/'main.swift').write_text(harness)
 subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'main.swift'),'-o',str(work/'fixtures')],check=True)
 cases=json.loads(subprocess.run([str(work/'fixtures')],check=True,capture_output=True,text=True).stdout)
 for output in outputs:
  output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({"provenance":provenance,"cases":cases},indent=2)+'\n')
 print('Generated',len(cases),'actual Swift photo-cluster fixtures')
