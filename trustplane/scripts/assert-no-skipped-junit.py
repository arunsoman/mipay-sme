#!/usr/bin/env python3
"""Fail CI when a Gradle test task ran no tests or skipped any tests."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    reports = sorted(Path(sys.argv[1]).glob("TEST-*.xml")) if len(sys.argv) == 2 else []
    if not reports:
        print("No JUnit XML reports found", file=sys.stderr)
        return 1

    tests = skips = 0
    for report in reports:
        suite = ET.parse(report).getroot()
        tests += int(suite.get("tests", "0"))
        skips += int(suite.get("skipped", "0"))
    print(f"RBAC JUnit results: {tests} tests, {skips} skipped")
    return 0 if tests > 0 and skips == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
