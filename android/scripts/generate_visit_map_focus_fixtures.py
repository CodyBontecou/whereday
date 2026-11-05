#!/usr/bin/env python3
"""Execute the current Swift visit-focus method with model/camera seams and real Foundation calendars."""
import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'IsoMe/Views/MapView.swift'
OUTPUT=ROOT/'android/app/src/test/resources/ios-visit-map-focus.json'
def extracted():
    source=SOURCE.read_text();start=source.index('    private func focusCompanionVisit() {')
    return source[start:source.index('\n    private var canPresentVisitRoute:',start)]
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
    fingerprints={'focusMethodSha256':hashlib.sha256(extracted().encode()).hexdigest(),'generatorSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    if args.check_source:
        saved=json.loads(OUTPUT.read_text());assert saved['provenance']==fingerprints,'Visit-focus method/generator changed; review and regenerate references'
        print('Swift visit-map-focus provenance verified');return
    cases=[{'id':name,'zone':zone,'arrival':arrival,'coordinate':coordinate} for name,zone,arrival,coordinate in [
        ('utc','UTC','2026-11-01T12:34:56.125Z',[18.0,-66.0]),
        ('spring-gap','America/New_York','2026-03-08T07:30:00Z',[40.7,-74.0]),
        ('second-repeated-hour','America/New_York','2026-11-01T06:30:00Z',[40.7,-74.0]),
        ('half-hour-overlap','Australia/Lord_Howe','2026-04-04T15:15:00Z',[-31.5,159.0]),
        ('quarter-hour-zone','Asia/Kathmandu','2026-10-31T20:30:00Z',[27.7,85.3]),
        ('date-line','Pacific/Kiritimati','2026-11-01T12:00:00Z',[1.8,179.999]),
        ('skipped-civil-day','Pacific/Apia','2011-12-30T10:15:00Z',[-13.8,-171.8]),
        ('historical-offset','Europe/Paris','1850-01-01T08:23:45Z',[48.85,2.35]),
    ]]
    harness='''import Foundation
import CoreLocation
struct Visit {let arrivedAt:Date;let coordinate:CLLocationCoordinate2D}
enum MapDatePreset {case today,yesterday}
struct MKCoordinateSpan {let latitudeDelta:Double;let longitudeDelta:Double}
struct MKCoordinateRegion {let center:CLLocationCoordinate2D;let span:MKCoordinateSpan}
enum CameraPosition {case region(MKCoordinateRegion);var value:MKCoordinateRegion {switch self {case .region(let value):return value}}}
final class Model {
    var mapDateRange=Date(timeIntervalSince1970:0)...Date(timeIntervalSince1970:1)
    var points=[ClosedRange<Date>]();var photos=[ClosedRange<Date>]()
    func loadMapLocationPoints(in range:ClosedRange<Date>) {points.append(range)}
    func loadMapPhotoMoments(in range:ClosedRange<Date>) {photos.append(range)}
}
struct Host {
    var focusedVisit:Visit?;var selectedPointID:UUID?=UUID();let viewModel=Model();var activePreset:MapDatePreset?=nil
    var cameraPosition=CameraPosition.region(MKCoordinateRegion(center:CLLocationCoordinate2D(latitude:0,longitude:0),span:MKCoordinateSpan(latitudeDelta:1,longitudeDelta:1)))
'''+extracted().replace('private func focusCompanionVisit()', 'private mutating func focusCompanionVisit()')+'''
    mutating func run() {focusCompanionVisit()}
}
let item=try JSONSerialization.jsonObject(with:FileHandle.standardInput.readDataToEndOfFile()) as! [String:Any]
let parser=ISO8601DateFormatter();parser.formatOptions=[.withInternetDateTime,.withFractionalSeconds]
let raw=item["arrival"] as! String
let date=parser.date(from:raw) ?? ISO8601DateFormatter().date(from:raw)!
precondition(Calendar.current.identifier == .gregorian)
let coordinate=item["coordinate"] as! [Double]
var host=Host(focusedVisit:Visit(arrivedAt:date,coordinate:CLLocationCoordinate2D(latitude:coordinate[0],longitude:coordinate[1])))
host.run()
let range=host.viewModel.mapDateRange
func ms(_ date:Date)->Int64 {Int64((date.timeIntervalSince1970*1000).rounded())}
let region=host.cameraPosition.value
let result:[String:Any]=["from":ms(range.lowerBound),"through":ms(range.upperBound),"coordinate":[region.center.latitude,region.center.longitude],"span":[region.span.latitudeDelta,region.span.longitudeDelta],"pointCalls":host.viewModel.points.count,"photoCalls":host.viewModel.photos.count,"pointSelectionCleared":host.selectedPointID == nil,"calendar":String(describing:Calendar.current.identifier),"zone":Calendar.current.timeZone.identifier]
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:result,options:[.sortedKeys]))
'''
    # SwiftUI's method mutates @State through a nonmutating wrapper. The value-only seam
    # requires a mutating declaration; the method body remains byte-for-byte unchanged.
    with tempfile.TemporaryDirectory(prefix='whereday-visit-focus-') as tmp:
        path=Path(tmp)/'main.swift';path.write_text(harness);binary=Path(tmp)/'focus'
        subprocess.run(['swiftc','-module-cache-path',str(Path(tmp)/'modules'),str(path),'-o',str(binary)],check=True,timeout=120)
        results=[]
        for case in cases:
            env=dict(os.environ,TZ=case['zone'],LANG='en_US.UTF-8',LC_ALL='en_US.UTF-8')
            value=json.loads(subprocess.check_output([str(binary),'-AppleLocale','en_US_POSIX'],input=json.dumps(case).encode(),env=env,timeout=30))
            assert value['pointCalls']==1 and value['photoCalls']==1 and value['pointSelectionCleared']
            results.append({'input':case,'expected':value})
    OUTPUT.write_text(json.dumps({'scope':'Verbatim Swift focus method body with model/camera seams; real Foundation Gregorian calendar; no MapKit rendering, UI or persistence','provenance':fingerprints,'sourceContextSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'cases':results},ensure_ascii=False,indent=2)+'\n')
    print('Generated',len(results),'Swift visit-focus references')
if __name__=='__main__':main()
