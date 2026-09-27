# GitHub Actions

Automation for building ZigBrains and keeping it on the current CLion / IntelliJ IDEA.
None of it signs or publishes the plugin; releases still go through `build.sh`.

| Workflow | Trigger | What it does |
|---|---|---|
| [`ci.yml`](workflows/ci.yml) | push to `master`, PRs, manual | Tests the automation scripts, runs `./gradlew buildPlugin check` (uploads the unsigned zip), then `verifyPlugin` against IDEA + CLion. |
| [`platform-check.yml`](workflows/platform-check.yml) | daily 05:17 UTC, manual | Asks the JetBrains release API for the newest CLion / IDEA release, compares it with `gradle/libs.versions.toml` and, if newer, starts `platform-bump.yml`. Skips versions that already have a `platform-bump/…` branch. |
| [`platform-bump.yml`](workflows/platform-bump.yml) | manual, or from `platform-check` | Updates the version files, runs `buildPlugin check verifyPlugin`, pushes `platform-bump/clion-<v>-idea-<v>` and opens a PR. If the build or verifier fails, the PR is opened as a **draft** with the compiler errors / verifier verdicts in its description (full log is a run artifact). |
| [`platform-eap.yml`](workflows/platform-eap.yml) | Mondays 04:43 UTC, manual | Early warning: builds against the newest EAP in the working copy only (nothing is committed). Not run on PRs, so it never blocks merging. |

The shared setup (freeing ~30 GB of runner disk for the IDE downloads, installing
the JetBrains Runtime from `java` in the version catalog, Gradle caching) lives
in [`actions/setup-build`](actions/setup-build/action.yml).

## Updating to a new CLion / IDEA

Usually nothing: `platform-check` notices the release and a PR appears.
To do it by hand: **Actions → Platform bump → Run workflow**.

| Input | Default | Meaning |
|---|---|---|
| `clion`, `idea` | `latest` | Version to target, e.g. `2026.2.2`. `latest` = newest on `channel`. |
| `idea_build` | – | IDEA build number; only needed if a version can't be resolved automatically. |
| `native_debug` | `auto` | `com.intellij.nativeDebug` version. `auto` asks the Marketplace for the build compatible with the IDEA build (falls back to the IDEA build number); `keep` leaves it unchanged. |
| `since_build` | `auto` | `pluginSinceBuild`. `auto` = branch of the new IDEA (e.g. `263`); `keep` leaves it. |
| `channel` | `release` | `eap` / `rc` resolve `latest` to preview builds (written as build numbers). |
| `skip_verify` | `false` | Skip `verifyPlugin`. |

What the bump changes: `intellij-clion`, `intellij-idea`, `intellij-nativeDebug`
in `gradle/libs.versions.toml` and `pluginSinceBuild` in `gradle.properties` —
the same lines as the manual `build: [NO BACKPORT] update to 20xx.y` commits.

When the bump crosses to a new platform branch (e.g. 262 → 263) the PR lists the
steps that stay manual: Java/Kotlin versions, creating the backport branch,
`publishVersions` in `build.gradle.kts`, branch lists in `build.sh` / `push.sh`,
README updater URLs and the changelog. Code fixes for new deprecations/API
changes are pushed to the PR branch as usual.

## One-time repository settings

- **Settings → Actions → General → Workflow permissions**: enable
  *Allow GitHub Actions to create and approve pull requests* (needed for
  `platform-bump` with the default `GITHUB_TOKEN`).
- Optional secret **`PLATFORM_BUMP_TOKEN`** (fine-grained PAT with *Contents* and
  *Pull requests* write on this repo). PRs created with the default
  `GITHUB_TOKEN` do not trigger other workflows, so `ci.yml` won't run on them
  (the bump workflow already builds and verifies, so this is only a nicety).
  With the PAT set, CI runs on bump PRs as on any other PR.

## Running the scripts locally

```sh
python3 .github/scripts/platform_versions.py current                # what we target now
python3 .github/scripts/platform_versions.py latest                 # newest CLion / IDEA release
python3 .github/scripts/platform_versions.py --channel eap latest   # newest EAP
python3 .github/scripts/platform_versions.py check                  # is an update needed?
python3 .github/scripts/platform_versions.py bump --dry-run         # preview a bump to latest
python3 .github/scripts/platform_versions.py bump --clion 2026.2.2 --idea 2026.2.2
python3 .github/scripts/test_platform_versions.py                   # offline unit tests
.github/scripts/build-report.sh build.log failure                   # PR-body summary of a Gradle log
```

`platform_versions.py` needs only Python 3.10+ (stdlib). The tests run on
temporary copies of the version files, with JetBrains API responses from
`scripts/fixtures/`.
