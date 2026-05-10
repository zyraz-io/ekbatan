# Releasing Ekbatan

This document covers the release pipeline: one-time setup, the per-release workflow, and the safety nets in between. The publishing surface is two targets:

- **15 jars to Maven Central** under groupId `io.github.zyraz-io` (via Sonatype Central Portal, stage-and-confirm). The Java packages in the source tree are `io.ekbatan.*` — Maven groupId and Java package don't need to match, and we kept the cleaner Java naming.
- **2 SMT shadow jars to GitHub Releases** as drop-in assets for Kafka Connect's `plugin.path`.

JReleaser orchestrates both from one config block in the root `build.gradle.kts`.

---

## Phase 0 — one-time setup

Do these once, before the first release. They never need to be repeated.

### 1. Sonatype Central Portal account

Sign up at <https://central.sonatype.com>. The Central Portal is the post-2024 publishing path; do not use the legacy OSSRH.

### 2. Claim the `io.github.zyraz-io` namespace

Sonatype's namespace convention is reverse-DNS of a thing you control. For projects hosted under a GitHub org, `io.github.<org>` is verified by creating a one-off repo whose name matches the verification code Sonatype generates.

In the Portal UI:
1. **Namespaces** → **Add Namespace** → enter `io.github.zyraz-io`.
2. Sonatype shows a verification code (looks like a short alphanumeric string, e.g. `abc123def456`).
3. Create a **public, empty** repo at `https://github.com/zyraz-io/<verification-code>` (use the exact code as the repo name).
4. Back in the Portal, click **Verify Namespace**. Sonatype checks the repo exists; verification is usually instant.

After verification you can delete the verification repo (or leave it — either is fine). The namespace is permanent.

### 3. Generate a GPG key pair

```bash
gpg --full-generate-key
# Choose: RSA and RSA, 4096 bits, no expiry (or 5y), name + email
```

Note the key ID (long form):

```bash
gpg --list-secret-keys --keyid-format LONG
# Look for: sec   rsa4096/<KEY_ID>
```

### 4. Publish the public half to a keyserver

Maven Central validates signatures against public keyservers. Publish to at least one of:

```bash
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

`keys.openpgp.org` is the most widely-used one. Other servers tend to cross-sync.

### 5. Export the key pair for CI

JReleaser needs both halves as env vars. Export them in ASCII-armored form:

```bash
gpg --armor --export <KEY_ID> > public-key.asc
gpg --armor --export-secret-keys <KEY_ID> > secret-key.asc
```

You'll paste the contents of these files into GitHub repo secrets in the next step. After that, **delete** the `.asc` files from your machine — they're sensitive.

### 6. Generate a Central Portal User Token

In the Portal UI: **View Account** → **Generate User Token**. You get a username/password pair separate from your Portal login — this is what the Publisher API uses. Save both halves now; they're shown only once.

### 7. Store six secrets on the GitHub repository

Go to <https://github.com/zyraz-io/ekbatan/settings/secrets/actions> and add:

| Secret name | Value |
|---|---|
| `MAVENCENTRAL_USERNAME` | The User Token's username (from step 6) |
| `MAVENCENTRAL_PASSWORD` | The User Token's password (from step 6) |
| `GPG_PUBLIC_KEY` | Full contents of `public-key.asc` (including `-----BEGIN ... -----` and `-----END ... -----` lines) |
| `GPG_PRIVATE_KEY` | Full contents of `secret-key.asc` |
| `GPG_PASSPHRASE` | The passphrase you used when generating the key |

`GITHUB_TOKEN` is auto-provided by GitHub Actions — you don't add it manually.

That's it for Phase 0. Done once, reused for every release forever.

---

## Optional: client-side tag-validation hook

The release workflow already verifies that the pushed tag matches `gradle.properties` (see the "Verify gradle.properties version matches tag" step in `release.yml`). The repo also ships a **pre-push hook** at `.githooks/pre-push` that runs the same check **before the tag leaves your machine** — catches the mistake one CI run earlier.

To enable, run once per clone:

```bash
git config core.hooksPath .githooks
```

That points git at the version-controlled hooks directory (instead of the default `.git/hooks/`, which isn't tracked). From then on, any `git push` that includes a tag matching `v<X.Y.Z>` is checked against `gradle.properties` at the tagged commit — mismatches abort the push.

To bypass (rarely needed, e.g. pushing a non-release tag):

```bash
git push --no-verify
```

The hook is purely a local convenience — even if you skip enabling it, the workflow's guard step still catches the same mistake. Skipping it just means you find out from a failing CI run instead of from your terminal.

---

## Phase 1 — per-release workflow

Once Phase 0 is done, releasing a version is short.

### Bump the version

Edit `gradle.properties`:

```properties
version=0.0.1
```

Use bare `X.Y.Z` for a real release; `X.Y.Z-SNAPSHOT` for snapshot iteration on Central Portal's snapshot endpoint.

Commit:

```bash
git add gradle.properties
git commit -m "Release 0.0.1"
git push origin main
```

### Tag and push the tag

```bash
git tag v0.0.1
git push origin v0.0.1
```

The tag push fires `.github/workflows/release.yml`, which runs:

```
./gradlew clean build
./gradlew publish              # → build/staging-deploy/ populated with 15 jars+sources+javadoc+POM
./gradlew jreleaserFullRelease # → signs, uploads to Sonatype staging, creates GitHub Release
```

### Verify on the Central Portal

Log into <https://central.sonatype.com>. Under **Deployments** you'll see a new entry for `io.github.zyraz-io:0.0.1`. Status will be **Validating** for ~1-2 min, then **Validated**.

Click into it. Check:

- All 15 modules listed
- Each has main jar + sources jar + javadoc jar + POM + signatures
- POM contents look right (group, artifact, version, license, developers, scm)

### Decide: Publish or Drop

- **Publish**: deployment goes live on Maven Central within ~10 min. Permanent. Cannot be deleted or overwritten.
- **Drop**: deployment is discarded. Version number is NOT burned — you can re-stage `0.0.1` again with corrections.

This is the stage-and-confirm safety net. Use it.

### GitHub Release

Independent of the Central Portal flow, the release workflow has already created the GitHub Release at `https://github.com/zyraz-io/ekbatan/releases/tag/v0.0.1` with the two SMT shadow jars attached:

