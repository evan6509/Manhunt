"""Release regression tests; GitHub mutations are simulated, never performed."""

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import publish_release
from release_version import parse_version, select_version
from verify_mod_jar import verify


COMMIT = "a" * 40
OTHER_COMMIT = "b" * 40


def release(tag, target=OTHER_COMMIT, draft=False, prerelease=False):
    return dict(tag_name=tag, target_commitish=target, draft=draft, prerelease=prerelease,
                html_url="https://example.invalid/release", assets=[])


class VersionTests(unittest.TestCase):
    def test_first_release_and_patch_increment(self):
        self.assertEqual(select_version([], COMMIT, {}, "1.0.0"), "1.0.0")
        self.assertEqual(select_version([release("v1.0.0")], COMMIT, {}, "1.0.0"), "1.0.1")

    def test_highest_version_not_api_order(self):
        releases = [release("v1.0.3"), release("v1.0.1"), release("v1.0.2")]
        self.assertEqual(select_version(releases, COMMIT, {}, "1.0.0"), "1.0.4")

    def test_higher_configured_version(self):
        self.assertEqual(select_version([release("v1.0.3")], COMMIT, {}, "2.0.0"), "2.0.0")

    def test_rerun_uses_tag_commit_not_mutable_branch(self):
        releases = [release("v1.0.0", "main"), release("v1.0.1")]
        self.assertEqual(select_version(releases, COMMIT, {"v1.0.0": COMMIT}, "1.0.0"), "1.0.0")
        self.assertEqual(select_version(releases, COMMIT, {}, "1.0.0"), "1.0.2")

    def test_draft_retry_and_reserved_versions(self):
        self.assertEqual(select_version([release("v1.0.0", COMMIT, draft=True)], COMMIT, {}, "1.0.0"), "1.0.0")
        self.assertEqual(select_version([release("v1.0.0", draft=True)], COMMIT, {}, "1.0.0"), "1.0.1")
        self.assertEqual(select_version([], COMMIT, {"v1.0.4": OTHER_COMMIT}, "1.0.0"), "1.0.5")

    def test_prereleases_and_unrelated_tags(self):
        releases = [release("v2.0.0-beta.1", prerelease=True), release("old-release"),
                    release("v2.0.0", prerelease=True)]
        self.assertEqual(select_version(releases, COMMIT, {}, "1.0.0"), "1.0.0")
        # A real tag still reserves its name even if its release is marked prerelease.
        self.assertEqual(select_version(releases, COMMIT, {"v2.0.0": OTHER_COMMIT}, "1.0.0"), "2.0.1")

    def test_wrong_tag_overrides_release_target(self):
        self.assertEqual(select_version([release("v1.0.0", COMMIT)], COMMIT,
                                        {"v1.0.0": OTHER_COMMIT}, "1.0.0"), "1.0.1")

    def test_invalid_versions(self):
        for value in ["1.0", "v1.0.0", "1.0.0-beta", "01.0.0", "1.0.0\n", "", "../1.0.0"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_version(value)


class JarTests(unittest.TestCase):
    def fixture(self, folder, embedded="1.0.1+mc26.2", include_tests=False):
        jar = Path(folder) / "manhunt-1.0.1+mc26.2.jar"
        metadata = dict(id="manhunt", version=embedded, depends={"minecraft": "26.2", "java": ">=25"})
        with ZipFile(jar, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps(metadata))
            for name in ["ManhuntMod", "ManhuntCommands", "ManhuntState", "ManhuntState$Hunt",
                         "ManhuntService", "TrackingCompass"]:
                archive.writestr(f"com/evanchubbuck/manhunt/{name}.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x45")
            archive.writestr("LICENSE", "MIT License test fixture")
            if include_tests:
                archive.writestr("com/evanchubbuck/manhunt/ManhuntGameTests.class", b"test fixture")
        return jar

    def test_installable_jar_and_sources_exclusion(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = self.fixture(folder)
            (Path(folder) / "manhunt-1.0.1+mc26.2-sources.jar").write_bytes(b"sources fixture")
            self.assertEqual(verify(folder, "1.0.1")[0], jar)

    def test_wrong_embedded_version_and_accidental_tests(self):
        with tempfile.TemporaryDirectory() as folder:
            self.fixture(folder, embedded="1.0.0+mc26.2")
            with self.assertRaisesRegex(ValueError, "embedded version"):
                verify(folder, "1.0.1")
            self.fixture(folder, include_tests=True)
            with self.assertRaisesRegex(ValueError, "Test classes"):
                verify(folder, "1.0.1")

    def test_missing_or_multiple_installable_jars(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                verify(folder, "1.0.1")
            jar = self.fixture(folder)
            (Path(folder) / "unexpected.jar").write_bytes(jar.read_bytes())
            with self.assertRaises(ValueError):
                verify(folder, "1.0.1")


class PublicationTests(unittest.TestCase):
    def run_publication(self, folder, existing=None, corrupt=False, api_failure=False, releases=(), version="1.0.0"):
        directory = Path(folder)
        jar = directory / f"manhunt-{version}+mc26.2.jar"
        jar.write_bytes(b"verified installable jar fixture")
        metadata = dict(version=f"{version}+mc26.2", depends={"minecraft": "26.2", "fabricloader": ">=0.19.3",
                                                      "fabric-api": ">=0.154.2", "java": ">=25"})
        calls = []

        def fake_gh(*args):
            calls.append(args)
            if args[0] == "api" and "releases?" in args[1]:
                if api_failure:
                    raise subprocess.CalledProcessError(1, ["gh", *args])
                return json.dumps([([existing] if existing else []) + list(releases)])
            if args[0] == "api" and "generate-notes" in args[3]:
                return json.dumps(dict(body="## What's Changed\n- Test change in #1"))
            if args[:2] == ("release", "download"):
                downloaded = Path(args[args.index("--dir") + 1])
                for asset in [jar, directory / "CHANGELOG.md", directory / "SHA256SUMS"]:
                    (downloaded / asset.name).write_bytes(asset.read_bytes())
                if corrupt:
                    (downloaded / jar.name).write_bytes(b"corrupted download")
            if args[:2] == ("release", "view"):
                return json.dumps(dict(url="https://example.invalid/release"))
            return ""

        env = dict(MANHUNT_VERSION=version, RELEASE_TAG=f"v{version}",
                   GITHUB_REPOSITORY="example/manhunt", GITHUB_SHA=COMMIT)
        with patch.dict(os.environ, env, clear=True), patch("sys.argv", ["publish_release.py", folder]), \
                patch.object(publish_release, "verify", return_value=(jar, metadata)), \
                patch.object(publish_release, "commit_notes", return_value="- Direct commit change"), \
                patch.object(publish_release, "tag_commit", return_value=COMMIT if existing and not existing["draft"] else None), \
                patch.object(publish_release, "gh", side_effect=fake_gh), contextlib.redirect_stdout(io.StringIO()):
            try:
                publish_release.main()
            finally:
                self.calls = calls
        return calls

    def test_draft_download_verification_then_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder)
            creation = next(call for call in calls if call[:2] == ("release", "create"))
            self.assertIn("--draft", creation)
            self.assertIn(COMMIT, creation)
            download = next(i for i, call in enumerate(calls) if call[:2] == ("release", "download"))
            publish = next(i for i, call in enumerate(calls) if "--draft=false" in call)
            self.assertLess(download, publish)
            self.assertIn("--latest", calls[publish])
            self.assertIn("Test change in #1", (Path(folder) / "CHANGELOG.md").read_text())
            self.assertIn("Direct commit change", (Path(folder) / "CHANGELOG.md").read_text())
            self.assertIn("SHA256SUMS", " ".join(creation))

    def test_corrupt_download_is_not_published(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "differs from the build"):
                self.run_publication(folder, corrupt=True)
            self.assertFalse(any("--draft=false" in call for call in self.calls))

    def test_api_failure_is_not_treated_as_missing_release(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(subprocess.CalledProcessError):
                self.run_publication(folder, api_failure=True)
            self.assertFalse(any(call[0] == "release" for call in self.calls))

    def test_retry_resumes_only_a_matching_draft(self):
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder, existing=release("v1.0.0", COMMIT, draft=True))
            self.assertFalse(any(call[:2] == ("release", "create") for call in calls))
            self.assertTrue(any(call[:2] == ("release", "upload") for call in calls))
            self.assertTrue(any("--draft=false" in call for call in calls))

    def test_older_draft_retry_does_not_replace_newer_latest_release(self):
        existing = release("v1.0.0", COMMIT, draft=True)
        newer = release("v1.0.1")
        self.assertEqual(select_version([existing, newer], COMMIT, {}, "1.0.0"), "1.0.0")
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder, existing=existing, releases=[newer])
            publication = next(call for call in calls if "--draft=false" in call)
            self.assertIn("--latest=false", publication)

    def test_unpublished_and_prerelease_versions_do_not_block_latest(self):
        releases = [release("v1.0.1", draft=True), release("v2.0.0", prerelease=True),
                    release("v3.0.0-beta.1", prerelease=True), release("nightly")]
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder, releases=releases)
            publication = next(call for call in calls if "--draft=false" in call)
            self.assertIn("--latest", publication)

    def test_latest_uses_numeric_version_order(self):
        for version, other_version, flag in [("1.0.9", "1.0.10", "--latest=false"),
                                             ("1.0.10", "1.0.9", "--latest")]:
            with self.subTest(version=version), tempfile.TemporaryDirectory() as folder:
                calls = self.run_publication(folder, releases=[release(f"v{other_version}")], version=version)
                publication = next(call for call in calls if "--draft=false" in call)
                self.assertIn(flag, publication)

    def test_published_rerun_does_not_modify_assets(self):
        existing = release("v1.0.0", COMMIT)
        existing["assets"] = [dict(name=name, state="uploaded") for name in
                              ["manhunt-1.0.0+mc26.2.jar", "CHANGELOG.md", "SHA256SUMS"]]
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder, existing=existing, releases=[release("v1.0.1")])
            self.assertFalse(any(call[0] == "release" for call in calls))

    def test_conflicting_tag_or_mutable_draft_target_is_rejected(self):
        with self.assertRaises(ValueError):
            publish_release.validate_target(release("v1.0.0", COMMIT), OTHER_COMMIT, COMMIT)
        with self.assertRaises(ValueError):
            publish_release.validate_target(release("v1.0.0", "main", draft=True), None, COMMIT)

    def test_commit_notes_include_direct_changes_and_use_release_range(self):
        output = COMMIT + "\0Fix [CSV] logging\n" + OTHER_COMMIT + "\0Handle commas\n"
        with patch.object(publish_release.subprocess, "check_output", return_value=output) as log:
            notes = publish_release.commit_notes("v1.0.0", COMMIT, "example/manhunt")
            self.assertIn("refs/tags/v1.0.0.." + COMMIT, log.call_args.args[0])
            self.assertIn("Fix \\[CSV\\] logging", notes)
            self.assertIn("Handle commas", notes)
            self.assertIn("/commit/" + OTHER_COMMIT, notes)
            publish_release.commit_notes(None, COMMIT, "example/manhunt")
            self.assertEqual(log.call_args.args[0][-1], COMMIT)


if __name__ == "__main__":
    unittest.main()
