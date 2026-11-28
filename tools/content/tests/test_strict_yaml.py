"""Exercise source parsing independently of the content schemas."""

import pytest
from seerah_content.strict_yaml import ContentSourceError, load


@pytest.mark.parametrize(
    "source",
    [
        "",
        "# empty",
        "---",
        "first: true\n---\nsecond: true",
        "%YAML 1.1\n---\nflag: yes",
        "id: one\nid: two",
        "nested: [{1: value}]",
        "true: value",
        "name: &id value",
        "name: *missing",
        "name: !custom value",
        "name: !!timestamp 2026-11-28",
        "number: .nan",
        "numbers: [.inf, -.inf]",
    ],
)
def test_invalid_document_reports_path(tmp_path, source):
    path = tmp_path / "content.yaml"
    path.write_text(source)
    with pytest.raises(ContentSourceError) as caught:
        load(path)
    assert caught.value.path == path


def test_yaml_12_data(tmp_path):
    path = tmp_path / "content.yaml"
    path.write_text(
        "%YAML 1.2\n---\ntitle: yes\ndate: 2026-11-28\nvalue: 20.5\nflags: [true, null, 1]"
    )
    assert load(path) == {
        "title": "yes",
        "date": "2026-11-28",
        "value": 20.5,
        "flags": [True, None, 1],
    }
