package com.evanchubbuck.manhunt.velocity;

import com.evanchubbuck.manhunt.network.NetworkFiles;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Everything needed to finish a rotation after a proxy restart lives outside world data. */
final class ControllerStore {
    static final class State {
        int schemaVersion = NetworkFiles.SCHEMA;
        String activeBackend;
        UUID activeWorldId;
        UUID lastCountedRunId;
        UUID previousRunner;
        Map<UUID, Integer> selections = new LinkedHashMap<>();
        Map<String, RetiredWorld> retired = new LinkedHashMap<>();
        Transition transition;

        void validate() {
            if (schemaVersion != NetworkFiles.SCHEMA || selections == null || retired == null
                    || (activeBackend == null) != (activeWorldId == null)) {
                throw new IllegalArgumentException("Invalid controller state");
            }
            selections.forEach((id, count) -> {
                if (id == null || count == null || count < 0) throw new IllegalArgumentException("Invalid runner counts");
            });
            retired.forEach((name, world) -> {
                if (name == null || name.isBlank() || world == null) throw new IllegalArgumentException("Invalid retired world");
            });
            if (transition != null) transition.validate();
        }
    }

    record RetiredWorld(UUID worldId, UUID runId) {
        RetiredWorld {
            java.util.Objects.requireNonNull(worldId);
            java.util.Objects.requireNonNull(runId);
        }
    }

    static final class Transition {
        String sourceBackend;
        UUID sourceWorldId;
        UUID sourceRunId;
        UUID previousRunner;
        String destinationBackend;
        UUID destinationWorldId;
        UUID nextRunId;
        UUID selectedRunner;
        Set<UUID> authorizedRunners = new LinkedHashSet<>();
        Set<UUID> requiredParticipants = new LinkedHashSet<>();
        Set<UUID> confirmedArrivals = new LinkedHashSet<>();
        boolean moved;

        void validate() {
            if (sourceBackend == null || sourceBackend.isBlank() || sourceWorldId == null || sourceRunId == null
                    || previousRunner == null || nextRunId == null
                    || authorizedRunners == null || requiredParticipants == null || confirmedArrivals == null
                    || requiredParticipants.contains(null) || confirmedArrivals.contains(null)
                    || !requiredParticipants.containsAll(confirmedArrivals)
                    || (moved && !confirmedArrivals.containsAll(requiredParticipants))
                    || (destinationBackend == null) != (destinationWorldId == null)
                    || (moved && destinationBackend == null) || (selectedRunner != null && !moved)
                    || (!authorizedRunners.isEmpty() && !moved)
                    || previousRunner.equals(selectedRunner)
                    || authorizedRunners.stream().anyMatch(candidate -> candidate == null || candidate.equals(previousRunner))
                    || sourceBackend.equals(destinationBackend)) {
                throw new IllegalArgumentException("Invalid persisted rotation");
            }
        }
    }

    private final Path path;
    ControllerStore(Path path) { this.path = path; }

    State load() throws IOException {
        if (!Files.exists(path)) return new State();
        try {
            if (Files.size(path) > 4_194_304) throw new IllegalArgumentException("Controller state is too large");
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!json.has("schemaVersion") || !json.has("selections") || !json.has("retired")) {
                throw new IllegalArgumentException("Incomplete controller state");
            }
            State state = new Gson().fromJson(json, State.class);
            if (state == null) throw new IllegalArgumentException("Empty controller state");
            if (state.transition != null && !state.transition.moved) {
                var transition = json.getAsJsonObject("transition");
                if (!transition.has("requiredParticipants") || !transition.has("confirmedArrivals")) {
                    throw new IllegalArgumentException("Unfinished rotation has no durable transfer participants");
                }
            }
            state.validate();
            return state;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid controller state; refusing to forget an unfinished rotation", exception);
        }
    }

    void save(State state) throws IOException {
        state.validate();
        NetworkFiles.writeAtomic(path, state);
    }
}
