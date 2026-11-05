#!/usr/bin/env python3
"""Plant known bugs in the popular-articles code and require the test suite to catch each one.

Usage: python3 scripts/check-tests-catch-bugs.py [--only NAME]

A green test suite only proves something if it would have gone red on a wrong implementation.
Each entry below is a realistic mistake tied to a rule in docs/popular-articles.md. The script
applies one at a time, runs ./gradlew test, and records whether at least one test failed.
Files are restored after every run, even on Ctrl-C. Exit code 1 if any bug went unnoticed.
"""
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = "src/main/kotlin/io/realworld/app/"
REPO = SRC + "domain/repository/ArticleRepository.kt"
SERVICE = SRC + "domain/service/ArticleService.kt"
CONTROLLER = SRC + "web/controllers/ArticleController.kt"
ROUTER = SRC + "web/Router.kt"
SLUG = SRC + "ext/String.kt"

# (name, rule, file, exact text to find, replacement)
BUGS = [
    # Positive control: the original author's tests pin "slug test" -> "slug-test". If this one is
    # ever reported as missed, the harness itself is broken, not the tests.
    ("control: slugs keep spaces", "slug", SLUG,
     '.replace(NON_SLUG_CHARS, "-")', '.replace(NON_SLUG_CHARS, " ")'),
    ("least popular first", "R1", REPO,
     "favoritesCount to SortOrder.DESC,", "favoritesCount to SortOrder.ASC,"),
    ("ties go to the older article", "R2", REPO,
     "Articles.createdAt to SortOrder.DESC,\n                Articles.id to SortOrder.DESC",
     "Articles.createdAt to SortOrder.ASC,\n                Articles.id to SortOrder.ASC"),
    ("inner join drops articles with no favorites", "R3", REPO,
     "Articles.join(Favorites, JoinType.LEFT, Articles.id, Favorites.article)",
     "Articles.join(Favorites, JoinType.INNER, Articles.id, Favorites.article)"),
    ("default page size 10 instead of 20", "R4", CONTROLLER,
     'intParam("limit", default = 20,', 'intParam("limit", default = 10,'),
    ("offset off by one", "R5", REPO,
     ".limit(limit, offset)", ".limit(limit, offset + 1)"),
    ("paging values not validated", "R6", CONTROLLER,
     "require(value != null && value in min..max)", "require(value != null)"),
    ("articlesCount is the page size", "R7", SERVICE,
     "ArticlesDTO(articles, articleRepository.countAll().toInt())", "ArticlesDTO(articles, articles.size)"),
    ("popular requires login", "R8", ROUTER,
     'authenticate(optional = true) {\n            get("feed/popular")', 'authenticate {\n            get("feed/popular")'),
    ("favorited ignores the viewer", "R9", REPO,
     "favorited = id in favoritedByViewer,", "favorited = false,"),
    ("favoriting twice is not idempotent", "R10", REPO,
     "if (!already) {", "if (true) {"),
    ("unfavorite does nothing", "R10", REPO,
     "Favorites.deleteWhere { (Favorites.article eq articleId) and (Favorites.user eq userId) }", "0"),
    ("existing feed loses its login requirement", "R13", ROUTER,
     'authenticate {\n            get("feed")', 'authenticate(optional = true) {\n            get("feed")'),
]


def run_tests() -> bool:
    """True when the suite passes."""
    cmd = ["./gradlew", "test", "--console=plain", "-q"]
    return subprocess.run(cmd, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def main() -> int:
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    bugs = [b for b in BUGS if only is None or only in b[0]]
    originals = {f: (ROOT / f).read_text() for f in {b[2] for b in bugs}}

    # Every planted change must apply to exactly one place, or the check proves nothing.
    for name, _, path, find, _ in bugs:
        count = originals[path].count(find)
        if count != 1:
            print(f"Cannot plant '{name}': expected the text once in {path}, found {count}.")
            return 2

    print("Baseline: the unmodified suite must pass ...", flush=True)
    if not run_tests():
        print("The test suite fails without any planted bug. Fix that first.")
        return 2

    results = []
    try:
        for name, rule, path, find, replace in bugs:
            (ROOT / path).write_text(originals[path].replace(find, replace))
            start = time.time()
            caught = not run_tests()
            (ROOT / path).write_text(originals[path])
            results.append((name, rule, caught))
            print(f"  {'caught ' if caught else 'MISSED '} {rule:4} {name} ({time.time() - start:.0f}s)", flush=True)
    finally:
        for path, text in originals.items():
            (ROOT / path).write_text(text)

    missed = [r for r in results if not r[2]]
    lines = [f"### Planted bugs: {len(results) - len(missed)} of {len(results)} caught by the tests\n",
             "| Rule | Planted bug | Result |", "|---|---|---|"]
    lines += [f"| {rule} | {name} | {'caught' if caught else '**missed**'} |" for name, rule, caught in results]
    summary = "\n".join(lines)
    print("\n" + summary)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as fh:
            fh.write(summary + "\n")
    return 1 if missed else 0


if __name__ == "__main__":
    sys.exit(main())
