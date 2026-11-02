#!/usr/bin/env python3
"""Execute the actual Swift map-date presets in isolated time zones (macOS)."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
source=(root/'IsoMe/Views/MapView.swift').read_text()
algorithm=source[source.index('enum MapDatePreset: CaseIterable, Hashable {'):source.index('\nstruct FilterBarToggle: View {')]
references=['2026-10-31T18:12:34.125Z','2026-03-08T07:30:00.000Z','2026-03-15T06:30:00.000Z',
            '2026-11-08T06:30:00.000Z','2026-10-25T01:30:00.000Z','2026-04-05T15:15:00.000Z',
            '2012-01-05T10:30:00.000Z','1965-07-12T00:00:00.000Z','2026-11-01T00:00:00.000Z','1969-10-05T12:30:00.000Z','1993-08-25T12:30:00.000Z','2011-12-30T12:00:00.000Z']
zones=['UTC','America/New_York','Europe/Berlin','Australia/Lord_Howe','Pacific/Apia','Asia/Kathmandu','America/Havana','Pacific/Chatham','Pacific/Kwajalein']
harness='''import Foundation
NSTimeZone.default=TimeZone(identifier:CommandLine.arguments[1])!
precondition(Calendar.current.identifier == .gregorian)
'''+algorithm+'''
let references=try JSONSerialization.jsonObject(with:Data(CommandLine.arguments[2].utf8)) as! [String]
let parser=ISO8601DateFormatter();parser.formatOptions=[.withInternetDateTime,.withFractionalSeconds]
var result:[[String:Any]]=[]
for value in references {
    let now=parser.date(from:value)!
    for preset in MapDatePreset.allCases {
        let range=preset.range(referenceDate:now)
        // Unix Double conversion can erase nextDown at midnight. Determine the
        // integer database boundaries by comparing real Date values instead.
        var first=Int64(floor(range.lowerBound.timeIntervalSince1970*1000))
        while Date(timeIntervalSince1970:Double(first)/1000)<range.lowerBound {first += 1}
        while Date(timeIntervalSince1970:Double(first-1)/1000)>=range.lowerBound {first -= 1}
        var until=Int64(floor(range.upperBound.timeIntervalSince1970*1000))+1
        while Date(timeIntervalSince1970:Double(until-1)/1000)>range.upperBound {until -= 1}
        while Date(timeIntervalSince1970:Double(until)/1000)<=range.upperBound {until += 1}
        result.append(["preset":String(describing:preset),"reference":value,"zone":CommandLine.arguments[1],
            "fromMillis":first,"untilMillis":until])
    }
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject:result,options:[.sortedKeys]))
'''
output=root/'android/domain/src/test/resources/ios-map-dates.json'
provenance={key:hashlib.sha256(value).hexdigest() for key,value in {
    'algorithm':algorithm.encode(),'harness':harness.encode(),'inputs':json.dumps([references,zones]).encode(),
    'generator':Path(__file__).read_bytes()}.items()}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('Swift map-date rules or inputs changed; regenerate on macOS')
    print('PASS: actual Swift map-date preset provenance');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-map-dates-') as folder:
    work=Path(folder);(work/'main.swift').write_text(harness)
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'main.swift'),'-o',str(work/'fixtures')],check=True)
    cases=[]
    for zone in zones:
        result=subprocess.run([str(work/'fixtures'),zone,json.dumps(references),'-AppleLocale','en_US'],env={**os.environ,'TZ':zone},capture_output=True,text=True,check=True)
        cases.extend(json.loads(result.stdout))
output.write_text(json.dumps({'provenance':provenance,'cases':cases},indent=2)+'\n')
print(f'Generated {len(cases)} actual Swift map-date ranges in {len(zones)} time zones')
