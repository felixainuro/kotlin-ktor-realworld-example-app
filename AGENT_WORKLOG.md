# Agent work log

**Walkthrough video:** _link to be added_

**Feature:** `GET /api/articles/feed/popular`. Spec: [docs/popular-articles.md](docs/popular-articles.md).
**Time:** about 45 minutes of wall-clock time from clone to final commit, plus a short planning conversation before it. The agents worked in parallel where they could.

## Harness and models

- **Harness:** Claude Code in the Claude desktop app.
- **Models:** Claude Fable 5.1 for the first read of the exercise and the repo. Claude Opus 5.5 for the build.
- **Shape:** one lead agent with full access, plus two subagents that were deliberately given less context than the lead had:
  - **Test author.** It got the written spec and the test infrastructure. It worked in its own git worktree, never saw the implementation, and wasn't allowed to run tests.
  - **Cold reviewer.** It got the diff and the spec. It was read-only and got none of the lead's conclusions.

## Representative instructions

1. **To the lead agent, before any code.** I asked whether we could build something that stands out. The agent argued against a bigger feature and for a stronger verification workflow: baseline first, spec before code, a separate test author, planted bugs, a cold review, and a CI gate. I agreed and told it to go.
2. **Lead to test author.** "Derive every test from the written spec, not from guesses about an implementation. … In this worktree those endpoints are still stubs, so your tests are expected to FAIL here."
3. **Lead to reviewer.** "You have no prior context on purpose. … For each finding give file:line, a concrete input that triggers it, and how sure you are."

## Where agents changed the approach

- **Reading first changed the scope.** About 10 minutes in, the lead agent found that the "Medium-like API" in the brief was mostly stubs. The build couldn't resolve its dependencies. Every test class was `@Ignore`d. Registration and login rejected every valid request. Nothing was served under `/api`. All three suggested features need articles and favorites, so the real job became the smallest slice that makes one feature honest.
- **"It compiles" was not accepted as done.** The first build fix compiled cleanly, but starting the app showed a crash. Dependabot had moved H2 to 2.x, which the old Exposed can't talk to, and nothing caught it because no test ran.
- **Verification was designed before the code.** The rule table in the spec exists so tests and planted bugs can each point at a rule.

## How the work was verified

Each check answers a different question. None of them relies on the agent that wrote the code saying it works.

