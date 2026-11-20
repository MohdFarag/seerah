# Implementation readiness review

- **Blocker, commands.** MAPTEST works in /src/tools/map. Repository-relative test paths fail there. Use tests/ paths.
- **Major, coverage.** test_config.py has negative-quadrant coverage and a fixture profile. Add equal and reversed endpoints for both axes and identify the real public-example test separately.
- **Major, path tests.** Exercise an absolute inside-root filename. Containment alone accepts it. Test a symlink loop and the difference between a one-argument call and an explicit broader data root.
- **Major, CLI fixtures.** The _data helper loads the original profile to generate a lock. Create the fixtures first, then replace the outline and stub the fetch/build work.
- **Major, task handoff.** Give each task its own file list. Commit only the YAML regression test after restoring the temporary mutation.
