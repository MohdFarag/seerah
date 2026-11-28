# Content source tools

The v1 YAML reader accepts one UTF-8 YAML 1.2 document per source file. Dates stay strings. Duplicate keys, non-string keys, anchors, aliases, unsupported tags and non-finite numbers fail with the source path.

Install the repository's development requirements and run the reader tests from the repository root:

```sh
.venv/bin/python -m pytest -c tools/content/pyproject.toml tools/content/tests
```

The existing content validator still reads the legacy JSON format. The source schemas, compiler and CLI integration are later parts of [Phase 1](../../docs/design/v1-content-pipeline-and-domain-spec.md).
