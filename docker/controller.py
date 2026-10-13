#!/usr/bin/env python3
"""Run a backend or proxy; archive completed worlds only after an exact reset ack.

Reset requests are generation-scoped. A durable transaction records the backend's
empty RESETTING acknowledgement before stopping Java. No ordinary process exit,
container restart, or unacknowledged request can archive a world.
"""

from __future__ import annotations

import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import uuid


class UnsafeReset(ValueError):
    """A reset cannot be proven safe; preserve every world file."""


def log(message: str) -> None:
    print(f"[manhunt] {message}", flush=True)


def canonical_uuid(value: object) -> str:
    if not isinstance(value, str):
        raise UnsafeReset("expected a UUID string")
    try:
        canonical = str(uuid.UUID(value))
    except (ValueError, AttributeError) as error:
        raise UnsafeReset("invalid UUID") from error
    if value != canonical:
        raise UnsafeReset("UUID must use canonical lowercase spelling")
    return canonical


def safe_child(base: Path, *parts: str) -> Path:
    """Never follow a symlink or allow a filename to escape the owned directory."""
    if base.is_symlink() or base.absolute() != base.resolve():
        raise UnsafeReset("storage root must not contain symlinks")
    target = base
    for part in parts:
        if not part or part in (".", "..") or "/" in part or "\\" in part:
            raise UnsafeReset("unsafe storage path")
        target = target / part
        if target.is_symlink():
            raise UnsafeReset("storage paths must not be symlinks")
    if not target.resolve().is_relative_to(base.resolve()):
        raise UnsafeReset("storage path escapes owned directory")
    return target


def fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_write(path: Path, value: dict) -> None:
    temporary = safe_child(path.parent, path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    fsync_directory(path.parent)


def read_json(path: Path) -> dict | None:
    if path.is_symlink():
        raise UnsafeReset("mailbox must not be a symlink")
    if not path.exists():
        return None
    if not path.is_file() or path.stat().st_size > 65536:
        raise UnsafeReset("mailbox must be a small regular JSON file")
    try:
        def no_duplicates(pairs: list[tuple[str, object]]) -> dict:
            result = {}
            for key, value in pairs:
                if key in result:
                    raise UnsafeReset("duplicate JSON key")
                result[key] = value
            return result
        with path.open(encoding="utf-8") as stream:
            value = json.load(stream, object_pairs_hook=no_duplicates)
    except (ValueError, UnicodeDecodeError) as error:
        raise UnsafeReset("malformed mailbox JSON") from error
    if not isinstance(value, dict):
        raise UnsafeReset("mailbox must contain an object")
    return value


def require_schema(value: dict) -> None:
    if type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1:
        raise UnsafeReset("unsupported mailbox schema")


class ResetController:
    def __init__(self, shared_dir: Path, server_id: str, clock=time.time):
        if not re.fullmatch(r"[a-z][a-z0-9-]*", server_id) or server_id == "try":
            raise UnsafeReset("invalid SERVER_ID")
        self.server_id = server_id
        self.clock = clock
        shared_dir = shared_dir.absolute()
        self.data_dir = safe_child(shared_dir, "server-" + server_id)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.network_dir = safe_child(self.data_dir, "network")
        self.network_dir.mkdir(exist_ok=True)
        self.status_file = safe_child(self.network_dir, "status.json")
        self.request_file = safe_child(self.network_dir, "reset.request.json")
        self.journal_file = safe_child(self.network_dir, "reset.transaction.json")

    def validate_ack(self, request: dict, status: dict, *, recovery=False) -> None:
        require_schema(request)
        require_schema(status)
        for name in ("worldId", "runId"):
            if canonical_uuid(request.get(name)) != canonical_uuid(status.get(name)):
                raise UnsafeReset("request does not match the acknowledged generation")
        canonical_uuid(status.get("bootId"))
        canonical_uuid(status.get("runnerId"))
        if not isinstance(status.get("runnerName"), str) or not status["runnerName"]:
            raise UnsafeReset("acknowledgement has no runner name")
        if not isinstance(status.get("outcome"), str) or not status["outcome"]:
            raise UnsafeReset("acknowledgement has no completion outcome")
        if status.get("serverId") != self.server_id:
            raise UnsafeReset("acknowledgement belongs to another backend")
        if status.get("phase") != "RESETTING":
            raise UnsafeReset("backend has not acknowledged RESETTING")
        if type(status.get("playerCount")) is not int or status["playerCount"] != 0:
            raise UnsafeReset("backend is not empty")
        stamp = status.get("updatedAt")
        now = int(self.clock() * 1000)
        if type(stamp) is not int or stamp <= 0 or stamp > now + 5000:
            raise UnsafeReset("invalid acknowledgement timestamp")
        if not recovery and now - stamp > 5000:
            raise UnsafeReset("acknowledgement is stale")

    def prepare(self) -> dict | None:
        """Called while Java is alive, before any shutdown signal is sent."""
        request = read_json(self.request_file)
        if request is None:
            return None
        status = read_json(self.status_file)
        if status is None:
            raise UnsafeReset("no backend acknowledgement")
        self.validate_ack(request, status)
        if read_json(self.journal_file) is not None:
            raise UnsafeReset("an earlier reset transaction requires recovery")
        world = safe_child(self.data_dir, "world")
        archive_dir = safe_child(self.data_dir, "archive")
        archive = safe_child(archive_dir, "world-" + request["worldId"])
        if not world.is_dir() or archive.exists():
            raise UnsafeReset("world is missing or its archive already exists")
        transaction = {"schemaVersion": 1, "serverId": self.server_id,
                       "request": request, "acknowledgement": status}
        atomic_write(self.journal_file, transaction)
        return transaction

    def finish(self, transaction: dict, *, process_alive: bool) -> bool:
        """After Java has exited, rename only world/; safely repeat after a crash."""
        if process_alive:
            raise UnsafeReset("refusing to touch a world while Java is alive")
        require_schema(transaction)
        if transaction.get("serverId") != self.server_id:
            raise UnsafeReset("transaction belongs to another backend")
        request = transaction.get("request")
        status = transaction.get("acknowledgement")
        if not isinstance(request, dict) or not isinstance(status, dict):
            raise UnsafeReset("transaction has no valid acknowledgement")
        self.validate_ack(request, status, recovery=True)
        # Remaining mailbox files may be absent after an interrupted cleanup;
        # if present they must still agree with the durable acknowledgement.
        current_request = read_json(self.request_file)
        current_status = read_json(self.status_file)
        if current_request is not None:
            self.validate_ack(current_request, status, recovery=True)
        if current_status is not None:
            self.validate_ack(request, current_status, recovery=True)
            if current_status.get("bootId") != status["bootId"]:
                raise UnsafeReset("backend boot changed during reset")
        world = safe_child(self.data_dir, "world")
        archive_dir = safe_child(self.data_dir, "archive")
        archive_dir.mkdir(exist_ok=True)
        archive = safe_child(archive_dir, "world-" + request["worldId"])
        if world.exists() and archive.exists():
            raise UnsafeReset("both the world and completed archive exist")
        if world.exists():
            if not world.is_dir():
                raise UnsafeReset("world is not a directory")
            world.rename(archive)
            fsync_directory(archive_dir)
            fsync_directory(self.data_dir)
            log(f"archived completed world {request['worldId']}")
        elif not archive.is_dir():
            raise UnsafeReset("neither the completed world nor its archive exists")
        # A blank level-seed gives the next world an independently random seed.
        update_properties(self.data_dir, {"level-seed": ""})
        for mailbox in (self.request_file, self.status_file,
                        safe_child(self.network_dir, "start.request.json"),
                        safe_child(self.network_dir, "reservation.json")):
            mailbox.unlink(missing_ok=True)
        fsync_directory(self.network_dir)
        self.journal_file.unlink(missing_ok=True)
        fsync_directory(self.network_dir)
        return True

    def recover(self, *, process_alive: bool) -> bool:
        """Run before boot; a crash alone is never a request to rotate worlds."""
        transaction = read_json(self.journal_file)
        if transaction is not None:
            return self.finish(transaction, process_alive=process_alive)
        request = read_json(self.request_file)
        if request is None:
            return False
        if process_alive:
            raise UnsafeReset("another Java process is still alive")
        status = read_json(self.status_file)
        if status is None:
            raise UnsafeReset("boot-time reset request has no prior acknowledgement")
        self.validate_ack(request, status, recovery=True)
        transaction = {"schemaVersion": 1, "serverId": self.server_id,
                       "request": request, "acknowledgement": status}
        atomic_write(self.journal_file, transaction)
        return self.finish(transaction, process_alive=False)


def update_properties(data_dir: Path, updates: dict[str, str]) -> None:
    path = safe_child(data_dir, "server.properties")
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    result = []
    for line in lines:
        stripped = line.lstrip()
        match = re.match(r"([^\s=:#]+)\s*[=:]", stripped)
        # Also remove alternate valid Java-properties syntax such as "key value".
        key = match.group(1) if match else stripped.split(maxsplit=1)[0] if stripped else ""
        if key not in updates:
            result.append(line)
    result.extend(key + "=" + value for key, value in updates.items())
    temporary = safe_child(data_dir, "server.properties.tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        stream.write("\n".join(result) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    fsync_directory(data_dir)


def copy_install(install_dir: Path, data_dir: Path) -> None:
    """Refresh baked libraries/mods without overwriting mutable server state."""
    allowed = {"fabric-server-launch.jar", "fabric-server-launcher.properties",
               "server.jar", "libraries", "mods"}
    for source in install_dir.iterdir():
        if source.name not in allowed:
            continue
        target = safe_child(data_dir, source.name)
        if source.is_dir():
            target.mkdir(exist_ok=True)
            for item in source.rglob("*"):
                destination = safe_child(target, *item.relative_to(source).parts)
                if item.is_dir():
                    destination.mkdir(exist_ok=True)
                else:
                    shutil.copy2(item, destination)
        else:
            shutil.copy2(source, target)


def live_backend_process(data_dir: Path) -> bool:
    """Linux containers: detect a leftover JVM even if its parent crashed."""
    proc = Path("/proc")
    if not proc.is_dir():
        raise UnsafeReset("process verification requires Linux /proc")
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            command = (entry / "cmdline").read_bytes().split(b"\0")
            cwd = (entry / "cwd").resolve(strict=True)
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
        if cwd == data_dir.resolve() and b"fabric-server-launch.jar" in command:
            return True
    return False


def validate_environment() -> tuple[str, str]:
    if os.environ.get("EULA", "").lower() != "true":
        raise ValueError("set EULA=true only after accepting the Minecraft EULA")
    secret = os.environ.get("FORWARDING_SECRET", "")
    if len(secret) < 32 or "change-me" in secret.lower() or "replace-with" in secret.lower() \
            or "dev-secret" in secret.lower() or any(character.isspace() for character in secret):
        raise ValueError("FORWARDING_SECRET must be a private random string of at least 32 characters")
    memory = os.environ.get("MC_MEMORY", "3G")
    if not re.fullmatch(r"[1-9][0-9]*[MG]", memory):
        raise ValueError("MC_MEMORY must be a positive JVM size such as 3G or 512M")
    return secret, memory


def stop_process(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        process.wait()
        return
    process.terminate()
    try:
        process.wait(timeout=35)
    except subprocess.TimeoutExpired:
        log("Java did not exit after 35 seconds; sending SIGKILL")
        process.kill()
        process.wait(timeout=5)


def run_server(shared_dir: Path, secret: str, memory: str) -> int:
    controller = ResetController(shared_dir, os.environ.get("SERVER_ID", "a"))
    lock_path = safe_child(controller.network_dir, "controller.lock")
    with lock_path.open("a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        if live_backend_process(controller.data_dir):
            raise UnsafeReset("another JVM already owns this backend")
        try:
            controller.recover(process_alive=False)
        except UnsafeReset as error:
            # A malformed/stale request is rejected without archiving. A valid
            # but interrupted transaction needs operator attention, not booting
            # into a half-reset world.
            if controller.journal_file.exists():
                raise
            log(f"ignored boot reset request: {error}")
        controller.status_file.unlink(missing_ok=True)
        fsync_directory(controller.network_dir)
        copy_install(Path("/opt/manhunt/server"), controller.data_dir)
        safe_child(controller.data_dir, "eula.txt").write_text("eula=true\n", encoding="utf-8")
        update_properties(controller.data_dir, {"online-mode": "false", "server-port": "25565",
                                               "server-ip": "", "level-name": "world",
                                               "enforce-secure-profile": "false"})
        config_dir = safe_child(controller.data_dir, "config", "manhunt")
        config_dir.mkdir(parents=True, exist_ok=True)
        config_file = safe_child(config_dir, "network.json")
        config = read_json(config_file) or {}
        config.update(enabled=True, serverId=controller.server_id, controlDirectory="network")
        atomic_write(config_file, config)
        environment = dict(os.environ, FABRIC_PROXY_SECRET=secret,
                           FABRIC_PROXY_HACK_ONLINE_MODE="true")
        stopped = False

        def shutdown(_signum, _frame):
            nonlocal stopped
            stopped = True

        signal.signal(signal.SIGTERM, shutdown)
        signal.signal(signal.SIGINT, shutdown)
        log(f"starting Fabric backend {controller.server_id} with Xmx={memory}")
        process = subprocess.Popen(["java", "-Xmx" + memory, "-jar", "fabric-server-launch.jar", "nogui"],
                                   cwd=controller.data_dir, env=environment)
        last_rejection = None
        try:
            while not stopped and process.poll() is None:
                try:
                    transaction = controller.prepare()
                    if transaction is not None:
                        log("matching empty RESETTING acknowledgement received; stopping Java")
                        stop_process(process)
                        controller.finish(transaction, process_alive=live_backend_process(controller.data_dir))
                        return 0
                    last_rejection = None
                except UnsafeReset as error:
                    if str(error) != last_rejection:
                        log(f"reset rejected: {error}")
                        last_rejection = str(error)
                time.sleep(1)
            if stopped:
                log("container shutdown: preserving the current world")
                stop_process(process)
                return 0
            code = process.wait()
            log(f"Java exited with code {code}; preserving the current world")
            return code if code >= 0 else 1
        finally:
            if process.poll() is None:
                stop_process(process)


def run_proxy(shared_dir: Path, secret: str, memory: str) -> int:
    proxy_dir = safe_child(shared_dir, "proxy")
    plugin_dir = safe_child(proxy_dir, "plugins", "manhunt")
    plugin_dir.mkdir(parents=True, exist_ok=True)
    # The proxy has its own persistent state; image replacement must not reset it.
    shutil.copy2("/opt/manhunt/velocity.jar", safe_child(proxy_dir, "velocity.jar"))
    shutil.copy2("/opt/manhunt/manhunt-velocity.jar",
                 safe_child(proxy_dir, "plugins", "manhunt-velocity.jar"))
    config_file = safe_child(plugin_dir, "config.json")
    config = read_json(config_file) or {}
    config.update(backendServers={server_id: {"serverId": server_id,
        "statusFile": str(safe_child(shared_dir, "server-" + server_id, "network", "status.json"))}
        for server_id in ("a", "b")}, pollSeconds=1, staleSeconds=5)
    atomic_write(config_file, config)
    secret_file = safe_child(proxy_dir, "forwarding.secret")
    secret_file.write_text(secret + "\n", encoding="utf-8")
    secret_file.chmod(0o600)
    safe_child(proxy_dir, "velocity.toml").write_text('''# Generated by the Manhunt Docker controller.
config-version = "2.8"
bind = "0.0.0.0:25578"
motd = "<gold>Manhunt</gold> <gray>Fresh worlds, new runners</gray>"
show-max-players = 100
online-mode = true
force-key-authentication = true
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
ping-passthrough = "DISABLED"
sample-players-in-ping = false
enable-player-address-logging = true

[servers]
a = "server-a:25565"
b = "server-b:25565"
try = ["a", "b"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 3000
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true
accepts-transfers = false
enable-reuse-port = false

[query]
enabled = false
port = 25578
map = "Manhunt"
show-plugins = false
''', encoding="utf-8")
    log("starting authenticated Velocity proxy on port 25578")
    os.chdir(proxy_dir)
    os.execvp("java", ["java", "-Xmx" + memory, "-jar", "velocity.jar"])
    return 0


def main() -> int:
    secret, memory = validate_environment()
    shared_dir = Path(os.environ.get("SHARED_DIR", "/manhunt")).absolute()
    if shared_dir.is_symlink() or shared_dir != shared_dir.resolve():
        raise UnsafeReset("SHARED_DIR must be an absolute directory without symlinks")
    shared_dir.mkdir(parents=True, exist_ok=True)
    mode = os.environ.get("MODE", "server")
    if mode == "server":
        return run_server(shared_dir, secret, memory)
    if mode == "proxy":
        return run_proxy(shared_dir, secret, memory)
    raise ValueError("MODE must be server or proxy")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        log(f"controller stopped safely: {error}")
        sys.exit(1)
