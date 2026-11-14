#!/usr/bin/env python3
"""Run unchanged native tests on one named disposable AVD without Gradle device discovery."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RUNNER = 'androidx.test.runner.AndroidJUnitRunner'
CLASS = re.compile(r'com\.bontecou\.whereday\.[A-Za-z0-9_.]+(?:#[A-Za-z0-9_]+)?')


def plan(module):
    if module == 'app':
        package = 'com.bontecou.whereday.mapqa'
        return package, package + '.test', [
            (ROOT/'app/build/outputs/apk/mapQa/app-mapQa.apk', package),
            (ROOT/'app/build/outputs/apk/androidTest/mapQa/app-mapQa-androidTest.apk', package+'.test')]
    if module not in ('data', 'platform'):
        raise ValueError('Only disposable app/data/platform test packages are supported')
    package = f'com.bontecou.whereday.{module}.test'
    return package, package, [(ROOT/f'{module}/build/outputs/apk/androidTest/debug/{module}-debug-androidTest.apk', package)]


def result(raw, expected, classes):
    bundle = {}; cases = []; statuses = []
    for line in raw.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            field = line[len('INSTRUMENTATION_STATUS: '):]
            if '=' in field:
                key, value = field.split('=', 1); bundle[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(':', 1)[1].strip()); statuses.append(code)
            if code == 0 and bundle.get('class') and bundle.get('test'):
                cases.append((bundle['class'], bundle['test']))
            bundle = {}
    success = re.search(r'\bOK \((\d+) tests?\)', raw)
    if (not success or int(success[1]) != expected or 'INSTRUMENTATION_CODE: -1' not in raw
            or 'FAILURES!!!' in raw or any(code < 0 for code in statuses)
            or len(cases) != expected or len(set(cases)) != expected):
        raise ValueError('Native results do not prove the expected complete, passing, unskipped run')
    for name, method in cases:
        if not any(spec == name or spec == name+'#'+method for spec in classes):
            raise ValueError('Native runner executed an unexpected test')
    return [{'class': name, 'test': method} for name, method in cases]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('adb', 'aapt2', 'serial', 'avd'):
        parser.add_argument('--'+flag, required=True)
    parser.add_argument('--server-port', type=int, default=5037)
    parser.add_argument('--module', choices=('app', 'data', 'platform'), required=True)
    parser.add_argument('--classes', required=True)
    parser.add_argument('--expected-tests', type=int, required=True)
    parser.add_argument('--extra', action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1024 <= args.server_port <= 65535:
        parser.error('Invalid local adb server port')
    if not re.fullmatch(r'emulator-[0-9]+', args.serial) or not args.avd.startswith('whereday-'):
        parser.error('Only an explicitly named Whereday QA emulator is allowed; physical devices are refused')
    classes = args.classes.split(',')
    if args.expected_tests < 1 or not all(CLASS.fullmatch(name) for name in classes):
        parser.error('Expected a positive test count and explicit Whereday class/method names')
    extras = []
    for item in args.extra:
        key, sep, value = item.partition('=')
        if not sep or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', key) or not re.fullmatch(r'[A-Za-z0-9_.-]+', value):
            parser.error('Instrumentation extras must be simple, shell-safe key=value atoms')
        if key == 'class':
            parser.error('Use --classes for the class filter')
        extras += ['-e', key, value]
    package, test_package, apks = plan(args.module)
    provenance = []
    # Verify local embedded identities before issuing any command to a device.
    for apk, expected_package in apks:
        badging = subprocess.check_output([args.aapt2, 'dump', 'badging', str(apk)], text=True)
        match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
        if not match or match[1] != expected_package:
            raise ValueError('Refusing an APK outside the exact disposable package allowlist')
        provenance.append({'path':str(apk), 'package':expected_package,
                           'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()})
    manifest = subprocess.check_output([args.aapt2, 'dump', 'xmltree', str(apks[-1][0]), '--file', 'AndroidManifest.xml'], text=True)
    if not re.search(r'android:targetPackage[^\n]*="'+re.escape(package)+'"', manifest):
        raise ValueError('Instrumentation does not target the exact disposable package')
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError('Use a fresh evidence directory; previous results cannot be overwritten')
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, '-P', str(args.server_port), '-s', args.serial]

    def run(label, command, *, metadata=False):
        path = args.output/(label+'.log')
        if metadata:
            completed = subprocess.run(adb+command, capture_output=True, text=True, timeout=30)
            path.write_text(completed.stdout+completed.stderr)
        else:
            # No observation timeout/retry: retain this live process until it actually finishes.
            with path.open('w') as stream:
                completed = subprocess.run(adb+command, stdout=stream, stderr=subprocess.STDOUT)
        if completed.returncode:
            raise RuntimeError(f'{label} failed; inspect {path}')
        return path.read_text()

    def identity(label):
        if run(label, ['emu', 'avd', 'name'], metadata=True).splitlines()[0].strip() != args.avd:
            raise RuntimeError('Serial no longer belongs to the explicitly selected QA AVD')

    identity('identity')
    for _, name in apks:
        installed = run('preinstalled-'+name, ['shell', 'pm', 'list', 'packages', name], metadata=True)
        if 'package:'+name in installed.splitlines():
            raise RuntimeError('Refusing to replace an already installed QA package: '+name)
    installed = []
    try:
        for apk, name in apks:
            identity('before-install-'+name); installed.append(name)
            run('install-'+name, ['install', str(apk)])
        identity('before-tests')
        raw = run('instrumentation', ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', args.classes,
                                     *extras, test_package+'/'+RUNNER])
        cases = result(raw, args.expected_tests, classes)
        proof = {'serial':args.serial, 'avd':args.avd, 'module':args.module, 'adbServerPort':args.server_port,
                 'execution':'Direct AndroidJUnitRunner; no Gradle device discovery',
                 'tests':len(cases), 'failures':0, 'errors':0, 'skipped':0,
                 'cases':cases, 'apks':provenance}
    finally:
        if installed:
            identity('before-target-stop')
            run('target-stop', ['shell', 'am', 'force-stop', package])
        for name in reversed(installed):
            identity('before-cleanup-'+name)
            run('stop-'+name, ['shell', 'am', 'force-stop', name])
            run('uninstall-'+name, ['uninstall', name])
    identity('after-tests')
    for _, name in apks:
        if 'package:'+name in run('remaining-'+name, ['shell', 'pm', 'list', 'packages', name], metadata=True).splitlines():
            raise RuntimeError('A disposable test package remains installed')
    (args.output/'verification.json').write_text(json.dumps(proof, indent=2)+'\n')
    print(json.dumps({key:proof[key] for key in ('module','tests','failures','errors','skipped')}), flush=True)


if __name__ == '__main__':
    main()
