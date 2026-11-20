# Comprehension review

## What this proposes

Fetch and build should reject a profile if the camera or the area added by its margin lies outside the supported map grid. The loader should allow relative outlines shared within the configured data directory and reject references that resolve outside it. Parser tests should demonstrate that Seerah reads unquoted timestamps as text and leaves the default YAML parser’s behavior intact.

## How the existing system works, per this document

Fetch and build already share a profile loader that checks quadrant and coordinate ordering and exposes an expanded data extent. A later packaging step opens the outline. The project’s strict YAML loader filters timestamps and rejects duplicate keys, and existing tests cover date-only input.

## Findings

- **Major, exact northern edge.** The plan requires an exact-edge acceptance test but gives a rounded latitude. It does not say how the test should obtain the authoritative value or whether a literal equal to the printed approximation must pass. Those choices can produce different results at the boundary.
- **Major, CLI verification.** “Passes profile validation” and “before fetch or block work” describe internal milestones. The plan does not identify an observable signal or test seam for either milestone. A CLI exit status alone cannot establish when work started.
- **Nit, branch verification.** The plan names the map workflow, toolchain image and slow public-example builds without a workflow path or invocation for running that suite. A direct pointer would make the verification reproducible for a new contributor.
