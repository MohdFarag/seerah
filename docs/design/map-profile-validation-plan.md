# Map profile validation implementation plan

> After owner approval, use superpowers:subagent-driven-development, with a fresh implementer and spec and quality review per task, then a whole-branch review.

**Goal.** Accept finite camera and margin-expanded bounds in the closed N/E tile domain (longitude 0 through 180 degrees and latitude 0 through the WebMercatorQuad northern edge). Require a profile-relative outline whose canonical path stays inside the configured data root. Unquoted dates and timestamps must load as strings, ordinary booleans must keep their type, and independent default parsers must keep their normal timestamp behavior.

**Architecture.** Fetch and build already call the same Python profile loader. Validate the coordinates and path there, before enumerating Copernicus cells or creating build work. Fetch validates the outline reference. The build opens its geometry when computing the size report. Existence, file type and geometry validation retain their current timing.

**Stack and references.** Python 3.11, pathlib, JSON Schema, ruamel.yaml, morecantile and pytest. Read the [approved map design](map-trial-build.md) and the [phase 1 input contract](map-trial-phase-1-plan.md). Baseline d18d629.

## Global constraints

Use synthetic fixtures and the public example. Preserve existing quadrant, ordering, zoom and fade checks, the example's zero margin, and CLI configuration-error exit code 2. No Android or private content changes are needed.

## Review focus

Check exact outer edges, margin-only overflow, non-finite YAML numbers, profile-relative sibling paths and symlink targets, and isolation of the timestamp resolver across parser instances.

## Detailed Implementation Plan

### 1. Bound the camera and its expanded data extent

Modify `tools/map/src/seerah_map/config.py`, `tools/map/src/seerah_map/schema/profile.schema.json`, `tools/map/tests/test_config.py` and `tools/map/README.md`.

- [ ] In `tools/map/tests/test_config.py`, use `_profile_with` to exercise `load_profile`. With zero margin, accept west/south 0, east 180 and north at morecantile.tms.get("WebMercatorQuad").bbox.top (85.0511287798066 in the pinned image). Reject math.nextafter(limit, math.inf). Reject an east of 180.01 and north of 86. Repeat with valid camera coordinates where only the margin pushes west or south below zero, east above 180, or north beyond the grid. Exercise YAML non-finite coordinates and margins. Assert ConfigError names the file and the offending field or margin. Preserve the existing quadrant tests and add parameterized equal and reversed endpoints for both axes.
- [ ] Run `python3 -m pytest tests/test_config.py -k profile` in the map image and confirm the new overflow cases fail on baseline.
- [ ] In `tools/map/src/seerah_map/schema/profile.schema.json`, set JSON Schema maximum to 180 for west/east and 90 for south/north. In `config.py`, retain the N/E diagnostics in `_check_bounds`, validate finite inputs and use the grid's latitude limit for actual tile coverage. Use the same WebMercatorQuad definition as grid.py without importing grid.py (it imports config). Check the returned profile's existing `data_extent` property before returning it. Attribute margin-only violations to `margin_deg`. Document accepted input and the difference between camera and expanded extent in `tools/map/README.md`.
- [ ] Re-run the same tests and expect all to pass, then run the public-example slow test test_example_builds_complete in tests/test_build_example.py during branch verification. Commit as `fix(map): validate profile coordinate coverage`.

### 2. Confine outline resolution to the configured data root

Modify `tools/map/src/seerah_map/config.py`, `tools/map/src/seerah_map/cli.py`, `tools/map/tests/test_config.py`, `tools/map/tests/test_cli.py` and `tools/map/README.md`.

- [ ] In `tools/map/tests/test_config.py`, create temporary data/trial and data/shared directories. Verify that a local filename and ../shared/outline.geojson resolve to their canonical paths. Reject absolute filenames pointing both inside and outside the root, an escaping parent path, a sibling with the same string prefix as the root, and a symlink pointing outside. Accept an internal symlink. Add a symlink loop and assert ConfigError names the field. Load the same ../shared outline with and without an explicit data_root and assert acceptance only with the broader root. Keep current behavior for an in-root file that has not yet been created. Assert errors name the profile and peninsula_outline.
- [ ] In `tools/map/tests/test_cli.py`, exercise both fetch and build using _data(tmp_path) before rewriting the outline in trial/t.yaml, and the roots from _roots(tmp_path). Replace cli.fetch with a recording stub returning downloaded, verified and message fields. For build use _stub_pipeline and its calls list. Invalid cases must leave both call logs empty and name the profile and field in stderr. Positive cases must return 0 and reach their respective stub. Assert an escaping outline yields status 2 before fetch or block work and a sibling inside the root passes profile validation. Run both test modules and confirm the new escape tests fail on baseline.
- [ ] In `config.py`, extend the existing signature to `load_profile(path: Path, *, data_root: Path | None = None) -> Profile`. Direct one-argument callers use the profile's parent as their boundary. Both callers in `cli.py` explicitly supply `args.data`. Reject Path.is_absolute() before joining. Resolve the relative outline against the profile directory, canonicalize the root and candidate, and check path-component containment. Convert resolution failures including symlink loops to ConfigError with field context. Keep file reading in pack.py. Document the root, sibling sharing and relative-path contract in the tool README and loader docstring.
- [ ] Run `python3 -m pytest tests/test_config.py tests/test_cli.py` in the image. Commit as `fix(map): confine profile outlines to the data root`.

### 3. Pin the strict YAML resolver behavior

Modify only `tools/map/tests/test_config.py`. The mutation check is temporary.

- [ ] Extend `tools/map/tests/test_config.py`. Existing tests already reject duplicate keys and check a date-only scalar. Load an unquoted ISO timestamp with a timezone and a date using two successive calls to strict_yaml.load. Assert both values equal their literal strings and a neighboring true scalar is the boolean True. After each call, assert a fresh unmodified ruamel safe pure YAML 1.2 parser constructs a datetime for the timestamp. This detects leakage into a shared resolver as well as loss of the filter.
- [ ] Run `python3 -m pytest tests/test_config.py -k 'yaml or dates or resolver'`. This characterization should already pass. Temporarily disable only the timestamp filter in `strict_yaml.py`, verify the new test fails, and restore that edit. Commit the test as `test(map): guard timestamp resolver isolation`.

### Branch verification

- [ ] Follow .github/workflows/map.yml, job test, steps “Tool tests (in the image)” and “Launcher tests (on the host)”. From /src/tools/map inside the image use python3 -m pytest for the full suite, including slow public-example builds. Run all focused commands from /src/tools/map using the phase 1 MAPTEST mounts. Run python3 -m unittest discover -s tools/map/host_tests from the repository root. Run `pre-commit run --all-files` and `python3 tools/validate_content.py`. Complete the AGENTS.md documentation review, verify links, and present the reviewed branch to the owner before publishing.
