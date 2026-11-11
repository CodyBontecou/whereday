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


def result(raw, expected, classes, expected_ignored=()):
    bundle = {}; cases = []; ignored = []; statuses = []
    for line in raw.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            field = line[len('INSTRUMENTATION_STATUS: '):]
            if '=' in field:
                key, value = field.split('=', 1); bundle[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(':', 1)[1].strip()); statuses.append(code)
            if code == 0 and bundle.get('class') and bundle.get('test'):
                cases.append((bundle['class'], bundle['test']))
            if code == -3 and bundle.get('class') and bundle.get('test'):
                ignored.append(bundle['class']+'#'+bundle['test'])
            bundle = {}
    success = re.search(r'\bOK \((\d+) tests?\)', raw)
    passed = expected-len(expected_ignored)
    if (not success or int(success[1]) != passed
            or not re.search(r'^INSTRUMENTATION_CODE: -1\s*$', raw, re.MULTILINE)
            or 'FAILURES!!!' in raw or any(code < 0 and code != -3 for code in statuses)
            or len(cases) != passed or len(set(cases)) != passed
            or len(ignored) != len(expected_ignored) or set(ignored) != set(expected_ignored)
            or statuses.count(-3) != len(ignored)
            or set(ignored) & {name+'#'+method for name,method in cases}):
        raise ValueError('Native results do not prove the expected complete, passing run and exact declared ignores')
    for name, method in cases+[tuple(spec.split('#')) for spec in ignored]:
        if not any(spec == name or spec == name+'#'+method for spec in classes):
            raise ValueError('Native runner executed an unexpected test')
    return [{'class': name, 'test': method} for name, method in cases]


def capture_failure(adb, output, package):
    """Capture the still-installed target before cleanup destroys its evidence."""
    commands = [
        ('failure-logcat.log', ['logcat', '-d', '-b', 'main', '-b', 'system', '-b', 'crash', '-b', 'events']),
        ('failure-exit-info.log', ['shell', 'dumpsys', 'activity', 'exit-info', package]),
        ('failure-memory.log', ['shell', 'dumpsys', 'meminfo', package]),
    ]
    for name, command in commands:
        with (output/name).open('w') as stream:
            try:
                subprocess.run(adb+command, stdout=stream, stderr=subprocess.STDOUT, timeout=15)
            except (subprocess.TimeoutExpired, OSError) as error:
                print(f'Diagnostic unavailable: {error}', file=stream)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('adb', 'aapt2', 'serial', 'avd'):
        parser.add_argument('--'+flag, required=True)
    parser.add_argument('--server-port', type=int, default=5037)
    parser.add_argument('--module', choices=('app', 'data', 'platform'), required=True)
    parser.add_argument('--classes', required=True)
    parser.add_argument('--expected-tests', type=int, required=True)
    parser.add_argument('--expected-ignored', action='append', default=[],
                        help='Exact pre-existing class#method ignore; missing or additional skips fail')
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
    if (len(set(args.expected_ignored)) != len(args.expected_ignored)
            or len(args.expected_ignored) >= args.expected_tests
            or any(not CLASS.fullmatch(spec) or '#' not in spec for spec in args.expected_ignored)):
        parser.error('Expected unique, exact ignored method names and at least one passing test')
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
        cases = result(raw, args.expected_tests, classes, args.expected_ignored)
        proof = {'serial':args.serial, 'avd':args.avd, 'module':args.module, 'adbServerPort':args.server_port,
                 'execution':'Direct AndroidJUnitRunner; no Gradle device discovery',
                 'tests':len(cases)+len(args.expected_ignored), 'passed':len(cases),
                 'failures':0, 'errors':0, 'skipped':len(args.expected_ignored),
                 'ignoredCases':args.expected_ignored,
                 'cases':cases, 'apks':provenance}
    except BaseException:
        try:
            identity('before-failure-diagnostics')
            capture_failure(adb, args.output, package)
        except Exception as error:
            print(f'Failure diagnostics unavailable: {error}', flush=True)
        raise
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
