import subprocess
import sys
import unittest
from pathlib import Path


class WorkspaceProcessRunnerGuardTest(unittest.TestCase):
    def test_rejects_physical_shared_and_unsafe_targets_before_tools(self):
        script = Path(__file__).with_name('run_workspace_process_test.py')
        baseline = dict(serial='emulator-5588', avd='whereday-owned-qa', **{'server-port':'5049', 'width':'800'})
        for change in [dict(serial='owner-physical-device'), dict(avd='gitsync-qa'),
                       dict(avd='whereday-qa;echo'), {'server-port':'5037'},
                       {'server-port':'65536'}, dict(width='1080')]:
            args = baseline | change
            argv = [sys.executable, str(script), '--adb', '/nonexistent/adb', '--aapt2', '/nonexistent/aapt2',
                    '--output', '/nonexistent/evidence']
            for key, value in args.items():
                argv += ['--'+key, value]
            result = subprocess.run(argv, capture_output=True, text=True)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertNotIn('FileNotFoundError', result.stderr)


if __name__ == '__main__':
    unittest.main()
