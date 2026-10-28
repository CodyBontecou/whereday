#!/usr/bin/env python3
"""Check public synthetic JSON samples with the actual Swift importer (macOS)."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HARNESS = r'''
import Foundation
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let visits = try ImportService.importFile(data: Data(contentsOf: root.appendingPathComponent("synthetic-visits.json")), format: .json)
let points = try ImportService.importFile(data: Data(contentsOf: root.appendingPathComponent("synthetic-points.json")), format: .json)
precondition(visits.visits.count == 2 && visits.points.isEmpty)
precondition(points.points.count == 3 && points.visits.isEmpty)
precondition(visits.visits[0].locationName == "Synthetic stop A")
precondition(visits.visits[0].departedAt!.timeIntervalSince(visits.visits[0].arrivedAt) == 900)
precondition(points.points[2].latitude == 0.0002)
precondition(points.points[2].timestamp.timeIntervalSince1970 == 946728120)
print("Passed actual ImportService: 2 visits, 3 points, names, coordinates, duration, timestamps.")

let missingCoordinates = Data(#"{"visits":[{"arrivedAt":"2000-01-01T11:45:00Z"}]}"#.utf8)
do {
    _ = try ImportService.importFile(data: missingCoordinates, format: .json)
    fatalError("Missing coordinates unexpectedly accepted")
} catch ImportError.invalidData {
    print("Passed: incomplete visit rejected.")
}
'''


def check():
    source = (ROOT / "IsoMe/Utilities/ExportService.swift").read_text()
    # Use the unchanged enum without the export service's UIKit/ExportKit dependencies.
    enum = source[source.index("enum ExportFormat:"):source.index("\nstruct ExportService")]
    with tempfile.TemporaryDirectory(prefix="isome-import-samples-") as directory:
        folder = Path(directory)
        (folder / "ExportFormat.swift").write_text("import Foundation\n" + enum)
        (folder / "main.swift").write_text(HARNESS)
        subprocess.run([
            "swiftc", "-module-cache-path", str(folder / "cache"),
            str(folder / "ExportFormat.swift"),
            str(ROOT / "IsoMe/Utilities/ImportService.swift"),
            str(folder / "main.swift"), "-o", str(folder / "verify"),
        ], check=True)
        subprocess.run([str(folder / "verify"), str(ROOT / "marketing/public/samples")], check=True)


if __name__ == "__main__":
    check()
