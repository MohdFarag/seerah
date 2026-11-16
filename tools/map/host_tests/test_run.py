"""Tests for the host launcher ``tools/map/run.py`` (standard library only)."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run  # noqa: E402

USER = (1000, 1000)
ENV = {
    "SEERAH_TOOL_COMMIT": "a" * 40,
    "SEERAH_DATA_COMMIT": "b" * 40,
    "SEERAH_IMAGE_DIGEST": "sha256:" + "c" * 64,
}


def _args(cmd=("build", "--profile", "x"), public=Path("/p"), data=Path("/d"), network=False):
    return run.docker_args(list(cmd), public, data, Path("/o"), "img:1", 8192, network, USER, ENV)


def _git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)


def _make_repo(root: Path, dirty: bool) -> Path:
    root.mkdir(parents=True)
    _git(root, "init", "-q")
    (root / "f.txt").write_text("x")
    _git(root, "add", ".")
    _git(root, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "m")
    if dirty:
        (root / "f.txt").write_text("changed")
    return root


class DockerArgsTest(unittest.TestCase):
    def test_args_are_a_list_and_paths_with_spaces_survive(self):
        args = _args(public=Path("/my repo/seerah"), data=Path("/my data"))
        self.assertIsInstance(args, list)
        self.assertIn("/my repo/seerah:/src:ro", args)
        self.assertIn("/my data:/data:ro", args)
        self.assertEqual(args[args.index("seerah_map") + 1 :], ["build", "--profile", "x"])

    def test_mounts_and_security(self):
        args = _args()
        for expected in (
            "/p:/src:ro",
            "/d:/data:ro",
            "seerah-map-cache:/cache",
            "seerah-map-work:/work",
            "/o:/out",
            "--security-opt",
            "label=disable",
            "--rm",
            "PYTHONPATH=/src/tools/map/src",
            "1000:1000",
        ):
            self.assertIn(expected, args)
        for name, value in ENV.items():
            self.assertIn(f"{name}={value}", args)
        self.assertEqual(args[args.index("-w") + 1], "/src")

    def test_network_none_except_fetch(self):
        offline = _args(network=False)
        self.assertEqual(offline[offline.index("--network") + 1], "none")
        self.assertNotIn("--network", _args(cmd=("fetch",), network=True))

    def test_memory_flags(self):
        args = _args()
        self.assertEqual(args[args.index("--memory") + 1], "8192m")
        self.assertEqual(args[args.index("--memory-swap") + 1], "8192m")


class ImageTest(unittest.TestCase):
    def test_image_tag_known_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            dockerfile, reqs = Path(tmp, "Dockerfile"), Path(tmp, "requirements.txt")
            dockerfile.write_bytes(b"FROM x\n")
            reqs.write_bytes(b"numpy==1\n")
            expected = hashlib.sha256(b"FROM x\nnumpy==1\n").hexdigest()[:16]
            self.assertEqual(run.image_tag(dockerfile, reqs), expected)

    def test_print_tag(self):
        with mock.patch("builtins.print") as printed:
            self.assertEqual(run.main(["--print-tag"]), 0)
        text = printed.call_args.args[0]
        self.assertRegex(text, r"^ghcr\.io/mohdfarag/seerah-map:[0-9a-f]{16}$")

    def test_local_image_digest(self):
        inspected = [{"Id": "sha256:" + "d" * 64, "RepoDigests": []}]
        done = subprocess.CompletedProcess([], 0, stdout=json.dumps(inspected), stderr="")
        with mock.patch("subprocess.run", return_value=done) as stub:
            image, digest = run.resolve_image("seerah-map:dev")
        self.assertEqual((image, digest), ("seerah-map:dev", "local:sha256:" + "d" * 64))
        for call in stub.call_args_list:
            self.assertNotIn("pull", call.args[0])

    def test_repository_digest_is_used_when_present(self):
        inspected = [{"Id": "sha256:1", "RepoDigests": ["r/x@sha256:" + "e" * 64]}]
        done = subprocess.CompletedProcess([], 0, stdout=json.dumps(inspected), stderr="")
        with mock.patch("subprocess.run", return_value=done):
            self.assertEqual(run.resolve_image("r/x:1")[1], "sha256:" + "e" * 64)


class CleanCheckTest(unittest.TestCase):
    def test_clean_repo_returns_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = _make_repo(Path(tmp, "r"), dirty=False)
            self.assertRegex(run.check_clean(repo), r"^[0-9a-f]{40}$")

    def test_dirty_public_checkout_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = _make_repo(Path(tmp, "public"), dirty=True)
            with self.assertRaises(SystemExit) as ctx:
                run.check_clean(repo)
            self.assertIn("commit or stash", str(ctx.exception))
            self.assertIn(str(repo), str(ctx.exception))

    def test_dirty_data_checkout_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            public = _make_repo(Path(tmp, "public"), dirty=False)
            data = _make_repo(Path(tmp, "content"), dirty=True)
            with (
                mock.patch.object(run, "PUBLIC_ROOT", public),
                self.assertRaises(SystemExit) as ctx,
            ):
                run.main(["--data", str(data), "--image", "x", "--", "fetch"])
            self.assertIn(str(data), str(ctx.exception))
            self.assertIn("commit or stash", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
