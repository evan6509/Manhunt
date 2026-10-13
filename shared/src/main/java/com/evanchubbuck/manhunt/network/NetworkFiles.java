package com.evanchubbuck.manhunt.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The versioned, generation-scoped mailbox shared by Fabric, Velocity, and Docker. */
public final class NetworkFiles {
    public static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_BYTES = 65_536;

    private NetworkFiles() {}

    public enum Phase { READY, RUNNING, COMPLETED, RESETTING }

    public record Status(int schemaVersion, String serverId, UUID worldId, UUID bootId, Phase phase,
                         UUID runId, UUID runnerId, String runnerName, String outcome,
                         int playerCount, long updatedAt) {
        public Status {
            schema(schemaVersion);
            if (serverId == null || !serverId.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("Invalid serverId");
            }
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(bootId, "bootId");
            Objects.requireNonNull(phase, "phase");
            if (playerCount < 0 || updatedAt <= 0) throw new IllegalArgumentException("Invalid status counters");
            if (phase == Phase.READY) {
                if (runId != null || runnerId != null || runnerName != null || outcome != null) {
                    throw new IllegalArgumentException("A ready world cannot contain a previous run");
                }
            } else {
                Objects.requireNonNull(runId, "runId");
                Objects.requireNonNull(runnerId, "runnerId");
                if (runnerName == null || runnerName.isBlank() || runnerName.length() > 64) {
                    throw new IllegalArgumentException("Invalid runnerName");
                }
                if (phase == Phase.RUNNING && outcome != null) {
                    throw new IllegalArgumentException("A running hunt cannot have an outcome");
                }
                if ((phase == Phase.COMPLETED || phase == Phase.RESETTING)
                        && (outcome == null || outcome.isBlank() || outcome.length() > 128)) {
                    throw new IllegalArgumentException("A completed hunt requires an outcome");
                }
            }
        }
    }

    public record StartRequest(int schemaVersion, UUID worldId, UUID runId, UUID runnerId) {
        public StartRequest {
            schema(schemaVersion);
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(runnerId, "runnerId");
        }
    }

    public record ResetRequest(int schemaVersion, UUID worldId, UUID runId) {
        public ResetRequest {
            schema(schemaVersion);
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    public record Reservation(int schemaVersion, UUID worldId, UUID runId) {
        public Reservation {
            schema(schemaVersion);
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    public static Optional<Status> readStatus(Path path) { return read(path, Status.class); }
    public static Optional<StartRequest> readStart(Path path) { return read(path, StartRequest.class); }
    public static Optional<ResetRequest> readReset(Path path) { return read(path, ResetRequest.class); }
    public static Optional<Reservation> readReservation(Path path) { return read(path, Reservation.class); }

    /** Future timestamps cannot keep an old server alive indefinitely. */
    public static boolean isFresh(Status status, long now, int staleSeconds) {
        long age = now - status.updatedAt();
        return age >= -1_000 && age <= staleSeconds * 1_000L;
    }

    private static <T> Optional<T> read(Path path, Class<T> type) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return Optional.empty();
            return Optional.ofNullable(GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), type));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static void writeAtomic(Path path, Object value) throws IOException {
        Objects.requireNonNull(value, "value");
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(GSON.toJson(value) + "\n");
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true); // A completed checkpoint must reach storage before any transfer/reset.
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            // Linux/macOS support syncing directory metadata; some providers (notably Windows) do not.
            try (FileChannel directory = FileChannel.open(absolute.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // The file content is still forced and the same-directory replacement is complete.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void schema(int schemaVersion) {
        if (schemaVersion != SCHEMA) throw new IllegalArgumentException("Unsupported network schema");
    }
}
