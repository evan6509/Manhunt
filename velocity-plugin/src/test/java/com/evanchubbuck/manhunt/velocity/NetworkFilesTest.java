package com.evanchubbuck.manhunt.velocity;

import com.evanchubbuck.manhunt.network.NetworkFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NetworkFilesTest {
    @TempDir Path temporary;

    @Test
    void roundTripValidatesIdentityAndRejectsMalformedOrWrongSchema() throws Exception {
        Path path = temporary.resolve("status.json");
        var status = new NetworkFiles.Status(1, "a", UUID.randomUUID(), UUID.randomUUID(), NetworkFiles.Phase.READY,
                null, null, null, null, 0, 100_000);
        NetworkFiles.writeAtomic(path, status);
        assertEquals(status, NetworkFiles.readStatus(path).orElseThrow());
        Files.writeString(path, "{\"schemaVersion\":2}");
        assertTrue(NetworkFiles.readStatus(path).isEmpty());
        Files.writeString(path, "{bad json");
        assertTrue(NetworkFiles.readStatus(path).isEmpty());
        assertTrue(NetworkFiles.readStatus(temporary.resolve("missing.json")).isEmpty());
    }

    @Test
    void staleAndFutureHeartbeatsCannotAuthorizeRouting() {
        var status = new NetworkFiles.Status(1, "a", UUID.randomUUID(), UUID.randomUUID(), NetworkFiles.Phase.READY,
                null, null, null, null, 0, 100_000);
        assertTrue(NetworkFiles.isFresh(status, 104_999, 5));
        assertFalse(NetworkFiles.isFresh(status, 106_000, 5));
        assertFalse(NetworkFiles.isFresh(status, 98_000, 5));
    }

    @Test
    void reservationsAndRequestsCarryExactWorldAndRunIds() throws Exception {
        UUID world = UUID.randomUUID(), run = UUID.randomUUID(), runner = UUID.randomUUID();
        NetworkFiles.writeAtomic(temporary.resolve("reservation.json"), new NetworkFiles.Reservation(1, world, run));
        NetworkFiles.writeAtomic(temporary.resolve("start.json"), new NetworkFiles.StartRequest(1, world, run, runner));
        NetworkFiles.writeAtomic(temporary.resolve("reset.json"), new NetworkFiles.ResetRequest(1, world, run));
        assertEquals(world, NetworkFiles.readReservation(temporary.resolve("reservation.json")).orElseThrow().worldId());
        assertEquals(run, NetworkFiles.readStart(temporary.resolve("start.json")).orElseThrow().runId());
        assertEquals(run, NetworkFiles.readReset(temporary.resolve("reset.json")).orElseThrow().runId());
        assertThrows(IllegalArgumentException.class, () -> new NetworkFiles.StartRequest(2, world, run, runner));
        assertThrows(NullPointerException.class, () -> new NetworkFiles.ResetRequest(1, null, run));
    }
}
