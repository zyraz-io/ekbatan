#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
skills=(
  ekbatan-maintainer
  ekbatan-examples
  ekbatan-verification
)

for target in "$root/.claude/skills" "$root/.agents/skills" "$root/plugins/ekbatan/skills"; do
  mkdir -p "$target"
  for skill in "${skills[@]}"; do
    rm -rf "$target/$skill"
    cp -R "$root/agent-skills/$skill" "$target/$skill"
  done
done

echo "Synced Ekbatan skills to .claude, .agents, and plugins/ekbatan."
