#!/usr/bin/env python3
"""Commit actual Android backup files through the complete Swift importer and models on macOS."""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--input', type=Path, default=ROOT / 'android/export/build/place-provenance/android-exports.json')
parser.add_argument('--output', type=Path, default=ROOT / 'android/export/build/place-provenance/swift-commit-acceptance.json')
args = parser.parse_args()
if platform.system() != 'Darwin':
    parser.error('The complete SwiftData commit check requires macOS.')
if not args.input.is_file():
    parser.error('Generate synthetic Android inputs with :export:test --tests *PlaceProvenanceInteropTest first.')
paths = [ROOT / p for p in [
    'IsoMe/Models/Visit.swift', 'IsoMe/Models/LocationPoint.swift',
    'IsoMe/Utilities/ImportService.swift', 'IsoMe/Utilities/GoogleTimelineImportService.swift',
    'IsoMe/Utilities/TimelineImportService.swift']]
formats = ROOT / 'IsoMe/Utilities/ExportService.swift'
harness = ROOT / 'android/scripts/fixtures/PlaceProvenanceCommit.swift'
text = formats.read_text()
format_enum = text[text.index('enum ExportFormat:'):text.index('\nstruct ExportService {')]
with tempfile.TemporaryDirectory(prefix='whereday-provenance-') as directory:
    work = Path(directory)
    enum = work / 'Formats.swift'
    enum.write_text('import Foundation\n' + format_enum)
    binary = work / 'acceptance'
    arch = 'arm64' if platform.machine() == 'arm64' else 'x86_64'
    subprocess.run(['swiftc', '-parse-as-library', '-target', f'{arch}-apple-macosx14.0',
                    '-module-cache-path', str(work / 'modules'), *map(str, paths), str(enum), str(harness),
                    '-o', str(binary)], check=True)
    run = subprocess.run([str(binary), str(args.input.resolve())], capture_output=True, text=True)
    if run.returncode:
        print(run.stderr or run.stdout, file=sys.stderr, end='')
        raise SystemExit(run.returncode)
    report = json.loads(run.stdout)
    report['sourceSHA256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in paths + [formats, harness, Path(__file__).resolve(),
                                ROOT / 'android/export/src/test/kotlin/com/bontecou/whereday/export/PlaceProvenanceInteropTest.kt',
                                ROOT / 'android/export/build.gradle.kts',
                                *[ROOT / 'android/export/src/main/kotlin/com/bontecou/whereday/export' / name for name in
                                  ['HistoryExporter.kt', 'StreamingHistoryExporter.kt', 'HistoryImporter.kt', 'StreamingHistoryReader.kt',
                                   'ImportRecordLimits.kt', 'CsvReader.kt', 'MarkdownHistoryReader.kt', 'ExportOptions.kt']],
                                *[ROOT / 'android/domain/src/main/kotlin/com/bontecou/whereday/domain' / name for name in
                                  ['Models.kt', 'VisitDraft.kt', 'PlaceLookup.kt']]]}
    report['inputSHA256'] = hashlib.sha256(args.input.read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print(f"PASS: {report['count']} real Android files previewed, committed and deduplicated through SwiftData")
