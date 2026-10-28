#!/usr/bin/env python3
"""Run the actual extracted Swift photo resolver on public synthetic metadata (macOS)."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile
root=Path(__file__).resolve().parents[2]
source=(root/'IsoMe/ViewModels/LocationViewModel.swift').read_text()
def extract(token):
    start=source.index(token);brace=source.index('{',start);depth=1;end=brace+1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
functions='\n'.join(extract(token) for token in ['    private func resolvedPhotoMoments(', '    private func nearestLocationPoint(', '    private func visitContainingPhoto('])
functions=functions.replace('let referenceDate = Date()','let referenceDate = fixtureNow')
constants='\n'.join(line.strip() for line in source.splitlines() if 'private let photoRouteInferenceWindow:' in line or 'private let photoVisitInferenceGracePeriod:' in line)
model=(root/'IsoMe/Models/PhotoMoment.swift').read_text()
structs=model[model.index('struct PhotoAssetLibraryMetadata:'):]
harness='''import Foundation
import CoreLocation
enum PhotoMomentCoordinateSource: String {case photoGPS="photo_gps", inferredFromRoute="inferred_route", inferredFromVisit="inferred_visit"}
struct LocationPoint {let id:String;let timestamp:Date;let latitude:Double;let longitude:Double;let isOutlier:Bool}
struct Visit {let id:String;let arrivedAt:Date;let departedAt:Date?;let latitude:Double;let longitude:Double}
'''+structs+'''
final class Resolver {
    var points:[LocationPoint]=[];var allVisits:[Visit]=[]
    let fixtureNow=Date(timeIntervalSince1970:1793109600)
    func fetchLocationPoints(in range:ClosedRange<Date>)->[LocationPoint] {
        points.filter {range.contains($0.timestamp)}.sorted {($0.timestamp,$0.id)<($1.timestamp,$1.id)}
    }
'''+constants+'\n'+functions+'''
    func run(_ photo:PhotoAssetLibraryMetadata,_ range:ClosedRange<Date>)->PhotoAssetMetadata? {resolvedPhotoMoments(from:[photo],in:range).first}
}
let now=1793109600.0
func date(_ offset:Double)->Date {Date(timeIntervalSince1970:now+offset)}
func point(_ id:String,_ offset:Double,_ bad:Bool=false)->LocationPoint {LocationPoint(id:id,timestamp:date(offset),latitude:48.8584+offset*0.0000001,longitude:2.2945,isOutlier:bad)}
func visit(_ id:String,_ arrival:Double,_ departure:Double?)->Visit {Visit(id:id,arrivedAt:date(arrival),departedAt:departure.map(date),latitude:18.0,longitude:-66.0)}
var cases:[[String:Any]]=[]
func add(_ name:String,_ time:Double=0,_ gps:[Double]?=nil,_ points:[LocationPoint]=[],_ visits:[Visit]=[],_ from:Double = -10,_ until:Double = 10) {
    let resolver=Resolver();resolver.points=points;resolver.allVisits=visits
    let asset=PhotoAssetLibraryMetadata(assetLocalIdentifier:"content://synthetic/"+name,takenAt:date(time),latitude:gps?.first,longitude:gps?.last)
    let result=resolver.run(asset,date(from)...date(until-0.001))
    cases.append(["name":name,"now":now,"time":now+time,"uri":asset.assetLocalIdentifier,"gps":gps as Any? ?? NSNull(),"from":now+from,"until":now+until,
        "points":points.map {["id":$0.id,"time":$0.timestamp.timeIntervalSince1970,"lat":$0.latitude,"lon":$0.longitude,"outlier":$0.isOutlier] as [String:Any]},
        "visits":visits.map {["id":$0.id,"arrival":$0.arrivedAt.timeIntervalSince1970,"departure":$0.departedAt?.timeIntervalSince1970 as Any? ?? NSNull(),"lat":$0.latitude,"lon":$0.longitude] as [String:Any]},
        "result":result.map {["lat":$0.latitude,"lon":$0.longitude,"source":$0.coordinateSource.rawValue] as [String:Any]} as Any? ?? NSNull()])
}
add("gps-wins",0,[48.8584,2.2945],[point("a",0)],[visit("v",-50,50)])
add("invalid-gps",0,[91,2.0],[point("a",-10)])
add("route-tie-earlier",0,nil,[point("before",-10),point("after",10)])
add("route-nearest-after",0,nil,[point("before",-11),point("after",10)])
add("route-window-boundary",0,nil,[point("a",-900)])
add("route-window-outside",0,nil,[point("a",-900.001)])
add("all-outlier-fallback",0,nil,[point("a",-10,true)])
add("good-anywhere-suppresses-bad",0,nil,[point("bad",0,true),point("good",905)],[visit("v",-50,50)],-10,10)
add("bad-outside-request-window",0,nil,[point("bad",-1000,true)])
add("route-before-visit",0,nil,[point("a",10)],[visit("v",-50,50)])
add("visit-grace-start",0,nil,[],[visit("v",900,1000)])
add("visit-grace-outside",0,nil,[],[visit("v",900.001,1000)])
add("open-visit-end",900,nil,[],[visit("v",-50,nil)],-10,1000)
add("open-visit-future-outside",900.001,nil,[],[visit("v",-50,nil)],-10,1000)
add("overlapping-visits-earliest",0,nil,[],[visit("early",-100,100),visit("later",-50,100)])
add("no-match")
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:cases,options:[.sortedKeys]))
'''
provenance={'resolver':hashlib.sha256(functions.encode()).hexdigest(),'constants':hashlib.sha256(constants.encode()).hexdigest(),'models':hashlib.sha256(structs.encode()).hexdigest(),'harness':hashlib.sha256(harness.encode()).hexdigest()}
output=root/'android/data/src/androidTest/assets/ios-photo-correlation.json'
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('Swift photo resolver changed; regenerate fixtures on macOS')
    print('PASS: actual Swift photo fixture provenance');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-photos-') as directory:
    work=Path(directory);main=work/'main.swift';main.write_text(harness)
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(main),'-o',str(work/'fixtures')],check=True)
    cases=json.loads(subprocess.run([str(work/'fixtures')],check=True,capture_output=True,text=True).stdout)
    output.write_text(json.dumps({'provenance':provenance,'cases':cases},indent=2)+'\n')
print(f'Generated {len(cases)} actual Swift photo correlation cases')
