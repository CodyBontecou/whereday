#!/usr/bin/env python3
"""Compile real iOS merge strategies against pinned ExportKit and save synthetic golden results."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = root / 'IsoMe/Utilities/ScheduledExportMergeStrategies.swift'
harness = root / 'android/scripts/fixtures/MergeFixtures.swift'
inputs = root / 'android/scripts/fixtures/scheduled-merge-inputs.json'
resolved = root / 'IsoMe.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved'
pin = next(p['state']['revision'] for p in json.loads(resolved.read_text())['pins'] if p['identity'] == 'exportkit')
provenance = {'exportkitRevision': pin, 'sha256': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [source,harness,inputs]}}
output = root / 'android/export/src/test/resources/ios-scheduled-merge.json'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check-source',action='store_true')
parser.add_argument('--exportkit',type=Path)
args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance'] != provenance:
        raise SystemExit('iOS merge source/package or fixture inputs changed; regenerate on macOS')
    print('PASS: merge fixture source hashes and package revision match')
else:
    candidates = [args.exportkit] if args.exportkit else sorted((Path.home() / 'Library/Developer/Xcode/DerivedData').glob('IsoMe-*/SourcePackages/checkouts/ExportKit'))
    package = next((p for p in candidates if p and subprocess.run(['git','-C',str(p),'rev-parse','HEAD'],capture_output=True,text=True).stdout.strip()==pin),None)
    if package is None: raise SystemExit(f'Provide --exportkit at pinned revision {pin}')
    if subprocess.run(['git','-C',str(package),'status','--porcelain','--untracked-files=no'],check=True,capture_output=True,text=True).stdout.strip():
        raise SystemExit('ExportKit checkout must be unmodified')
    with tempfile.TemporaryDirectory(prefix='whereday-merge-') as folder:
        work=Path(folder)
        subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),'-emit-library','-emit-module','-module-name','ExportKit',*map(str,sorted((package/'Sources/ExportKit').glob('*.swift'))),'-o',str(work/'libExportKit.dylib'),'-emit-module-path',str(work/'ExportKit.swiftmodule')],check=True)
        original=source.read_text()
        # Select complete actual declarations; app export-plan types are not needed by this harness.
        error=original[original.index('enum ScheduledExportMergeError:'):original.index('/// A deterministic file/window plan')]
        strategies=original[original.index('/// Appends CSV rows'):]
        selected=work/'MergeStrategies.swift';selected.write_text('import Foundation\nimport ExportKit\n'+error+strategies)
        main=work/'main.swift';main.write_bytes(harness.read_bytes())
        binary=work/'merge-fixtures'
        subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),'-I',str(work),'-L',str(work),'-lExportKit','-Xlinker','-rpath','-Xlinker',str(work),str(selected),str(main),'-o',str(binary)],check=True)
        cases=json.loads(subprocess.run([str(binary),str(inputs)],check=True,capture_output=True,text=True).stdout)
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps({'provenance':provenance,'cases':cases},ensure_ascii=False,indent=2)+'\n')
    print(f'Generated {len(cases)} iOS merge goldens')
