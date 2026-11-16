from pathlib import Path

import pytest
from seerah_map.workarea import atomic_write, fingerprint, list_areas, open_area, remove

BASE = {
    "profile": b"profile",
    "edits": b"edits",
    "lock": b"lock",
    "tool_commit": "abc123",
    "image_digest": "sha256:feed",
    "variant": "full",
}


def _fp(tmp_path: Path, **changes) -> str:
    return fingerprint(**{**BASE, **changes}, src_root=tmp_path)


def test_fingerprint_changes_with_each_input(tmp_path):
    base = _fp(tmp_path)
    assert base == _fp(tmp_path)
    changes = {
        "profile": b"other",
        "edits": b"other",
        "lock": b"other",
        "tool_commit": "def456",
        "image_digest": "sha256:beef",
        "variant": "lite",
    }
    seen = {base}
    for name, value in changes.items():
        seen.add(_fp(tmp_path, **{name: value}))
    assert len(seen) == len(changes) + 1


def test_fingerprint_without_provenance_tracks_code(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    (src / "a.py").write_text("one")
    kwargs = {"tool_commit": None, "image_digest": None, "src_root": src}
    first = fingerprint(b"p", b"e", b"l", variant="full", **kwargs)
    assert first == fingerprint(b"p", b"e", b"l", variant="full", **kwargs)
    (src / "a.py").write_text("two")
    assert first != fingerprint(b"p", b"e", b"l", variant="full", **kwargs)


def test_code_hash_ignores_bytecode_caches(tmp_path):
    from seerah_map.workarea import code_hash

    (tmp_path / "a.py").write_text("one")
    before = code_hash(tmp_path)
    (tmp_path / "__pycache__").mkdir()
    (tmp_path / "__pycache__" / "a.cpython-311.pyc").write_bytes(b"x")
    (tmp_path / "b.pyc").write_bytes(b"x")
    assert code_hash(tmp_path) == before


def test_areas_coexist(tmp_path):
    first = open_area(tmp_path, "a" * 64)
    second = open_area(tmp_path, "b" * 64)
    assert first.path != second.path
    assert first.path.is_dir() and second.path.is_dir()
    (first.tiles_dir / "t").write_bytes(b"12345")
    assert open_area(tmp_path, "a" * 64).path == first.path
    assert (first.tiles_dir / "t").exists()
    assert list_areas(tmp_path) == [("a" * 64, 5), ("b" * 64, 0)]
    remove(first)
    assert [fp for fp, _ in list_areas(tmp_path)] == ["b" * 64]


def test_atomic_write_leaves_nothing_on_error(tmp_path):
    target = tmp_path / "out.bin"
    with pytest.raises(RuntimeError), atomic_write(target) as tmp:
        tmp.write_bytes(b"partial")
        raise RuntimeError
    assert list(tmp_path.iterdir()) == []


def test_atomic_write_replaces(tmp_path):
    target = tmp_path / "out.bin"
    target.write_bytes(b"old")
    with atomic_write(target) as tmp:
        tmp.write_bytes(b"new")
        assert target.read_bytes() == b"old"
    assert target.read_bytes() == b"new"
    assert list(tmp_path.iterdir()) == [target]
