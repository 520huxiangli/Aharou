#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def check_reports(directory):
    reports = sorted(Path(directory).glob("TEST-*.xml"))
    if not reports:
        raise ValueError("没有单测 XML 报告")
    tests = failures = errors = skipped = 0
    for report in reports:
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite")) if root.tag == "testsuites" else []
        if not suites:
            raise ValueError(f"报告结构无效：{report.name}")
        for suite in suites:
            declared = {}
            for key in ("tests", "failures", "errors", "skipped"):
                raw = suite.get(key, "0")
                try:
                    declared[key] = int(raw)
                except ValueError:
                    raise ValueError(f"报告计数无效：{report.name}/{key}") from None
                if declared[key] < 0:
                    raise ValueError(f"报告计数为负数：{report.name}/{key}")
            cases = list(suite.findall("testcase"))
            if declared["tests"] != len(cases):
                raise ValueError(f"报告用例计数不一致：{report.name}")
            tests += declared["tests"]
            failures += max(declared["failures"], len(suite.findall(".//failure")))
            errors += max(declared["errors"], len(suite.findall(".//error")))
            skipped += max(declared["skipped"], len(suite.findall(".//skipped")))
    if tests == 0 or tests <= skipped:
        raise ValueError("没有实际执行的单测用例")
    if failures or errors:
        raise ValueError(f"单测失败：tests={tests} failures={failures} errors={errors}")
    return tests, skipped


def main(argv):
    if len(argv) != 2:
        print("用法：check_unit_test_results.py <报告目录>", file=sys.stderr)
        return 2
    try:
        tests, skipped = check_reports(argv[1])
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(f"[OK] 单测报告有效：{tests} 项，跳过 {skipped} 项，无失败或错误")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
