#!/usr/bin/env python3
"""Execute current Swift Google readers and timeline parse validation with synthetic JSON."""
import argparse,hashlib,json,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SOURCES=[ROOT/'IsoMe/Utilities'/name for name in ('GoogleTimelineImportService.swift','TimelineImportService.swift','ImportService.swift')]
OUTPUT=ROOT/'android/export/src/test/resources/ios-google-timeline.json'
def extracted():
    google,timeline,imports=[p.read_text() for p in SOURCES]
    models=imports[imports.index('struct ImportedVisit:'):imports.index('extension ImportedVisit')]+imports[imports.index('struct ImportedLocationPoint:'):imports.index('struct ImportResult')]
    prelude=timeline[timeline.index('enum ImportSourceFormat:'):timeline.index('struct ImportOptions:')]
    parse=timeline[timeline.index('    static func parse(data:'):timeline.index('    func commit(')]
    return models+prelude+google+'\nenum TimelineParser {\n'+parse+'}\n'
def cases():
    out=[]
    def add(name,root):out.append({'id':name,'json':json.dumps(root,ensure_ascii=False,separators=(',',':'))})
    def point(**extra):return dict(latitudeE7=377000000,longitudeE7=-1224000000,timestampMs='1577836800000',accuracy=8,**extra)
    good=point()
    add('raw-basic',{'locations':[good,{'latitudeE7':0,'longitudeE7':0,'timestamp':'2020-01-01T01:00:00.123Z','altitude':12,'velocity':4.5}]})
    for key,value in [('latitudeE7',True),('latitudeE7',910000000),('longitudeE7',1810000000),('timestampMs','NaN'),('accuracy',-5),('accuracy',None),('accuracy',True),('altitude','NaN'),('velocity',-1),('latitudeE7','0x10'),('latitudeE7','1d')]:
        row=dict(good);row[key]=value;add('raw-'+key+'-'+str(value),{'locations':[good,row]})
    add('raw-aliases-and-fallback',{'locations':[{'latE7':'377000000','lngE7':'-1224000000','timestampMs':'bad','timestamp':'2020-01-01T01:00:00Z'},{'latitudeE7':None,'latE7':377000000,'longitudeE7':-1224000000,'latLng':'geo:18,-66','timestampMs':'0'}]})
    add('raw-unknown-and-nonobject',{'locations':[good,{},None,3,'text',[]]})
    location={'latitudeE7':377000000,'longitudeE7':-1224000000,'name':'Cafe 日本語','address':'1 Test Street'}
    duration={'startTimestampMs':'1577836800000','endTimestamp':'2020-01-01T01:00:00Z'}
    add('semantic-visit',{'timelineObjects':[{'placeVisit':{'location':location,'duration':duration}},{}]})
    activity={'duration':duration,'startLocation':location,'endLocation':{'latLng':'geo:37.701,-122.401'},'simplifiedRawPath':{'points':[{'latE7':377001000,'lngE7':-1224001000,'timestampMs':'1577836801000'},{'latLng':'geo:37.7,-122.4'}]},'waypointPath':{'waypoints':[{'latLng':'geo:37.71,-122.41','durationMinutesOffsetFromStartTime':2}]}}
    add('semantic-paths',{'timelineObjects':[{'activitySegment':activity}]})
    tied={'duration':duration,'waypointPath':{'waypoints':[{'latLng':'geo:18.000001,-66','timestampMs':'1577836801000'}]},'simplifiedRawPath':{'points':[{'latLng':'geo:18,-66','timestampMs':'1577836801000'}]}}
    add('semantic-path-order-with-ties',{'timelineObjects':[{'activitySegment':tied}]})
    add('semantic-visit-precedence',{'timelineObjects':[{'activitySegment':activity,'placeVisit':{'location':location,'duration':duration}}]})
    add('semantic-invalid-interval',{'timelineObjects':[{'placeVisit':{'location':location,'duration':duration}},{'activitySegment':dict(activity,duration={'startTimestamp':'2020-01-02T00:00:00Z','endTimestamp':'2020-01-01T00:00:00Z'})}]})
    segment={'timelinePath':[{'point':'geo:37.7,-122.4','durationMinutesOffsetFromStartTime':'5'},{'point':'37.701°, -122.401°','durationMinutesOffsetFromStartTime':15},{'point':'geo:37.702,-122.402','time':'2020-01-01T00:30:00Z'},{'point':'geo:37.703,-122.403','durationMinutesOffsetFromStartTime':65},{'point':'geo:37,-122'}],'visit':{'topCandidate':{'placeLocation':{'latLng':'37.7°, -122.4°'},'placeName':'Lunch'}},'activity':{'start':{'latLng':'geo:37.7,-122.4'},'end':{'latLng':'geo:37.701,-122.401'}},'startTime':'2020-01-01T02:00:00+02:00','endTime':'2020-01-01T03:00:00+02:00'}
    add('device-object',{'semanticSegments':[segment]});add('device-array',[segment])
    add('device-string-location',[{'startTime':'2020-01-01T00:00:00Z','endTime':'2020-01-01T01:00:00Z','visit':{'topCandidate':{'placeLocation':'geo:37.7,-122.4'}}}])
    add('device-unsupported',[segment,{'startTime':'2020-01-01T00:00:00Z','endTime':'2020-01-01T01:00:00Z','unknown':True}])
    add('device-reversed',[segment,dict(segment,endTime='2019-12-31T23:00:00Z')])
    add('root-priority',{'semanticSegments':[segment],'timelineObjects':[{'activitySegment':activity}],'locations':[good],'visits':[{}]})
    add('semantic-priority',{'semanticSegments':[segment],'timelineObjects':[{'activitySegment':activity}]})
    for value in ['2020-01-01T00:00:00Z','2020-01-01T00:00:00.123456789Z','2020-01-01T00:00:00+02:00','2020-01-01T00:00:00+02','2020-01-01T00:00:00Ztail','2020-01-01t00:00:00z','2020-02-30T00:00:00Z','2020-01-01T24:00:00Z','2016-12-31T23:59:60Z','0001-01-01T00:00:00Z','4001-01-01T00:00:00Z','4002-01-01T00:00:00Z']:
        add('date-'+value,{'locations':[good,{'latLng':'geo:18,-66','timestamp':value}]})
    for value in ['-62135769600000','64092211200000','-1','0','123.456','1577836800123.456','1e300']:
        add('milliseconds-'+value,{'locations':[good,{'latLng':'geo:18,-66','timestampMs':value}]})
    for name,root in [('empty',{'locations':[]}),('bad-array',{'locations':'bad'}),('empty-device',[]),('unsupported',{'unrelated':[]})]:add(name,root)
    out.append({'id':'malformed-known-unicode','json':'{"locations":[{"latLng":"geo:18,-66","timestampMs":0,"name":"\\ud800"}]}'})
    return out

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
    body=extracted();provenance={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCES}
    provenance.update(extractedSha256=hashlib.sha256(body.encode()).hexdigest(),generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    if args.check_source:
        assert json.loads(OUTPUT.read_text())['provenance']==provenance,'Google Timeline reference source changed; review and regenerate with Swift'
        print('Swift Google Timeline provenance verified');return
    harness='''import Foundation
import CoreFoundation
enum ImportError: Error {case unsupportedFormat}
enum ExportFormat {case json,csv,markdown}
struct LegacyRecords {var visits=[ImportedVisit]();var points=[ImportedLocationPoint]()}
enum ImportService {static func importFile(data:Data,format:ExportFormat) throws -> LegacyRecords {throw ImportError.unsupportedFormat}}
'''+body+'''
let cases=try JSONSerialization.jsonObject(with:FileHandle.standardInput.readDataToEndOfFile()) as! [[String:Any]]
var results=[[String:Any]]()
func nullable(_ x:Any?)->Any {x ?? NSNull()}
func millis(_ x:Date)->Int64 {Int64((x.timeIntervalSince1970*1000).rounded())}
for item in cases {
    do {
        let payload=try TimelineParser.parse(data:(item["json"] as! String).data(using:.utf8)!,fileName:"history.json")
        let visits=payload.visits.map {v -> [String:Any] in ["latitude":v.latitude,"longitude":v.longitude,"arrival":millis(v.arrivedAt),"departure":nullable(v.departedAt.map(millis)),"name":nullable(v.locationName),"address":nullable(v.address),"source":nullable(v.sourceRaw),"placeSource":nullable(v.placeSourceRaw)]}
        let points=payload.points.map {p -> [String:Any] in ["latitude":p.latitude,"longitude":p.longitude,"time":millis(p.timestamp),"accuracy":p.horizontalAccuracy,"altitude":nullable(p.altitude),"speed":nullable(p.speed)]}
        results.append(["source":payload.format.rawValue,"visits":visits,"points":points,"invalid":payload.invalidRecordCount,"unsupported":payload.unsupportedRecordCount])
    } catch {results.append(["error":true])}
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys]))
'''
    inputs=cases()
    with tempfile.TemporaryDirectory(prefix='whereday-google-swift-') as temp:
        path=Path(temp)/'main.swift';path.write_text(harness)
        raw=subprocess.check_output(['swift','-module-cache-path',str(Path(temp)/'modules'),str(path)],input=json.dumps(inputs).encode(),timeout=120)
    expected=json.loads(raw);OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    OUTPUT.write_text(json.dumps({'scope':'Verbatim Swift Google parser and timeline parse validation; legacy Whereday parser stubbed and unused; no storage/UI/network acceptance','provenance':provenance,'cases':[dict(input=i,expected=e) for i,e in zip(inputs,expected)]},indent=2,ensure_ascii=False)+'\n')
    print('Generated',len(inputs),'Swift Google Timeline references')
if __name__=='__main__':main()
