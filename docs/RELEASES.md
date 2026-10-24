# Building and releases

Work on `codex/development` and review a pull request into `main`. A successful
push to `main` builds, tests, and publishes the next patch release. Development
pushes, pull requests, and manual workflow runs build and test without publishing.
Version selection and publication share one serialized workflow queue.

Each release contains:

- `manhunt-<version>+mc26.2.jar`: the Fabric 26.2 server mod.
- `manhunt-<version>+mc26.3.jar`: the Fabric 26.3 server mod (API >=0.165.0).
- `manhunt-velocity-<version>+mc26.2.jar`: the Velocity proxy plugin, requiring Java 25.
- `manhunt-docker-<version>+mc26.2.zip`: source, Gradle wrapper, and Docker deployment setup.
- `CHANGELOG.md`: GitHub pull request notes, direct commits, and installation instructions.
- `SHA256SUMS`: hashes of the three JARs, deployment ZIP, and changelog.

For a single server, install the Fabric JAR alongside Fabric API in `mods`.
For fresh worlds after each run, extract the Docker ZIP, copy `.env.example` to
`.env`, accept the Minecraft EULA and set a private forwarding secret there,
and follow [DOCKER.md](DOCKER.md).
The ZIP sets `MANHUNT_VERSION` in its example environment to the selected release
version; `RELEASE_VERSION` also records it. The plugin and mod must match versions.

The source ZIP includes only deployment files and source/build instructions from
an explicit allowlist. Runtime `.env`, private configuration, worlds, Gradle
caches, build output, and Git history are excluded. `gradlew` and the Docker
entrypoint retain executable permissions.

Local release checks, using Java 25:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 -m unittest discover -s docker -p 'test_*.py' -v
EULA=false FORWARDING_SECRET=local-config-validation-only docker compose config --quiet
export JAVA_HOME=/path/to/jdk-25
MANHUNT_VERSION=1.0.1 ./gradlew --no-daemon --max-workers=1 --project-cache-dir .gradle/26.2 clean build runGametest
MANHUNT_VERSION=1.0.1 ./gradlew --no-daemon --max-workers=1 --project-cache-dir .gradle/26.3 -Pminecraft_version=26.3 build runGametest
MANHUNT_VERSION=1.0.1 python3 scripts/verify_mod_jar.py build/26.3/libs 26.3
MANHUNT_VERSION=1.0.1 python3 scripts/verify_mod_jar.py
MANHUNT_VERSION=1.0.1 python3 scripts/verify_velocity_jar.py
MANHUNT_VERSION=1.0.1 python3 scripts/package_release.py
MANHUNT_VERSION=1.0.1 python3 scripts/verify_release_assets.py
```

Build both targets sequentially; run the 26.2 clean first because it removes all
build output. Velocity keeps its established `+mc26.2` filename and protocol;
the Docker ZIP and image remain pinned to 26.2. Staging requires both Fabric JARs
and rejects a missing target rather than replacing 26.2 with 26.3.

Staging requires an empty `build/release`; a clean Gradle build removes previous
staging output. The workflow verifies the same artifact set again after download.
Publication first creates an unpublished draft, verifies every downloaded asset
against the build, and only then publishes. A matching unfinished draft can be
retried. An already published release is left unchanged, including its assets;
rerunning its commit reuses its version. Resuming an older draft preserves a
newer release's Latest marker.

Local builds and automated tests do not demonstrate a live proxy migration,
connected-player gameplay, or a GitHub publication. Check those surfaces
separately before claiming them verified.
