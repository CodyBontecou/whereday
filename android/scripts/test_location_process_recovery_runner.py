import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import verify_location_process_recovery as recovery


class LocationProcessRecoveryRunnerTest(unittest.TestCase):
    def test_prepares_foreground_only_after_identity_and_preserves_before_after_evidence(self):
        events = []

        def run(label, command, **options):
            events.append((label, command, options))
            return subprocess.CompletedProcess(command, 0, "synthetic diagnostic", "")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            recovery.prepare_foreground(run, lambda: events.append("identity"), output, "seed")
            self.assertEqual("identity", events[0])
            wake = next(i for i, event in enumerate(events) if event != "identity" and event[0] == "seed-wake")
            dismiss = next(i for i, event in enumerate(events) if event != "identity" and event[0] == "seed-dismiss-keyguard")
            self.assertEqual("identity", events[wake - 1])
            self.assertEqual(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], events[wake][1])
            self.assertEqual("identity", events[dismiss - 1])
            self.assertEqual(["shell", "wm", "dismiss-keyguard"], events[dismiss][1])
            self.assertLess(wake, dismiss)
            for phase in ("before", "after"):
                evidence = json.loads((output / f"seed-{phase}-foreground-diagnostics.json").read_text())
                self.assertEqual({"power", "window", "window-policy", "activities"}, set(evidence))
                self.assertTrue(all(value == {"exitCode": 0} for value in evidence.values()))
            # No permissions, provider controls, settings changes or replacement Activity launches.
            mutations = [event[1] for event in events if event != "identity" and "dumpsys" not in event[1]]
            self.assertEqual([events[wake][1], events[dismiss][1]], mutations)

    def test_failed_wake_is_not_accepted_or_followed_by_dismissal(self):
        commands = []

        def run(label, command, **options):
            commands.append(command)
            if "KEYCODE_WAKEUP" in command:
                raise RuntimeError("synthetic wake failure")
            return subprocess.CompletedProcess(command, 0, "", "")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "wake failure"):
                recovery.prepare_foreground(run, lambda: None, Path(directory), "recover")
        self.assertNotIn(["shell", "wm", "dismiss-keyguard"], commands)

    def test_changed_identity_after_snapshots_prevents_wake(self):
        run = Mock(return_value=subprocess.CompletedProcess([], 0, "", ""))
        identity = Mock(side_effect=[None, RuntimeError("selected AVD changed")])
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "AVD changed"):
                recovery.prepare_foreground(run, identity, Path(directory), "seed")
        self.assertTrue(run.called)
        self.assertTrue(all("dumpsys" in call.args[1] for call in run.call_args_list))

    def test_unverified_identity_prevents_all_foreground_actions(self):
        run = Mock()
        identity = Mock(side_effect=RuntimeError("wrong emulator"))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "wrong emulator"):
                recovery.prepare_foreground(run, identity, Path(directory), "seed")
        run.assert_not_called()

    def test_diagnostics_record_nonzero_and_timeout_without_claiming_success(self):
        calls = []

        def run(label, command, **options):
            calls.append((command, options))
            if command == ["shell", "dumpsys", "power"]:
                raise subprocess.TimeoutExpired(command, 10)
            return subprocess.CompletedProcess(command, 7, "", "synthetic diagnostic rejection")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            recovery.capture_diagnostics(run, output, "failure", logcat=True)
            evidence = json.loads((output / "failure-diagnostics.json").read_text())
            self.assertEqual({"error": "TimeoutExpired"}, evidence["power"])
            self.assertEqual({"exitCode": 7}, evidence["logcat"])
            self.assertEqual(5, len(calls))
            self.assertTrue(all(options == dict(check=False, timeout=10) for _, options in calls))
            self.assertEqual(["shell", "logcat", "-d", "-t", "500", "-v", "threadtime"], calls[-1][0])

    def test_seed_failure_keeps_failure_and_diagnostics_before_destructive_cleanup(self):
        # Fake only external adb/aapt processes. Exercise main's real phase ordering,
        # original seed rejection, diagnostics, and cleanup without contacting a device.
        commands = []

        def run(argv, **options):
            command = argv[3:]  # adb -s emulator-5554
            commands.append(command)
            stdout = "test\nOK\n" if command == ["emu", "avd", "name"] else ""
            return subprocess.CompletedProcess(argv, 0, stdout, "")

        def aapt(argv, **options):
            if "xmltree" in argv:
                return f'android:targetPackage="{recovery.PACKAGE}"'
            package = recovery.TEST_PACKAGE if argv[-1].endswith("test.apk") else recovery.PACKAGE
            return f"package: name='{package}'"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "evidence"
            argv = ["recovery", "--adb", "adb", "--aapt2", "aapt2", "--serial", "emulator-5554",
                    "--avd", "test", "--apk", "app.apk", "--test-apk", "test.apk", "--output", str(output)]
            seed = Mock()
            seed.poll.return_value = 0  # Instrumentation exited without a seed proof.
            with patch.object(sys, "argv", argv), patch.object(recovery.subprocess, "run", side_effect=run), \
                    patch.object(recovery.subprocess, "check_output", side_effect=aapt), \
                    patch.object(recovery.subprocess, "Popen", return_value=seed):
                with self.assertRaisesRegex(RuntimeError, "Seed did not acknowledge"):
                    recovery.main()
            self.assertTrue((output / "failure-diagnostics.json").is_file())
            self.assertFalse((output / "verification.json").exists())
            snapshot = commands.index(["shell", "logcat", "-d", "-t", "500", "-v", "threadtime"])
            cleanup = commands.index(["shell", "am", "force-stop", recovery.PACKAGE])
            self.assertLess(snapshot, cleanup)
            self.assertNotIn(["reboot"], commands)
            self.assertFalse(any("kill" in command for command in commands))


if __name__ == "__main__":
    unittest.main()
