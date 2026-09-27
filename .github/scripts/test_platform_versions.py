#!/usr/bin/env python3
"""
Offline tests for platform_versions.py.

Run from anywhere:  python3 .github/scripts/test_platform_versions.py
"""

import io
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FIXTURES = HERE / "fixtures"
sys.path.insert(0, str(HERE))

import platform_versions as pv  # noqa: E402


class CopyOfRepo(unittest.TestCase):
    """Each test works on a temporary copy of the real version files."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (self.tmp / "gradle").mkdir()
        shutil.copy(REPO / "gradle" / "libs.versions.toml", self.tmp / "gradle" / "libs.versions.toml")
        shutil.copy(REPO / "gradle.properties", self.tmp / "gradle.properties")
        self.orig = pv.Repo(self.tmp).current()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def run_cli(self, *argv, env_output=None):
        buf = io.StringIO()
        old = pv.os.environ.pop("GITHUB_OUTPUT", None)
        if env_output:
            pv.os.environ["GITHUB_OUTPUT"] = str(env_output)
        try:
            with redirect_stdout(buf):
                rc = pv.main(["--root", str(self.tmp), *argv])
        finally:
            pv.os.environ.pop("GITHUB_OUTPUT", None)
            if old is not None:
                pv.os.environ["GITHUB_OUTPUT"] = old
        self.assertEqual(rc, 0)
        return json.loads(buf.getvalue())

    def set_current(self, clion, idea, since):
        t = self.tmp / "gradle" / "libs.versions.toml"
        s = pv.replace_toml_value(t.read_text(), "intellij-clion", clion)
        s = pv.replace_toml_value(s, "intellij-idea", idea)
        t.write_text(s)
        p = self.tmp / "gradle.properties"
        p.write_text(pv.replace_prop(p.read_text(), "pluginSinceBuild", since))


class HelperTests(unittest.TestCase):
    def test_branch_of(self):
        self.assertEqual(pv.branch_of("262.9437.65"), "262")
        self.assertEqual(pv.branch_of("2026.2.0.1"), "262")
        self.assertEqual(pv.branch_of("2025.3"), "253")

    def test_version_key(self):
        self.assertGreater(pv.version_key("2026.2.1"), pv.version_key("2026.2.0.1"))
        self.assertGreater(pv.version_key("2026.10"), pv.version_key("2026.9"))
        self.assertEqual(pv.version_key("2026.2"), pv.version_key("2026.2"))

    def test_toml_replace_keeps_layout(self):
        text = '# @keep\nintellij-clion = "1.0"  # comment\nintellij-idea = "1.0"\n'
        out = pv.replace_toml_value(text, "intellij-clion", "2.0")
        self.assertEqual(out, '# @keep\nintellij-clion = "2.0"  # comment\nintellij-idea = "1.0"\n')

    def test_props_replace_empty_value(self):
        text = "pluginSinceBuild=262\npluginUntilBuild=\n"
        self.assertEqual(pv.read_prop(text, "pluginUntilBuild"), "")
        self.assertEqual(pv.replace_prop(text, "pluginSinceBuild", "263"),
                         "pluginSinceBuild=263\npluginUntilBuild=\n")

    def test_missing_key(self):
        with self.assertRaises(SystemExit):
            pv.read_toml_value("foo = \"1\"\n", "intellij-clion")

    def test_parse_plugin_list(self):
        data = (FIXTURES / "plugins-nativeDebug.xml").read_bytes()
        self.assertEqual(pv.parse_plugin_list(data, "com.intellij.nativeDebug"), "262.10523.35")
        empty = (FIXTURES / "plugins-empty.xml").read_bytes()
        self.assertIsNone(pv.parse_plugin_list(empty, "com.intellij.nativeDebug"))


class CliTests(CopyOfRepo):
    def test_current_matches_repo(self):
        out = self.run_cli("current")
        self.assertEqual(out, self.orig)
        self.assertTrue(out["clion"])
        self.assertTrue(out["since_build"].isdigit())

    def test_check_detects_update(self):
        self.set_current("2026.2.0.1", "2026.2.0.1", "262")
        out = self.run_cli("--releases-fixture", str(FIXTURES / "releases-release.json"), "check")
        self.assertTrue(out["update_needed"])
        self.assertEqual(out["outdated"], ["clion", "idea"])
        self.assertEqual(out["latest"]["clion"]["version"], "2026.2.2")
        self.assertFalse(out["new_branch"])

    def test_check_up_to_date(self):
        self.set_current("2026.2.2", "2026.2.2", "262")
        out = self.run_cli("--releases-fixture", str(FIXTURES / "releases-release.json"), "check")
        self.assertFalse(out["update_needed"])

    def test_check_new_branch_and_github_output(self):
        self.set_current("2026.2.2", "2026.2.2", "262")
        gh_out = self.tmp / "gh_output"
        out = self.run_cli("--channel", "eap", "--releases-fixture", str(FIXTURES / "releases-eap.json"),
                           "check", env_output=gh_out)
        self.assertTrue(out["new_branch"])
        lines = gh_out.read_text().splitlines()
        self.assertIn("update_needed=true", lines)
        self.assertIn("latest_clion_version=2026.3", lines)
        self.assertIn("latest_idea_build=263.2034.12", lines)
        self.assertIn("outdated=clion idea", lines)

    def test_bump_latest_with_marketplace(self):
        out = self.run_cli("--releases-fixture", str(FIXTURES / "releases-release.json"),
                           "--plugins-fixture", str(FIXTURES / "plugins-nativeDebug.xml"), "bump")
        self.assertTrue(out["changed"])
        self.assertEqual(out["native_debug_source"], "marketplace")
        now = pv.Repo(self.tmp).current()
        self.assertEqual(now["clion"], "2026.2.2")
        self.assertEqual(now["idea"], "2026.2.2")
        self.assertEqual(now["native_debug"], "262.10523.35")
        self.assertEqual(now["since_build"], "262")
        self.assertEqual(now["until_build"], self.orig["until_build"])

    def test_bump_marketplace_miss_falls_back_to_idea_build(self):
        out = self.run_cli("--releases-fixture", str(FIXTURES / "releases-eap.json"),
                           "--plugins-fixture", str(FIXTURES / "plugins-empty.xml"),
                           "bump", "--clion", "263.2034.18", "--idea", "263.2034.12")
        self.assertEqual(out["native_debug_source"], "fallback")
        self.assertEqual(out["after"]["native_debug"], "263.2034.12")
        self.assertEqual(out["after"]["since_build"], "263")
        self.assertTrue(out["branch_changed"])

    def test_bump_eap_prefer_build(self):
        out = self.run_cli("--channel", "eap", "--releases-fixture", str(FIXTURES / "releases-eap.json"),
                           "--plugins-fixture", str(FIXTURES / "plugins-empty.xml"), "bump", "--prefer-build")
        self.assertEqual(out["after"]["clion"], "263.2034.18")
        self.assertEqual(out["after"]["idea"], "263.2034.12")
        self.assertEqual(out["after"]["native_debug"], "263.2034.12")
        self.assertEqual(out["after"]["since_build"], "263")

    def test_bump_explicit_values_and_keep(self):
        out = self.run_cli("bump", "--clion", "2026.2.2", "--idea", "2026.2.2",
                           "--native-debug", "262.1.2", "--since-build", "keep")
        self.assertEqual(out["after"]["native_debug"], "262.1.2")
        self.assertEqual(out["after"]["since_build"], self.orig["since_build"])
        self.assertEqual(out["native_debug_source"], "input")

    def test_bump_resolves_marketing_version_to_build(self):
        out = self.run_cli("--releases-fixture", str(FIXTURES / "releases-release.json"),
                           "--plugins-fixture", str(FIXTURES / "plugins-empty.xml"),
                           "bump", "--clion", "2026.2.0.1", "--idea", "2026.2.0.1")
        self.assertEqual(out["after"]["native_debug"], "262.9437.65")

    def test_bump_dry_run_does_not_write(self):
        toml_before = (self.tmp / "gradle" / "libs.versions.toml").read_text()
        out = self.run_cli("bump", "--clion", "2099.1", "--idea", "2099.1",
                           "--native-debug", "991.1.1", "--dry-run")
        self.assertTrue(out["changed"])
        self.assertEqual((self.tmp / "gradle" / "libs.versions.toml").read_text(), toml_before)

    def test_bump_noop(self):
        out = self.run_cli("bump", "--clion", self.orig["clion"], "--idea", self.orig["idea"],
                           "--native-debug", "keep", "--since-build", "keep")
        self.assertFalse(out["changed"])

    def test_bump_only_touches_version_lines(self):
        toml_path = self.tmp / "gradle" / "libs.versions.toml"
        before = toml_path.read_text().splitlines()
        self.run_cli("bump", "--clion", "2099.1", "--idea", "2099.1", "--native-debug", "991.1.1")
        after = toml_path.read_text().splitlines()
        diff = [(a, b) for a, b in zip(before, after) if a != b]
        self.assertEqual(len(before), len(after))
        self.assertEqual(len(diff), 3)

    def test_bump_rejects_bad_since_build(self):
        with self.assertRaises(SystemExit):
            self.run_cli("bump", "--clion", "2026.2.2", "--idea", "2026.2.2",
                         "--native-debug", "keep", "--since-build", "abc")


if __name__ == "__main__":
    unittest.main(verbosity=2)
