# Maintaining

## One-time GitHub settings (owner)

**About** (repo home → ⚙️ next to About):

- Description: `السيرة — Explore the Prophet's ﷺ biography through place, time and original texts. Arabic Android app with a 2D historical map.`
- Topics: `seerah`, `sirah`, `islamic`, `arabic`, `rtl`, `android`, `kotlin`, `jetpack-compose`, `maplibre`, `historical-map`, `offline-first`

**Protect `main`** (Settings → Rules → Rulesets → New branch ruleset, target `main`):

- Require a pull request before merging (1 approval is optional for a solo owner)
- Require status checks: `lint-and-validate`, `conventional`
- Require linear history; block force pushes and deletions
- Settings → General → Pull Requests: allow **squash merge** only and use the PR title as the commit message

**Security:** enable private vulnerability reporting, Dependabot alerts and secret scanning with push protection.

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
