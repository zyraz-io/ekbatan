---
name: ekbatan-examples
description: Maintain Ekbatan example applications. Use when adding, updating, auditing, or verifying examples under ekbatan-examples across Spring Boot, Quarkus, Micronaut, Gradle, Maven, PostgreSQL, MySQL, MariaDB, native-image, sharded, saga, and job-worker variants.
---

# Ekbatan Examples

Use this skill when a change affects example applications, example docs, example wiring, or consistency across the example matrix.

## First Reads

- `ekbatan-examples/README.md`.
- The closest existing example for the same stack/build/dialect.
- `references/example-matrix.md`.
- Relevant framework docs under `docs/wiring/`, `docs/database/`, and `docs/runtime/`.

## Matrix Discipline

When changing a base wallet behavior, check every affected axis:

- Stack: Spring Boot, Quarkus, Micronaut.
- Build: Gradle, Maven.
- Dialect: PostgreSQL, MySQL, MariaDB.
- Runtime: JVM and native-named examples.
- Special cases: sharded PostgreSQL, saga, job worker.

Do not assume examples are generated unless you find a generator. Use `rg` and compare representative files directly.

## Common Patterns

- Keep domain/action/repository code semantically identical across stacks unless the stack requires wiring differences.
- Keep dialect-specific provider classes correct: PostgreSQL uses PostgreSQL providers, MySQL uses MySQL providers, MariaDB uses MariaDB providers.
- For action write serialization, acquire `KeyedLockProvider` in the caller around the full `executor.execute(...)` call.
- Keep native examples aligned with their JVM equivalents unless native-image support requires a specific difference.
- Keep README claims backed by source search.
- Avoid updating generated or build output.

## Workflow

1. Identify the affected projects with `find ekbatan-examples -mindepth 2 -maxdepth 2`.
2. Inspect one known-good example for each stack and dialect family before editing.
3. Make the smallest mechanical cross-matrix edit possible.
4. Run formatters for affected builds.
5. Run representative compiles/tests first, then broader example verification when behavior is cross-cutting.
6. If docs claim "all examples", prove it with `rg` or a small matrix script.

Use `ekbatan-verification` for the full example test matrix.