| Check | Question it answers | Result |
|---|---|---|
| Baseline before any change (HTTP probe and Postman suite) | What worked before? | `GET /tags` only. Postman: 0 of 17 assertions. |
| Original author's tests, enabled unchanged | Does the code match what this repo already expected? | 4 of 4 pass (create, favorite, unfavorite, tags) |
| Acceptance tests from a separate agent | Does the code match the spec? | 31 of 31 pass. All 31 fail against the stubs, each for the right reason. |
| Planted bugs (`scripts/check-tests-catch-bugs.py`) | Would the tests notice a wrong implementation? | 0 of 12 caught before the acceptance tests. 12 of 12 after, plus a positive control. |
| Cold review by a fresh agent | What did everyone else miss? | 11 findings plus build notes, 10 reproduced over HTTP. Fixed: 9 findings and 2 build notes. 8 of the fixes have a regression test that fails on the old code, and the rest were checked by hand. 2 findings recorded but not fixed. |
| RealWorld Postman suite | Did the existing contract regress? | 88 of 104 assertions pass, gated by a ratchet in CI |
| JDK matrix and CI | Does it hold on a clean machine? | Green on 17 and 21: 47 pass, 17 skipped stubs. Compiled against the JDK 17 API on both. The [first CI run](https://github.com/felixainuro/kotlin-ktor-realworld-example-app/actions/runs/41946711496) passed all four jobs in about 3.5 minutes. |

The lead agent read the acceptance tests before trusting them. The ranking expectations are written out by hand, not computed by code that could share a bug with the implementation. The pagination test requires pages to equal exact consecutive slices for page sizes 1 through 4.


## What the agents got wrong or made me reconsider

- **The lead agent's own brief had a bad example.** It told the test author to use `alice@x.com`, but the app's email check rejects one-letter domains. The agent's own smoke run caught it and corrected the test author mid-task, before any test depended on it.
- **The first dependency fix compiled but crashed.** The closest Exposed release on Maven Central compiled fine, then failed at startup against H2 2.x. Only running the app showed it.
- **The first fix for Ktor's opt-in error was to sprinkle `@OptIn` annotations.** It was replaced with returning Ktor's general engine type, which removes the opt-in error for every caller.
- **The spec had 16 gaps.** The test author found them, including the tie-break direction, fractional paging values, status codes and bad tokens. Where the code already decided the answer, it went into the spec. The rest are listed there as open.
- **The cold review found what every other check missed.** It reproduced its findings over HTTP or by reading the library source. Most sat outside the spec, so the acceptance tests and planted bugs had no reason to catch them. The one inside the spec, R5 for huge offsets, slipped because no test tried an offset past 2^31:
  - **Password change.** Fixing the inverted validation made password changes possible. They stored the password unhashed and locked the user out, and the original test passed because it only checked the email.
  - **Password leaks.** Register echoed the plain password, and login returned the hash. The original tests asserted that leak, and they now assert its absence.
  - **Same-title race.** Two creates with the same title at the same moment returned 500.
  - **Non-Latin titles.** Every Cyrillic or Chinese title got the slug `article`.
  - **Large offsets.** Offsets past 2^31 returned 422 instead of an empty page.
  - **Old tags path.** `GET /tags`, the one endpoint that worked before, broke when routes moved under `/api`. The lead agent's own commit message wrongly said nothing working was lost.
  - **JDK 21 APIs.** The JDK 21 build could compile JDK 21-only calls that would crash on 17.

  `ReviewFindingsTest` holds one test per fixed finding. All 8 fail against the code before the fixes and pass after.
- **Small slips the tools caught:** a Python 3.9 f-string error, a deleted workflows folder, and an unused shell variable that actionlint flagged.

## Decisions and trade-offs

- **Routes moved under `/api`.** That's where the RealWorld spec, the Postman suite and the repo's own tests expect them. The old root paths only ever served `GET /tags`.
- **Exposed upgraded to 0.45, not H2 downgraded.** Going back to H2 1.4 would have reintroduced known CVEs. 0.45 is the last Exposed release before its query API changed, so the migration stayed small.
- **Bytecode target is 17,** the lowest JDK in the CI matrix, so one build output runs on 17 and 21. JDK 16 is end-of-life.
- **`articlesCount` is the total,** per the RealWorld spec. The original author's ignored list tests assume the page size, and the spec doc records this choice.
- **`author.following` is always `false`.** The follow endpoints are still stubs, so a computed value couldn't be tested.
- **The Postman suite gates on a ratchet, not on "all green".** Stub endpoints make "all green" impossible today. `spec-api/passing-assertions.txt` records what passes, CI fails on any regression, and the list can only grow.
- **Not fixed, out of scope, and recorded here so nobody assumes otherwise:**
  - The JWT signing key is hard-coded, so anyone with the source can mint a token for any user. The reviewer proved this against the new create endpoint.
  - Passwords are an unsalted HMAC with that same key.
  - The app starts an H2 PostgreSQL-protocol server in production code.
  - An expired token gets a 401 on the public feed.
  - Updating to a taken email or username returns 500.

  The fix for the first two is real secret handling plus bcrypt or Argon2. That's a change of its own, not something to fold into a feature.
- **`GET /tags` stays available at its old path** as an alias, because it was the only endpoint that worked before the move to `/api`.

## What I would do differently next time

- **Make the test port configurable on day one.** It's hard-coded to 8080, so the test author and the reviewer could compile but not run tests in parallel with the lead.
- **Start the planted-bug list with the spec.** Each rule already maps to a bug, so the list could have been written before the code.
- **Put the curl smoke script in the repo as a test from the start,** instead of keeping it in local notes.
- **Spend the saved time next on the stub list endpoints.** `GET /api/articles` is the obvious next slice, and the ratchet would record its gains.
