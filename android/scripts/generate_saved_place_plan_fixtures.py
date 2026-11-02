#!/usr/bin/env python3
"""Compile actual iOS saved-place duplicate planning and capture ellipsoidal distances."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
management=root/'IsoMe/Utilities/SavedPlaceManagement.swift'
csv=root/'IsoMe/Utilities/SavedPlaceImportService.swift'
model=root/'IsoMe/Models/Visit.swift'
harness=root/'android/scripts/fixtures/SavedPlacePlanFixtures.swift'
inputs=root/'android/scripts/fixtures/saved-place-plan-inputs.json'
output=root/'android/domain/src/test/resources/ios-saved-place-plans.json'
android_output=root/'android/data/src/androidTest/assets/ios-saved-place-plans.json'
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [management,csv,model,harness,inputs]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance: raise SystemExit('Saved-place plan sources changed; regenerate on macOS')
    if android_output.read_bytes()!=output.read_bytes(): raise SystemExit('Android test asset differs from the generated saved-place fixture')
    print('PASS: saved-place plan fixture source hashes match');raise SystemExit(0)
original=model.read_text();start=original.index('final class SavedPlace');end=original.index('\nextension Visit',start)
selected='import Foundation\nimport CoreLocation\n'+original[start:end]
with tempfile.TemporaryDirectory(prefix='whereday-place-plans-') as directory:
    work=Path(directory);source=work/'Model.swift';main=work/'main.swift';binary=work/'fixtures'
    source.write_text(selected);main.write_bytes(harness.read_bytes())
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(source),str(csv),str(management),str(main),'-o',str(binary)],check=True)
    cases=json.loads(subprocess.run([str(binary),str(inputs)],capture_output=True,text=True,check=True).stdout)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps({'provenance':provenance,'cases':cases},ensure_ascii=False,indent=2)+'\n')
    android_output.parent.mkdir(parents=True,exist_ok=True);android_output.write_bytes(output.read_bytes())
print(f'Generated {len(cases)} actual iOS saved-place merge fixtures')
