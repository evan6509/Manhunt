# Manhunt with Velocity and fresh worlds

This deployment runs one authenticated Velocity proxy and two Fabric 26.2
backends. Players join the proxy once; after a hunt ends they move together to
the other backend's fresh world. The completed world is archived and its backend
starts another new world for the next rotation.

Start the first hunt with `/manhunt start <runner>`. Runner death, the runner's
dragon victory, and `/manhunt stop` complete a hunt. Once everyone has reached
the fresh world, the next hunt starts automatically with a randomly chosen
runner who differs from the previous runner. At least two connected players are
needed to choose another runner; otherwise the next hunt waits. The compass
updates every minute and the locator bar is disabled during each hunt.

## Setup

Install Docker Engine with Compose, or Docker Desktop. The defaults allocate
3 GB to each backend and 512 MB to Velocity, so allow at least 8 GB of memory
for Docker plus memory for the host. The first build downloads the Java 25
image, Minecraft, Fabric, Fabric API, FabricProxy-Lite, and Velocity.

From this repository or the deployment ZIP attached to a release:

```sh
cp .env.example .env
python3 -c 'import secrets; print(secrets.token_hex(32))'
```

Put the generated private value in `.env` as `FORWARDING_SECRET`. Read and
accept the [Minecraft EULA](https://aka.ms/MinecraftEULA), then set `EULA=true`.
Neither a missing secret nor the example value can start the containers.
The repository's `.env` is ignored by Git and excluded from the Docker build.

```sh
docker compose config
docker compose up --build -d
docker compose logs -f
```

Connect using a Minecraft 26.2 client to `localhost:25578`, or the Docker
host's address and that port. `VELOCITY_PORT` changes the host port only. The
25578 default leaves Linked Hardcore's 25577 port available. Backend ports are
not published to the host.

Use an operator account for the initial command. To grant operator permissions
after the backend has created `ops.json`, shut down the deployment and edit that
backend's `ops.json` in its persistent storage using your authenticated UUID,
then start it again. Apply the same operator entry to both backends so it
remains available after transfers. Operator lists, whitelists, and configuration
are preserved when worlds rotate. See
[Minecraft's server instructions](https://www.minecraft.net/en-us/download/server)
for server administration.

The release ZIP's `.env.example` selects its matching `MANHUNT_VERSION`.
When building another released version from source, set that exact version:

```sh
MANHUNT_VERSION=1.0.1 docker compose build
MANHUNT_VERSION=1.0.1 docker compose up -d
```

Both the Fabric mod and Velocity plugin receive that version. Updating an image
preserves the running world's files and the proxy's runner history. An ordinary
restart also preserves the current hunt; it does not create a new world.

```sh
docker compose down
docker compose up -d
```

`docker compose down` preserves the named volume. Removing that volume removes
all current worlds, archives, operator lists, and history, so do not add `-v`
when stopping a deployment you want to keep.

## Storage and reset safety

The named `manhunt-data` volume is mounted at `/manhunt` in all three
containers. Its layout is:

```text
/manhunt/
  proxy/
    velocity.toml
    forwarding.secret
    plugins/manhunt/config.json
    plugins/manhunt/controller-state.json
  server-a/
    world/
    archive/world-<completed-world-UUID>/
    network/status.json
    network/start.request.json
    network/reservation.json
    network/reset.request.json
    network/reset.transaction.json
    config/manhunt/network.json
    ops.json
    whitelist.json
  server-b/
    (same backend layout)
```

The proxy reserves a fresh destination before transferring players. It requests
the vacated backend's reset only for that completed world and run. The Fabric
backend confirms `RESETTING` with zero players and matching world/run UUIDs.
The Docker controller records that acknowledgement, stops the JVM, confirms
that it exited, and renames **only `world/`** into its UUID-named archive. It
never deletes the completed world. Minecraft stores dimensions, player data,
advancements, and statistics inside that world folder, so the replacement world
also starts with fresh inventories and progress. Server-level settings remain.

The controller rejects stale live acknowledgements, malformed JSON, generation
mismatches, nonempty backends, and symlinked storage paths. A durable reset
transaction makes recovery safe if the container stops between acknowledgement,
shutdown, archive rename, and mailbox cleanup. A boot-time request without a
matching prior acknowledgement cannot reset a world. A JVM crash without an
acknowledged reset preserves the world and restarts it.

Archives accumulate. Back them up or manage them while the deployment is
stopped; no automatic archive deletion is configured. To recover a completed
world, stop all three containers first and restore the archived folder using
your backup process. That restoration also restores its completed Manhunt state;
administrative recovery is separate from automatic rotation.

## Network configuration

The image pins Fabric Loader 0.19.3, Fabric API 0.154.2+26.2,
[FabricProxy-Lite 2.12.0](https://modrinth.com/mod/fabricproxy-lite/version/CsEpiziv),
and Velocity 4.0.0 build 6. FabricProxy-Lite and Velocity downloads are checked
against their published checksums. Velocity authenticates players with
`online-mode=true` and uses modern forwarding; backends authenticate the
forwarding secret and run with `online-mode=false` behind the Compose network.
See [Velocity's modern forwarding guidance](https://docs.papermc.io/velocity/player-information-forwarding/)
and [backend security guidance](https://docs.papermc.io/velocity/security/).

Docker generates proxy and bridge network settings from `.env` on each boot,
preserving the proxy's controller history and other backend config files.
Advanced proxy configuration needs changes to `docker/controller.py`; do not
edit generated `velocity.toml` and expect that edit to survive a restart.

## Validation

The controller's filesystem fixtures run without a Docker daemon:

```sh
python3 -m unittest discover -s docker -p 'test_*.py' -v
sh -n docker/entrypoint.sh
```

These checks cover reset guards, path safety, preserving server settings, and
recovery after interrupted resets. They do not establish that the Docker image
boots successfully or that connected Minecraft clients transfer correctly.
For a deployment check, also run `docker compose config`, build the image, then
verify first start, runner death, dragon victory, stop, disconnect/reconnect,
and full rotation using connected clients.
