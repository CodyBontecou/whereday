import unittest
from unittest.mock import patch
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
from run_qa_instrumentation import capture_failure, plan, result

C='com.bontecou.whereday.VisitOverlapFlowTest'
RAW=f'''INSTRUMENTATION_STATUS: class={C}
INSTRUMENTATION_STATUS: test=example
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class={C}
INSTRUMENTATION_STATUS: test=example
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=Time: 0.1
OK (1 test)
INSTRUMENTATION_CODE: -1
'''

class QaInstrumentationTest(unittest.TestCase):
    def test_accepts_only_complete_native_evidence(self):
        self.assertEqual([{'class':C,'test':'example'}],result(RAW,1,[C]))
        for text,count,classes in [(RAW,2,[C]),(RAW,1,[C+'Other']),
            (RAW.replace('INSTRUMENTATION_CODE: -1',''),1,[C]),
            (RAW.replace('STATUS_CODE: 0','STATUS_CODE: -2'),1,[C]),
            (RAW.replace('STATUS_CODE: 0','STATUS_CODE: -3'),1,[C]),
            (RAW.replace('STATUS_CODE: 0','STATUS_CODE: -4'),1,[C]),
            (RAW+'FAILURES!!!',1,[C])]:
            with self.assertRaises(ValueError):result(text,count,classes)
    def test_method_filter_is_exact(self):
        result(RAW,1,[C+'#example'])
        with self.assertRaises(ValueError):result(RAW,1,[C+'#other'])
    def test_only_exact_existing_ignores_are_allowed(self):
        ignored=C+'#obsolete'
        status=f'''INSTRUMENTATION_STATUS: class={C}
INSTRUMENTATION_STATUS: test=obsolete
INSTRUMENTATION_STATUS_CODE: -3
'''
        raw=status+RAW
        self.assertEqual([{'class':C,'test':'example'}],result(raw,2,[C],[ignored]))
        for text,count,allow in [(raw,2,[]),(RAW,2,[ignored]),
                                (raw,2,[C+'#another']), (status+raw,2,[ignored]),
                                (raw.replace('STATUS_CODE: -3','STATUS_CODE: -4'),2,[ignored]),
                                (raw.replace('test=obsolete','test=example'),2,[C+'#example']),
                                (raw.replace('INSTRUMENTATION_CODE: -1','INSTRUMENTATION_CODE: -10'),2,[ignored])]:
            with self.assertRaises(ValueError):result(text,count,[C],allow)
    def test_missing_completion_status_still_fails(self):
        incomplete=RAW[:RAW.index('INSTRUMENTATION_STATUS_CODE: 0')]
        with self.assertRaises(ValueError):result(incomplete,1,[C])
    def test_duplicate_completion_cannot_replace_a_missing_case(self):
        with self.assertRaises(ValueError):result(RAW+RAW,2,[C])
    def test_failure_diagnostics_continue_after_a_timeout(self):
        with TemporaryDirectory() as directory:
            with patch('run_qa_instrumentation.subprocess.run',
                       side_effect=[subprocess.TimeoutExpired('adb',15),None,None]) as run:
                capture_failure(['adb','-s','emulator-5558'],Path(directory),'com.bontecou.whereday.mapqa')
            self.assertEqual(3,run.call_count)
            self.assertTrue(all(call.kwargs['timeout']==15 for call in run.call_args_list))
            self.assertIn('Diagnostic unavailable', (Path(directory)/'failure-logcat.log').read_text())
            self.assertTrue((Path(directory)/'failure-memory.log').exists())
    def test_only_disposable_package_plans_exist(self):
        for module in ['app','data','platform']:
            target,runner,apks=plan(module)
            self.assertNotEqual('com.bontecou.whereday',target)
            self.assertTrue(all(pkg!='com.bontecou.whereday' for _,pkg in apks))
            self.assertTrue(runner.endswith('.test'))
        with self.assertRaises(ValueError):plan('release')

if __name__=='__main__':unittest.main()
