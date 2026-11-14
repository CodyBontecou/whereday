#!/usr/bin/env python3
"""Execute current Swift outing builder, summaries and Timeline day/distance properties."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[2]
OUTPUT=ROOT/'android/domain/src/test/resources/ios-timeline-statistics.json'
INPUT=ROOT/'android/scripts/fixtures/timeline-statistics-inputs.json'
NATIVE=ROOT/'android/data/src/androidTest/assets/ios-timeline-statistics.json'
EXPORT=ROOT/'android/export/src/test/resources/ios-timeline-statistics.json'
PLATFORM=ROOT/'android/platform/src/androidTest/assets/ios-timeline-statistics.json'
HARNESS=ROOT/'android/scripts/fixtures/TimelineStatisticsFixtures.swift'
def declaration(source,marker):
    start=source.index(marker);brace=source.index('{',start);end=brace+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
def fragments():
    s=(ROOT/'IsoMe/Models/RecordingSession.swift').read_text()
    p=(ROOT/'IsoMe/Models/LocationPoint.swift').read_text()
    v=(ROOT/'IsoMe/Views/OutingsView.swift').read_text()
    model=(ROOT/'IsoMe/ViewModels/LocationViewModel.swift').read_text()
    map_view=(ROOT/'IsoMe/Views/MapView.swift').read_text()
    export=(ROOT/'IsoMe/Utilities/ExportService.swift').read_text()
    renderers='struct ExportService {\n'+'\n'.join(declaration(export,marker)+('()' if marker=='private static let iso8601Formatter:' else '') for marker in ['struct ExportableOuting:', 'struct OutingsExportData:', 'private static let iso8601Formatter:', 'static func exportOutingsToJSON(', 'static func exportOutingsToCSV(', 'private static func exportableOuting(', 'private static func outingVisits(', 'private static func outingNotes(', 'private static func escapeCSVField('])+'\n}\n'
    return dict(renderers=renderers,request=declaration(model,'struct MapSessionFocusRequest:'),
        focus=declaration(model,'    func focusMap(on session:'),
        applyFocus=declaration(map_view,'    private func applyMapFocusRequestIfNeeded()'),
        record=declaration(s,'final class RecordingSession'),
        point=p[p.index('final class LocationPoint'):p.index('    var accessibilityTimestamp:')]+'\n}\n',
        summary=s[s.index('struct RecordingSessionSummary:'):s.index('    var formattedTimeRange:')]+'\n}\n',
        gap=declaration(s,'enum RecordingSessionGapPreset:'),
        duration=declaration(s,'enum RecordingSessionMinimumDurationPreset:'),
        minimum=declaration(s,'enum RecordingSessionMinimumPointCountPreset:'),
        configuration=declaration(s,'struct RecordingSessionInferenceConfiguration:'),
        builder=declaration(s,'enum RecordingSessionBuilder {'),
        day=declaration(v,'    private var selectedDayRange:'),
        selection=declaration(v,'    private var sessions:'),
        total=declaration(v,'    private var totalDistance:'),
        overlap=declaration(v,'    private func rangesOverlap('),
        visits=declaration((ROOT/'IsoMe/ViewModels/LocationViewModel.swift').read_text(),'    func visitsOverlappingDateRange('))
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
    parts=fragments();provenance={k:hashlib.sha256(v.encode()).hexdigest() for k,v in parts.items()}
    provenance.update({str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [INPUT,HARNESS,Path(__file__)]})
    if args.check_source:
        assert json.loads(OUTPUT.read_text())['provenance']==provenance,'Timeline statistics reference sources changed; inspect and regenerate'
        assert NATIVE.read_bytes()==OUTPUT.read_bytes(),'Native timeline reference asset is stale'
        assert EXPORT.read_bytes()==OUTPUT.read_bytes(),'Export timeline reference resource is stale'
        assert PLATFORM.read_bytes()==OUTPUT.read_bytes(),'Platform timeline reference asset is stale'
        print('Current Swift timeline statistics provenance verified');return
    selected='import Foundation\nimport CoreLocation\n'+''.join(parts[k]+'\n' for k in ['record','point','summary','gap','duration','minimum','configuration','builder'])
    selected+=parts['request']+'\nfinal class FocusModel {var mapDateRange:ClosedRange<Date>?;var mapFocusRequest:MapSessionFocusRequest?;var pointRange:ClosedRange<Date>?;var photoRange:ClosedRange<Date>?\nfunc loadMapLocationPoints(in range:ClosedRange<Date>){pointRange=range}\nfunc loadMapPhotoMoments(in range:ClosedRange<Date>){photoRange=range}\n'+parts['focus']+'\n}\n'
    selected+='final class FocusMap {let viewModel:FocusModel;var appliedMapFocusRequestID:UUID?;var activePreset:String?="Today";var fits=0\ninit(_ model:FocusModel){viewModel=model}\nfunc syncPhotoMomentsForCurrentRangeIfAuthorized(){}\nfunc validateRouteReplayState(){}\nfunc fitMapToContent(){fits+=1}\n'+parts['applyFocus']+'\nfunc apply()->Bool {applyMapFocusRequestIfNeeded()}\n}\n'
    selected+='struct Visit {let id:String;let arrivedAt:Date;let departedAt:Date?}\n'+parts['renderers']+'final class Host {let allSessions:[RecordingSessionSummary];let selectedDate:Date;var allVisits=[Visit]()\ninit(_ all:[RecordingSessionSummary],_ date:Date){allSessions=all;selectedDate=date}\n'
    selected+='\n'.join(parts[k] for k in ['day','selection','total','overlap','visits'])
    selected+='\nvar selected:[RecordingSessionSummary]{sessions}\nvar distance:Double{totalDistance}\nvar bounds:ClosedRange<Date>{selectedDayRange}\n}\n'
    with tempfile.TemporaryDirectory(prefix='whereday-timeline-statistics-') as d:
        work=Path(d);(work/'Selected.swift').write_text(selected);(work/'main.swift').write_bytes(HARNESS.read_bytes())
        binary=work/'fixtures'
        subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'Selected.swift'),str(work/'main.swift'),'-o',str(binary)],check=True)
        results=[]
        for case in json.loads(INPUT.read_text()):
            env=os.environ.copy();env['TZ']=case['zone']
            result=subprocess.run([str(binary)],input=json.dumps(case),text=True,capture_output=True,env=env,check=True)
            results.append(dict(input=case,expected=json.loads(result.stdout)))
        OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(dict(provenance=provenance,cases=results),indent=2)+'\n')
    NATIVE.parent.mkdir(parents=True,exist_ok=True);NATIVE.write_bytes(OUTPUT.read_bytes());EXPORT.write_bytes(OUTPUT.read_bytes());PLATFORM.write_bytes(OUTPUT.read_bytes())
    print(f'Generated {len(results)} executed current Swift builder/day/statistics references')
if __name__=='__main__':main()
