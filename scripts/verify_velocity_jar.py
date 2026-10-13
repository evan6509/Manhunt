#!/usr/bin/env python3
"""Verify the dependency-free Velocity plugin that accompanies the Fabric mod."""

import json
import os
from pathlib import Path
import struct
import sys
from zipfile import ZipFile

from release_version import parse_version, properties


MAIN_CLASS = "com.evanchubbuck.manhunt.velocity.ManhuntVelocityPlugin"
SHARED_CLASS = "com/evanchubbuck/manhunt/network/NetworkFiles.class"


def verify(directory, version=None, companion_jars=()):
    props = properties()
    version = version or os.environ.get("MANHUNT_VERSION", props["mod_version"])
    parse_version(version)
    full_version = f"{version}+mc{props['minecraft_version']}"
    filename = f"manhunt-velocity-{full_version}.jar"
    jars = [path for path in Path(directory).glob("*.jar")
            if not path.name.endswith("-sources.jar") and path.name not in companion_jars]
    if len(jars) != 1 or jars[0].name != filename:
        raise ValueError(f"Expected one Velocity plugin named {filename}; found {jars}")
    with ZipFile(jars[0]) as jar:
        if jar.testzip() is not None:
            raise ValueError("Velocity plugin failed its ZIP integrity check")
        metadata = json.loads(jar.read("velocity-plugin.json"))
        if metadata.get("id") != "manhunt" or metadata.get("version") != full_version:
            raise ValueError("Velocity plugin identity or embedded version does not match this build")
        if metadata.get("main") != MAIN_CLASS:
            raise ValueError("Velocity plugin entrypoint does not match this build")
        names = set(jar.namelist())
        expected = {"LICENSE", MAIN_CLASS.replace(".", "/") + ".class", SHARED_CLASS}
        if missing := expected - names:
            raise ValueError(f"Missing Velocity plugin files: {sorted(missing)}")
        if "fabric.mod.json" in names or any("gametest" in name.lower() or name.endswith(("Test.class", "Tests.class"))
                                              for name in names):
            raise ValueError("Mod metadata or test classes are included in the Velocity plugin")
        for name in names:
            if name.endswith(".jar"):
                raise ValueError(f"Bundled dependency in the Velocity plugin: {name}")
            if name.endswith(".class"):
                if not name.startswith("com/evanchubbuck/manhunt/"):
                    raise ValueError(f"Shaded dependency in the Velocity plugin: {name}")
                bytecode = jar.read(name)
                if len(bytecode) < 8 or bytecode[:4] != b"\xca\xfe\xba\xbe" or struct.unpack(">H", bytecode[6:8])[0] != 69:
                    raise ValueError(f"Velocity class is not valid Java 25 bytecode: {name}")
    return jars[0], metadata


def main():
    jar, metadata = verify(sys.argv[1] if len(sys.argv) > 1 else "velocity-plugin/build/libs")
    print(f"Verified {jar} (Velocity plugin version {metadata['version']})")


if __name__ == "__main__":
    main()
