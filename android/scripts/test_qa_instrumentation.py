import unittest
from run_qa_instrumentation import plan, result

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
    def test_only_disposable_package_plans_exist(self):
        for module in ['app','data','platform']:
            target,runner,apks=plan(module)
            self.assertNotEqual('com.bontecou.whereday',target)
            self.assertTrue(all(pkg!='com.bontecou.whereday' for _,pkg in apks))
            self.assertTrue(runner.endswith('.test'))
        with self.assertRaises(ValueError):plan('release')

if __name__=='__main__':unittest.main()
