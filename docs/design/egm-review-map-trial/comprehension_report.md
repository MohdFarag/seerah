# Stage 6: Comprehension test, map trial specification

**Target:** `docs/design/map-trial-build.md` (lines 1–736; FeatureRange 1–355, DetailedRange 356–736).

**Reviewer:** egm-comprehension. It read only the specification, not the files it links, and no document standard applied. Status: completed.

## What this proposes

The document plans a one-off trial to settle the questions ADR 0009 left open before any real map package is built: zoom 11 or 12, how close-up terrain is shown, the real map size, whether valleys are feasible, and which modern features need cleanup. It adds three things:

1. A Python build tool that runs in a container (`tools/map/`). It builds elevation from Copernicus data in 2,048-pixel blocks, applies synthetic test edits, renders the "style C" shaded relief, tiles it with GDAL's tiler, and packs PMTiles files with Protomaps' converter. Builds can resume, give the same bytes for the same inputs, and stay within a memory budget.
2. A permanent `:core:map` Android module that draws those files with MapLibre.
3. A release-like `benchmark` build type. It holds a throwaway trial screen: file import over USB, a variant switch, a measurement overlay and a scripted camera path. The owner judges each trial item on three devices.

Trial settings live in the private `seerah-content` repository. Trial output is marked so it can never be packaged.

## How the existing system works, per this document

ADR 0009 sets out the map pipeline. Its decision 13 makes a measured trial the gate before the first map package. The only working map today is the spike: a reference-only prototype app plus a script that loads the whole area into memory. "Build 11" of the spike is the look the owner approved. The v1 Android app is an empty scaffold. ADR 0002 sets performance targets that are measured in release builds only. ADR 0007 forbids release builds from loading unsigned files, and signing does not exist yet. ADR 0003 makes one module the only MapLibre user. The owner's machine has 13 GB of memory, which is too little for the spike's whole-area method at trial size.

## Findings

**Verdict:** the document can be paraphrased overall, but CO-1 blocks it. The trial's success criteria and step contents are written in terms of decision 13's items, and the document never lists them. The Major findings are mostly gaps in how the parts connect: which inputs each command reads, what survives the work-area cleanup, and how files and settings reach the device.

**Counts:** 1 🔴, 9 🟡, 10 🟢.

### 🔴 CO-1. Decision 13's "eleven items" are never listed

**Lines:** 45, 152, 158, 165, 207–210, 323, 330, 714.

- **The gap:** the whole trial exists to answer "decision 13's eleven items" (G1), and the order of work is written in item numbers ("items 1, 6, 11, 9 and 10", "items 7 and 8", "item 5"). The items are never listed.
- **The Problem section:** it lists five questions, without saying how those five relate to the eleven items.
- **What a reader can and cannot work out:** items 5, 7, 8 and 10 can partly be inferred from context. Items 2, 3 and 4 never appear, so the reader cannot check that the plan covers every item, or tell what step 4 actually judges.
- **Fix:** add a numbered table of the eleven items. For each, give a one-line question, the step that answers it, the variant or build it needs, and the evidence the owner judges.

### 🟡 CO-2. Box, extent, margin and ring

**Lines:** 75, 118, 127, 133, 154, 162, 200, 561.

- **The terms:** the document uses "trial box", "extent", "tiled extent", "data extent", "camera bounds", "margin" and "one-cell ring" without saying how they relate.
- **The two ranges disagree:**
  - line 75 gives the box with a one-cell ring as latitude 11–32° N, longitude 33–60° E (616 cells);
  - lines 200 and 561 give the box as 12–32° N, 34–60° E, with a 1° margin.
- **What the numbers suggest:** 616 cells is 22 × 28, which matches 11–33° N, 33–61° E. So line 75's ranges look like cell corner coordinates, not the area's edges.
- **Still unclear:** whether the lock's "one-cell ring" is the 1° margin or an extra ring outside it.
- **Fix:** define each term once (camera bounds = box; data extent = box plus margin; locked area = data extent plus ring, or equal to the data extent), and give every range as edges in the same convention.

### 🟡 CO-3. When and how `base.pmtiles` is built

**Lines:** 89–90, 114–123, 152–158, 431, 495, 500, 515.

- **No command builds it:** the command table and stages 1–4 do not say which command builds `base.pmtiles`, or whether every `build` rebuilds it for each variant.
- **Its input disappears:** `base.py` reads `terrain.vrt` from the work area, but the work area is removed on success.
- **Natural Earth is never fetched:** it is "pinned in the lock" (lines 500, 515), but neither `lock` nor `fetch` mentions downloading or hashing it.
- **Zoom bands undefined:** the "zoom bands" used for generalisation are never given.
- **Fix:** say where base building sits in `build` (for example, a stage 5 run before cleanup). Say that `lock` and `fetch` handle Natural Earth. List the zoom bands.

### 🟡 CO-4. The valley probe's inputs

**Lines:** 171, 206, 507–510.

- **The problem:** `probe-valleys` resamples "the edited elevation" (`terrain.vrt`). That file exists only inside a build's work area, which is deleted when the build succeeds.
- **What a reader cannot tell:** step 1 says the probe "runs alongside", so it is unclear what the probe reads, or whether it must run before cleanup or against a failed or kept work area.
- **Fix:** state the probe's prerequisite. For example: "runs stage 1 itself", or "takes a profile and builds its own terrain blocks".

### 🟡 CO-5. How a command finds its edits and lock, and the window profiles

**Lines:** 118, 127–129, 200, 206, 416, 505, 561.

