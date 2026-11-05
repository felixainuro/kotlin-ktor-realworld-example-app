# Agent work log

**Walkthrough video:** _link to be added_

**Feature:** `GET /api/articles/feed/popular` ([spec](docs/popular-articles.md)). About 80 minutes from clone to the last commit.

## Harnesses and models

- **Harness:** Claude Code in the Claude desktop app. Claude Fable 5.1 read the brief and the repo; Claude Opus 5.5 did the build.
- **Lead agent:** full access. It read the code, repaired the build, implemented the feature and wrote the CI.
- **Two subagents, each given less context on purpose:**
  - **Test author.** It got only the spec and the test setup, worked in its own git worktree and never saw the code.
  - **Cold reviewer.** It got only the diff, was read-only and got none of the lead's conclusions.

## Representative instructions

- **Me to the lead, before any code:** "Can we build something that stands out?" It argued against a bigger feature and for stronger proof that the work is right, and I agreed.
- **Lead to test author:** "Derive every test from the written spec, not from guesses about an implementation."
- **Lead to reviewer:** "You have no prior context on purpose. Give file:line, a concrete input that triggers it, and how sure you are."

## Where agents changed the approach

- **Reading first changed the scope.** The "Medium-like API" in the brief was mostly stubs. The build didn't resolve, every test was skipped, login rejected valid users, and nothing was under `/api`. So the job became getting it to build, then the smallest slice that makes one feature real.
- **Proof was designed before the code.** The spec numbers its rules R1 to R13, so every test and every planted bug points at one rule.

## How I verified it

| Check | Result |
|---|---|
| Baseline before any change | Only `GET /tags` worked. RealWorld suite: 0 of 17 assertions. |
| Original author's tests, enabled unchanged | 4 of 4 pass |
| Acceptance tests from the separate test author | 31 of 31 pass. All 31 fail against the stubs. |
| Planted bugs, one per rule (`scripts/check-tests-catch-bugs.py`) | 0 of 12 caught before the acceptance tests. 12 of 12 after, plus a positive control. |
| Cold review | 11 findings. 10 were fixed and 1 was kept as a decision. The 11 tests in `ReviewFindingsTest` all fail on the old code. The single-snapshot read and the JDK 17 API check were verified by hand. |
| RealWorld Postman suite | 88 of 104 pass. The rest are stub endpoints. CI fails if any passing assertion regresses. |
| CI on JDK 17 and 21 | Green, 50 tests on each. |

## What the agents got wrong

- **A bad example in the lead's own brief.** It gave the test author `alice@x.com`, which the app's email check rejects. Its smoke test caught it before any test was written.
- **A fix that compiled but crashed.** The first dependency fix compiled, then crashed at startup. "It compiles" was not accepted as done.
- **Gaps in the lead's work, found by the cold review:**
  - Fixing login made password changes possible, but they were stored unhashed and locked users out.
  - Register echoed the plain password.
  - Two creates with the same title at once returned 500.
  - Non-Latin titles all got the slug `article`.
  - The JWT key was hard-coded, so anyone could forge a login.
- **A false claim in a commit message.** The lead claimed in a commit message that nothing working was lost. The reviewer showed that `GET /tags` broke.

## Decisions

- **Routes moved under `/api`,** where the spec and every test expect them. `GET /tags` stays as an alias at its old path.
- **Exposed 0.45, not an older H2.** Downgrading H2 would have reintroduced known CVEs. JDK target is 17, the lowest in the CI matrix.
- **`articlesCount` is the total, not the page size,** per the RealWorld spec.
- **Left as is:**
  - The remaining stub endpoints.
  - `author.following` stays `false`, because following isn't built yet.
  - An expired token gets 401 even on the public feed, so the client knows to drop it.

## Next time

- **A configurable test port from the start.** It's hard-coded to 8080, which kept the subagents from running tests in parallel with the lead.
- **The planted-bug list written with the spec,** before any code.
- **The cold review run after every slice, not once at the end.** It found more than any other check.
