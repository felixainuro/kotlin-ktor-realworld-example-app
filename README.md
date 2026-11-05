[![CI](https://github.com/felixainuro/kotlin-ktor-realworld-example-app/actions/workflows/ci.yml/badge.svg)](https://github.com/felixainuro/kotlin-ktor-realworld-example-app/actions/workflows/ci.yml)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/5b6503dfa3024a0dbbf173e333f80bcf)](https://app.codacy.com/app/Rudge/kotlin-ktor-realworld-example-app?utm_source=github.com&utm_medium=referral&utm_content=Rudge/kotlin-ktor-realworld-example-app&utm_campaign=Badge_Grade_Dashboard)
[![BCH compliance](https://bettercodehub.com/edge/badge/Rudge/kotlin-ktor-realworld-example-app?branch=master)](https://bettercodehub.com/)

# ![RealWorld Example App](logo.png)

> ### Kotlin + Ktor codebase containing real world examples (CRUD, auth, advanced patterns, etc) that adheres to the [RealWorld](https://github.com/gothinkster/realworld) spec and API

### [RealWorld](https://github.com/gothinkster/realworld)

This codebase was created to demonstrate a fully fledged fullstack application built with **Kotlin + Ktor + Kodein + Exposed** including CRUD operations, authentication, routing, pagination, and more.

We've gone to great lengths to adhere to the **Kotlin + Ktor** community styleguides & best practices.

For more information on how to this works with other frontends/backends, head over to the [RealWorld](https://github.com/gothinkster/realworld) repo.

## Project status (November 2026)

This codebase is a partial implementation. Be aware of what works before building on it.

| Area | Status |
|---|---|
| Users: register, login, current user, update | Working, covered by `UserControllerTest` and `ReviewFindingsTest` |
| Tags | Working |
| Articles: create, favorite, unfavorite | Working, added for the popular feed |
| `GET /api/articles/feed/popular` | Working, spec in [docs/popular-articles.md](docs/popular-articles.md) |
| Articles: list, filter, feed, get, update, delete | Stubs, logic still commented out |
| Comments, profiles, follow | Stubs |

The RealWorld Postman suite currently passes 88 of 104 assertions. CI fails if any assertion
that passes today stops passing (see `spec-api/passing-assertions.txt`).

# How it works

The application was built with:

  - [Kotlin](https://github.com/JetBrains/kotlin) as programming language
  - [Ktor](https://github.com/ktorio/ktor) as web framework
  - [Kodein](https://github.com/Kodein-Framework/Kodein-DI) as dependency injection framework
  - [Jackson](https://github.com/FasterXML/jackson-module-kotlin) as data bind serialization/deserialization
  - [Java-jwt](https://github.com/auth0/java-jwt) for JWT spec implementation
  - [HikariCP](https://github.com/brettwooldridge/HikariCP) as datasource to abstract driver implementation
  - [H2](https://github.com/h2database/h2database) as database
  - [Exposed](https://github.com/JetBrains/Exposed) as Sql framework to persistence layer
  - [slugify](https://github.com/slugify/slugify)

Tests:

  - [junit](https://github.com/junit-team/junit4)
  - [Unirest](https://github.com/Kong/unirest-java) to call endpoints in tests

#### Structure
      + config/
          All app setups. Ktor, Kodein and Database
      + domain/
        + repository/
            Persistence layer and tables definition
        + service/
            Logic layer and transformation data
      + ext/
          Extension of String for email validation
      + utils/
          Jwt and Encrypt classes
      + web/
        + controllers
            Classes and methods to mapping actions of routes
        Router definition to features and exceptions
      - App.kt <- The main class

# Getting started

You need JDK 17 or newer. CI runs on JDK 17 and 21. The Gradle wrapper downloads Gradle itself.

The server starts on [8080](http://localhost:8080) and serves the API under `/api`.

Build and run the tests:
> ./gradlew clean build

Start the server:
> ./gradlew run

Optional environment variables:

| Variable | Effect |
|---|---|
| `JWT_SECRET` | Signing key for login tokens, at least 32 characters. Without it a random key is generated at startup, so tokens end with the process. That matches the in-memory database. |
| `H2_PG_SERVER=true` | Starts H2's PostgreSQL-protocol server on localhost:5435 for inspecting the database. Off by default. |

Run the RealWorld Postman suite in [spec-api](spec-api) against the running server (needs Node.js):
> APIURL=http://localhost:8080/api ./spec-api/run-api-tests.sh

Check that the tests catch known bugs in the popular feed (plants each bug, runs the suite, restores the code):
> python3 scripts/check-tests-catch-bugs.py

## Continuous integration

`.github/workflows/ci.yml` runs on every push to master and every pull request:

- **Build and test** on JDK 17 and 21, with Gradle caching. Failures are listed in the job summary and annotated.
- **Tests catch planted bugs** runs `scripts/check-tests-catch-bugs.py`.
- **RealWorld API spec** starts the app, runs the Postman suite and fails on any regression against `spec-api/passing-assertions.txt`.

# Help

Please fork and PR to improve the code.
