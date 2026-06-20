# Ekbatan Claude Code Plugin

This plugin packages Claude Code skills for working on the Ekbatan Java persistence/action framework.

## Included Skills

- `ekbatan-maintainer`: develop and review framework changes.
- `ekbatan-examples`: maintain the example application matrix.
- `ekbatan-verification`: choose and run the correct verification suites.

## Local Validation

From the repository root:

```bash
agent-skills/scripts/sync.sh
claude plugin validate --strict plugins/ekbatan
```

Try the plugin in a one-off Claude session:

```bash
claude --plugin-dir ./plugins/ekbatan
```

## Publication Checklist

1. Keep `plugins/ekbatan/.claude-plugin/plugin.json` versioned.
2. Run `agent-skills/scripts/sync.sh`.
3. Run `claude plugin validate --strict plugins/ekbatan`.
4. Create a release tag with `claude plugin tag --dry-run plugins/ekbatan`, then without `--dry-run` when ready.
5. Submit the plugin through the Claude community plugin submission flow.

Submission requires the project owner's Anthropic/Claude account access.
