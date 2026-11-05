#!/usr/bin/env python3
"""Fail CI when a RealWorld spec assertion that used to pass starts failing.

Usage: spec-ratchet.py <newman-report.json> <passing-list.txt> [--write]

The bundled Postman suite covers the whole RealWorld API, but this codebase still has stub
endpoints, so "every assertion passes" cannot be the gate yet. Instead the passing list records
every assertion known to pass. Any of them failing (or not running) is a regression and fails
the build. Newly passing assertions are reported; add them with --write in the same change,
so the bar only ever goes up.
"""
import json
import os
import sys
from collections import Counter, defaultdict


def results(report_path):
    report = json.load(open(report_path, encoding="utf-8"))
    folder_of = {}

    def walk(items, top=None):
        for item in items:
            if "item" in item:
                walk(item["item"], top or item["name"])
            else:
                folder_of[item["id"]] = top or "(root)"

    walk(report["collection"]["item"])
    seen = Counter()
    passing, failing = [], []
    per_folder = defaultdict(lambda: [0, 0])
    for execution in report["run"]["executions"]:
        folder = folder_of.get(execution["item"]["id"], "(unknown)")
        request = execution["item"]["name"]
        for assertion in execution.get("assertions", []):
            key = f"{folder} / {request} / {assertion['assertion']}"
            seen[key] += 1
            if seen[key] > 1:
                key += f" #{seen[key]}"
            per_folder[folder][0] += 1
            if assertion.get("error"):
                failing.append(key)
            else:
                passing.append(key)
                per_folder[folder][1] += 1
    return passing, failing, per_folder


def main() -> int:
    report_path, list_path = sys.argv[1], sys.argv[2]
    passing, failing, per_folder = results(report_path)
    if "--write" in sys.argv:
        with open(list_path, "w", encoding="utf-8") as fh:
            fh.write("\n".join(sorted(passing)) + "\n")
        print(f"Wrote {len(passing)} passing assertions to {list_path}")
        return 0

    expected = {line.strip() for line in open(list_path, encoding="utf-8") if line.strip()}
    now_passing = set(passing)
    regressions = sorted(expected - now_passing)
    newly_passing = sorted(now_passing - expected)

    lines = [f"### RealWorld API spec: {len(passing)} of {len(passing) + len(failing)} assertions pass\n",
             "| Folder | Passing |", "|---|---|"]
    lines += [f"| {folder} | {ok}/{total} |" for folder, (total, ok) in per_folder.items()]
    lines.append("")
    if regressions:
        lines.append(f"**❌ {len(regressions)} assertion(s) that used to pass now fail or did not run:**\n")
        lines += [f"- {key}" for key in regressions]
        for key in regressions:
            print(f"::error title=Spec regression::{key}")
    else:
        lines.append(f"✅ No regressions against the {len(expected)} known-passing assertions.")
    if newly_passing:
        lines.append(f"\n**{len(newly_passing)} newly passing.** Run `python3 scripts/spec-ratchet.py "
                     f"<report> {list_path} --write` and commit the list to raise the bar:\n")
        lines += [f"- {key}" for key in newly_passing]
    summary = "\n".join(lines)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as fh:
            fh.write(summary + "\n")
    print(summary)
    return 1 if regressions else 0


if __name__ == "__main__":
    sys.exit(main())
