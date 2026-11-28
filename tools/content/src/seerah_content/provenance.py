"""Resolve a content source revision for release builds."""

import re
import subprocess
from pathlib import Path


def source_revision(root: Path, allow_dirty: bool, override: str | None) -> str:
    """Require a clean checkout or an explicit local fixture override."""

    def git(*args):
        return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)

    head = git("rev-parse", "HEAD")
    if head.returncode and not (allow_dirty and override):
        raise ValueError("outside a Git checkout, pass --allow-dirty and --source-commit")
    if not allow_dirty:
        status = git("status", "--porcelain", "--untracked-files=all")
        if status.returncode or status.stdout:
            raise ValueError("release requires a clean source checkout")
        tracked = git("ls-files", "-z", "--", ".")
        names = set(tracked.stdout.split("\0"))
        sources = [root / "content.yaml"]
        for folder in ("world", "years", "texts", "stories"):
            sources.extend((root / folder).glob("*.yaml"))
        for path in sources:
            if path.relative_to(root).as_posix() not in names or path.is_symlink():
                raise ValueError(f"{path}: release source must be a tracked regular file")
    sha = override or head.stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{40}(?:[0-9a-f]{24})?", sha):
        raise ValueError("invalid source commit")
    if not allow_dirty and sha != head.stdout.strip():
        raise ValueError("source commit must match HEAD")
    return sha
