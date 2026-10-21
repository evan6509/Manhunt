# Linked Hardcore death delivery

Manhunt requires the Linked Hardcore durable death-outbox API when that optional
companion is installed. Standalone mode still has no companion dependency and
keeps the network bridge disabled by default. The companion protocol is defined
in Linked Hardcore's `docs/PROTOCOL.md` (contract version 3; scoped v2 wire bytes
are unchanged). Update both mods on every backend and the Linked Hardcore proxy
together. Do not run Manhunt's separate world controller alongside it.

## Ordering and recovery

1. Either death listener may run first. Before changing the hunt, Manhunt calls
   `LinkedHardcoreMod.preparePlayerDeath(runnerUuid)`.
2. The companion atomically commits `(serverId, generationId, playerUuid)` to
   `config/linkedhardcore/death-outbox.json`, outside resettable worlds. Repeated
   preparation of that identity returns the same event. It sends nothing yet.
3. Manhunt saves the completion, transfer latch, and `linked_death_generation`
   with the world. The companion's send hook may then return permission; its
   normal sender prepares the same event idempotently and sends `WORLD_PLAYER_DIED`.
4. On backend restart, the companion reloads the journal and sends exactly that
   frame over a current connection. Without a carrier it waits without clearing
   or postponing retry eligibility. A different player can carry the runner's report.
5. The proxy journals the ended source generations, pending handoff, and absolute
   accepted death count before ACK. Its existing replay restores that record;
   duplicates ACK without counting twice or starting another handoff. After commit,
   source cleanup/reset identity and generation checks prevent changing a newer run.
6. Only `WORLD_ACK` matching the deceased UUID and current generation atomically
   removes the backend event. Legacy/unrelated ACKs cannot clear it. Reports for
   retired generations remain quarantined and cannot be replayed into a new world.

The event identity intentionally uses the existing world-scoped protocol: one
world generation has one accepted linked-run termination. It does not represent
multiple independent deaths for the same player/world. Outside a hunt, ordinary
Linked Hardcore deaths use this same durable path. Hunter suppression, source
spectator locks, local manual stop/dragon wins, and standalone behavior remain.

## Failure model and upgrades

The regression models a process crash exactly after the native Manhunt latch is
saved and before the companion send body executes. It reloads native disk state,
discards the old messenger, and requires a byte-identical recovered report without
a new death callback. Companion integration tests additionally reconstruct both
sender and proxy around acceptance/ACK loss, counters, and handoff commit.
All fixtures are disposable; these tests do not use connected remote players.

The guarantee covers JVM/process crashes with completed atomic file replacements
preserved, not power loss. The outbox closes the write before atomic rename and
refuses a filesystem without atomic replacement; there is no end-to-end fsync
contract across backend and proxy journals. Disk corruption, partial rollback,
manual journal deletion, and changing identity under a running JVM need operator
recovery. A crash before the death intent is committed is outside this window.

Manhunt rejects an older installed companion without the preparation API before
play. An old completed runner-death latch without `linked_death_generation`
cannot prove whether its event was lost or ACKed; startup fails closed and keeps
the source intact. A mismatched generation also fails closed. Drain outstanding
work on old versions before upgrading. If an old latch already exists, preserve
all world/outbox/proxy lifecycle/counter files and reconcile them explicitly; never
clear the latch, fabricate delivery proof, or reset a world to bypass the gate.
Standalone saves and linked sources closed by another backend's transfer remain
readable. New protocol-v3 proxies reject unscoped v1 death reports without ACK,
count, transfer, or reset; world-scoped v2 layouts remain readable.
