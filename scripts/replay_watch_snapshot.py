#!/usr/bin/env python3
"""Replay a Watch queue through iso.me's production codec/importer on iOS Simulator."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = 'com.bontecou.isome'


def run(*args, check=True):
    return subprocess.run(args, check=check, text=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--simulator', required=True, help='iOS Simulator UDID; physical devices are unsupported')
    parser.add_argument('--snapshot', type=Path, default=ROOT / 'IsoMeTests/Fixtures/watch-offline-route.json')
    parser.add_argument('--derived-data', type=Path, default=Path(tempfile.gettempdir()) / 'isome-watch-sync-build')
    parser.add_argument('--app', type=Path, help='Use an already built Debug simulator .app')
    parser.add_argument('--skip-tests', action='store_true')
    parser.add_argument('--legacy-queue', action='store_true', help='Replay the pre-visit-recording Watch queue format')
    parser.add_argument('--runs', type=int, default=2, help='Replay twice by default, retaining the isolated store between runs')
    args = parser.parse_args()
    if args.runs < 1:
        parser.error('--runs must be positive')
    snapshot = json.loads(args.snapshot.read_text())
    expected_visits = len({v['id'] for v in snapshot['visits']}) if snapshot.get('visitRecordingVersion') == 1 else None
    if args.legacy_queue:
        snapshot.pop('visits', None)
        snapshot.pop('visitRecordingVersion', None)
    devices = json.loads(run('xcrun', 'simctl', 'list', 'devices', '--json').stdout)['devices']
    device = next((d for runtime, items in devices.items() if '.iOS-' in runtime
                   for d in items if d['udid'] == args.simulator and d.get('isAvailable')), None)
    if device is None:
        parser.error('--simulator must name an available iOS Simulator')
    if device['state'] != 'Booted':
        run('xcrun', 'simctl', 'boot', args.simulator)
    run('xcrun', 'simctl', 'bootstatus', args.simulator, '-b')
    args.derived_data.mkdir(parents=True, exist_ok=True)
    if not args.skip_tests or args.app is None:
        action = 'build' if args.skip_tests else 'test'
        command = ['xcodebuild', '-project', str(ROOT / 'IsoMe.xcodeproj'), '-scheme', 'IsoMe',
                   '-configuration', 'Debug', '-destination', f'platform=iOS Simulator,id={args.simulator}',
                   '-derivedDataPath', str(args.derived_data), 'CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-']
        if action == 'test':
            command += ['-only-testing:IsoMeTests/WatchLocationSyncTests',
                        '-only-testing:IsoMeTests/WatchLocationSyncImportTests']
        log = args.derived_data / 'watch-sync-build.log'
        print(f'Running xcodebuild {action}; log: {log}', flush=True)
        with log.open('w') as output:
            subprocess.run(command + [action], stdout=output, stderr=subprocess.STDOUT, check=True)
    app = args.app or args.derived_data / 'Build/Products/Debug-iphonesimulator/IsoMe.app'
    run('xcrun', 'simctl', 'terminate', args.simulator, BUNDLE, check=False)
    run('xcrun', 'simctl', 'install', args.simulator, str(app))
    container = Path(run('xcrun', 'simctl', 'get_app_container', args.simulator, BUNDLE, 'data').stdout.strip())
    directory = container / 'Documents/WatchSyncReplay'
    directory.mkdir(parents=True, exist_ok=True)
    # Only the explicit replay database is reset. The normal application store is untouched.
    for name in ('phone.store', 'phone.store-shm', 'phone.store-wal', 'report.json', 'remaining-state.json', 'prepared-snapshot.json'):
        (directory / name).unlink(missing_ok=True)
    (directory / 'snapshot.json').write_text(json.dumps(snapshot))
    expected = {
        'points': len({p['id'] for p in snapshot.get('points', [])}),
        'sessions': len({s['id'] for s in snapshot.get('sessions', [])}),
    }
    if expected_visits is not None:
        expected['visits'] = expected_visits
    for index in range(args.runs):
        run('xcrun', 'simctl', 'terminate', args.simulator, BUNDLE, check=False)
        (directory / 'report.json').unlink(missing_ok=True)
        run('xcrun', 'simctl', 'launch', args.simulator, BUNDLE, '--watch-sync-replay')
        deadline = time.monotonic() + 30
        while not (directory / 'report.json').exists():
            if time.monotonic() >= deadline:
                raise RuntimeError('Simulator did not produce a replay report within 30 seconds')
            time.sleep(0.2)
        report = json.loads((directory / 'report.json').read_text())
        if report.get('succeeded') is not True:
            raise RuntimeError(f'Replay failed: {report}')
        for key, count in expected.items():
            if report[key] != count:
                raise AssertionError(f'{key}: expected {count}, got {report[key]}')
        remaining = json.loads((directory / 'remaining-state.json').read_text())
        if report['queuedPoints'] or any(v.get('departedAt') is not None for v in remaining['visits']):
            raise AssertionError('Acknowledged points or completed visits remain queued')
        if any(s.get('endedAt') is not None for s in remaining['sessions']):
            raise AssertionError('Completed sessions remain queued')
        print(f'PASS {index + 1}/{args.runs}: {json.dumps(report, sort_keys=True)}', flush=True)
    print(f'Replay artifacts: {directory}')


if __name__ == '__main__':
    main()
