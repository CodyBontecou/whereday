#!/usr/bin/env python3
"""Execute selected current Swift timeline rules with value-only model seams."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'IsoMe/Views/OutingsView.swift'
OUTPUT=ROOT/'android/app/src/test/resources/ios-timeline-navigation.json'

def fragments():
    source=SOURCE.read_text()
    def between(start,end):return source[source.index(start):source.index(end,source.index(start))]
    return dict(latest=between('    private var latestActivityDate:', '    private var hasAnyTimelineData:'),
                initial=between('    private func selectInitialTimelineDateIfNeeded()', '    private func moveSelectedDay'),
                label=between('    private var emptyStateActionLabel:', '    private func performEmptyStateAction()'),
                action=between('    private func performEmptyStateAction()', '    private var emptyStateMessage:'),
                ordering=between('    private var timelineEvents:', '    private var inferenceConfiguration:'),
                priority=between('    var sortPriority: Int {', '\n}\n\nprivate struct OutingOverviewMetric:'))

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
    parts=fragments();provenance={name:hashlib.sha256(value.encode()).hexdigest() for name,value in parts.items()}
    provenance['generator']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    if args.check_source:
        assert json.loads(OUTPUT.read_text())['provenance']==provenance,'Swift timeline rules changed; inspect and regenerate references'
        print('Selected current Swift timeline navigation provenance verified');return
    harness='''import Foundation
struct Record {let id:String;let startedAt:Date;let endedAt:Date?;var arrivedAt:Date {startedAt};var timestamp:Date {startedAt}}
final class Model {var allRecordingSessions=[Record]();var allVisits=[Record]();var locationPoints=[Record]()}
enum TimelineEvent {case visit(Record),session(Record)
 var startDate:Date {switch self {case .visit(let v),.session(let v):return v.startedAt}}
 var id:String {switch self {case .visit(let v),.session(let v):return v.id}}
'''+parts['priority']+'''
}
final class Host {
 let viewModel=Model();var selectedDate=Date();var hasAutoSelectedInitialTimelineDate=false
 var sessions=[Record]();var dayVisits=[Record]()
'''+''.join(parts[k] for k in ['latest','ordering','initial','label','action'])+'''
 func run()->[String:Any] {
  let original=selectedDate;let label=emptyStateActionLabel
  performEmptyStateAction();let target=selectedDate;selectedDate=original
  selectInitialTimelineDateIfNeeded()
  let format=DateFormatter();format.calendar=Calendar.current;format.timeZone=Calendar.current.timeZone;format.locale=Locale(identifier:"en_US_POSIX");format.dateFormat="yyyy-MM-dd"
  return ["today":format.string(from:Date()),"selected":format.string(from:original),"initial":format.string(from:selectedDate),"handled":hasAutoSelectedInitialTimelineDate,"emptyLabel":label as Any? ?? NSNull(),"emptyTarget":label == nil ? NSNull() : format.string(from:target),"order":timelineEvents.map(\\.id),"latestMillis":latestActivityDate.map {Int64(($0.timeIntervalSince1970*1000).rounded())} as Any? ?? NSNull()]
 }
}
let raw=try JSONSerialization.jsonObject(with:FileHandle.standardInput.readDataToEndOfFile()) as! [String:Any]
let calendar=Calendar.current;precondition(calendar.identifier == .gregorian)
let today=calendar.startOfDay(for:Date())
func date(_ offset:Int)->Date {calendar.date(byAdding:.day,value:offset,to:today)!.addingTimeInterval(9*3600)}
func records(_ key:String)->[Record] {(raw[key] as? [[String:Any]] ?? []).map {r in Record(id:r["id"] as! String,startedAt:date(r["day"] as! Int),endedAt:(r["end"] as? Int).map(date))}}
let host=Host();host.selectedDate=date(raw["selected"] as! Int);host.hasAutoSelectedInitialTimelineDate=raw["handled"] as? Bool ?? false
host.viewModel.allRecordingSessions=records("sessions");host.viewModel.allVisits=records("visits");host.viewModel.locationPoints=records("points")
host.sessions=records("displayedSessions");host.dayVisits=records("displayedVisits")
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:host.run(),options:[.sortedKeys]))
'''
    cases=[dict(id='older-visit',selected=0,visits=[dict(id='visit',day=-3)]),
           dict(id='today-has-events',selected=0,visits=[dict(id='old',day=-3)],displayedVisits=[dict(id='today',day=0)]),
           dict(id='no-history',selected=0),dict(id='explicit-past',selected=-7,handled=True,visits=[dict(id='old',day=-3)]),
           dict(id='session-end-wins',selected=0,sessions=[dict(id='outing',day=-7,end=-2)],visits=[dict(id='visit',day=-3)]),
           dict(id='point-wins',selected=0,sessions=[dict(id='outing',day=-7,end=-3)],points=[dict(id='point',day=-1)]),
           dict(id='latest-is-selected',selected=-3,handled=True,visits=[dict(id='visit',day=-3)]),
           dict(id='future-history',selected=0,points=[dict(id='point',day=1)]),
           dict(id='same-day-no-jump',selected=0,points=[dict(id='point',day=0)]),
           dict(id='mixed-order',selected=0,handled=True,displayedSessions=[dict(id='late-outing',day=-1),dict(id='tied-outing',day=-3)],displayedVisits=[dict(id='tied-visit',day=-3),dict(id='first-visit',day=-4)])]
    results=[]
    with tempfile.TemporaryDirectory(prefix='whereday-timeline-reference-') as folder:
        source=Path(folder)/'main.swift';source.write_text(harness);binary=Path(folder)/'timeline'
        subprocess.run(['swiftc','-module-cache-path',str(Path(folder)/'modules'),str(source),'-o',str(binary)],check=True)
        for zone in ['UTC','America/New_York','Pacific/Kiritimati']:
            for case in cases:
                expected=json.loads(subprocess.check_output([str(binary),'-AppleLocale','en_US_POSIX'],input=json.dumps(case).encode(),env=dict(os.environ,TZ=zone)))
                results.append(dict(input=case,zone=zone,expected=expected))
    OUTPUT.write_text(json.dumps(dict(scope='Verbatim selected Swift timeline properties/methods with value-only model/event seams; Foundation Gregorian calendar, not SwiftUI execution or full timeline membership',provenance=provenance,sourceContextSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),cases=results),indent=2)+'\n')
    print(f'Executed {len(results)} selected Swift timeline references')

if __name__=='__main__':main()
