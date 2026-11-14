#!/usr/bin/env python3
"""Create old-library settings bytes in an isolated Java process; CI checks committed provenance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'scripts/LegacyPreferencesFixture.java'
FIXTURE = ROOT / 'data/src/androidTest/assets/legacy-settings-1.1.7.preferences_pb'
PROVENANCE = FIXTURE.with_suffix('.json')
ARTIFACTS = [
    ('androidx.datastore', 'datastore-preferences-core-android', '1.1.7', 'aar'),
    ('androidx.datastore', 'datastore-core-android', '1.1.7', 'aar'),
    ('androidx.datastore', 'datastore-preferences-proto', '1.1.7', 'jar'),
    ('androidx.datastore', 'datastore-preferences-external-protobuf', '1.1.7', 'jar'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.20', 'jar'),
]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = {'java': sha(JAVA), 'python': sha(Path(__file__))}
    if args.check:
        record = json.loads(PROVENANCE.read_text())
        assert record['source'] == source and record['fixtureSha256'] == sha(FIXTURE)
        assert [a['coordinate'] for a in record['artifacts']] == [':'.join(a[:3]) for a in ARTIFACTS]
        print('Legacy preferences fixture provenance verified')
        return
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    records = []
    with tempfile.TemporaryDirectory(prefix='whereday-legacy-preferences-') as directory:
        tmp = Path(directory)
        jars = []
        for group, module, version, extension in ARTIFACTS:
            paths = [p for p in (cache / group / module / version).glob('*/*.' + extension) if '-sources' not in p.name]
            if len(paths) != 1:
                raise SystemExit(f'Expected one cached {group}:{module}:{version}; found {len(paths)}')
            artifact = paths[0]
            records.append({'coordinate': ':'.join((group, module, version)), 'sha256': sha(artifact)})
            if extension == 'aar':
                jar = tmp / (module + '.jar')
                with zipfile.ZipFile(artifact) as archive:
                    jar.write_bytes(archive.read('classes.jar'))
            else:
                jar = artifact
            jars.append(str(jar))
        classpath = os.pathsep.join(jars)
        subprocess.run(['javac', '-cp', classpath, '-d', str(tmp), str(JAVA)], check=True)
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['java', '-cp', classpath + os.pathsep + str(tmp), 'LegacyPreferencesFixture', str(FIXTURE)], check=True)
    PROVENANCE.write_text(json.dumps({'source': source, 'artifacts': records, 'fixtureSha256': sha(FIXTURE)}, indent=2) + '\n')
    print(f'Generated {FIXTURE.name}: {FIXTURE.stat().st_size} bytes using DataStore 1.1.7')

if __name__ == '__main__':
    main()
