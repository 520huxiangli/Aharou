import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'check_unit_test_results.py'
SPEC = importlib.util.spec_from_file_location('check_unit_test_results', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class UnitTestReportGateTest(unittest.TestCase):
    def check(self, xml):
        with tempfile.TemporaryDirectory() as directory:
            if xml is not None:
                Path(directory, 'TEST-fixture.xml').write_text(xml)
            return MODULE.check_reports(directory)

    def test_valid_report(self):
        self.assertEqual((1, 0), self.check('<testsuite tests="1" failures="0" errors="0"><testcase name="ok"/></testsuite>'))

    def test_missing_and_empty_reports_are_rejected(self):
        for xml in (None, '<testsuite tests="0"/>'):
            with self.assertRaises(ValueError):
                self.check(xml)

    def test_failure_or_error_is_rejected(self):
        for tag in ('failure', 'error'):
            with self.assertRaises(ValueError):
                self.check(f'<testsuite tests="1"><testcase><{tag}/></testcase></testsuite>')

    def test_skipped_only_is_not_success(self):
        with self.assertRaises(ValueError):
            self.check('<testsuite tests="1" skipped="1"><testcase><skipped/></testcase></testsuite>')

    def test_inconsistent_counts_are_rejected(self):
        with self.assertRaises(ValueError):
            self.check('<testsuite tests="2"><testcase/></testsuite>')


if __name__ == '__main__':
    unittest.main()
