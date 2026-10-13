package com.evanchubbuck.manhunt.velocity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Prefer players with the fewest actual starts; never repeat the preceding runner. */
final class RunnerSelection {
    private RunnerSelection() {}

    static Optional<UUID> choose(List<UUID> players, UUID previous, Map<UUID, Integer> counts,
                                 RandomGenerator random) {
        if (players.stream().distinct().count() < 2) return Optional.empty();
        List<UUID> eligible = players.stream().distinct().filter(id -> !id.equals(previous)).toList();
        if (eligible.isEmpty()) return Optional.empty();
        int minimum = eligible.stream().mapToInt(id -> counts.getOrDefault(id, 0)).min().orElseThrow();
        List<UUID> ties = eligible.stream().filter(id -> counts.getOrDefault(id, 0) == minimum).toList();
        return Optional.of(ties.get(random.nextInt(ties.size())));
    }
}
