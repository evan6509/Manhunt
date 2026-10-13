#!/usr/bin/env python3
"""Check the installable Manhunt JAR before upload and after artifact download."""

import json
import os
from pathlib import Path
import struct
import sys
from zipfile import ZipFile

from release_version import parse_version, properties


def verify(directory, version=None, companion_jars=()):
    props = properties()
    version = version or os.environ.get("MANHUNT_VERSION", props["mod_version"])
    parse_version(version)
    full_version = f"{version}+mc{props['minecraft_version']}"
    filename = f"manhunt-{full_version}.jar"
    jars = [path for path in Path(directory).glob("*.jar")
            if not path.name.endswith("-sources.jar") and path.name not in companion_jars]
    if len(jars) != 1 or jars[0].name != filename:
        raise ValueError(f"Expected one installable JAR named {filename}; found {jars}")
    with ZipFile(jars[0]) as jar:
        if jar.testzip() is not None:
            raise ValueError("JAR failed its ZIP integrity check")
        metadata = json.loads(jar.read("fabric.mod.json"))
        if metadata["id"] != "manhunt" or metadata["version"] != full_version:
            raise ValueError("JAR mod identity or embedded version does not match this build")
        if metadata["depends"]["minecraft"] != props["minecraft_version"]:
            raise ValueError("JAR Minecraft dependency does not match this build")
        if metadata["depends"]["java"] != ">=25":
            raise ValueError("JAR must require Java 25")
        expected = {"LICENSE", "com/evanchubbuck/manhunt/ManhuntMod.class",
                    "com/evanchubbuck/manhunt/ManhuntCommands.class",
                    "com/evanchubbuck/manhunt/ManhuntState.class",
                    "com/evanchubbuck/manhunt/ManhuntState$Hunt.class",
                    "com/evanchubbuck/manhunt/ManhuntService.class",
                    "com/evanchubbuck/manhunt/TrackingCompass.class",
                    "com/evanchubbuck/manhunt/network/NetworkFiles.class"}
        names = set(jar.namelist())
        if missing := expected - names:
            raise ValueError(f"Missing release files: {sorted(missing)}")
        if any("gametest" in name.lower() or name.endswith(("Test.class", "Tests.class")) for name in names):
            raise ValueError("Test classes are included in the installable JAR")
        for name in names:
            if name.endswith(".class"):
                bytecode = jar.read(name)
                if len(bytecode) < 8 or bytecode[:4] != b"\xca\xfe\xba\xbe" or struct.unpack(">H", bytecode[6:8])[0] != 69:
                    raise ValueError(f"Class is not valid Java 25 bytecode: {name}")
    return jars[0], metadata


def main():
    jar, metadata = verify(sys.argv[1] if len(sys.argv) > 1 else "build/libs")
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"jar={jar.as_posix()}\n")
    print(f"Verified {jar} (mod version {metadata['version']})")


if __name__ == "__main__":
    main()
