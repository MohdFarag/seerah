# Content authoring tools

Validate the [synthetic example](../../content/example/) with the repository virtual environment. Install the dependencies with `.venv/bin/python -m pip install -r requirements-dev.txt`.

```sh
.venv/bin/python tools/validate_content.py
.venv/bin/python tools/validate_content.py content/example --compile /tmp/seerah-preview
.venv/bin/python -m pytest -c tools/content/pyproject.toml tools/content/tests
```

Validation accepts drafts, checks references and detects cycles. Compilation writes eight sorted UTF-8 JSON documents under the output directory's `data/` folder. A partial draft can pass authoring checks while still lacking fields needed to compile. Use `--strict-warnings` to fail on a step that changes both time and camera targets without a reviewed exception. Use `--require-approved` for release checks. See the [field definitions and release rules](../../docs/design/v1-content-pipeline-and-domain-spec.md) and the [signing commands](../packages/README.md).

## Migrating a JSON checkout

Run the migration in the content owner's environment. Choose a new sibling directory for the output. The helper preserves existing event review states. It leaves missing dates unknown and marks newly extracted sources and texts as drafts. Resolve conflicting index and detail fields before conversion.

```sh
PYTHONPATH=tools/content/src .venv/bin/python -m seerah_content.migrate_legacy \
  /path/to/legacy-content /path/to/new-yaml-content --mapping reviewed-mapping.yaml
.venv/bin/python tools/validate_content.py /path/to/new-yaml-content
```

The optional YAML mapping supplies reviewed information missing from the legacy format. For example, this synthetic link uses a type chosen by the reviewer.

```yaml
relations:
  example-second-event/example-first-event: continues
```

The helper reports missing relation types before publishing the output. It also accepts `places` and `areas` lists in v1 world-file format. For a legacy `geometry_ref`, supply a `routes` mapping keyed by the reference with its reviewed `coordinates` and `station_waypoint_indices`. Compare the output with the original before replacing any content. Keep exactly one root marker, either `content.yaml` or `index/phases.json`.

The legacy CI job can keep installing only `jsonschema==4.26.0` until conversion. For the YAML workflow, after checking out the public tools in `seerah/` and the content in `content/`, use:

```yaml
- run: python -m pip install -r seerah/tools/content/requirements.in
- run: python seerah/tools/validate_content.py content
```
