#!/usr/bin/env python3
"""Compile the actual iOS filename template for safe synthetic token fixtures."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
template=root/'IsoMe/Models/FilenameTemplate.swift'
export=root/'IsoMe/Utilities/ExportService.swift'
harness=root/'android/scripts/fixtures/FilenameFixtures.swift'
inputs=root/'android/scripts/fixtures/filename-inputs.json'
output=root/'android/export/src/test/resources/ios-filenames.json'
provenance={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [template,export,harness,inputs]}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance: raise SystemExit('Filename fixture sources changed; regenerate on macOS')
    print('PASS: filename fixture source hashes match');raise SystemExit(0)
original=export.read_text();formats=original[original.index('enum ExportFormat:'):original.index('struct ExportService')]
with tempfile.TemporaryDirectory(prefix='whereday-filenames-') as directory:
    work=Path(directory);selected=work/'Selected.swift';main=work/'main.swift';binary=work/'fixtures'
    selected.write_text('import Foundation\n'+formats+'\nstruct ExportOptions {enum DataKind:String {case visits,points,outings,all}}\n'+template.read_text())
    main.write_bytes(harness.read_bytes())
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(selected),str(main),'-o',str(binary)],check=True)
    cases=json.loads(subprocess.run([str(binary),str(inputs)],capture_output=True,text=True,check=True).stdout)
    output.write_text(json.dumps({'provenance':provenance,'cases':cases},ensure_ascii=False,indent=2)+'\n')
print(f'Generated {len(cases)} actual iOS filename fixtures')
