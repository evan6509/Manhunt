# Manhunt for Fabric 26.2

One player is the runner. Everyone else is a hunter with a compass pointing to
the runner's last recorded location. The optional Docker/Velocity setup moves
everyone to a fresh world between hunts and automatically chooses a different
runner for the next round. Download the mod JAR from
[GitHub Releases](https://github.com/evan6509/Manhunt/releases) and install it with
Fabric API **0.154.2+26.2 or newer for 26.2** in the server's `mods` folder,
using Fabric Loader **0.19.3 or newer** and **Java 25**.

This mod runs on the server. Hunters can connect with unmodified Minecraft
26.2 clients. For singleplayer/LAN, install it and Fabric API on the host.

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
  receive the current snapshot within a second of becoming accessible in a
  hunter's inventory or open container. Closed containers and offline player
  inventories are not scanned.
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
  `/manhunt stop` also ends it manually.
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

## Build and verify

Use a JDK 25 installation, then run:

```sh
./gradlew --no-daemon build runGametest
```

The installable mod is `build/libs/manhunt-1.0.0+mc26.2.jar`; the Velocity plugin
is `velocity-plugin/build/libs/manhunt-velocity-1.0.0+mc26.2.jar`. The `-sources.jar`
is source code, not a second mod to install. Tests are in a separate source set
and are excluded from the installable JAR. The GameTest report is written to
`build/gametest-results.xml`.

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
installed. GitHub requires a paid plan to enforce protected branches in this
private repository, so the PR workflow is documented but is not enforced by
GitHub's branch-protection settings.

GitHub Actions builds and tests development pushes and pull requests. A
successful update to `main` builds the mod with Java 25, runs GameTests, checks
the installable JAR, and publishes a release. Manual workflow runs only build
and test.

The first release is `v1.0.0`; later releases increment the patch version
automatically. The version is embedded in the mod and its filename. You can
raise `mod_version` in `gradle.properties` to begin a larger version, such as
`2.0.0`. Retries reuse the release for the same commit. Pending `main` runs are
queued so version selection and publication do not overlap.

Each release contains the Fabric JAR, Velocity plugin JAR, Docker source ZIP,
`CHANGELOG.md`, and `SHA256SUMS`.
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
MANHUNT_VERSION=1.0.1 ./gradlew --no-daemon clean build runGametest
MANHUNT_VERSION=1.0.1 python3 scripts/verify_mod_jar.py
```
