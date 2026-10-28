#!/usr/bin/env python3
"""Generate daily/interval goldens by compiling the actual iOS implementation on macOS.

Normal Android JVM/CI tests consume the checked-in output without requiring Swift.
--check-source verifies source hashes without a Swift toolchain or changing files.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
sources = [root / 'IsoMe/Services/IntervalExportScheduleDateMath.swift',
           root / 'android/scripts/fixtures/ScheduleFixtures.swift']
output = root / 'android/domain/src/test/resources/ios-export-schedule.tsv'
resolved = root / 'IsoMe.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved'
pin = next(p['state']['revision'] for p in json.loads(resolved.read_text())['pins'] if p['identity'] == 'exportkit')
header = ''.join(f'# sha256 {hashlib.sha256(p.read_bytes()).hexdigest()} {p.relative_to(root)}\n' for p in sources)
header += f'# ExportKit revision {pin}\n'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--exportkit', type=Path, help='Local checkout at the revision pinned by the iOS app')
parser.add_argument('--check-source', action='store_true')
args = parser.parse_args()
if args.check_source:
    if not output.read_text().startswith(header):
        raise SystemExit('iOS scheduling source or fixture harness changed; regenerate goldens on macOS')
    print('PASS: schedule fixture source hashes match')
else:
    candidates = [args.exportkit] if args.exportkit else sorted((Path.home() / 'Library/Developer/Xcode/DerivedData').glob('IsoMe-*/SourcePackages/checkouts/ExportKit'))
    package = next((p for p in candidates if p and subprocess.run(['git', '-C', str(p), 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip() == pin), None)
    if package is None:
        raise SystemExit(f'Provide --exportkit CHECKOUT at the pinned revision {pin}')
    if subprocess.run(['git', '-C', str(package), 'status', '--porcelain', '--untracked-files=no'], check=True, capture_output=True, text=True).stdout.strip():
        raise SystemExit('Fixture generation requires an unmodified ExportKit checkout')
    with tempfile.TemporaryDirectory(prefix='whereday-schedule-') as folder:
        work = Path(folder)
        main = work / 'main.swift'
        main.write_bytes(sources[1].read_bytes())
        binary = work / 'schedule-fixtures'
        library = work / 'libExportAutomationKit.dylib'
        package_sources = sorted((package / 'Sources/ExportAutomationKit').glob('*.swift'))
        subprocess.run(['swiftc', '-module-cache-path', str(work / 'module-cache'), '-emit-library', '-emit-module', '-module-name', 'ExportAutomationKit', *map(str, package_sources), '-o', str(library), '-emit-module-path', str(work / 'ExportAutomationKit.swiftmodule')], check=True)
        subprocess.run(['swiftc', '-module-cache-path', str(work / 'module-cache'), '-I', str(work), '-L', str(work), '-lExportAutomationKit', '-Xlinker', '-rpath', '-Xlinker', str(work), str(sources[0]), str(main), '-o', str(binary)], check=True)
        generated = subprocess.run([str(binary)], check=True, capture_output=True, text=True).stdout
        output.write_text(header + generated)
    print(f'Generated {len(generated.splitlines()) - 1} iOS daily/interval cases in {output.relative_to(root)}')
