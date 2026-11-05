#!/usr/bin/env python3
"""Compile the actual iOS CountryDayCalculator.csv declaration for synthetic byte-level fixtures."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
source=root/'IsoMe/Models/CountryStay.swift'
harness=root/'android/scripts/fixtures/CountryCsvFixtures.swift'
inputs=root/'android/scripts/fixtures/country-csv-inputs.json'
output=root/'android/export/src/test/resources/ios-country-csv.json'
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [source,harness,inputs]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance: raise SystemExit('Country CSV source or fixture inputs changed; regenerate on macOS')
    print('PASS: country CSV fixture source hashes match')
else:
    original=source.read_text()
    structs=original[original.index('struct CountryPresence:'):original.index('enum CountryDayCalculator')]
    start=original.index('static func csv(');brace=original.index('{',start);depth=1;end=brace+1
    while depth:
        depth += (original[end]=='{') - (original[end]=='}');end+=1
    method=original[start:end]
    with tempfile.TemporaryDirectory(prefix='whereday-country-csv-') as folder:
        work=Path(folder);selected=work/'CountryCsv.swift';main=work/'main.swift';binary=work/'fixtures'
        selected.write_text('import Foundation\n'+structs+'\nenum CountryDayCalculator {\n'+method+'\n}\n')
        main.write_bytes(harness.read_bytes())
        subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(selected),str(main),'-o',str(binary)],check=True)
        cases=json.loads(subprocess.run([str(binary),str(inputs)],check=True,capture_output=True,text=True).stdout)
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps({'provenance':provenance,'cases':cases},ensure_ascii=False,indent=2)+'\n')
    print(f'Generated {len(cases)} country CSV goldens from actual iOS source')
