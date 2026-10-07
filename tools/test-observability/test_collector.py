"""Checks that broken/missing evidence can never appear as passing."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import dashboard as d

class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.folder=Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
    def xml(self,text):
        p=self.folder/'report.xml';p.write_text(text);return p
    def test_junit_counts_errors_and_skips(self):
        r=d.junit([self.xml('<testsuites><testsuite><testcase name="ok"/><testcase name="bad"><error message="oops"/></testcase><testcase name="skip"><skipped/></testcase></testsuite></testsuites>')])
        self.assertEqual((r['passed'],r['failed'],r['skipped']),(1,1,1))
    def test_coverage_uses_counts_and_unknown_zero_denominator(self):
        r=d.coverage(self.xml('<coverage lines-valid="10" lines-covered="3" branches-valid="0" branches-covered="0"/>'))
        self.assertEqual(r['line']['percent'],30)
        self.assertIsNone(r['branch']['percent'])
    def test_jacoco_uses_root_totals_without_double_counting(self):
        r=d.coverage(self.xml('<report><package name="p"><counter type="LINE" covered="3" missed="7"/></package><counter type="LINE" covered="3" missed="7"/></report>'))
        self.assertEqual(r['line']['total'],10)
    def collect(self,code):
        run={'id':'fixture','services':[{'id':'s','layers':{}}]}
        with patch.object(d,'persist'),patch.object(d,'artifacts',return_value=[]):
            d.collect(run,'s','unit',[d.sys.executable,'-c',code],self.folder,self.folder,['report.xml'],None,5)
        return run['services'][0]['layers']['unit']
    def test_successful_command_without_tests_is_blocked(self):
        self.assertEqual(self.collect('pass')['status'],'blocked')
    def test_failed_command_with_passing_xml_is_blocked(self):
        self.xml('<testsuite><testcase name="ok"/></testsuite>')
        self.assertEqual(self.collect('raise SystemExit(1)')['status'],'blocked')
    def test_all_skipped_is_blocked(self):
        self.xml('<testsuite><testcase><skipped/></testcase></testsuite>')
        self.assertEqual(self.collect('pass')['status'],'blocked')
    def test_malformed_report_is_blocked(self):
        self.xml('<broken')
        self.assertEqual(self.collect('pass')['status'],'blocked')
    def test_real_failure_is_visible(self):
        self.xml('<testsuite><testcase><failure message="assertion"/></testcase></testsuite>')
        self.assertEqual(self.collect('raise SystemExit(1)')['status'],'failed')
    def test_pass_requires_execution_and_report(self):
        self.xml('<testsuite><testcase name="ok"/></testsuite>')
        self.assertEqual(self.collect('pass')['status'],'passed')
if __name__=='__main__':unittest.main()
