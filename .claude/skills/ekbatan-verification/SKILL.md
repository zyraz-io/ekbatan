---
name: ekbatan-verification
description: Choose and run Ekbatan verification suites. Use when asked to test, validate, run CI-equivalent checks, run JVM examples, run native/heavy verification, inspect GitHub Actions verification workflows, or decide which Gradle/Maven/Testcontainers commands prove a change.
---

# Ekbatan Verification

Use this skill for test planning and execution in the Ekbatan repository.

## First Reads

- `references/test-matrix.md`.
- `.github/workflows/ci.yml` for normal CI.
- `.github/workflows/heavy-verification.yml` for native/heavy coverage.
- `AGENTS.md` for project conventions.

## Default Strategy

1. Run focused tests that target the changed behavior.
2. Run root JVM tests when shared framework code changed:

   ```bash
   TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test --max-workers=1 --continue
   ```

3. Run standalone example JVM tests when examples changed:

   ```bash
   agent-skills/ekbatan-verification/scripts/run-jvm-example-tests.sh
   ```

4. Run native/heavy tests only when explicitly requested or when the change affects native-image behavior. They are expensive.

## Reporting

Report exactly what ran and what did not run. Distinguish:

- Root JVM tests.
- Standalone Gradle example JVM tests.
- Standalone Maven example JVM tests.
- Native image tests.
- GitHub Actions heavy verification.

If a command fails due to sandboxing, Docker, dependency cache, or network access, rerun with appropriate approval if available. Do not claim full coverage when only representative tests ran.

## GitHub Actions

Heavy verification is manual/scheduled and can be controlled from the GitHub Actions UI through `run_mode`:

- `respect-recent-activity`: keep the recent-commit gate.
- `force`: run the full heavy workflow regardless of recent commits.

Use the workflow rather than local native tests when the user wants CI-equivalent heavy validation.
