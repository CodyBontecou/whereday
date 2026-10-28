#!/usr/bin/env python3
"""Compile the actual iOS replay calculator on public synthetic coordinates (macOS)."""
from pathlib import Path
import argparse, hashlib, json, subprocess, tempfile

root=Path(__file__).resolve().parents[2]
source=(root/'IsoMe/Views/MapView.swift').read_text()
calculator=source.split('enum RouteReplayCalculator {',1)[1].split('// MARK: - Road Snapping Helpers',1)[0]
calculator='enum RouteReplayCalculator {'+calculator
output=root/'android/data/src/androidTest/assets/ios-route-replay.json'
harness='''import Foundation
import CoreLocation
struct LocationPoint {
    let coordinate: CLLocationCoordinate2D
    let timestamp: Date
    func distance(to other: LocationPoint) -> Double {
        CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude).distance(from: CLLocation(latitude: other.coordinate.latitude, longitude: other.coordinate.longitude))
    }
}
struct RouteReplaySnapshot {
    let index: Int; let progress: Double; let totalPointCount: Int; let visiblePoints: [LocationPoint]
    let currentPoint: LocationPoint; let elapsedDuration: TimeInterval; let totalDuration: TimeInterval
    let distanceMeters: Double; let totalDistanceMeters: Double
}
'''+calculator+'''
var indices: [[String: Any]] = []
for count in [0, 1, 2, 4, 181, 182, 2500, 1000000] {
    for progress in [-1.0, 0, 0.25, 0.5, 0.75, 1, 2] {
        indices.append(["count": count, "progress": progress, "index": RouteReplayCalculator.index(for: progress, pointCount: count) as Any? ?? NSNull(), "step": RouteReplayCalculator.playbackStepSize(pointCount: count)])
    }
}
let coordinates = [[48.8584, 2.2945], [48.8590, 2.2950], [48.8585, 2.2940], [48.8600, 2.2960]]
let points = coordinates.enumerated().map { index, c in LocationPoint(coordinate: CLLocationCoordinate2D(latitude: c[0], longitude: c[1]), timestamp: Date(timeIntervalSince1970: 1793095200 + Double(index * 60))) }
let snapshots = [0.0, 0.5, 1.0].map { progress -> [String: Any] in
    let value = RouteReplayCalculator.snapshot(points: points, progress: progress)!
    return ["progress": progress, "index": value.index, "distance": value.distanceMeters, "totalDistance": value.totalDistanceMeters, "elapsed": value.elapsedDuration, "duration": value.totalDuration]
}
let result: [String: Any] = ["indices": indices, "coordinates": coordinates, "snapshots": snapshots]
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]))
'''
provenance={'calculator':hashlib.sha256(calculator.encode()).hexdigest(),'harness':hashlib.sha256(harness.encode()).hexdigest()}
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-source',action='store_true');args=parser.parse_args()
if args.check_source:
    if json.loads(output.read_text())['provenance']!=provenance:raise SystemExit('iOS replay calculator/harness changed; regenerate fixtures on macOS')
    print('PASS: actual iOS replay fixture provenance');raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='whereday-replay-') as directory:
    work=Path(directory);main=work/'main.swift';main.write_text(harness)
    subprocess.run(['swiftc','-module-cache-path',str(work/'modules'),str(main),'-o',str(work/'fixtures')],check=True)
    cases=json.loads(subprocess.run([str(work/'fixtures')],capture_output=True,text=True,check=True).stdout)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps({'provenance':provenance,**cases},indent=2)+'\n')
print(f'Generated {len(cases["indices"])} actual Swift index/pacing cases and {len(cases["snapshots"])} snapshots')
