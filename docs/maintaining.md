# Maintaining

## One-time GitHub settings (owner)

These live in the repository settings, not in files, so they are listed here. The repository is **public**.

**About** (repo home → ⚙️ next to About):

- Description: `سيرة رسول الله ﷺ — Explore the Prophet's ﷺ biography through place, time and original texts. Arabic Android app with a 2D historical map.`
- Topics: `seerah`, `sirah`, `islamic`, `arabic`, `rtl`, `android`, `kotlin`, `jetpack-compose`, `maplibre`, `historical-map`, `offline-first`

**Protect `main`** (Settings → Rules → Rulesets → New branch ruleset, target `main`):

- Require a pull request before merging (1 approval is optional for a solo owner)
- Require status checks: `lint-and-validate` (CI), `conventional` (PR title), `android` (Android)
- Require linear history; block force pushes and deletions
- Settings → General → Pull Requests: allow **squash merge** only, use the PR title as the commit message, and turn on **Automatically delete head branches**

**Actions** (Settings → Actions → General):

- Require actions to be pinned to a full-length commit SHA (every workflow already does; this stops a new unpinned one)
- Workflow permissions: read repository contents only (each job asks for more when it needs it)

**Security** (Settings → Code security):

- Private vulnerability reporting (the [security policy](../SECURITY.md) relies on it)
- Dependabot alerts and Dependabot security updates
- Secret scanning with push protection
- Code scanning, **default setup** (CodeQL for Kotlin, Python and GitHub Actions)

**Labels** (Issues → Labels): the issue forms apply `bug`, `enhancement` and `content`. `content` does not exist yet:

```sh
gh label create content --color 0E8A16 --description "Seerah content: texts, dates, places"
```

## Releasing (SemVer)

1. Move the **Unreleased** entries in `CHANGELOG.md` under a new `## [X.Y.Z] - YYYY-MM-DD`.
2. Merge that through a PR as `chore(release): vX.Y.Z`.
3. Tag `main`:

   ```sh
   git switch main && git pull
   git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z
   ```

   The `Release` workflow then creates the GitHub release.

Pre-1.0 (`0.y.z`) covers prototypes and the closed beta. `1.0.0` is the public release. Content packages carry their own `content_version`, separate from app tags.
