#!/usr/bin/env python3
"""Run actual iOS road-selection, stitching and replay rules with synthetic directions (macOS)."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'IsoMe/Views/MapView.swift'
OUTPUT=ROOT/'android/domain/src/test/resources/ios-road-snapping.json'
source=SOURCE.read_text()
helpers=source.split('struct RoadSnappingPoint:',1)[1].split('// MARK: - Helper Extension',1)[0]
helpers='struct RoadSnappingPoint:'+helpers
marker='    private static func snappedCoordinates(for segment: CandidateSegment)'
start=helpers.index(marker);brace=helpers.index('{',start);end=brace+1;depth=1
while depth:
    depth+=(helpers[end]=='{')-(helpers[end]=='}');end+=1
replacement='''    private static func snappedCoordinates(for segment: CandidateSegment) async -> [CLLocationCoordinate2D]? {
        for transport in preferredTransports(for: segment) {
            let name = transport == .walking ? "walking" : "automobile"
            fixtureCalls.append(["index": segment.startIndex, "transport": name])
            if fixtureMode == "missing" { continue }
            let distance = fixtureMode == "badwalking" && name == "walking" ? 1_000_000.0 : segment.distance * 1.5
            guard isPlausible(route: MKRoute(distance: distance), for: segment) else { continue }
            let a = segment.start.coordinate; let b = segment.end.coordinate
            let middle = CLLocationCoordinate2D(latitude:(a.latitude+b.latitude)/2+0.00005,longitude:(a.longitude+b.longitude)/2)
            let route = [a, CLLocationCoordinate2D(latitude:a.latitude+0.000001,longitude:a.longitude), middle,
                CLLocationCoordinate2D(latitude:b.latitude-0.000001,longitude:b.longitude), b]
            return stitchedCoordinates(route,source:a,destination:b)
        }
        return nil
    }'''
adapted=helpers[:start]+replacement+helpers[end:]
harness='''import Foundation
import MapKit
struct LocationPoint {
    let id = UUID(); let latitude:Double; let longitude:Double; let timestamp:Date
    let horizontalAccuracy:Double; let speed:Double? = nil; let isOutlier=false
    var coordinate:CLLocationCoordinate2D {CLLocationCoordinate2D(latitude:latitude,longitude:longitude)}
}
// Only the result distance is used by the actual plausibility predicate.
struct MKRoute {let distance:Double}
var fixtureCalls:[[String:Any]]=[]
var fixtureMode="good"
'''+adapted+'''
func coordinate(_ value:CLLocationCoordinate2D)->[Double] {[value.latitude,value.longitude]}
func serialize(_ segment:RoadSnappedRouteSegment)->[String:Any] {
 ["start":segment.startIndex,"end":segment.endIndex,"snapped":segment.isSnapped,"coordinates":segment.coordinates.map(coordinate)]
}
@main struct Runner {
 static func main() async throws {
  let base=Date(timeIntervalSince1970:1793095200)
  func p(_ lat:Double,_ lon:Double,_ seconds:Double,_ accuracy:Double=5)->RoadSnappingPoint {
   RoadSnappingPoint(coordinate:CLLocationCoordinate2D(latitude:lat,longitude:lon),timestamp:base.addingTimeInterval(seconds),horizontalAccuracy:accuracy)
  }
  let a=p(37,-122,0)
  let longPoints:[RoadSnappingPoint]=(0..<46).map {index -> RoadSnappingPoint in let i=Double(index);return p(0,i*0.002+i*i*0.00001,i*120)}
  var cases:[(String,[RoadSnappingPoint],String)]=[]
  cases.append(("empty",[],"good"))
  cases.append(("single",[a],"good"))
  cases.append(("short-coalesced",[a,p(37.00005,-122.00005,10),p(37.0001,-122.0001,20),p(37.00015,-122.00015,30)],"good"))
  cases.append(("walk",[a,p(37.002,-122.002,300)],"good"))
  cases.append(("drive",[a,p(37.01,-122.01,60)],"good"))
  cases.append(("walking-fallback",[a,p(37.002,-122.002,300)],"badwalking"))
  cases.append(("missing-coalesced",[a,p(37.002,-122.002,300),p(37.004,-122.004,600)],"missing"))
  cases.append(("zero-time",[a,p(37.002,-122.002,0)],"good"))
  cases.append(("reversed-time",[a,p(37.002,-122.002,-1)],"good"))
  cases.append(("two-hour-inclusive",[a,p(37.002,-122.002,7200)],"good"))
  cases.append(("two-hour-exceeded",[a,p(37.002,-122.002,7200.001)],"good"))
  cases.append(("too-fast",[a,p(37.01,-122.01,1)],"good"))
  cases.append(("too-far",[a,p(38,-122,7200)],"good"))
  cases.append(("poor-accuracy",[a,p(37.002,-122.002,300,500)],"good"))
  cases.append(("distance-scaled-accuracy",[a,p(37.01,-122.01,300,500)],"good"))
  cases.append(("forty-largest",longPoints,"good"))
  cases.append(("forty-largest-missing",longPoints,"missing"))
  var results:[[String:Any]]=[]
  for (name,points,mode) in cases {
   fixtureCalls=[];fixtureMode=mode
   let route=await RoadSnappedRouteBuilder.buildRoute(for:points,sourceFingerprint:42)
   let distances=zip(points,points.dropFirst()).map {$0.coordinate.roadSnapDistance(to:$1.coordinate)}
   let inputs=points.enumerated().map {i,p in ["id":"p\\(i)","latitude":p.coordinate.latitude,"longitude":p.coordinate.longitude,"seconds":p.timestamp.timeIntervalSince(base),"accuracy":p.horizontalAccuracy] as [String:Any]}
   results.append(["name":name,"mode":mode,"points":inputs,"candidateDistances":distances,"calls":fixtureCalls,
    "segments":route.segments.map(serialize),"replay":(-1...points.count).map {index in ["index":index,"segments":route.segments(upTo:index).map(serialize)] as [String:Any]}])
  }
  FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys]))
 }
}
'''
provenance={'helpers':hashlib.sha256(helpers.encode()).hexdigest(),'adapterAndHarness':hashlib.sha256(harness.encode()).hexdigest(),'generator':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(OUTPUT.read_text())['provenance']!=provenance:raise SystemExit('Road-snapping reference changed; regenerate on macOS')
    print('PASS: actual iOS road-snapping helper/adapter provenance');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-road-fixtures-') as directory:
    work=Path(directory);main=work/'main.swift';main.write_text(harness)
    subprocess.run(['swiftc','-parse-as-library','-module-cache-path',str(work/'modules'),str(main),'-o',str(work/'fixtures')],check=True)
    rows=json.loads(subprocess.run([str(work/'fixtures')],check=True,capture_output=True,text=True).stdout)
    platform=subprocess.run(['sw_vers'],check=True,capture_output=True,text=True).stdout.strip()
OUTPUT.parent.mkdir(parents=True,exist_ok=True)
OUTPUT.write_text(json.dumps({'provenance':provenance,'platform':platform,'contract':'Actual selection/coalescing/stitching/plausibility/transport/replay method bodies; synthetic directions adapter only. MapKit candidate distances recorded separately from Android surface distances. No real directions requests.','cases':rows},indent=2)+'\n')
print('Generated',len(rows),'Swift road-snapping references')
