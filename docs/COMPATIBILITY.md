# Fabric 26.2 and 26.3 compatibility

26.2 remains the default and Docker target. Select 26.3 with
`-Pminecraft_version=26.3`; its output is isolated in `build/26.3`.
The Velocity plugin keeps its established `+mc26.2` filename and unchanged
network protocol. Release staging requires both Fabric artifacts, the existing
Velocity artifact, and the 26.2 Docker source ZIP.

## Upstream evidence checked on 2026-10-24

- [Fabric Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)
  lists stable `0.165.0+26.3`.
- The [API POM](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.0+26.3/fabric-api-0.165.0+26.3.pom)
  and downloaded API JAR both identify `0.165.0+26.3`. Its `fabric.mod.json`
  requires Loader `>=0.19.3`, Java `>=25`, and Minecraft `~26.3-`.
  Manhunt uses a numeric API minimum `>=0.165.0` and exact Minecraft `26.3`.
- [Mojang's 26.3 metadata](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json)
  requires Java 25. The cached server JAR matches its published SHA-1:
  `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`.
- `javap` inspection of the actual 26.3 mapped Minecraft JAR and Fabric module
  JARs confirmed saved-data, death, respawn, and connection callback signatures.
  `ServerPlayer.drop(ItemStack, boolean, boolean)` changed to
  `drop(ItemStack, boolean, Prediction)`. Per-target `MinecraftCompat` classes
  preserve the 26.2 call and use `Prediction.SERVER_ONLY` for 26.3's server-created
  compass. No Linked Hardcore protocol or transfer/recovery code changed.

## Validation

Use exported `JAVA_HOME` pointing to JDK 25, `--no-daemon --max-workers=1`, and
sequential target builds with `--project-cache-dir .gradle/26.2` or `.gradle/26.3`.
Run the default clean first: it removes the entire parent `build` directory.
See [README.md](../README.md#build-and-verify) for the exact commands.

Target selection, dependency predicates, missing-target release rejection, and
Docker pinning tests were run failing before implementation. Publication tests
were updated and run failing before adapting changelog generation. Adapter source
archive tests also failed before adding the target directories to the allowlist.

Validated locally with JDK `25.0.5+8`, mod version `1.0.0`:

| Check | Result |
| --- | --- |
| Python scripts/release/target suite | 39 passed |
| Docker controller Python suite | 23 passed |
| Velocity JUnit suite, included in build | 19 passed |
| 26.2 `build runGametest` | successful; 34 tests, zero failures/errors |
| 26.3 `build runGametest` | successful; 34 tests, zero failures/errors |
| Both installable Fabric JAR verifiers | passed; Java 25 bytecode, no bundled companion/tests |
| Velocity verifier | passed; established `1.0.0+mc26.2` identity |
| Local release staging and asset verification | passed; both Fabric JARs, Velocity JAR, 26.2 Docker ZIP |

Standalone reports are `build/gametest-results.xml` and
`build/26.3/gametest-results.xml`. Installable metadata:

| Artifact | Minecraft | Fabric API | Loader | Java |
| --- | --- | --- | --- | --- |
| `manhunt-1.0.0+mc26.2.jar` | `26.2` | `>=0.154.2` | `>=0.19.3` | `>=25` |
| `manhunt-1.0.0+mc26.3.jar` | `26.3` | `>=0.165.0` | `>=0.19.3` | `>=25` |

The optional companion suites used the matching Linked Hardcore 0.1.0 JARs,
not substituted bytecode or bundled companion code. Their SHA-256 digests were:

- 26.2: `7425e9ed22b6cf7082fc179129d418c15d8ed663c8088b5be6befeda3bb422da`.
- 26.3: `4f33831d3ea1af3d8743ac6b44630e3b82388282dec11b0f10e1f5cbc57be308`.

For a rerun, set `-PlinkedHardcoreJar=<path-to-matching-linked-hardcore-jar>`
when invoking `runGametest`; 26.3 also uses `-Pminecraft_version=26.3`. Each
passed all 23 tests
(22 Manhunt integration tests plus `minecraft:always_pass`) with zero failures
or errors. Standalone totals likewise include `minecraft:always_pass`. Reports are
`build/gametest-linked-hardcore-results.xml` and
`build/26.3/gametest-linked-hardcore-results.xml`.

All generated EULA files and the archived `.env.example` remain false. No live
server or world was used. Docker is unavailable in this environment, so Compose
configuration, image builds, connected-player gameplay, deployed proxy rotation,
and remote Actions/publication are not established by these local checks.
