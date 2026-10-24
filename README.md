# Manhunt for Fabric 26.2 and 26.3

One player is the runner. Everyone else is a hunter with a compass pointing to
the runner's last recorded location. The optional Docker/Velocity setup moves
everyone to a fresh world between hunts and automatically chooses a different
runner for the next round. Download the mod JAR from
[GitHub Releases](https://github.com/evan6509/Manhunt/releases) and install it with
Fabric API **0.154.2+26.2 or newer for 26.2** in the server's `mods` folder,
using Fabric Loader **0.19.3 or newer** and **Java 25**.
Development builds also provide a separate **26.3** JAR requiring Fabric API
**0.165.0+26.3 or newer for 26.3**. Install only the JAR matching your server;
26.2 remains the default build and Docker target.

The latest public release, [v1.0.0](https://github.com/evan6509/Manhunt/releases/tag/v1.0.0),
was published **2026-10-13** and verified on **2026-10-24**. Its Fabric 26.2
assets are the [mod JAR](https://github.com/evan6509/Manhunt/releases/download/v1.0.0/manhunt-1.0.0%2Bmc26.2.jar),
[Velocity plugin](https://github.com/evan6509/Manhunt/releases/download/v1.0.0/manhunt-velocity-1.0.0%2Bmc26.2.jar),
[Docker package](https://github.com/evan6509/Manhunt/releases/download/v1.0.0/manhunt-docker-1.0.0%2Bmc26.2.zip),
[changelog](https://github.com/evan6509/Manhunt/releases/download/v1.0.0/CHANGELOG.md),
and [SHA256SUMS](https://github.com/evan6509/Manhunt/releases/download/v1.0.0/SHA256SUMS).
This README also describes `codex/development`, including Linked Hardcore
integration and later transfer/recovery safeguards not established as part of
v1.0.0. Build that branch to use those changes. The development branch builds
separate artifacts for **26.2 and 26.3**;
this does not add 26.3 assets to the existing v1.0.0 release.

This mod runs on the server. Hunters can connect with unmodified Minecraft
clients matching the server version. For singleplayer/LAN, install it and Fabric
API on the host.

## Commands

| Command | Effect | Permission |
| --- | --- | --- |
| `/manhunt start <player>` | Choose an online runner, give everyone else a compass, and disable the locator bar. | Operator level 2 / cheats |
| `/manhunt stop` | End the hunt and restore its settings; in the Docker network, move everyone into the next fresh world. | Operator level 2 / cheats |
| `/manhunt status` or `/manhunt` | Show the runner and time until the next snapshot. | Everyone |
| `/manhunt compass` | Recover a lost compass; reuse one already carried. | Hunters |

Example: `/manhunt start Steve`. Player names have tab completion. A hunt can
start with only the runner online; players who join later become hunters.
Stop the current hunt before choosing a different runner.

## Tracking rules

- The first location is recorded immediately. Further snapshots happen every
  **1,200 server ticks**, or **60 seconds at normal server speed**. Lag or a
  paused world slows the timer. Moving, joining, requesting a replacement, and
  respawning never reveal a fresher position between snapshots.
- Hover over the compass to see the runner's recorded dimension and X/Y/Z.
  The needle points at that snapshot while you are in the same dimension. In
  another dimension it spins, as vanilla compasses do; use the tooltip to find
  which dimension to enter. No lodestone block is required.
- If the runner disconnects or is dead at the snapshot time, the previous
  location is retained. Tracking resumes on the regular schedule once they
  return. The runner is identified by UUID, including after respawning.
- Joining hunters and respawned hunters receive a compass. A full inventory
  drops the compass at their feet without replacing existing items.
- Ordinary compasses are never modified. Stored/dropped hunting compasses
  receive the current snapshot within **20 server ticks** of becoming accessible
  in a hunter's inventory or open container. Online players' ender chest
  inventories are also scanned, even when closed; other closed containers and
  offline player inventories are not scanned.
- The hunt and last recorded location are saved with the world and survive
  server restarts; the minute timer starts again when the world loads.
- Starting a hunt immediately disables the vanilla locator bar for everyone
  in every dimension. It stays off during the hunt, including after a restart.
  `/manhunt stop` restores the setting from before the hunt; if it was already
  off, it stays off. The previous setting is saved with the hunt.
- `/manhunt compass` has no cooldown, so hunters can request replacements
  whenever needed.
- Runner death gives the hunters a win. An Ender Dragon defeat credited to the
  runner gives the runner a win. Hunter deaths do not end the hunt.
  A dragon already dying when a hunt starts cannot win that hunt, including
  after the hunt is saved and reloaded. `/manhunt stop` also ends it manually.
- Standalone hunts end in the current world. The Docker network holds players
  in spectator mode while transferring them to the next fresh world, where
  they begin with fresh inventories and world progress.

## Fresh worlds through Velocity and Docker

The deployment includes a Velocity 4.0 proxy and two Fabric 26.2 backends.
Players connect to the proxy on port **25578**. Start the first hunt with
`/manhunt start <player>`. After a win or manual stop:

1. Velocity reserves the other ready, empty backend and transfers everyone.
2. After the transfers succeed and the source is empty, its Docker controller
   stops that server and archives the complete old world.
3. The backend starts again with a new world and random seed, ready for the
   following transfer.
4. The next hunt starts automatically once at least two players have arrived.
   The preceding runner is excluded. Players with fewer runner turns get
   priority, with random selection among ties.

Selection history and pending transfers survive proxy restarts. An ordinary
backend crash restarts the same world; only an acknowledged completed run can
authorize an archive/reset. Invalid, stale, occupied, or failed transfers do
not authorize resetting the source. Finished worlds cannot start another hunt.
If no fresh backend is available, the group waits rather than reusing a world.
Required participants and confirmed arrivals are saved before a rotation can
authorize a reset; disconnecting does not erase an outstanding transfer.
Older proxy journals with unfinished transfers and no participant records are
rejected and leave the source world intact. Backend startup also rejects a
checkpoint whose world identity conflicts with native saved data; missing native
identities can still recover from a checkpoint with a consistent hunt lifecycle.

Follow [Docker setup and launch instructions](docs/DOCKER.md). This setup is
separate from Linked Hardcore and uses its own volume and host port. Old worlds
remain in the backend's `archive/` directory for recovery.

For a manual installation, put the Velocity plugin in the proxy's `plugins/`
directory, install the Fabric mod and FabricProxy-Lite on every backend, and
configure the shared-volume status paths as described in the Docker guide.
The bridge defaults to disabled for standalone installations. Its generated
`config/manhunt/network.json` controls `enabled`, `serverId`, and
`controlDirectory`. Modern forwarding uses an authenticated proxy and a shared
secret; backend ports stay private.

## Using your Linked Hardcore setup

The development branch automatically detects the Fabric mod from
[Linked Hardcore](https://github.com/evan6509/linked-hardcore). Install this
Manhunt JAR built from this branch on **every Linked Hardcore backend**,
alongside Linked Hardcore and
Fabric API **0.156.0+26.2 or newer for 26.2**, or **0.165.0+26.3 or newer for
26.3**. Use a Linked Hardcore artifact built for the same Minecraft target;
Manhunt's standalone 26.3 tests do not establish companion integration.
Keep your existing Linked Hardcore
proxy configuration and reset executor, but update both mods on every backend
and the Linked Hardcore proxy together to the compatible durable-outbox protocol
(contract version 3). No Manhunt plugin belongs on that
proxy. Leave `enabled` set to `false` in `config/manhunt/network.json`;
starting both world controllers together is rejected with a configuration error.

During a hunt, **only the runner's death** is reported to Linked Hardcore.
Hunters can die, respawn into play, and recover their compasses without ending
the run or incrementing Linked Hardcore's proxy death counter. Runner death
ends the hunt after durably recording the original death report; Linked Hardcore
handles its timer, spectator countdown, transfer, and reset. Completed source worlds
stay closed across respawns, reconnects, and backend restarts. A transfer
initiated by the proxy on another backend also closes the local hunt.

Death reports recover after backend restart, including a crash between saving
Manhunt's completion latch and the first send. Only a matching world-scoped ACK
clears the durable outbox. This requires the updated companion preparation API;
old ambiguous runner-death latches fail closed for operator reconciliation.
See [delivery ordering, upgrade rules, and durability limits](docs/LINKED_HARDCORE.md).

Start a hunt with `/manhunt start <runner>` in each fresh world. Automatic
runner selection belongs to Manhunt's separate Velocity stack and is not
available through the current Linked Hardcore protocol. Dragon victory and
`/manhunt stop` end the hunt **in the current world**: Linked Hardcore currently
supports death-triggered transfers only, so Manhunt does not fabricate deaths
to request a transfer. Outside a hunt, ordinary Linked Hardcore rules apply:
any player's death can end its shared run. Separate simultaneous backends have
separate Manhunt runner selections and snapshots.

For the existing Linked Hardcore Docker deployment, stop its backend containers
and copy the Manhunt JAR into each persistent backend's `mods/` directory
(`/lh/server-a/mods/`, `/lh/server-b/mods/`, and any additional backends).
Restart using your existing Linked Hardcore launch command. Its executor
preserves those mod directories when replacing worlds; use the Linked Hardcore
Compose stack for that installation. Update every backend together. Never
replace or delete an active world's files to install the mod.

## Build and verify

Use a JDK 25 installation, then run:

```sh
export JAVA_HOME=/path/to/jdk-25
./gradlew --no-daemon --max-workers=1 --project-cache-dir .gradle/26.2 clean build runGametest
python3 scripts/verify_mod_jar.py
./gradlew --no-daemon --max-workers=1 --project-cache-dir .gradle/26.3 -Pminecraft_version=26.3 build runGametest
python3 scripts/verify_mod_jar.py build/26.3/libs 26.3
```

Run the targets sequentially in this order: the default `clean` removes all of
`build/`, including 26.3 output. Unsupported targets fail at configuration time.
The installable mods are `build/libs/manhunt-1.0.0+mc26.2.jar` and
`build/26.3/libs/manhunt-1.0.0+mc26.3.jar`; the Velocity plugin
is `velocity-plugin/build/libs/manhunt-velocity-1.0.0+mc26.2.jar`. The `-sources.jar`
is source code, not a second mod to install. Fabric GameTests are in a separate
source set
and are excluded from the installable JAR. The GameTest report is written to
`build/gametest-results.xml` for 26.2 and `build/26.3/gametest-results.xml`
for 26.3.

Run the separate compatibility suite against a built Linked Hardcore Fabric JAR:

```sh
./gradlew --no-daemon --max-workers=1 runGametest -PlinkedHardcoreJar=/absolute/path/to/fabric-mod-0.1.0.jar
```

For 26.3, add `-Pminecraft_version=26.3 --project-cache-dir .gradle/26.3` and
supply an actual 26.3 companion JAR. The 26.3 suite uses Fabric API 0.165.0+26.3
and writes `build/26.3/gametest-linked-hardcore-results.xml`.
This loads the actual companion (with Fabric API 0.156.0+26.2 for 26.2) and tests its
transformed death-report and transfer code. Its report is
`build/gametest-linked-hardcore-results.xml`. The external JAR is used only by
the test runtime and is never bundled in the installable Manhunt JAR.

The default build includes the Velocity plugin's unit tests. To check the
Docker rotation controller separately, run:

```sh
python3 -m unittest discover -s docker -p 'test_*.py' -v
```

These commands describe automated validation; they do not establish a test pass
for this checkout. The companion suite requires an operator-supplied compatible
JAR and is not run by the default GitHub Actions workflow. Automated tests and
JAR checks do not demonstrate connected-player gameplay or a deployed proxy
rotation; verify those separately.

The implementation follows Fabric's [command registration API](https://docs.fabricmc.net/develop/commands/basics)
and [server GameTest framework](https://docs.fabricmc.net/develop/automatic-testing).

## Development and releases

Work and commit on `codex/development`. Open a pull request into `main` for
review; changes reach `main` by merging the PR. Do not commit directly to
`main`. After cloning, install the local commit guard:

```sh
git switch codex/development
git config core.hooksPath scripts/git-hooks
```

The guard prevents ordinary direct commits to `main` in clones where it is
installed. GitHub previously refused private-repository branch protection on
the account's
plan. Follow the reviewed-PR policy and do not assume remote enforcement.
Merging, publishing, or pushing `main` requires explicit authorization;
a successful push to `main` triggers publication.

GitHub Actions builds and tests development pushes and pull requests. A
successful update to `main` builds the mod with Java 25, runs GameTests, checks
the installable JAR, and publishes a release. Manual workflow runs only build
and test.

The release series starts at `v1.0.0`; later releases increment the patch version
automatically. The version is embedded in the mod and its filename. You can
raise `mod_version` in `gradle.properties` to begin a larger version, such as
`2.0.0`. Retries reuse the release for the same commit. Pending `main` runs are
queued so version selection and publication do not overlap.

The release workflow packages `manhunt-<version>+mc26.2.jar`, `manhunt-<version>+mc26.3.jar`,
`manhunt-velocity-<version>+mc26.2.jar`,
`manhunt-docker-<version>+mc26.2.zip`, `CHANGELOG.md`, and `SHA256SUMS`.
For v1.0.0, `<version>` is `1.0.0`; these naming patterns come from the
packaging configuration, not a live asset verification.
The release description includes GitHub-generated PR notes, linked commits
since the previous release, and installation requirements. Assets are staged
in a draft and downloaded for verification before publication.

Validate release helpers locally with:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/verify_mod_jar.py
```

To check another embedded version without editing the project version:

```sh
MANHUNT_VERSION=1.0.1 ./gradlew --no-daemon --max-workers=1 --project-cache-dir .gradle/26.2 clean build runGametest
MANHUNT_VERSION=1.0.1 ./gradlew --no-daemon --max-workers=1 --project-cache-dir .gradle/26.3 -Pminecraft_version=26.3 build runGametest
MANHUNT_VERSION=1.0.1 python3 scripts/verify_mod_jar.py
```
