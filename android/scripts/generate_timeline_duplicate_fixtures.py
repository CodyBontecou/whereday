#!/usr/bin/env python3
"""Execute the current Swift TimelineImportService duplicate predicates and bucket rules."""
import argparse,hashlib,json,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];SOURCE=ROOT/'IsoMe/Utilities/TimelineImportService.swift';OUTPUT=ROOT/'android/domain/src/test/resources/ios-timeline-duplicates.json'
def extract():return SOURCE.read_text()[SOURCE.read_text().index('private struct VisitMatch {'):]
def main():
 p=argparse.ArgumentParser();p.add_argument('--check-source',action='store_true');args=p.parse_args();body=extract();provenance={'sourceSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'extractedSha256':hashlib.sha256(body.encode()).hexdigest(),'generatorSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
 if args.check_source:
  assert json.loads(OUTPUT.read_text())['provenance']==provenance;print('Swift timeline duplicate provenance verified');return
 cases=[]
 for kind in ['point','visit']:
  for time in [-1.5,-1,-.5,-.499,0,.499,.5,1,1.5,59.999,60,60.001,61]:
   cases.append(dict(kind=kind,timeA=0,timeB=time,latA=18,lonA=-66,latB=18,lonB=-66,endA=3600,endB=3600))
  for latitude in [18.000089,18.000091,18.00090,18.00091,18.01]:
   cases.append(dict(kind=kind,timeA=1577836800,timeB=1577836800,latA=18,lonA=-66,latB=latitude,lonB=-66,endA=1577840400,endB=1577840400))
  for end in [None,3299.999,3300,3600,3900,3900.001]:
   cases.append(dict(kind=kind,timeA=0,timeB=0,latA=18,lonA=-66,latB=18,lonB=-66,endA=3600,endB=end))
  cases.append(dict(kind=kind,timeA=-.5,timeB=-.5,latA=0,lonA=179.99999,latB=0,lonB=-179.99999,endA=None,endB=None))
 harness='''import Foundation
import CoreLocation
'''+body+'''
let rows=try JSONSerialization.jsonObject(with:FileHandle.standardInput.readDataToEndOfFile()) as! [[String:Any]]
var results=[[String:Any]]()
for r in rows {
 func n(_ k:String)->Double {(r[k] as! NSNumber).doubleValue}
 func d(_ k:String)->Date? {(r[k] as? NSNumber).map {Date(timeIntervalSince1970:$0.doubleValue)}}
 if r["kind"] as! String == "visit" {
  let a=VisitMatch(latitude:n("latA"),longitude:n("lonA"),arrival:d("timeA")!,departure:d("endA"))
  let b=VisitMatch(latitude:n("latB"),longitude:n("lonB"),arrival:d("timeB")!,departure:d("endB"))
  results.append(["bucketA":a.bucket,"bucketB":b.bucket,"matches":abs(a.bucket-b.bucket)<=1 && a.matches(b)])
 } else {
  let a=PointMatch(latitude:n("latA"),longitude:n("lonA"),time:d("timeA")!)
  let b=PointMatch(latitude:n("latB"),longitude:n("lonB"),time:d("timeB")!)
  results.append(["bucketA":a.bucket,"bucketB":b.bucket,"matches":a.bucket==b.bucket && a.matches(b)])
 }
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:results,options:[.sortedKeys]))
'''
 with tempfile.TemporaryDirectory(prefix='whereday-duplicate-swift-') as tmp:
  path=Path(tmp)/'main.swift';path.write_text(harness)
  raw=subprocess.check_output(['swift','-module-cache-path',str(Path(tmp)/'modules'),str(path)],input=json.dumps(cases).encode(),timeout=120)
 OUTPUT.write_text(json.dumps({'scope':'Verbatim Swift duplicate bucket/predicate rules with CoreLocation distance; selected boundary cases only','provenance':provenance,'cases':[dict(input=i,expected=e) for i,e in zip(cases,json.loads(raw))]},indent=2)+'\n');print('Generated',len(cases),'Swift duplicate references')
if __name__=='__main__':main()
