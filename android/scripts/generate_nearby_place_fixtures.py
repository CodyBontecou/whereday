#!/usr/bin/env python3
"""Execute the actual iOS nearby normalization/ranking methods on synthetic MapKit items."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile
root=Path(__file__).resolve().parents[2]
source=root/'IsoMe/Services/GeocodingService.swift'
formatter=root/'IsoMe/Services/PlaceSearchService.swift'
harness=root/'android/scripts/fixtures/NearbyPlaceFixtures.swift'
output=root/'android/domain/src/test/resources/ios-nearby-places.json'
inputs=[source,formatter,harness,Path(__file__).resolve()]
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('Nearby-place reference sources changed; regenerate on macOS')
    print('PASS: nearby-place fixture source hashes match');raise SystemExit(0)
text=source.read_text();format_text=formatter.read_text()
def block(text,marker):
    start=text.index(marker);brace=text.index('{',start);depth=1;end=brace+1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]
selected='import Foundation\nimport CoreLocation\nimport MapKit\n'
selected+=block(text,'struct NearbyPlaceSuggestion: Identifiable {')+'\n'
selected+=block(format_text,'enum MapItemPlaceFormatter {')+'\n'
selected+='enum Reference {\n'+block(text,'    static func makeSuggestions(')+'\n'+block(text,'    private static func suggestionID(')+'\n}\n'
with tempfile.TemporaryDirectory(prefix='whereday-nearby-') as directory:
    work=Path(directory);(work/'Reference.swift').write_text(selected);(work/'main.swift').write_bytes(harness.read_bytes())
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(work/'Reference.swift'),str(work/'main.swift'),'-o',str(work/'fixtures')],check=True)
    cases=json.loads(subprocess.run([str(work/'fixtures'),'-AppleLocale','en_US'],capture_output=True,text=True,check=True).stdout)
    output.write_text(json.dumps({'provenance':provenance,**cases},ensure_ascii=False,indent=2)+'\n')
print(f'Generated {len(cases["cases"])} actual Swift nearby-place cases')
