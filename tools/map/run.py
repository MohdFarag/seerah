#!/usr/bin/env python3
"""Host launcher for the map toolchain.

Starts the toolchain image and runs ``python3 -m seerah_map <command>`` inside it. The
host needs only Python 3.11 (standard library), git and docker or podman-compatible
``docker``. Both checkouts (this repository and the data repository) must be clean, so the
commits recorded in the build provenance describe exactly what was built.

Usage::

    run.py [--private PATH] [--data PATH] [--out PATH] [--memory-mib 8192]
           [--image REF] [--print-tag] -- <command> [args]
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

IMAGE_REPO = "ghcr.io/al-seerah/seerah-map"
PUBLIC_ROOT = Path(__file__).resolve().parents[2]
TOOL_DIR = Path(__file__).resolve().parent
TAG_HEX_CHARS = 16
DEFAULT_MEMORY_MIB = 8192
CACHE_VOLUME = "seerah-map-cache"
WORK_VOLUME = "seerah-map-work"
PYTHONPATH_IN_IMAGE = "/src/tools/map/src"
FETCH_COMMAND = "fetch"
DOCKER = "docker"
DIGEST_SEPARATOR = "@"


def image_tag(dockerfile: Path, requirements: Path) -> str:
    """Return the image tag: first 16 hex characters of SHA-256(Dockerfile + requirements.txt).

    Args:
        dockerfile: path to the Dockerfile.
        requirements: path to the hash-locked requirements.txt.
    """
    digest = hashlib.sha256(dockerfile.read_bytes() + requirements.read_bytes())
    return digest.hexdigest()[:TAG_HEX_CHARS]


def _git(repo: Path, *args: str) -> str:
    done = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, text=True, check=False
    )
    if done.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed in {repo}: {done.stderr.strip()}")
    return done.stdout.strip()


def check_clean(repo: Path) -> str:
    """Return the HEAD commit of ``repo``, or exit if it has uncommitted changes.

    Args:
        repo: root of a git checkout.
    """
    if _git(repo, "status", "--porcelain"):
        raise SystemExit(f"{repo} has uncommitted or untracked changes: commit or stash them first")
    return _git(repo, "rev-parse", "HEAD")


def _inspect(image: str) -> dict:
    done = subprocess.run(
        [DOCKER, "image", "inspect", image], capture_output=True, text=True, check=False
    )
    if done.returncode != 0:
        raise SystemExit(f"image {image} not found: {done.stderr.strip()}")
    return json.loads(done.stdout)[0]


def resolve_image(ref: str | None) -> tuple[str, str]:
    """Return ``(image to run, image digest)``.

    With ``ref`` the image is used as is and never pulled; an image without a repository
    digest is recorded as ``local:<Id>``. Without ``ref`` the image for the current
    Dockerfile and requirements is pulled from ``IMAGE_REPO``.

    Args:
        ref: an image reference given with ``--image``, or None.
    """
    if ref is None:
        tag = image_tag(TOOL_DIR / "Dockerfile", TOOL_DIR / "requirements.txt")
        ref = f"{IMAGE_REPO}:{tag}"
        pulled = subprocess.run([DOCKER, "pull", ref], check=False)
        if pulled.returncode != 0:
            raise SystemExit(
                f"could not pull {ref}: check the network and registry access,"
                " or build the image locally and pass it with --image"
            )
    info = _inspect(ref)
    repo_digests = info.get("RepoDigests") or []
    if repo_digests and DIGEST_SEPARATOR in repo_digests[0]:
        return ref, repo_digests[0].split(DIGEST_SEPARATOR, 1)[1]
    return ref, f"local:{info['Id']}"


def docker_args(
    cmd: list[str],
    public: Path,
    data: Path,
    out: Path,
    image: str,
    memory_mib: int,
    network: bool,
    user: tuple[int, int],
    env: dict[str, str],
) -> list[str]:
    """Build the ``docker run`` argument list (never a shell string).

    Args:
        cmd: the ``seerah_map`` command and its arguments.
        public: this repository's root, mounted read-only at /src.
        data: the data root, mounted read-only at /data.
        out: the output directory, mounted at /out.
        image: image reference to run.
        memory_mib: memory and swap limit in MiB.
        network: whether the container may use the network (only ``fetch`` needs it).
        user: host ``(uid, gid)`` the container runs as.
        env: environment variables to pass in (the provenance values).
    """
    args = [
        DOCKER,
        "run",
        "--rm",
        "--security-opt",
        "label=disable",
        "--memory",
        f"{memory_mib}m",
        "--memory-swap",
        f"{memory_mib}m",
        "--user",
        f"{user[0]}:{user[1]}",
        "-v",
        f"{public}:/src:ro",
        "-v",
        f"{data}:/data:ro",
        "-v",
        f"{CACHE_VOLUME}:/cache",
        "-v",
        f"{WORK_VOLUME}:/work",
        "-v",
        f"{out}:/out",
        "-w",
        "/src",
        "-e",
        f"PYTHONPATH={PYTHONPATH_IN_IMAGE}",
    ]
    if not network:
        args += ["--network", "none"]
    for name, value in env.items():
        args += ["-e", f"{name}={value}"]
    return [*args, image, "python3", "-m", "seerah_map", *cmd]


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__.splitlines()[0],
        usage="run.py [options] -- <command> [args]",
    )
    parser.add_argument(
        "--private",
        type=Path,
        default=Path("../seerah-content"),
        help="private content checkout (default ../seerah-content)",
    )
    parser.add_argument("--data", type=Path, help="data root (default <private>/map)")
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("map-data/dist"),
        help="output directory (default map-data/dist)",
    )
    parser.add_argument(
        "--memory-mib",
        type=int,
        default=DEFAULT_MEMORY_MIB,
        help=f"container memory limit (default {DEFAULT_MEMORY_MIB})",
    )
    parser.add_argument("--image", help="run this image instead of pulling IMAGE_REPO:<tag>")
    parser.add_argument(
        "--print-tag", action="store_true", help="print the image reference and exit"
    )
    return parser


def data_root(opts: argparse.Namespace) -> Path:
    """Return the absolute data root: ``--data``, or ``<--private>/map``.

    Relative paths are resolved against this repository's root.

    Args:
        opts: parsed launcher options.
    """
    return (PUBLIC_ROOT / (opts.data or opts.private / "map")).resolve()


def main(argv: list[str]) -> int:
    """Run the toolchain command after ``--`` in the image; return its exit code.

    Args:
        argv: command-line arguments without the program name.
    """
    if "--" in argv:
        split = argv.index("--")
        own, cmd = argv[:split], argv[split + 1 :]
    else:
        own, cmd = argv, []
    opts = _parser().parse_args(own)
    if opts.print_tag:
        tag = image_tag(TOOL_DIR / "Dockerfile", TOOL_DIR / "requirements.txt")
        print(f"{IMAGE_REPO}:{tag}")
        return 0
    if not cmd:
        raise SystemExit("give a command after --, for example: -- fetch --profile trial/full")

    if shutil.which(DOCKER) is None:
        raise SystemExit("docker not found: install docker or podman-docker")

    data = data_root(opts)
    if not data.is_dir():
        raise SystemExit(f"data folder {data} does not exist: check --private / --data")
    out = (PUBLIC_ROOT / opts.out).resolve()
    tool_commit = check_clean(PUBLIC_ROOT)
    data_commit = check_clean(Path(_git(data, "rev-parse", "--show-toplevel")))
    image, digest = resolve_image(opts.image)
    env = {
        "SEERAH_TOOL_COMMIT": tool_commit,
        "SEERAH_DATA_COMMIT": data_commit,
        "SEERAH_IMAGE_DIGEST": digest,
    }
    args = docker_args(
        cmd,
        PUBLIC_ROOT,
        data,
        out,
        image,
        opts.memory_mib,
        cmd[0] == FETCH_COMMAND,
        (os.getuid(), os.getgid()),
        env,
    )
    out.mkdir(parents=True, exist_ok=True)
    return subprocess.run(args, check=False).returncode


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
