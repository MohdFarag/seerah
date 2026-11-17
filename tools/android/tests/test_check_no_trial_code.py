"""Tests for check_no_trial_code.py, with a fake aapt2 standing in for the real one."""

import contextlib
import io
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_no_trial_code as guard  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"
RELEASE_COMPONENTS = [
    "app.seerah.MainActivity",
    "androidx.startup.InitializationProvider",
    "androidx.profileinstaller.ProfileInstallReceiver",
]


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text()


class GuardTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.sdk = Path(self._tmp.name)

    def install_aapt2(self, version: str, script_body: str) -> Path:
        directory = self.sdk / "build-tools" / version
        directory.mkdir(parents=True)
        aapt2 = directory / "aapt2"
        aapt2.write_text("#!/bin/sh\n" + script_body)
        aapt2.chmod(aapt2.stat().st_mode | stat.S_IXUSR)
        return aapt2

    def install_printing(self, text: str, exit_code: int = 0) -> Path:
        dump = self.sdk / "dump.txt"
        dump.write_text(text)
        return self.install_aapt2("37.0.0", f'cat "{dump}"\nexit {exit_code}\n')

    def run_guard(self, **env) -> int:
        environment = {"ANDROID_HOME": str(self.sdk), **env}
        with mock.patch.dict(os.environ, environment, clear=True):
            return guard.main(["app.apk"])

    def test_release_manifest_passes(self):
        self.assertEqual(guard.components(fixture("release_manifest.txt")), RELEASE_COMPONENTS)
        self.install_printing(fixture("release_manifest.txt"))
        self.assertEqual(self.run_guard(), 0)

    def test_trial_component_fails(self):
        self.install_printing(fixture("trial_manifest.txt"))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(self.run_guard(), 1)
        self.assertIn("app.seerah.trial.TrialActivity", out.getvalue())

    def test_component_without_name_fails_and_next_component_is_still_checked(self):
        dump = (
            "N: android=http://schemas.android.com/apk/res/android\n"
            "  E: manifest (line=2)\n"
            "    E: application (line=5)\n"
            "      E: activity (line=6)\n"
            "        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true\n"
            "      E: activity (line=8)\n"
            "        A: http://schemas.android.com/apk/res/android:name(0x01010003)="
            '"app.seerah.trial.TrialActivity" (Raw: "app.seerah.trial.TrialActivity")\n'
        )
        with self.assertRaises(ValueError):
            guard.components(dump)
        self.install_printing(dump)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(self.run_guard(), 1)

    def test_fails_when_dump_empty(self):
        self.install_printing("")
        self.assertEqual(self.run_guard(), 1)

    def test_fails_when_dump_unparsable(self):
        self.install_printing("this is not a manifest\n")
        self.assertEqual(self.run_guard(), 1)

    def test_fails_when_aapt2_exits_non_zero(self):
        self.install_printing(fixture("release_manifest.txt"), exit_code=1)
        self.assertEqual(self.run_guard(), 1)

    def test_fails_when_build_tools_missing(self):
        self.assertEqual(self.run_guard(), 1)

    def test_newest_build_tools_chosen_numerically(self):
        for version in ("9.0.0", "37.0.0", "36.1.0"):
            self.install_aapt2(version, "exit 0\n")
        found = guard.find_aapt2({"ANDROID_HOME": str(self.sdk)})
        self.assertEqual(found, self.sdk / "build-tools" / "37.0.0" / "aapt2")

    def test_sdk_root_used_when_android_home_unset(self):
        self.install_aapt2("37.0.0", "exit 0\n")
        found = guard.find_aapt2({"ANDROID_SDK_ROOT": str(self.sdk)})
        self.assertEqual(found, self.sdk / "build-tools" / "37.0.0" / "aapt2")


if __name__ == "__main__":
    unittest.main()
