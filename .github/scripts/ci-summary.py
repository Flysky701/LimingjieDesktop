"""Summarize CI evidence without requiring an artifact upload or external packages."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def summary(root: Path, client: str, version: str, self_test: Path | None) -> str:
    if not version and (root / "app/build.gradle.kts").is_file():
        source = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
        match = re.search(r'versionName\s*=\s*"([^"]+)"', source)
        version = match.group(1) if match else "not available"
    lines = [f"## {client}", "", f"Version: `{version or 'not available'}`", ""]
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    failures = []
    reports = sorted(root.glob("**/build/test-results/**/TEST-*.xml"))
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
        for case in suite.findall("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                # Names only: full logs remain in the build step, not in the public summary.
                failures.append(f"{case.get('classname', '')}.{case.get('name', '')}")
    if reports:
        passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
        lines += ["| Tests | Passed | Failures | Errors | Skipped |", "| ---: | ---: | ---: | ---: | ---: |",
                  f"| {totals['tests']} | {passed} | {totals['failures']} | {totals['errors']} | {totals['skipped']} |", ""]
    else:
        lines += ["No JUnit reports were produced; this is not evidence of passing tests.", ""]
    if failures:
        lines += ["Failed tests:", ""] + [f"- `{name.replace('`', '')}`" for name in failures[:20]] + [""]
    ocr = root / "ocr/build/reports/ocr-eval.json"
    if ocr.is_file():
        data = json.loads(ocr.read_text(encoding="utf-8"))
        lines += ["### OCR quality", "",
                  f"Accuracy: **{data['accuracy']:.2%}**; threshold passed: **{data['accuracyThresholdPassed']}**.",
                  f"Real samples: **{data['samples']}/{data['targetSamples']}**; coverage complete: **{data['coverageComplete']}**.", ""]
    lint = sorted(root.glob("**/build/reports/lint-results*.xml"))
    if lint:
        counts = {}
        for issue in ET.parse(lint[0]).getroot().findall("issue"):
            severity = issue.get("severity", "Unknown")
            counts[severity] = counts.get(severity, 0) + 1
        lines += ["Lint: " + (", ".join(f"{key}={value}" for key, value in sorted(counts.items())) or "no issues") + ".", ""]
    if self_test and self_test.is_file():
        data = json.loads(self_test.read_text(encoding="utf-8"))
        statuses = [item["status"] for item in data["checks"]]
        lines += [f"Portable self-test `{data['version']}`: passed={data['passed']}; "
                  f"checks passed={statuses.count('passed')}, failed={statuses.count('failed')}, skipped={statuses.count('skipped')}.", ""]
    lines += ["Build, OCR, packaging and upload step statuses remain authoritative. Skipped tests are not passes.", ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--client", required=True)
    parser.add_argument("--version", default="")
    parser.add_argument("--self-test", type=Path)
    args = parser.parse_args()
    text = summary(args.root, args.client, args.version, args.self_test)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with Path(target).open("a", encoding="utf-8") as output:
            output.write(text)
    else:
        print(text)


if __name__ == "__main__":
    main()
