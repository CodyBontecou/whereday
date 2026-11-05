#!/usr/bin/env python3
"""Run the disposable platform.test clear proof across three force-stopped processes."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

PACKAGE = "com.bontecou.whereday.platform.test"
CLASS = "com.bontecou.whereday.platform.JournalClearProcessRecoveryTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("An explicitly selected emulator serial is required; physical devices are not supported")
    if not args.avd.strip() or not args.apk.is_file() or args.apk.name != "platform-debug-androidTest.apk":
        parser.error("Expected the named QA AVD and platform instrumentation APK")
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]

    def run(label, command):
        result = subprocess.run(adb + command, capture_output=True, text=True)
        (args.output / f"{label}.log").write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError(f"{label} failed; inspect its preserved log")
        return result.stdout

    name = run("avd", ["emu", "avd", "name"]).splitlines()[0].strip()
    if name != args.avd:
        raise RuntimeError("The selected serial no longer belongs to the expected QA AVD")
    if run("preinstalled", ["shell", "pm", "list", "packages", PACKAGE]).strip():
        raise RuntimeError("Refusing to replace an already installed platform test package")
    installed = False
    try:
        run("install", ["install", "-r", str(args.apk.resolve())]); installed = True
        for phase in ("seed", "recover", "completed"):
            output = run(phase, ["shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS,
                                 "-e", "clearRecoveryPhase", phase, f"{PACKAGE}/androidx.test.runner.AndroidJUnitRunner"])
            if "OK (1 test)" not in output or "INSTRUMENTATION_CODE: -1" not in output or "FAILURES!!!" in output:
                raise RuntimeError(f"{phase} did not prove success; inspect its preserved log")
            run(f"stop-{phase}", ["shell", "am", "force-stop", PACKAGE])
            print(f"Clear recovery phase passed: {phase}", flush=True)
        proof = run("process-proof", ["shell", "run-as", PACKAGE, "cat", "no_backup/qa-clear-proof"])
        rows = [line.split(":") for line in proof.splitlines()]
        if [row[0] for row in rows] != ["seed", "recover", "completed"] or len({row[1] for row in rows}) != 3:
            raise RuntimeError("Expected three distinct successful instrumentation processes")
        digest = hashlib.sha256()
        with args.apk.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        (args.output / "verification.json").write_text(json.dumps({
            "serial": args.serial, "avd": args.avd, "package": PACKAGE,
            "phases": [row[0] for row in rows], "pids": [int(row[1]) for row in rows],
            "apkSha256": digest.hexdigest(),
            "pendingClearResumed": True, "postCompletionHistoryPreserved": True,
        }, indent=2) + "\n")
    finally:
        if installed:
            run("cleanup-stop", ["shell", "am", "force-stop", PACKAGE])
            run("uninstall", ["uninstall", PACKAGE])
    if run("remaining-packages", ["shell", "pm", "list", "packages", PACKAGE]).strip():
        raise RuntimeError("Disposable platform test package remains installed")


if __name__ == "__main__":
    main()
