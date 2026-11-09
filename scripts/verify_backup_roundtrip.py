#!/usr/bin/env python3
"""Verify actual Apple backup/import round trips with host macOS SwiftData only.

No app, simulator, device, network, personal history, or persistent store is used.
The UIKit-dependent ExportFormat declaration is the only extracted source seam.
"""

import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    "IsoMe/Models/Visit.swift", "IsoMe/Models/LocationPoint.swift",
    "IsoMe/Utilities/ImportService.swift", "IsoMe/Utilities/GoogleTimelineImportService.swift",
    "IsoMe/Utilities/TimelineImportService.swift", "IsoMe/Utilities/WheredayBackupService.swift",
    "IsoMe/Models/RecordingSession.swift", "IsoMe/Models/PhotoMoment.swift",
    "IsoMe/Models/CountryStay.swift", "Shared/DistanceFormatter.swift",
]
FORMAT_SOURCE = "IsoMe/Utilities/ExportService.swift"
HARNESS = "scripts/fixtures/BackupRoundTrip.swift"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--service-source", type=Path,
                        help="Explicit historical backup-service snapshot for a recorded before-fix run")
    args = parser.parse_args()
    if platform.system() != "Darwin":
        parser.error("This check requires actual macOS SwiftData.")

    # Compile immutable copies so hashes identify exactly the compiled inputs,
    # even while another agent edits the task worktree.
    paths = SOURCES + [FORMAT_SOURCE, HARNESS, "scripts/verify_backup_roundtrip.py"]
    inputs = {path: (args.service_source if args.service_source and path.endswith(
        "/WheredayBackupService.swift") else ROOT / path).read_bytes() for path in paths}
    hashes = {path: hashlib.sha256(data).hexdigest() for path, data in inputs.items()}
    with tempfile.TemporaryDirectory(prefix="whereday-backup-roundtrip-") as directory:
        work = Path(directory)
        compiled = []
        for path in SOURCES + [HARNESS]:
            target = work / Path(path).name
            target.write_bytes(inputs[path])
            compiled.append(str(target))
        text = inputs[FORMAT_SOURCE].decode("utf-8")
        enum = text[text.index("enum ExportFormat:"):text.index("\nstruct ExportService {")]
        seam = work / "Formats.swift"
        seam.write_text("import Foundation\n" + enum, encoding="utf-8")
        binary = work / "acceptance"
        arch = "arm64" if platform.machine() == "arm64" else "x86_64"
        subprocess.run([
            "swiftc", "-parse-as-library", "-target", f"{arch}-apple-macosx14.0",
            "-module-cache-path", str(work / "modules"), *compiled, str(seam),
            "-o", str(binary),
        ], check=True)
        run = subprocess.run([str(binary)], capture_output=True, text=True)
    if run.stderr:
        print(run.stderr, end="")
    report = json.loads(run.stdout)
    report.update({
        "scope": "Actual current models, backup export, TimelineImportService preview/commit and in-memory macOS SwiftData; only ExportFormat declaration extracted",
        "sourceSHA256": hashes,
        "historicalServiceSource": str(args.service_source.resolve()) if args.service_source else None,
    })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{'PASS' if report['passed'] else 'FAIL'}: {report['passedCount']}/{len(report['cases'])} actual SwiftData backup scenarios; {args.output}")
    raise SystemExit(run.returncode or (0 if report["passed"] else 1))


if __name__ == "__main__":
    main()
