#!/usr/bin/env python3
"""Compile the actual Swift saved-place CSV parser on synthetic input."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
source=root/'IsoMe/Utilities/SavedPlaceImportService.swift'
harness=root/'android/scripts/fixtures/SavedPlaceCsvFixtures.swift'
inputs=root/'android/scripts/fixtures/saved-place-csv-inputs.json'
output=root/'android/export/src/test/resources/ios-saved-place-csv.json'
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [source,harness,inputs]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance: raise SystemExit('Saved-place CSV sources changed; regenerate on macOS')
    print('PASS: saved-place CSV fixture source hashes match');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-place-csv-') as directory:
    work=Path(directory);main=work/'main.swift';binary=work/'fixtures';main.write_bytes(harness.read_bytes())
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(source),str(main),'-o',str(binary)],check=True)
    cases=json.loads(subprocess.run([str(binary),str(inputs)],capture_output=True,text=True,check=True).stdout)
    output.write_text(json.dumps({'provenance':provenance,'cases':cases},ensure_ascii=False,indent=2)+'\n')
print(f'Generated {len(cases)} actual iOS saved-place CSV fixtures')