- **Missing paths:** the CLI takes only `--profile PATH`, and the document never says how `build` finds `edits.yaml` and `sources.lock` (the same folder, or named in the profile?).
- **One lock, several profiles:** the three window profiles sit beside the single full-box lock. Step 1 runs "`lock` and `fetch` for the small windows", but `lock` writes one `sources.lock` and refuses to overwrite it without `--replace`.
- **What a reader cannot tell:** whether the windows share the full lock or each get their own.
- **Fix:** add profile fields for the edits and lock paths, or state a folder convention, and say which lock each window uses.

### 🟡 CO-6. Performance targets and "relief budget"

**Lines:** 31, 39, 50, 189, 642.

- **No numbers:** pass or fail is "ADR 0002's targets", but no target numbers appear.
- **Undefined term:** G6 records "a relief budget with its headroom" without saying what that is.
- **Ambiguous statistic:** line 642 defines "typical frame rate" as "frames in the last second". That is a moving reading, not a per-run statistic, so the per-run value and the median across runs are unclear.
- **Fix:** quote ADR 0002's four targets. Define "relief budget" and "headroom" in one sentence. State the per-run statistic: for example, the median of the one-second readings over the run.

### 🟡 CO-7. Latitude calibration

**Lines:** 52, 143, 286, 470.

- **The problem:** "exaggeration calibrated from 8 so that at 23° N the result matches build 11" does not say how the calibration is done, so a reader cannot reproduce G8.
- **Fix:** give the rule. For example: exaggeration = 8 × cos(23°) ≈ 7.36, because build 11 used the uncorrected Mercator pixel size.

### 🟡 CO-8. The "terrain border"

**Lines:** 135, 138, 144, 480.

- **Undefined:** the style border is defined (three times each blur's σ, plus one pixel). The "terrain border" read around each block in stage 1 is not, though the mask smoothing and bilinear warping depend on it.
- **Misleading wording:** line 138 says an edit "never reaches beyond the block border". That can be read as edits being clipped at block edges, which contradicts the seam test with an edit crossing a block edge (line 528).
- **Fix:** give the terrain border's formula (for example, the mask smoothing radius plus one warp pixel). Reword line 138 to say that each block rasterises every edit that touches its padded window.

### 🟡 CO-9. Output names and the import folder layout

**Lines:** 150, 187, 188, 495, 639, 641.

- **Two names for the outputs:** line 150 names them `relief.pmtiles` and `relief-value.pmtiles`; line 495 names them `<out>/<variant>-<mode>/relief.pmtiles`.
- **Two import folders:** line 187 copies to `trial-map/<variant>/`; line 639 copies to `trial-map/<folder name>/`.
- **What a reader cannot tell:** what folder the owner copies to the device, or how each variant pairs its relief file with a `base.pmtiles`, which `MapFiles` requires.
- **Fix:** show one concrete folder tree, from the build output, through the USB copy, to the layout on the device.

### 🟡 CO-10. Camera path and camera limits on the device

**Lines:** 175–180, 190, 604–607, 641, 644.

- **Missing source:** the camera stops come "from the trial profile's metadata", but the PMTiles metadata fields (line 495) contain no stop positions, camera bounds or minimum zoom. So it is unclear where `CameraLimits` and the stop coordinates come from, and whether a path over mountains makes sense for a small window.
- **Missing parameter:** `MapCamera` is "exposed by `SeerahMap` through a callback", but the `SeerahMap` signature (lines 180, 606) has no such parameter.
- **Fix:** name the source of the stops and limits (for example, a camera-path file shipped with the trial code, or extra metadata fields). Add the callback to the stated signature.

### 🟢 CO-11. Fingerprint inputs

**Lines:** 131, 441. The fingerprint inputs at line 131 leave out `mode`, which line 441 includes. **Fix:** make both lists the same.

### 🟢 CO-12. `contrast`

**Lines:** 127, 466, 470. `settings.contrast` is used in the style function but is missing from the profile's list of style settings. **Fix:** add it to line 127.

### 🟢 CO-13. "Item" means two things

**Lines:** 515, plus 152, 158, 207–210.

- **The problem:** "item" means both decision 13's items and this plan's file list. "`cells[]` as in item 10" can be read as trial item 10 (the Natural Earth join) rather than file 10 (`lock.py`).
- **Fix:** write "file 10" or "see `lock.py`".

### 🟢 CO-14. Launcher name

**Lines:** 112, 387, 682. The launcher is called `tools/map/run` at line 112 and `run.py` elsewhere. **Fix:** use one name.

### 🟢 CO-15. The third device

**Lines:** 31, 50, 154, 515.

- **The problem:** only the Oppo A55 and the MatePad are named. The third test device is not, and the "largest test screen" used in the Natural Earth calculation is not given in pixels.
- **Fix:** list the three devices and their screen sizes once.

### 🟢 CO-16. The `review` field's shape

**Lines:** 128, 200, 456. `review: approved` at line 200 and `review.status == approved` at line 456 leave the field's shape unclear. **Fix:** use one form.

### 🟢 CO-17. Method names

**Lines:** 13, 252–265. "Method M2" appears in the Overview but is only defined indirectly, through the rejected alternatives M1, M3 and M4. **Fix:** define M2 in one clause at line 13.

### 🟢 CO-18. ADR 0009 decision 8

**Lines:** 120, 265. Decision 8 is cited for two different rules: refusing uncommitted changes, and running real builds on the owner's machine. **Fix:** state what decision 8 says once.

### 🟢 CO-19. CI wording

**Lines:** 402, 657, 662.

- **Two names for one job:** the "publish job" at line 402 is called `image` at line 657.
- **Self-contradiction:** "Add `android/core/**` to the path filter (already covered by `android/**`)".
- **Fix:** use one job name, and say that no path-filter change is needed.

### 🟢 CO-20. Undefined background terms

**Lines:** 19, 265. "Seven-lens review" and "free-until-release policy" are used without definition. **Fix:** add a short gloss for each, or a link.
