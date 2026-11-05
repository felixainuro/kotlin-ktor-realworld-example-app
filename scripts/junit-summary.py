#!/usr/bin/env python3
"""Summarise JUnit XML results in the GitHub job summary and annotate each failure.

Usage: junit-summary.py <results-dir> <label>

Gradle already fails the job when a test fails. This only makes the failure easy to find:
a table at the top of the run page plus one error annotation per failing test.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    results_dir, label = sys.argv[1], sys.argv[2]
    files = sorted(glob.glob(os.path.join(results_dir, "*.xml")))
    out = []
    if not files:
        out.append(f"### {label}: no test results\nThe build failed before tests ran. See the build step log.\n")
    else:
        total = skipped = 0
        failures = []
        for path in files:
            for case in ET.parse(path).getroot().iter("testcase"):
                total += 1
                name = f"{case.get('classname', '').split('.')[-1]} > {case.get('name')}"
                problem = case.find("failure")
                if problem is None:  # Element truthiness is about children, so compare to None
                    problem = case.find("error")
                if problem is not None:
                    message = (problem.get("message") or problem.text or "").strip().splitlines()
                    failures.append((name, message[0][:300] if message else "(no message)"))
                elif case.find("skipped") is not None:
                    skipped += 1
        passed = total - skipped - len(failures)
        icon = "❌" if failures else "✅"
        out.append(f"### {icon} {label}: {passed} passed, {len(failures)} failed, {skipped} skipped\n")
        if failures:
            out.append("| Test | Failure |\n|---|---|")
            for name, message in failures:
                cell = message.replace("|", "\\|")  # keep pipes from breaking the table
                out.append(f"| `{name}` | {cell} |")
                print(f"::error title={label}: {name}::{message}")
        out.append("")
    summary = "\n".join(out)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as fh:
            fh.write(summary + "\n")
    print(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
