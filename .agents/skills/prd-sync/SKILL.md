---
name: prd-sync
description: Re-snapshot the Seerah PRD from Notion (the source of truth) into docs/prd/, show what changed, and find the repository docs that now disagree with it. Use this whenever the owner says the PRD or Notion page changed ("حدّثت الوثيقة", "sync the PRD", "re-snapshot"), before work that depends on a requirement that may have moved, or when docs/requirements.md, docs/open-decisions.md or the roadmap seem out of step with Notion.
---

# PRD sync

The PRD lives in Notion; `docs/prd/` holds a read-only snapshot so agents and reviewers can work offline and see changes in git. The snapshot is never edited by hand. It is regenerated from Notion, and this skill does that the same way every time.

## Steps

1. **Fetch the page** with the Notion connector's fetch tool, id `40e88743a2df81ffb7ede29b75cc79b0`. The page is large, so the tool result is normally saved to a file; note that path. If it comes back inline, write it to a file in a temporary folder outside the repository.

2. **Extract the snapshot.** The script keeps the unified PRD (from the title down to `# سجل المسودة السابقة والمناقشات`), drops the discussion log, which is history and never a requirement, converts Notion markup to Markdown, and leaves out the work-log sections listed in `references/excluded-sections.txt`:

   ```sh
   python3 -I .agents/skills/prd-sync/scripts/extract_prd.py <fetched-file> docs/prd/PRD-v0.2.ar.md \
     --exclude-file .agents/skills/prd-sync/references/excluded-sections.txt \
     --compare docs/prd/PRD-v0.2.ar.md
   ```

   Run it once with `--compare` and an output path in a temporary folder first, so you see the new and removed headings before overwriting anything.

3. **Decide about new headings.** A new heading may be a requirement or a work log. Keep requirements in the snapshot. If one looks like a log (a dated progress report, a review round, a status update), ask the owner before adding it to `excluded-sections.txt`, because leaving out a requirement by mistake is worse than keeping a log. If the script stops because the page structure changed, stop and tell the owner.

4. **Check the version.** If the PRD's version line (`الإصدار: …`) moved past 0.2, write the snapshot to a new file named for the version (e.g. `PRD-v0.3.ar.md`), and update `docs/prd/README.md`, the links in `docs/requirements.md` and anything else that points at the old file.

5. **Review the diff** with `git diff docs/prd/`. Summarise for the owner, in English, what changed in substance: decisions added, changed or reversed. Leave out formatting churn.

6. **Find what now disagrees.** Sources rank: the owner's latest decision, then the PRD, then accepted ADRs, then everything else in `docs/`. For each substantive change, search `docs/requirements.md`, `docs/open-decisions.md`, `docs/roadmap.md`, `content/README.md`, `docs/design/`, the ADRs and `AGENTS.md` for statements it contradicts. Fix the lower-ranked doc in the same change, except an accepted ADR, which you report and don't edit without the owner. An open decision the PRD has now settled moves out of `open-decisions.md`; one it reopened moves back in.

7. **Update `docs/prd/README.md`** with the new snapshot date, then run `pre-commit run --all-files` (the snapshot itself is excluded from Markdown lint on purpose).

Commit only when asked, e.g. `docs(prd): re-snapshot PRD from Notion (2026-11-20)`.
