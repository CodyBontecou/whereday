#!/usr/bin/env python3
"""Exercise saved visit drafts after actual QA process death on one disposable Whereday AVD.

Build app:assembleMapQa, app:assembleMapQaAndroidTest and platform:assembleDebugAndroidTest
first. No Gradle device discovery. Installs only fresh isolated QA/test packages, then removes them.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tarfile
from run_qa_instrumentation import plan, result, RUNNER


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('adb', 'aapt2', 'serial', 'avd'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--server-port', type=int, required=True)
    parser.add_argument('--width', type=int, choices=(800, 2200), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if (not re.fullmatch(r'emulator-[0-9]+', args.serial) or not args.avd.startswith('whereday-')
            or not re.fullmatch(r'[A-Za-z0-9_.-]+', args.avd) or not 1024 <= args.server_port <= 65535
            or args.server_port == 5037):
        parser.error('An explicitly named Whereday QA emulator and private ADB port are required')
    if args.output.exists() and any(args.output.iterdir()):
        parser.error('Use a fresh evidence directory')
    apks = plan('app')[2]+plan('platform')[2]
    provenance = []
    for apk, package in apks:
        badging = subprocess.check_output([args.aapt2, 'dump', 'badging', str(apk)], text=True)
        if not re.search(r"^package: name='"+re.escape(package)+"'", badging, re.MULTILINE):
            raise ValueError('Refusing a non-QA APK')
        provenance.append(dict(path=str(apk), package=package, sha256=hashlib.sha256(apk.read_bytes()).hexdigest()))
    for apk, target in ((apks[1][0], plan('app')[0]), (apks[2][0], plan('platform')[0])):
        manifest = subprocess.check_output([args.aapt2, 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
        if not re.search(r'android:targetPackage[^\n]*="'+re.escape(target)+'"', manifest):
            raise ValueError('Instrumentation target differs from disposable allowlist')
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, '-P', str(args.server_port), '-s', args.serial]

    def command(*parts):
        return subprocess.check_output(adb+list(parts), text=True).strip()

    def identity():
        if (command('emu', 'avd', 'name').splitlines()[0] != args.avd
                or command('shell', 'getprop', 'ro.kernel.qemu') != '1'
                or command('shell', 'getprop', 'ro.boot.qemu.avd_name') != args.avd):
            raise RuntimeError('Device ownership changed')

    identity()
    for _, package in apks:
        if 'package:'+package in command('shell', 'pm', 'list', 'packages', package).splitlines():
            raise RuntimeError('Refusing to replace existing package '+package)
    baseline = dict(size=command('shell', 'wm', 'size'), density=command('shell', 'wm', 'density'))
    if 'Override' in baseline['size'] or 'Override' in baseline['density']:
        raise RuntimeError('Expected the owned AVD with its original display configuration')
    (args.output/'display-baseline.json').write_text(json.dumps(baseline, indent=2)+'\n')
    installed = []; cases = []
    fixture = 'com.bontecou.whereday.WorkspaceProcessFixtureTest'
    controller = 'com.bontecou.whereday.platform.WorkspaceProcessDeathTest'
    try:
        for apk, package in apks:
            identity(); installed.append(package)
            command('install', str(apk))
        command('shell', 'wm', 'size', f'{args.width}x1600'); command('shell', 'wm', 'density', '200')
        for label, selector, package in (
                ('seed', fixture+'#seed', plan('app')[1]),
                ('process', controller, plan('platform')[1]),
                ('verify', fixture+'#verify', plan('app')[1])):
            identity()
            with (args.output/(label+'.log')).open('w') as stream:
                # Native runner is allowed to finish; no competing UI automation connection.
                subprocess.run(adb+['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', selector,
                    '-e', 'allowWorkspaceProcessDeath', 'true', '-e', 'expectedProcessAvd', args.avd,
                    package+'/'+RUNNER], stdout=stream, stderr=subprocess.STDOUT, check=True)
            cases += result((args.output/(label+'.log')).read_text(), 1, [selector])
    finally:
        identity()
        if plan('platform')[0] in installed:
            with (args.output/'ui-proof.tar').open('wb') as stream:
                subprocess.run(adb+['exec-out', 'run-as', plan('platform')[0], 'tar', '-cf', '-', 'files/workspace-process-proof'],
                    stdout=stream, stderr=subprocess.PIPE, check=False)
        for package in reversed(installed):
            identity(); command('shell', 'am', 'force-stop', package); command('uninstall', package)
        command('shell', 'wm', 'size', 'reset'); command('shell', 'wm', 'density', 'reset')
        restored = dict(size=command('shell', 'wm', 'size'), density=command('shell', 'wm', 'density'))
        if restored != baseline:
            raise RuntimeError('Display baseline was not restored')
        for _, package in apks:
            if 'package:'+package in command('shell', 'pm', 'list', 'packages', package).splitlines():
                raise RuntimeError('Disposable package remains installed')
        (args.output/'cleanup.json').write_text(json.dumps(dict(display=restored, packagesAbsent=installed), indent=2)+'\n')
    proof = dict(serial=args.serial, avd=args.avd, adbServerPort=args.server_port, width=args.width,
                 tests=len(cases), failures=0, errors=0, skipped=0, cases=cases, apks=provenance)
    with tarfile.open(args.output/'ui-proof.tar') as archive:
        for label in ('discard', 'approve'):
            raw = archive.extractfile('files/workspace-process-proof/'+label+'-pids.txt').read().decode()
            match = re.fullmatch(r'before=([0-9]+)\nafter=([0-9]+)\n', raw)
            if not match or match[1] == match[2]:
                raise ValueError('No proof of a replaced process')
        for label in ('pending-discard', 'restored-discard', 'restored-approve', 'approved'):
            for suffix in ('.xml', '.png'):
                data = archive.extractfile('files/workspace-process-proof/'+label+suffix).read()
                if not data or (suffix == '.png' and not data.startswith(b'\x89PNG\r\n\x1a\n')):
                    raise ValueError('Missing UI evidence')
    proof['uiProofSha256'] = hashlib.sha256((args.output/'ui-proof.tar').read_bytes()).hexdigest()
    (args.output/'verification.json').write_text(json.dumps(proof, indent=2)+'\n')
    print(json.dumps(proof), flush=True)


if __name__ == '__main__':
    main()
