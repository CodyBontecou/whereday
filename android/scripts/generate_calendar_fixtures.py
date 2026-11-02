#!/usr/bin/env python3
"""Execute the current Swift calendar draft constructors with synthetic model inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'IsoMe/Views/CalendarEventEditor.swift'
OUTPUT=ROOT/'android/domain/src/test/resources/ios-calendar-events.json'

def extracted():
    source=SOURCE.read_text()
    return source[source.index('struct CalendarEventDraft:'):source.index('    func makeEvent(')]+'}\n'

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
    hashes={'calendarSourceSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'extractedDraftSha256':hashlib.sha256(extracted().encode()).hexdigest(),'generatorSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    if args.check_source:
        saved=json.loads(OUTPUT.read_text())
        assert saved['provenance']==hashes,'Calendar reference sources changed; review and regenerate with Swift'
        print('Calendar Swift reference provenance verified');return
    start=1793511000123
    cases=[]
    for name,fields in [('named-address',{'name':'  Cafe 日本語  ','address':'  Calle 1  ','end':start+120000}),('name-fallback',{'name':'Cafe','address':'  '}),('coordinate-fallback',{}),('address-title',{'address':'Street'}),('future-open',{'now':start-60000})]:
        case=dict(id=name,kind='visit',start=start,now=start+60000,coordinate=[18.1234567,-66.9876543],fallback='Ubicación desconocida');case.update(fields);cases.append(case)
    for name,fields in [('exact',{'end':start+120000,'name':'  Walk  ','coordinate':[18.1234567,-66.9876543]}),('inferred',{'end':start+30000}),('active',{}),('future-active',{'now':start-1000})]:
        case=dict(id=name,kind='outing',start=start,now=start+60000,fallback='Salida 2');case.update(fields);cases.append(case)
    # The model seams provide already resolved display titles; draft constructor bodies are verbatim.
    harness='''import Foundation
import CoreLocation
struct Visit {let displayName:String;let arrivedAt:Date;let departedAt:Date?;let coordinate:CLLocationCoordinate2D;let address:String?;let locationName:String?}
struct Point {let coordinate:CLLocationCoordinate2D}
struct RecordingSessionSummary {let title:String;let startedAt:Date;let effectiveEndDate:Date;let isActive:Bool;let startPoint:Point?}
'''+extracted()+'''
let cases=try JSONSerialization.jsonObject(with:FileHandle.standardInput.readDataToEndOfFile()) as! [[String:Any]]
var results=[[String:Any]]()
for item in cases {
    func date(_ key:String)->Date? {(item[key] as? NSNumber).map {Date(timeIntervalSince1970:$0.doubleValue/1000)}}
    func normalized(_ text:String?)->String? {guard let value=text?.trimmingCharacters(in:.whitespacesAndNewlines),!value.isEmpty else {return nil};return value}
    let coordinate=(item["coordinate"] as? [Double]).map {CLLocationCoordinate2D(latitude:$0[0],longitude:$0[1])}
    let start=date("start")!,now=date("now")!,end=date("end"),name=item["name"] as? String,address=item["address"] as? String,fallback=item["fallback"] as! String
    let draft:CalendarEventDraft
    if item["kind"] as! String == "visit" {
        draft=CalendarEventDraft(visit:Visit(displayName:normalized(name) ?? address ?? fallback,arrivedAt:start,departedAt:end,coordinate:coordinate!,address:address,locationName:name),now:now)
    } else {
        draft=CalendarEventDraft(session:RecordingSessionSummary(title:normalized(name) ?? fallback,startedAt:start,effectiveEndDate:end ?? now,isActive:end == nil,startPoint:coordinate.map {Point(coordinate:$0)}),now:now)
    }
    results.append(["title":draft.title,"start":Int64((draft.startDate.timeIntervalSince1970*1000).rounded()),"end":Int64((draft.endDate.timeIntervalSince1970*1000).rounded()),"location":draft.location as Any? ?? NSNull()])
}
let data=try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys])
FileHandle.standardOutput.write(data)
'''
    with tempfile.TemporaryDirectory(prefix='whereday-calendar-swift-') as temp:
        path=Path(temp)/'main.swift';path.write_text(harness)
        raw=subprocess.check_output(['swift','-module-cache-path',str(Path(temp)/'modules'),str(path)],input=json.dumps(cases).encode(),timeout=120)
    expected=json.loads(raw)
    OUTPUT.write_text(json.dumps({'scope':'Verbatim current Swift draft constructors with synthetic model seams; no EventKit UI or writes','provenance':hashes,'cases':[dict(input=i,expected=e) for i,e in zip(cases,expected)]},ensure_ascii=False,indent=2)+'\n')
    print('Generated',len(cases),'Swift calendar reference cases')

if __name__=='__main__':main()