- `ekbatan-debezium-smt-avro-0.0.1.jar`
- `ekbatan-debezium-smt-protobuf-0.0.1.jar`

GitHub Releases are editable and deletable — you can edit the release notes or remove a bad release without ceremony.

---

## Local dry runs

Before pushing a tag, validate locally:

```bash
# Parses + reports the resolved JReleaser config; no network calls.
./gradlew jreleaserConfig

# Verifies everything builds + publishes to ~/.m2/repository so you can
# point a sample app at the artifacts and check the POMs render correctly.
./gradlew publishToMavenLocal
```

To do a full release entirely from your machine (no CI), export the same env vars on your shell:

```bash
export JRELEASER_GPG_PUBLIC_KEY="$(cat public-key.asc)"
export JRELEASER_GPG_SECRET_KEY="$(cat secret-key.asc)"
export JRELEASER_GPG_PASSPHRASE="<passphrase>"
export JRELEASER_GITHUB_TOKEN="<personal-access-token-with-repo-scope>"
export JRELEASER_MAVENCENTRAL_USERNAME="<user-token-username>"
export JRELEASER_MAVENCENTRAL_PASSWORD="<user-token-password>"

./gradlew clean build
./gradlew publish
./gradlew jreleaserFullRelease
```

For a one-shot test that exercises every step but uploads nothing, JReleaser supports `--dry-run`:

```bash
./gradlew jreleaserFullRelease --dry-run
```

---

## What's recoverable, what isn't

| Step | Recoverable? |
|---|---|
| `SNAPSHOT` versions on Central Portal's snapshot endpoint | yes — each push overwrites |
| Tag pushed to git | yes — `git push --delete origin v0.0.1` removes |
| Release staged on Central Portal, not yet Published | yes — click "Drop" in the Portal UI |
| Release **Published** to Maven Central | **no — permanent** |
| GitHub Release (the SMT shadow jars) | yes — edit or delete via GitHub UI |

The only irreversible step is clicking **Publish** on the Central Portal. Until then, every other step can be redone.

---

## Future: auto-publish

Once you've done a few stage-and-confirm releases and trust the pipeline, flip the JReleaser config from stage-and-confirm to auto-publish:

```kotlin
// In build.gradle.kts, jreleaser.deploy.maven.mavenCentral.sonatype:
active.set(org.jreleaser.model.Active.ALWAYS) // was: RELEASE
```

With `ALWAYS`, JReleaser uploads and then auto-clicks Publish for you. Skip this until you've shipped a few releases manually.
