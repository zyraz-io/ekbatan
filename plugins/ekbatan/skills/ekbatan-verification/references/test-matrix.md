# Ekbatan Test Matrix

## Normal CI

Root CI runs:

```bash
./gradlew clean build --stacktrace
```

For local broad JVM verification, prefer:

```bash
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test --max-workers=1 --continue
```

Use `--max-workers=1` for Testcontainers-heavy runs to reduce Docker/database contention.

## Standalone Example JVM Tests

The examples are separate builds. Root Gradle tests do not cover every standalone example.

Run all example JVM tests:

```bash
agent-skills/ekbatan-verification/scripts/run-jvm-example-tests.sh
```

Run only one side:

```bash
agent-skills/ekbatan-verification/scripts/run-jvm-example-tests.sh --gradle
agent-skills/ekbatan-verification/scripts/run-jvm-example-tests.sh --maven
```

Projects with `native` in their directory name still run JVM tests when using `test` or `verify`. Native-image tests are separate.

## Native/Heavy Verification

Root native tests:

```bash
export GRAALVM_HOME="$JAVA_HOME"
./gradlew \
  -Dorg.gradle.java.installations.paths="$JAVA_HOME" \
  -Dorg.gradle.java.installations.auto-detect=false \
  nativeTest --max-workers=1 --continue --stacktrace \
  -x :ekbatan-integration-tests-event-pipeline-debezium-kafka-json:nativeTest \
  -x :ekbatan-integration-tests-event-pipeline-debezium-kafka-avro-smt:nativeTest \
  -x :ekbatan-integration-tests-event-pipeline-debezium-kafka-protobuf-smt:nativeTest
```

Standalone native examples use project-specific tasks:

- Micronaut Gradle native: `./gradlew test nativeTest --stacktrace`
- Quarkus Gradle native: `./gradlew test testNative --stacktrace`
- Spring Boot Gradle native: `./gradlew test nativeTest --stacktrace`
- Quarkus Maven native: `./mvnw -B -ntp -Dnative verify`
- Spring Boot Maven native: `./mvnw -B -ntp verify && ./mvnw -B -ntp -PnativeTest test`
- Micronaut Maven native: `./mvnw -B -ntp verify && ./mvnw -B -ntp -Dpackaging=native-image -DskipTests package`

Prefer the GitHub Actions heavy workflow for full native coverage.

## Output Discipline

When reporting verification, include:

- Command.
- Scope covered.
- Whether native tests were included.
- Whether examples were included.
- Failing module/test and report path if any command failed.
