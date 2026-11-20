# Map profile validation plan review

All five EGM stages reviewed the planning draft. The table records how their findings changed the plan. The requested changes fit the current loader and existing CLI boundaries.

| Priority | Stage and finding | Evidence checked | Disposition |
| --- | --- | --- | --- |
| Blocker | Readiness and comprehension, test invocation | Phase 1 MAPTEST and map.yml use /src/tools/map. | Corrected focused paths and added workflow and host commands. |
| Major | Initial and intermediate zoom-out, outline responsibilities | Fetch loads the profile. Build passes the outline to size_by_zoom, which opens it with OGR. The happy-path profile fixture references an absent outline. | Narrowed the goal to containment. Preserved existing file-opening behavior, including for missing files and directories. |
| Major | Comprehension and initial zoom-out, exact northern edge | The pinned image returned bbox.top = 85.0511287798066. The draft's rounded number exceeds it. | Test the exact grid value and next representable value above it. |
| Major | Critic, acceptance contract | Phase 1 documents quadrant, order and relative outline paths. Both CLI commands use load_profile. | Stated the closed coordinate domain, expanded margin and canonical root rules in the goal. |
| Major | Comprehension and readiness, CLI observability | test_cli._data loads the original profile and _stub_pipeline records build stages. | Rewrite the input after fixture creation and assert status, error field and work calls. |
| Major | Readiness, coordinate and path coverage | Current test_config covers quadrant and synthetic fixture loading. Canonical paths can be inside the root even when supplied as absolute. | Added ordering regressions, public example verification, absolute inside-root rejection, symlink loops and implicit/explicit root tests. |
| Major | Readiness, handoff | The tasks touch overlapping loader and test files. | Listed each task's files and isolated the YAML mutation from the commit. |
| Nit | Critic, YAML terminology | In the pinned image, successive strict/default parsers returned str, datetime, str, datetime for the same timestamp. | Specified observable types and parser isolation. |

The three tasks are ready for owner review. The production changes and their planned tests still require implementation and verification.
