#!/usr/bin/env python3
"""Run the current Swift manual-visit URL parser against synthetic links."""
import argparse,hashlib,json,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'IsoMe/Services/ManualVisitRoute.swift'
OUTPUT=ROOT/'android/domain/src/test/resources/ios-visit-links.json'
def fragment():
    text=SOURCE.read_text();return text[text.index('enum ManualVisitRoute:'):text.index('@MainActor @Observable')]
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--check-source',action='store_true');args=p.parse_args()
    provenance={'sourceSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'parserSha256':hashlib.sha256(fragment().encode()).hexdigest(),'generatorSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    if args.check_source:
        assert json.loads(OUTPUT.read_text())['provenance']==provenance,'Visit link parser changed; review and regenerate'
        print('Swift visit-link reference provenance verified');return
    urls=['isome://check-in','whereday://log-visit/','isome://check-in?name=Cafe%20日本語&latitude=18.1&longitude=-66.2','isome://check-in?name=A+B','isome://check-in?arrival=2026-10-31T12:30:00Z','isome://log-visit?arrival=2026-10-31T12:30:00Z&departure=2026-10-31T13:30:00Z','whereday://visit/123E4567-E89B-12D3-A456-426614174000','isome://visit/123e4567-e89b-12d3-a456-426614174000','ISOME://check-in','isome://CHECK-IN','isome://%63heck-in','https://check-in','isome://user@check-in','isome://check-in:80','isome://check-in#','isome://check-in/path','isome://visit/123e4567-e89b-12d3-a456-426614174000?','isome://visit/not-a-uuid','isome://visit/123e4567e89b12d3a456426614174000','isome://check-in?name','isome://check-in?name=','isome://check-in?name=%20%20','isome://check-in?name=%C2%A0Cafe%C2%A0','isome://check-in?name=A&name=B','isome://check-in?name=A&%6eame=B','isome://check-in?unknown=A','isome://check-in?latitude=1','isome://check-in?latitude=90&longitude=180','isome://check-in?latitude=-90&longitude=-180','isome://check-in?latitude=91&longitude=0','isome://check-in?latitude=nan&longitude=0','isome://check-in?latitude=Infinity&longitude=0','isome://check-in?latitude=%201&longitude=0','isome://check-in?latitude=0x10&longitude=0','isome://check-in?latitude=0x1p0&longitude=0','isome://check-in?latitude=1e1&longitude=-0.5','isome://check-in?latitude=+1&longitude=0','isome://check-in?arrival=2026-11-02T00:00:00Z','isome://check-in?arrival=2026-10-31T12:30:00.123Z','isome://check-in?arrival=2026-10-31T12:30:00+01:00','isome://check-in?arrival=2026-10-31T12:30:00+0100','isome://check-in?arrival=20261031T123000Z','isome://check-in?arrival=2026-10-31T12:30Z','isome://check-in?arrival=2026-02-30T12:30:00Z','isome://log-visit?arrival=2026-10-31T12:30:00Z','isome://log-visit?departure=2026-10-31T13:30:00Z','isome://log-visit?arrival=2026-10-31T13:30:00Z&departure=2026-10-31T12:30:00Z','isome://log-visit?arrival=2026-10-31T12:30:00Z&departure=2026-10-31T12:30:00Z','isome://check-in?departure=2026-10-31T12:30:00Z','isome://check-in?name=%2520','isome://check-in?name=A%26B%3DC','isome://check-in?name=%FF','isome://check-in?name=%','isome://check-in?latitude=&longitude=0','isome://check-in?latitude=1.&longitude=0','isome://visit/123e4567-e89b-12d3-a456-426614174000/']
    urls += ['isome://check-in?arrival='+value for value in ['2026-13-01T00:00:00Z','2026-01-32T00:00:00Z','2026-01-00T00:00:00Z','2026-01-01T24:00:00Z','2026-01-01T23:59:60Z','2026-01-01T23:60:00Z','2026-10-31T12:30:00+01','2026-10-31t12:30:00z','2026-10-31T12:30:00Zextra','1850-01-01T00:00:00Z','1500-03-01T00:00:00Z','0001-01-01T00:00:00Z','0000-01-01T00:00:00Z']]
    urls += ['isome://check-in?latitude='+value+'&longitude=0' for value in ['1d','1f','0X10','0x1.8','1_0','.5','%2B1']]
    harness='import Foundation\n'+fragment()+'''
let inputs=try JSONSerialization.jsonObject(with:FileHandle.standardInput.readDataToEndOfFile()) as! [String]
let now=ISO8601DateFormatter().date(from:"2026-11-01T00:00:00Z")!
var rows=[[String:Any]]()
for raw in inputs {
 var row:[String:Any]=["input":raw]
 if let url=URL(string:raw) {
  row["url"]=url.absoluteString
  do {
   let route=try ManualVisitRoute.parse(url,now:now);row["valid"]=true
   switch route {
   case .visit(let id):row["kind"]="visit";row["id"]=id.uuidString.lowercased()
   case .checkIn(let name,let lat,let lon,let arrival):row["kind"]="checkIn";row["name"]=name as Any? ?? NSNull();row["latitude"]=lat as Any? ?? NSNull();row["longitude"]=lon as Any? ?? NSNull();row["arrival"]=arrival.map {Int64(($0.timeIntervalSince1970*1000).rounded())} as Any? ?? NSNull()
   case .pastVisit(let name,let lat,let lon,let arrival,let departure):row["kind"]="pastVisit";row["name"]=name as Any? ?? NSNull();row["latitude"]=lat as Any? ?? NSNull();row["longitude"]=lon as Any? ?? NSNull();row["arrival"]=arrival.map {Int64(($0.timeIntervalSince1970*1000).rounded())} as Any? ?? NSNull();row["departure"]=departure.map {Int64(($0.timeIntervalSince1970*1000).rounded())} as Any? ?? NSNull()
   }
  } catch {row["valid"]=false}
 } else {row["url"]=raw;row["valid"]=false}
 rows.append(row)
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:rows,options:[.sortedKeys]))
'''
    with tempfile.TemporaryDirectory(prefix='whereday-visit-link-') as temp:
        f=Path(temp)/'main.swift';f.write_text(harness)
        raw=subprocess.check_output(['swift','-module-cache-path',str(Path(temp)/'modules'),str(f)],input=json.dumps(urls).encode(),timeout=120)
    value={'scope':'Verbatim current Swift route parser; normalized URL inputs, no routing/UI or writes','provenance':provenance,'now':'2026-11-01T00:00:00Z','cases':json.loads(raw)}
    OUTPUT.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');print('Generated Swift visit-link cases',len(value['cases']))
if __name__=='__main__':main()
