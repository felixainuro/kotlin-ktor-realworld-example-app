# Popular articles: behaviour spec

Written before the implementation. The acceptance tests in
`src/test/kotlin/io/realworld/app/web/controllers/PopularArticlesTest.kt` were derived
from this document, by a different agent from the one that wrote the code.

## Endpoint

`GET /api/articles/feed/popular`

Returns articles ordered by how many users have favorited them, most favorited first,
in the existing multiple-articles response format:

```json
{ "articles": [ { "slug": "...", "title": "...", "favoritesCount": 3, "favorited": false,
                  "author": { "username": "...", "bio": null, "image": null, "following": false }, ... } ],
  "articlesCount": 42 }
```

## Rules

| # | Rule | Decision and reason |
|---|------|---------------------|
| R1 | Order | `favoritesCount` descending. |
| R2 | Ties | Newer article first (creation time), then higher internal id first, so the order is total and pages never overlap or skip. |
| R3 | Zero favorites | Included, after every favorited article. "Popular" is a ranking, not a filter. |
| R4 | `limit` | Optional, default 20, whole number from 1 to 100. Matches the default the existing list endpoints use. |
| R5 | `offset` | Optional, default 0, integer of 0 or more. An offset past the end returns an empty page. |
| R6 | Bad paging values | Non-numeric or fractional (`1.5`), `limit` < 1 or > 100, or `offset` < 0 return `422` with the RealWorld error body `{"errors":{"body":["..."]}}`. The old code would have thrown a 500. |
| R7 | `articlesCount` | Total number of articles that match (here: all articles), not the page size, so clients can paginate. This follows the RealWorld spec. |
| R8 | Login | Optional. Anonymous callers get `200` with `favorited: false` on every article. A malformed or badly signed token gets `401`, as on every other endpoint. |
| R9 | `favorited` | When logged in, `true` only for articles the caller has favorited. |
| R10 | Counting | `favoritesCount` counts distinct users. Favoriting twice counts once. Unfavoriting lowers the count and can change the order. |
| R11 | Empty database | `200`, `articles: []`, `articlesCount: 0`. |
| R12 | No data leaks | `author` is a profile (`username`, `bio`, `image`, `following`). It never contains email, password or token. |
| R13 | Existing feed unchanged | `GET /api/articles/feed` still requires login. Adding a route under `/feed` must not change that. |

## Supporting endpoints made real for this feature

The codebase shipped article endpoints as stubs with the logic commented out. Popular
articles needs articles and favorites to exist, so these were implemented, and only these:

- `POST /api/articles` (login required): create with `title`, `description`, `body`,
  optional `tagList`. The slug comes from the title (`"slug test"` becomes `slug-test`);
  a taken slug gets a numeric suffix (`slug-test-2`). Missing title, description or body gives `422`.
- `POST /api/articles/{slug}/favorite` and `DELETE /api/articles/{slug}/favorite`
  (login required): both idempotent, both return the article as it is after the call, unknown slug gives `404`.
- Success is `200` everywhere. "Login required" means `401` without a valid token.

## Questions the test author raised, and the answers

The acceptance tests were written from this document alone, and their author listed every
place it was ambiguous. The answers above (tie direction, fractional values, status codes,
bad tokens) came from that list. Still open, and untested:

- A token that is validly signed but belongs to a user who no longer matches it (for example
  after an email change) is treated as anonymous here, but gets `401` where login is required.
- Whether favoriting your own article should count. It does today; RealWorld doesn't say.
- Slugs for titles that are only punctuation fall back to `article`, `article-2`, ...

## Out of scope, on purpose

- A time window such as "popular this week". All-time favorites is what was asked for.
- `author.following` is always `false`. The profile and follow endpoints are still stubs in
  this codebase, so there is no way to follow anyone yet.
- Caching or a stored counter. Counting with a join is fine at this data size.
- An article whose slug is literally `feed` is shadowed by `/articles/feed`. This was
  already true for the existing feed route.
