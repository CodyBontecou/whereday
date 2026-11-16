#!/usr/bin/env python3
"""Compile the actual iOS backup service to produce Whereday backup fixtures, or to restore Android backups.

Default: write android/export/src/test/resources/ios-backup.json with the exact single-file and multipart files
written by IsoMe/Utilities/WheredayBackupService.swift for a synthetic journal.

--verify-android DIR: restore each Android-written backup set in DIR (one subdirectory per set, produced by
WheredayBackupInteropTest) through iOS TimelineImportService preview/commit, then compare the journal iOS stored
with the Android test's expected.json. Host macOS SwiftData in memory only; no app, simulator or device is used.
"""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
SOURCES = [
    "IsoMe/Models/Visit.swift", "IsoMe/Models/LocationPoint.swift",
    "IsoMe/Utilities/ImportService.swift", "IsoMe/Utilities/GoogleTimelineImportService.swift",
    "IsoMe/Utilities/TimelineImportService.swift", "IsoMe/Utilities/WheredayBackupService.swift",
    "IsoMe/Models/RecordingSession.swift", "IsoMe/Models/PhotoMoment.swift",
    "IsoMe/Models/CountryStay.swift", "Shared/DistanceFormatter.swift",
]
FORMAT_SOURCE = "IsoMe/Utilities/ExportService.swift"
HARNESS = "android/scripts/fixtures/BackupFixtures.swift"
output = root / "android/export/src/test/resources/ios-backup.json"
provenance = {path: hashlib.sha256((root / path).read_bytes()).hexdigest()
              for path in SOURCES + [FORMAT_SOURCE, HARNESS, "android/scripts/generate_backup_fixtures.py"]}


def build(work):
    compiled = []
    for path in SOURCES + [HARNESS]:
        target = work / Path(path).name
        target.write_bytes((root / path).read_bytes())
        compiled.append(str(target))
    # Only the UIKit-dependent ExportFormat declaration is extracted, as scripts/verify_backup_roundtrip.py does.
    text = (root / FORMAT_SOURCE).read_text(encoding="utf-8")
    seam = work / "Formats.swift"
    seam.write_text("import Foundation\n" + text[text.index("enum ExportFormat:"):text.index("\nstruct ExportService {")], encoding="utf-8")
    binary = work / "backup-fixtures"
    arch = "arm64" if platform.machine() == "arm64" else "x86_64"
    subprocess.run(["swiftc", "-parse-as-library", "-target", f"{arch}-apple-macosx14.0", "-module-cache-path", str(work / "modules"),
                    *compiled, str(seam), "-o", str(binary)], check=True)
    return binary


def records(backup):
    return {key: {row["id"].upper(): row for row in backup[key]}
            for key in ["visits", "points", "recordingSessions", "savedPlaces", "photoMoments", "countryStays"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check-source", action="store_true")
    mode.add_argument("--verify-android", type=Path)
    args = parser.parse_args()
    if args.check_source:
        if json.loads(output.read_text(encoding="utf-8"))["provenance"] != provenance:
            raise SystemExit("Backup sources or harness changed; regenerate on macOS")
        print("PASS: backup fixture source hashes match")
        return
    if platform.system() != "Darwin":
        parser.error("This generator requires actual macOS SwiftData.")
    with tempfile.TemporaryDirectory(prefix="whereday-backup-fixtures-") as directory:
        binary = build(Path(directory))
        if not args.verify_android:
            fixture = json.loads(subprocess.run([str(binary), "generate"], check=True, capture_output=True, text=True).stdout)
            fixture["provenance"] = provenance
            output.write_text(json.dumps(fixture, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
            print(f"Wrote {output.relative_to(root)}: 1 single file, {len(fixture['multipart'])} multipart files")
            return
        sets = sorted(path for path in args.verify_android.iterdir() if path.is_dir())
        if not sets:
            raise SystemExit(f"No Android backup sets in {args.verify_android}")
        for backup_set in sets:
            run = subprocess.run([str(binary), "verify", str(backup_set.resolve())], capture_output=True, text=True)
            if run.returncode:
                raise SystemExit(f"FAIL: {backup_set.name}: iOS rejected the Android backup: {run.stderr.strip()}")
            result = json.loads(run.stdout)
            expected = json.loads((backup_set / "expected.json").read_text(encoding="utf-8"))
            restored = records(json.loads(result["restored"]))
            wanted = records(expected)
            for collection, rows in wanted.items():
                if set(rows) != set(restored[collection]):
                    raise SystemExit(f"FAIL: {backup_set.name}: {collection} identifiers differ after iOS restore")
                for identifier, row in rows.items():
                    actual = restored[collection][identifier]
                    if row != actual:
                        raise SystemExit(f"FAIL: {backup_set.name}: {collection} {identifier} differs:\n android {row}\n ios     {actual}")
            total = sum(len(rows) for rows in wanted.values())
            if result["restoredCount"] != total or result["repeatedRestoredCount"] != 0:
                raise SystemExit(f"FAIL: {backup_set.name}: restored {result['restoredCount']} of {total}, repeated {result['repeatedRestoredCount']}")
            print(f"PASS: {backup_set.name}: iOS restored {total} records from {len(result['files'])} Android file(s); a repeat restored 0")


if __name__ == "__main__":
    main()
