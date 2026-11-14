"""Exercise the shared iOS test runner with fake Xcode tools (no simulator needed)."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
RUNNER = ROOT / "scripts/test-ios.sh"


class IOSTestRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.capture = self.folder / "invocation.json"
        self.result = self.folder / "results with spaces" / "IsoMe.xcresult"
        self.env = os.environ.copy()
        self.env.pop("CODE_SIGNING_ALLOWED", None)
        self.env.pop("XCODE_XCCONFIG_FILE", None)
        self.env.update({
            "PATH": f"{self.folder}:{self.env['PATH']}",
            "CAPTURE": str(self.capture),
            "DEVICES": json.dumps({"devices": {
                "com.apple.CoreSimulator.SimRuntime.watchOS-26-5": [
                    {"name": "iPhone impostor", "udid": "watch", "isAvailable": True}],
                "com.apple.CoreSimulator.SimRuntime.iOS-26-5": [
                    {"name": "iPhone unavailable", "udid": "unavailable", "isAvailable": False},
                    {"name": "iPad", "udid": "ipad", "isAvailable": True},
                    {"name": "iPhone shutdown", "udid": "shutdown", "isAvailable": True},
                    {"name": "iPhone booted", "udid": "booted", "isAvailable": True, "state": "Booted"}],
            }}),
        })
        self.tool("xcrun", 'import os\nprint(os.environ["DEVICES"])\n')
        self.tool("xcodebuild", 'import json, os, sys\n'
                  'with open(os.environ["CAPTURE"], "w") as f:\n'
                  '    json.dump({"args": sys.argv[1:], "cwd": os.getcwd()}, f)\n'
                  'sys.exit(int(os.environ.get("XCODE_EXIT", "0")))\n')

    def tool(self, name, body):
        path = self.folder / name
        path.write_text("#!/usr/bin/env python3\n" + body)
        path.chmod(0o755)

    def run_runner(self, *args):
        return subprocess.run(
            ["/bin/bash", str(RUNNER), "--result-bundle-path", str(self.result), *args],
            env=self.env, cwd=self.folder, text=True, capture_output=True,
        )

    def invocation(self):
        return json.loads(self.capture.read_text())

    def test_default_selects_booted_iphone_and_enables_signing(self):
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)
        invocation = self.invocation()
        args = invocation["args"]
        self.assertEqual(invocation["cwd"], str(ROOT))
        self.assertEqual(args[0], "test")
        self.assertEqual(args[args.index("-configuration") + 1], "Debug")
        self.assertEqual(args[args.index("-destination") + 1], "platform=iOS Simulator,id=booted")
        self.assertEqual(args[args.index("-resultBundlePath") + 1], str(self.result))
        self.assertEqual(args[-1], "CODE_SIGNING_ALLOWED=YES")

    def test_falls_back_to_available_shutdown_iphone(self):
        devices = json.loads(self.env["DEVICES"])
        devices["devices"]["com.apple.CoreSimulator.SimRuntime.iOS-26-5"].pop()
        self.env["DEVICES"] = json.dumps(devices)
        self.assertEqual(self.run_runner().returncode, 0)
        self.assertIn("platform=iOS Simulator,id=shutdown", self.invocation()["args"])

    def test_explicit_destination_and_filters_are_preserved(self):
        self.env["DEVICES"] = "not JSON"  # Explicit destination must bypass discovery.
        filters = ["-only-testing:IsoMeTests/WebhookManagerTests", "-skip-testing:IsoMeTests/Other"]
        result = self.run_runner("--destination", "platform=iOS Simulator,name=iPhone Test", *filters)
        self.assertEqual(result.returncode, 0, result.stderr)
        for value in ["platform=iOS Simulator,name=iPhone Test", *filters]:
            self.assertIn(value, self.invocation()["args"])

    def test_rejects_signing_override_before_building(self):
        result = self.run_runner("CODE_SIGNING_ALLOWED=NO")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("signing", result.stderr.lower())
        self.assertFalse(self.capture.exists())

    def test_rejects_unsigned_environment_before_building(self):
        self.env["CODE_SIGNING_ALLOWED"] = "NO"
        result = self.run_runner()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("signing", result.stderr.lower())
        self.assertFalse(self.capture.exists())

    def test_rejects_inherited_xcconfig_before_building(self):
        config = self.folder / "unsigned override.xcconfig"
        config.write_text("CODE_SIGNING_ALLOWED = NO\n")
        self.env["XCODE_XCCONFIG_FILE"] = str(config)
        result = self.run_runner()
        self.assertFalse(self.capture.exists(), "xcodebuild must not run with an inherited xcconfig")
        self.assertEqual(result.returncode, 2)
        self.assertIn("Unset XCODE_XCCONFIG_FILE", result.stderr)
        self.assertIn("overrides command-line build settings", result.stderr)

    def test_rejects_even_an_empty_inherited_xcconfig(self):
        self.env["XCODE_XCCONFIG_FILE"] = ""
        result = self.run_runner()
        self.assertFalse(self.capture.exists())
        self.assertEqual(result.returncode, 2)
        self.assertIn("Unset XCODE_XCCONFIG_FILE", result.stderr)

    def test_rejects_missing_simulator(self):
        self.env["DEVICES"] = '{"devices": {}}'
        result = self.run_runner()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No available iPhone simulator", result.stderr)
        self.assertFalse(self.capture.exists())

    def test_rejects_non_simulator_destination(self):
        result = self.run_runner("--destination", "platform=iOS,id=physical")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.capture.exists())

    def test_preserves_existing_result_bundle(self):
        self.result.mkdir(parents=True)
        marker = self.result / "keep.txt"
        marker.write_text("keep")
        result = self.run_runner()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(), "keep")
        self.assertFalse(self.capture.exists())

    def test_preserves_xcodebuild_failure_exit_code(self):
        self.env["XCODE_EXIT"] = "65"
        self.assertEqual(self.run_runner().returncode, 65)

    def test_rejects_unsupported_and_incomplete_arguments(self):
        for args in [("--destination",), ("--result-bundle-path",), ("-xcconfig", "unsigned.xcconfig")]:
            with self.subTest(args=args):
                self.assertNotEqual(self.run_runner(*args).returncode, 0)
                self.assertFalse(self.capture.exists())


if __name__ == "__main__":
    unittest.main()
