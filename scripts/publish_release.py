#!/usr/bin/env python3
"""Stage release assets, verify their downloaded bytes, then publish the release."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from release_version import parse_version, stable_releases, tag_commit
from verify_mod_jar import verify


def gh(*args):
    # API/network failures stop publication; they never mean "release missing".
    return subprocess.check_output(["gh", *args], text=True)


def validate_target(release, actual_tag_commit, commit):
    if actual_tag_commit is not None and actual_tag_commit != commit:
        raise ValueError("Release tag points at a different commit")
    if release is not None and actual_tag_commit is None:
        if not release["draft"] or release.get("target_commitish") != commit:
            raise ValueError("Existing release does not identify this exact commit")


def previous_tag(releases, current_tag, commit):
    published = [release for release in stable_releases(releases)
                 if not release["draft"] and release["tag_name"] != current_tag]
    published.sort(key=lambda release: parse_version(release["tag_name"][1:]), reverse=True)
    for release in published:
        tag = release["tag_name"]
        if tag_commit(tag) is not None:
            result = subprocess.run(["git", "merge-base", "--is-ancestor", f"refs/tags/{tag}", commit],
                                    check=False)
            if result.returncode == 0:
                return tag
            if result.returncode != 1:
                raise ValueError(f"Could not resolve previous release {tag}")
    return None


def commit_notes(previous, commit, repository):
    revision = f"refs/tags/{previous}..{commit}" if previous else commit
    log = subprocess.check_output(["git", "log", "--reverse", "--format=%H%x00%s", revision], text=True)
    bullets = []
    for line in log.splitlines():
        sha, subject = line.split("\0", 1)
        subject = re.sub(r"([\\`*_{}\[\]<>])", r"\\\1", subject)
        bullets.append(f"- {subject} ([{sha[:7]}](https://github.com/{repository}/commit/{sha}))")
    return "\n".join(bullets)


def write_changelog(path, version, jar, metadata, notes, repository, commit, commits):
    depends = metadata["depends"]
    requirements = ", ".join(f"{name} `{depends[key]}`" for key, name in (
        ("minecraft", "Minecraft"), ("fabricloader", "Fabric Loader"),
        ("fabric-api", "Fabric API"), ("java", "Java")))
    path.write_text(
        f"# Manhunt {version}\n\n{notes.strip()}\n\n"
        f"## Commits\n\n{commits}\n\n"
        f"## Install\n\nDownload `{jar.name}` and put it in the server's `mods` folder.\n\n"
        f"Requirements: {requirements}.\n\n"
        f"Source: [{commit[:7]}](https://github.com/{repository}/commit/{commit}).\n",
        encoding="utf-8")


def verify_downloads(assets, downloaded):
    for asset in assets:
        if (Path(downloaded) / asset.name).read_bytes() != asset.read_bytes():
            raise ValueError(f"Downloaded release asset differs from the build: {asset.name}")


def main():
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else "release")
    version = os.environ["MANHUNT_VERSION"]
    tag = os.environ["RELEASE_TAG"]
    if tag != f"v{version}":
        raise ValueError("Release tag does not match the selected mod version")
    repository = os.environ["GITHUB_REPOSITORY"]
    commit = os.environ["GITHUB_SHA"]
    jar, metadata = verify(directory, version)
    pages = json.loads(gh("api", f"repos/{repository}/releases?per_page=100", "--paginate", "--slurp"))
    releases = [release for page in pages for release in page]
    matches = [release for release in releases if release["tag_name"] == tag]
    if len(matches) > 1:
        raise ValueError(f"Multiple releases exist for {tag}")
    existing = matches[0] if matches else None
    validate_target(existing, tag_commit(tag), commit)
    if existing is not None and not existing["draft"]:
        required = {jar.name, "CHANGELOG.md", "SHA256SUMS"}
        uploaded = {asset["name"] for asset in existing["assets"] if asset["state"] == "uploaded"}
        if not required <= uploaded:
            raise ValueError("Published release is missing expected assets; leaving it unchanged")
        print(f"Already published: {existing['html_url']}")
        return

    args = ["api", "--method", "POST", f"repos/{repository}/releases/generate-notes",
            "-f", f"tag_name={tag}", "-f", f"target_commitish={commit}"]
    previous = previous_tag(releases, tag, commit)
    if previous is not None:
        args += ["-f", f"previous_tag_name={previous}"]
    notes = json.loads(gh(*args))["body"]
    changelog = directory / "CHANGELOG.md"
    write_changelog(changelog, version, jar, metadata, notes, repository, commit,
                    commit_notes(previous, commit, repository))
    checksums = directory / "SHA256SUMS"
    checksums.write_text("".join(
        f"{hashlib.sha256(asset.read_bytes()).hexdigest()}  {asset.name}\n"
        for asset in [jar, changelog]), encoding="utf-8")
    assets = [jar, changelog, checksums]
    title = f"Manhunt {version} (Minecraft {metadata['depends']['minecraft']})"
    if existing is None:
        gh("release", "create", tag, *(str(asset) for asset in assets), "--repo", repository,
           "--title", title, "--notes-file", str(changelog), "--target", commit, "--draft")
    else:
        # Only an unpublished draft can have its assets replaced on a retry.
        gh("release", "upload", tag, *(str(asset) for asset in assets), "--repo", repository, "--clobber")
        gh("release", "edit", tag, "--repo", repository, "--title", title, "--notes-file", str(changelog))
    with tempfile.TemporaryDirectory(prefix="manhunt-release-") as downloaded:
        gh("release", "download", tag, "--repo", repository, "--dir", downloaded)
        verify_downloads(assets, downloaded)
    # Resuming an older draft must not replace a newer stable download.
    current_version = parse_version(version)
    newer_published = any(not release["draft"]
                          and parse_version(release["tag_name"][1:]) > current_version
                          for release in stable_releases(releases))
    latest = "--latest=false" if newer_published else "--latest"
    gh("release", "edit", tag, "--repo", repository, "--draft=false", latest)
    url = json.loads(gh("release", "view", tag, "--repo", repository, "--json", "url"))["url"]
    print(f"Published: {url}")
    if "GITHUB_STEP_SUMMARY" in os.environ:
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(f"Published [{tag}]({url}) with the installable JAR, changelog, and checksums.\n")


if __name__ == "__main__":
    main()
