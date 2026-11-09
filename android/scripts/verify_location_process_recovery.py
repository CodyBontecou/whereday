#!/usr/bin/env python3
"""Kill only a disposable mapQa process and verify real location-service recovery."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.bontecou.whereday.mapqa"
TEST_PACKAGE = PACKAGE + ".test"
CLASS = "com.bontecou.whereday.LocationProcessRecoveryTest"
PROOF = "no_backup/qa-location-process-proof.json"


def capture_diagnostics(run, output, label, *, logcat=False):
    """Observe the selected disposable emulator without changing its state."""
    commands = {
        "power": ["shell", "dumpsys", "power"],
        "window": ["shell", "dumpsys", "window", "windows"],
        "window-policy": ["shell", "dumpsys", "window", "policy"],
        "activities": ["shell", "dumpsys", "activity", "activities"],
    }
    if logcat:
        commands["logcat"] = ["shell", "logcat", "-d", "-t", "500", "-v", "threadtime"]
    results = {}
    for name, command in commands.items():
        try:
            result = run(f"{label}-{name}", command, check=False, timeout=10)
            results[name] = {"exitCode": result.returncode}
        except Exception as error:
            # A failed diagnostic must not prevent the other observations or cleanup.
            results[name] = {"error": type(error).__name__}
    (output / f"{label}-diagnostics.json").write_text(json.dumps(results, indent=2) + "\n")


def prepare_foreground(run, identity, output, phase):
    # The emulator runner's initial unlock precedes a potentially long Gradle build;
    # foreground-service tests need this precondition immediately before each phase.
    identity()
    capture_diagnostics(run, output, f"{phase}-before-foreground")
    identity()
    run(f"{phase}-wake", ["shell", "input", "keyevent", "KEYCODE_WAKEUP"])
    identity()
    run(f"{phase}-dismiss-keyguard", ["shell", "wm", "dismiss-keyguard"])
    capture_diagnostics(run, output, f"{phase}-after-foreground")
    # Neither a successful shell command nor these snapshots prove window focus.
    # The instrumentation still requires hasWindowFocus within its original bound.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("adb", "aapt2", "serial", "avd"):
        parser.add_argument("--" + name, required=True)
    for name in ("apk", "test-apk", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--mode", choices=("route", "visits", "both"), default="both")
    parser.add_argument("--termination", choices=("kill", "force-stop", "reboot"), default="kill")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial) or not args.avd.strip():
        parser.error("An explicitly named QA emulator is required; physical devices are refused")
    if args.output.exists() and (not args.output.is_dir() or any(args.output.iterdir())):
        parser.error("Use a fresh output directory so prior evidence cannot be overwritten or mistaken for this run")
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]

    def run(label, command, *, check=True, timeout=90):
        result = subprocess.run(adb + command, capture_output=True, text=True, timeout=timeout)
        (args.output / f"{label}.log").write_text(result.stdout + result.stderr)
        if check and result.returncode:
            raise RuntimeError(f"{label} failed; inspect its preserved log")
        return result

    def identity():
        if run("avd", ["emu", "avd", "name"]).stdout.splitlines()[0].strip() != args.avd:
            raise RuntimeError("Serial no longer belongs to the selected QA AVD")

    def manifest(apk, package):
        output = subprocess.check_output([args.aapt2, "dump", "badging", str(apk)], text=True)
        match = re.search(r"^package: name='([^']+)'", output, re.MULTILINE)
        if not match or match[1] != package:
            raise RuntimeError(f"Refusing APK whose embedded package is not {package}")

    identity()
    manifest(args.apk, PACKAGE)
    manifest(args.test_apk, TEST_PACKAGE)
    target = subprocess.check_output([args.aapt2, "dump", "xmltree", str(args.test_apk), "--file", "AndroidManifest.xml"], text=True)
    if not re.search(r'android:targetPackage[^\n]*="' + re.escape(PACKAGE) + '"', target):
        raise RuntimeError("Instrumentation must target only mapQa")
    for package in (PACKAGE, TEST_PACKAGE):
        if run("preinstalled-" + package, ["shell", "pm", "list", "packages", package]).stdout.strip():
            raise RuntimeError("Refusing to replace an already installed QA package")
    installed = []
    seed = None
    try:
        for apk, package in ((args.apk, PACKAGE), (args.test_apk, TEST_PACKAGE)):
            run("install-" + package, ["install", str(apk.resolve())]); installed.append(package)

        def command(phase):
            return ["shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS,
                    "-e", "locationRecoveryPhase", phase, "-e", "locationRecoveryMode", args.mode,
                    "-e", "locationRecoveryTermination", args.termination,
                    f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"]

        prepare_foreground(run, identity, args.output, "seed")
        with (args.output / "seed.log").open("w") as log:
            seed = subprocess.Popen(adb + command("seed"), stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 45
            while True:
                ready = run("seed-proof-poll", ["shell", "run-as", PACKAGE, "cat", PROOF], check=False)
                try:
                    proof = json.loads(ready.stdout)
                except (ValueError, TypeError):
                    proof = None
                if proof and proof.get("phase") == "seed":
                    break
                if seed.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Seed did not acknowledge live services within its bound")
                time.sleep(.2)
            (args.output / "seed-proof.json").write_text(json.dumps(proof, indent=2) + "\n")
            identity()
            pid = str(proof["pid"])
            if not pid.isdigit() or proof.get("mode") != args.mode:
                raise RuntimeError("Invalid live QA proof")
            if pid not in run("live-pid", ["shell", "pidof", PACKAGE]).stdout.split():
                raise RuntimeError("Ready proof does not identify the currently live QA process")
            boot_before=run("boot-before", ["shell", "settings", "get", "global", "boot_count"]).stdout.strip()
            if not boot_before.isdigit():
                raise RuntimeError("This probe requires readable Android boot evidence")
            if args.termination == "kill":
                run("terminate", ["shell", "run-as", PACKAGE, "kill", "-9", pid])
            elif args.termination == "force-stop":
                run("terminate", ["shell", "am", "force-stop", PACKAGE])
            else:
                run("terminate", ["reboot"])
            seed.wait(timeout=30)
            if args.termination == "reboot":
                run("wait-device", ["wait-for-device"])
                boot_deadline=time.monotonic()+90
                while True:
                    completed=run("boot-completed-poll", ["shell", "getprop", "sys.boot_completed"],check=False).stdout.strip()
                    changed=run("boot-count-poll", ["shell", "settings", "get", "global", "boot_count"],check=False).stdout.strip()
                    if completed=="1" and changed.isdigit() and changed!=boot_before:
                        break
                    if time.monotonic()>boot_deadline:
                        raise RuntimeError("QA emulator reboot did not complete")
                    time.sleep(1)
                identity()
            boot_after=run("boot-after", ["shell", "settings", "get", "global", "boot_count"]).stdout.strip()
            if (boot_before!=boot_after)!=(args.termination=="reboot"):
                raise RuntimeError("Observed boot identity does not match the requested termination")
            if args.termination!="reboot" and pid in run("after-termination-pid", ["shell", "pidof", PACKAGE], check=False).stdout.split():
                raise RuntimeError("Original QA process survived termination")
        prepare_foreground(run, identity, args.output, "recover")
        # The recover phase may wait up to 60 s per condition after a reboot.
        recovered = run("recover", command("recover"), timeout=180)
        output = recovered.stdout
        if "OK (1 test)" not in output or "INSTRUMENTATION_CODE: -1" not in output or "FAILURES!!!" in output:
            raise RuntimeError("Recovery assertions failed; inspect recover.log")
        final = json.loads(run("recovered-proof", ["shell", "run-as", PACKAGE, "cat", PROOF]).stdout)
        if final.get("phase") != "recovered" or (boot_before==boot_after and final.get("recoveredPid")==final.get("pid")):
            raise RuntimeError("Expected successful recovery in a new process")
        verification = {"serial": args.serial, "avd": args.avd, "mode": args.mode,
                        "termination": args.termination, "bootBefore":int(boot_before), "bootAfter":int(boot_after), "proof": final,
                        "apkSha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                        "testApkSha256": hashlib.sha256(args.test_apk.read_bytes()).hexdigest()}
        (args.output / "verification.json").write_text(json.dumps(verification, indent=2) + "\n")
        print(json.dumps(verification, indent=2))
    except Exception:
        # Collect focus/power/Activity evidence before force-stop/uninstall destroys it.
        try:
            identity()
            capture_diagnostics(run, args.output, "failure", logcat=True)
        except Exception as error:
            print(f"Could not preserve failure diagnostics: {type(error).__name__}", file=sys.stderr)
        raise
    finally:
        if installed:
            identity()
            run("cleanup-stop", ["shell", "am", "force-stop", PACKAGE])
        if seed is not None and seed.poll() is None:
            seed.wait(timeout=30)
        for package in reversed(installed):
            run("uninstall-" + package, ["uninstall", package])
        for package in installed:
            if run("remaining-" + package, ["shell", "pm", "list", "packages", package]).stdout.strip():
                raise RuntimeError("A disposable QA package remains installed")


if __name__ == "__main__":
    main()
