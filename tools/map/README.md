# tools/map

The map build tool for the trial that [ADR 0009](../../docs/adr/0009-map-data-pipeline.md) decision 13 requires. It downloads pinned Copernicus elevation and Water Body Mask cells and Natural Earth files, renders the style C relief block by block, cuts it into tiles and packs them into one PMTiles archive with a run report. The design is in [docs/design/map-trial-build.md](../../docs/design/map-trial-build.md); this is phase 1 of [its plan](../../docs/design/map-trial-phase-1-plan.md).

The tool runs in a toolchain image (GDAL, go-pmtiles and the Python packages in `requirements.txt`). The image holds no tool code: the code runs from this checkout, mounted read-only at `/src`.

## Commands

| Command | What it does |
| --- | --- |
| `fetch --profile P` | Downloads P's cells and the Natural Earth files into `/cache`. With no lock it writes `/out/<name>.lock` and says where to copy it; with a lock it verifies every cached file and refuses any mismatch. |
| `build --profile P --variant {painted,value}` | Runs the block stage, the tile stage and pack, and writes `/out/<name>-<variant>-<fp8>/`. Options: `--workers`, and `--per-worker-mib` (default 2048, `GDAL_CACHEMAX` included). |
| `clean [--fingerprint F \| --all]` | Lists work areas under `/work` with their sizes, or removes one or all. |

Exit codes: 0 for success, 1 when a check fails, 2 for a usage or configuration error.

## Profile, edits and lock

A profile `P` is named by its path relative to `/data`, without `.yaml` (for example `trial/full`, or `example/example` with `map-data` mounted as `/data`). Its folder holds:

```text
<folder>/
  <name>.yaml       the profile (purpose: trial, camera bounds, margin, fade, zooms, quality, peninsula_outline)
  edits.yaml        the trial edits; only edits with review: approved are applied
  <name>.lock       every cell's address, size and SHA-256 (or absent: true), and the Natural Earth files
```

The schemas are in [src/seerah_map/schema/](src/seerah_map/schema/). A new lock is made by the first `fetch`, which writes it to `/out` and prints the copy command. Review it, copy it beside the profile (for the trial, `seerah-content/map/trial/`) and commit it there. `build` refuses to run without a committed lock.

## Running it: `run.py`

`run.py` needs only Python 3.11 (standard library), git and `docker`. It refuses to run if this checkout or the data checkout has uncommitted changes, pulls the image whose tag matches this checkout's `Dockerfile` and `requirements.txt`, and starts it:

```sh
python3 tools/map/run.py [--private PATH] [--data PATH] [--out PATH] [--memory-mib 8192] \
  [--image REF] [--print-tag] -- <command> [args]
```

- `--private` is the private content checkout (default `../seerah-content`); `/data` is its `map/` folder unless `--data` is given.
- `--out` is the output folder (default `map-data/dist`, not committed). Relative paths resolve against this repository's root.
- `--memory-mib` sets both `--memory` and `--memory-swap` (default 8192). The build chooses its workers from this cap and `--per-worker-mib`.
- `--image` runs a local image (for example `seerah-map:dev`) instead of pulling one.
- `--print-tag` prints the image reference and exits.

For example, to build the synthetic example with a locally built image:

```sh
docker build -t seerah-map:dev tools/map
python3 tools/map/run.py --data map-data --image seerah-map:dev -- fetch --profile example/example
python3 tools/map/run.py --data map-data --image seerah-map:dev -- build --profile example/example --variant painted
```

## The `docker run` contract

Any runner can start the image directly, as long as it keeps this contract (`run.py` builds exactly these arguments):

- **Mounts:** the public checkout at `/src` and the data root at `/data`, both read-only; writable `/cache` (downloads, the `seerah-map-cache` volume), `/work` (work areas, the `seerah-map-work` volume) and `/out` (output).
- **Working directory and code:** `-w /src` and `PYTHONPATH=/src/tools/map/src`; the command is `python3 -m seerah_map <command>`.
- **Environment:** `SEERAH_TOOL_COMMIT`, `SEERAH_DATA_COMMIT` and `SEERAH_IMAGE_DIGEST`, recorded as the build's provenance. A missing one is reported as a warning and recorded as `unknown`.
- **Memory:** `--memory` (and `--memory-swap` equal to it); the worker count is derived from the cgroup's `memory.max`.
- **Network:** `--network none` for every command except `fetch`.
- **User and labels:** `--user` set to the host user, and `--security-opt label=disable`, so SELinux never relabels the checkouts.

The thread and GDAL cache variables are set in the image, so every runner gets them.

## Output

`build` writes one folder per profile, variant and fingerprint:

```text
<out>/<name>-<variant>-<fp8>/
  relief.pmtiles    the relief archive
  metadata.json     format, zooms, bounds, trial: true, variant, encoding, fingerprint, commits, image digest
  report.json       provenance, stage times, memory, disk, tile counts, size per zoom inside and outside the outline
```

**Trial output is never packaged or published.** Every `metadata.json` says `trial: true`, and the future packaging step must refuse it. It is not the `tile_schema` 1 map package.

## Tests

```sh
docker build -t seerah-map:dev tools/map
docker run --rm --security-opt label=disable \
  -v "$PWD:/src:ro" -v "$PWD/map-data:/data:ro" -v seerah-map-test-cache:/cache \
  -w /src/tools/map -e PYTHONPATH=/src/tools/map/src \
  seerah-map:dev python3 -m pytest            # every test, in the image
python3 -m unittest discover -s tools/map/host_tests   # the launcher, on the host
```

Tests marked `slow` build the synthetic example in [map-data/example/](../../map-data/example/), downloading its cells into `/cache` on the first run.

## CI

The [`map` workflow](../../.github/workflows/map.yml) runs on changes to `tools/map/`, `map-data/example/` or the workflow, and by hand:

- **`image`:** checks that `IMAGE_REPO` in `run.py` names the repository owner's namespace, computes the tag with `run.py --print-tag`, and builds the image if the tag is not in the registry. It pushes it to GHCR only from `main`.
- **`test`:** pulls the image (or builds it, on a pull request that changes it), restores the example's downloads from a cache keyed on `example.lock`, runs every test in the image with `--memory 6g`, then runs the launcher's tests on the host.

The image name, `ghcr.io/mohdfarag/seerah-map`, is the one constant `IMAGE_REPO` in `run.py`.

The image package is public (made so by the owner on 2026-11-16), so pulling it needs no login. Both jobs still log in to GHCR: `image` to push, and `test` so CI keeps working if the package is ever made private again.

Dependabot proposes updates to the base image and the Python packages, except numpy: it comes from the base image's Ubuntu package (GDAL's bindings are built against it), so its pin in `requirements.in` is changed by hand to match whenever the base image is updated.
