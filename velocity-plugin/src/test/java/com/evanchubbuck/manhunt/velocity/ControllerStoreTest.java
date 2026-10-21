package com.evanchubbuck.manhunt.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ControllerStoreTest {
    @TempDir Path temporary;

    @Test
    void roundTripRetainsReservedDestinationRunnerAndRetiredGeneration() throws Exception {
        var store = new ControllerStore(temporary.resolve("controller.json"));
        var state = new ControllerStore.State();
        state.activeBackend = "b";
        state.activeWorldId = UUID.randomUUID();
        UUID runner = UUID.randomUUID(), oldWorld = UUID.randomUUID(), oldRun = UUID.randomUUID();
        state.previousRunner = runner;
        state.selections.put(runner, 3);
        state.retired.put("a", new ControllerStore.RetiredWorld(oldWorld, oldRun));
        var transition = new ControllerStore.Transition();
        transition.sourceBackend = "a";
        transition.sourceWorldId = oldWorld;
        transition.sourceRunId = oldRun;
        transition.previousRunner = runner;
        transition.destinationBackend = "b";
        transition.destinationWorldId = state.activeWorldId;
        transition.nextRunId = UUID.randomUUID();
        transition.requiredParticipants.add(runner);
        transition.confirmedArrivals.add(runner);
        transition.moved = true;
        transition.selectedRunner = UUID.randomUUID();
        state.transition = transition;
        store.save(state);
        var restored = store.load();
        assertEquals(state.activeWorldId, restored.activeWorldId);
        assertEquals(3, restored.selections.get(runner));
        assertEquals(state.retired.get("a"), restored.retired.get("a"));
        assertEquals(transition.nextRunId, restored.transition.nextRunId);
        assertEquals(transition.selectedRunner, restored.transition.selectedRunner);
        assertEquals(transition.requiredParticipants, restored.transition.requiredParticipants);
        assertEquals(transition.confirmedArrivals, restored.transition.confirmedArrivals);
        assertTrue(restored.transition.moved);
    }

    @Test
    void legacyUnfinishedTransferWithoutParticipantsFailsClosed() throws Exception {
        Path path = temporary.resolve("controller.json");
        var json = new com.google.gson.JsonObject();
        json.addProperty("schemaVersion", 1);
        json.add("selections", new com.google.gson.JsonObject());
        json.add("retired", new com.google.gson.JsonObject());
        var transition = new com.google.gson.JsonObject();
        transition.addProperty("sourceBackend", "a");
        transition.addProperty("sourceWorldId", UUID.randomUUID().toString());
        transition.addProperty("sourceRunId", UUID.randomUUID().toString());
        transition.addProperty("previousRunner", UUID.randomUUID().toString());
        transition.addProperty("destinationBackend", "b");
        transition.addProperty("destinationWorldId", UUID.randomUUID().toString());
        transition.addProperty("nextRunId", UUID.randomUUID().toString());
        json.add("transition", transition);
        Files.writeString(path, json.toString());
        assertThrows(java.io.IOException.class, () -> new ControllerStore(path).load(),
                "Old pending transfers cannot prove which disconnected players still need to arrive");
        assertEquals(json.toString(), Files.readString(path));
    }

    @Test
    void corruptExistingStateFailsClosedInsteadOfForgettingWorlds() throws Exception {
        Path path = temporary.resolve("controller.json");
        Files.writeString(path, "{}");
        assertThrows(java.io.IOException.class, () -> new ControllerStore(path).load());
        assertEquals("{}", Files.readString(path));
    }
}
