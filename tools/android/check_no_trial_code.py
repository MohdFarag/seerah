#!/usr/bin/env python3
"""Check that an APK's manifest declares no map-trial component.

The map trial's code lives in `app.seerah.trial` and ships only in the `benchmark` build
type. A release APK must contain none of it; ADR 0007 decision 10 asks for a build check
that proves a release build carries no development-only path.

The check reads the compiled manifest with `aapt2 dump xmltree` and fails when it names an
`app.seerah.trial` component. It also fails when it checked nothing: no aapt2, aapt2 exiting
non-zero, a dump with no manifest in it, or a component it cannot name.

Usage: check_no_trial_code.py APK
Exit code 0 when the manifest was read and is clean, 1 otherwise.
"""

import os
import re
import subprocess
import sys
from collections.abc import Mapping
from pathlib import Path

TRIAL_PACKAGE = "app.seerah.trial"
COMPONENT_ELEMENT = re.compile(r"^\s*E: (?:activity|activity-alias|service|receiver|provider) ")
NAME_ATTRIBUTE = re.compile(r':name\(0x[0-9a-f]+\)="([^"]*)"')
ANY_ELEMENT = re.compile(r"^\s*E: ")
MANIFEST_ELEMENT = re.compile(r"^\s*E: manifest ", re.MULTILINE)
SDK_VARIABLES = ("ANDROID_HOME", "ANDROID_SDK_ROOT")


def find_aapt2(env: Mapping[str, str]) -> Path | None:
    """The aapt2 of the newest build-tools version under the SDK, or None."""
    for variable in SDK_VARIABLES:
        if not env.get(variable):
            continue
        versions = [
            d for d in (Path(env[variable]) / "build-tools").glob("*") if (d / "aapt2").is_file()
        ]
        if versions:
            return max(versions, key=version_key) / "aapt2"
    return None


def version_key(directory: Path) -> tuple[int, ...]:
    """Sort key comparing build-tools versions numerically ("37.0.0" > "9.0.0")."""
    return tuple(int(part) for part in re.findall(r"\d+", directory.name))


def components(dump: str) -> list[str]:
    """Names of the manifest's activities, services, receivers and providers, in order.

    A component's name must be among its own attributes, which aapt2 prints before the next
    element; raises ValueError for a component without one, so an unreadable manifest fails.
    """
    names = []
    unnamed = None
    for line in dump.splitlines():
        if ANY_ELEMENT.match(line):
            if unnamed is not None:
                raise ValueError(f"component without a name: {unnamed.strip()}")
            if COMPONENT_ELEMENT.match(line):
                unnamed = line
        elif unnamed is not None and (match := NAME_ATTRIBUTE.search(line)):
            names.append(match.group(1))
            unnamed = None
    if unnamed is not None:
        raise ValueError(f"component without a name: {unnamed.strip()}")
    return names


def read_manifest(apk: str, env: Mapping[str, str]) -> str:
    """The manifest dump of an APK; raises RuntimeError when it could not be read."""
    aapt2 = find_aapt2(env)
    if aapt2 is None:
        raise RuntimeError("aapt2 not found: set ANDROID_HOME or ANDROID_SDK_ROOT")
    result = subprocess.run(
        [str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"aapt2 failed: {result.stderr.strip()}")
    if not MANIFEST_ELEMENT.search(result.stdout):
        raise RuntimeError("aapt2 printed no manifest")
    return result.stdout


def main(args: list[str]) -> int:
    if len(args) != 1:
        print(__doc__)
        return 2
    try:
        dump = read_manifest(args[0], os.environ)
    except RuntimeError as error:
        print(f"error: {error}")
        return 1
    try:
        names = components(dump)
    except ValueError as error:
        print(f"error: {error}")
        return 1
    trial = [name for name in names if name.startswith(TRIAL_PACKAGE)]
    for name in trial:
        print(f"error: {args[0]} declares the trial component {name}")
    if not trial:
        print(f"ok: {args[0]} declares no {TRIAL_PACKAGE} component")
    return 1 if trial else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
