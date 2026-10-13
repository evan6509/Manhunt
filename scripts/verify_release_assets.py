#!/usr/bin/env python3
"""Verify the exact Fabric, Velocity, and Docker artifacts for a release."""

import os
from pathlib import Path
import sys

from deployment_archive import verify_archive
from release_version import parse_version, properties
from verify_mod_jar import verify as verify_mod
from verify_velocity_jar import verify as verify_velocity


def verify(directory, version=None):
    props = properties()
    version = version or os.environ.get("MANHUNT_VERSION", props["mod_version"])
    parse_version(version)
    full_version = f"{version}+mc{props['minecraft_version']}"
    directory = Path(directory)
    mod_name = f"manhunt-{full_version}.jar"
    plugin_name = f"manhunt-velocity-{full_version}.jar"
    docker_name = f"manhunt-docker-{full_version}.zip"
    expected = {mod_name, plugin_name, docker_name}
    present = {path.name for path in directory.iterdir()}
    if not expected <= present or present - expected - {"CHANGELOG.md", "SHA256SUMS"}:
        raise ValueError(f"Expected exact release assets {sorted(expected)}; found {sorted(present)}")
    mod, metadata = verify_mod(directory, version, companion_jars=(plugin_name,))
    plugin, _ = verify_velocity(directory, version, companion_jars=(mod_name,))
    deployment = verify_archive(directory / docker_name, version)
    return [mod, plugin, deployment], metadata


def main():
    assets, metadata = verify(sys.argv[1] if len(sys.argv) > 1 else "build/release")
    print(f"Verified {metadata['version']}: " + ", ".join(str(asset) for asset in assets))


if __name__ == "__main__":
    main()
