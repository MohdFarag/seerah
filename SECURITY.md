# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for a security problem. Report it privately through GitHub's [private vulnerability reporting](https://github.com/MohdFarag/seerah/security/advisories/new). You should get a reply within 7 days.

Useful to include: what is affected (the app, a content package, a workflow), how to reproduce it, and what an attacker could do with it.

## Scope

- The Android app in `android/` and the content packages it downloads and verifies.
- The build and release workflows in `.github/workflows/`.

The technical spike in `android/spike/` is a finished experiment that is not distributed; reports about it are welcome but are not treated as vulnerabilities in the app.

Content mistakes (a wrong date, place or text) are not security issues. Report them with the **Content correction** issue template.

## Supported versions

Before the first public release, only the latest build from `main` is supported.
