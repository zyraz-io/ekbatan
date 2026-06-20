#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: run-jvm-example-tests.sh [--all|--gradle|--maven] [--log-dir DIR]

Runs standalone Ekbatan example JVM tests from the repository root.
Projects with "native" in the directory name are still run with JVM test tasks.

Options:
  --all          Run Gradle and Maven examples (default)
  --gradle       Run only standalone Gradle examples
  --maven        Run only standalone Maven examples
  --log-dir DIR  Store per-project logs in DIR
  -h, --help     Show this help
USAGE
}

mode="all"
log_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all)
      mode="all"
      shift
      ;;
    --gradle)
      mode="gradle"
      shift
      ;;
    --maven)
      mode="maven"
      shift
      ;;
    --log-dir)
      log_dir="${2:?missing log directory}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
timestamp="$(date +%Y%m%d%H%M%S)"
log_dir="${log_dir:-/tmp/ekbatan-example-jvm-tests-$timestamp}"
mkdir -p "$log_dir"

export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"

failed=0

run_gradle() {
  while IFS= read -r settings; do
    local dir name logfile
    dir="${settings%/settings.gradle.kts}"
    name="${dir#ekbatan-examples/}"
    logfile="$log_dir/${name}.gradle.log"
    printf 'GRADLE START %s\n' "$name"
    if (cd "$repo_root/$dir" && ./gradlew test --max-workers=1 --continue > "$logfile" 2>&1); then
      printf 'GRADLE PASS  %s\n' "$name"
    else
      local code=$?
      printf 'GRADLE FAIL  %s exit=%s log=%s\n' "$name" "$code" "$logfile"
      failed=1
    fi
  done < <(cd "$repo_root" && find ekbatan-examples -mindepth 2 -maxdepth 2 -name settings.gradle.kts | sort)
}

run_maven() {
  while IFS= read -r pom; do
    local dir name logfile
    dir="${pom%/pom.xml}"
    name="${dir#ekbatan-examples/}"
    logfile="$log_dir/${name}.maven.log"
    printf 'MAVEN START %s\n' "$name"
    if (cd "$repo_root/$dir" && ./mvnw -q test > "$logfile" 2>&1); then
      printf 'MAVEN PASS  %s\n' "$name"
    else
      local code=$?
      printf 'MAVEN FAIL  %s exit=%s log=%s\n' "$name" "$code" "$logfile"
      failed=1
    fi
  done < <(cd "$repo_root" && find ekbatan-examples -mindepth 2 -maxdepth 2 -name pom.xml | sort)
}

case "$mode" in
  all)
    run_gradle
    run_maven
    ;;
  gradle)
    run_gradle
    ;;
  maven)
    run_maven
    ;;
esac

printf 'LOG_DIR %s\n' "$log_dir"
exit "$failed"
