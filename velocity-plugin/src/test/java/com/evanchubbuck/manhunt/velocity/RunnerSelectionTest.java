package com.evanchubbuck.manhunt.velocity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RunnerSelectionTest {
    @Test
    void neverRepeatsPreviousRunnerAndPrefersLeastUsedEligiblePlayer() {
        UUID previous = UUID.randomUUID(), experienced = UUID.randomUUID(), newest = UUID.randomUUID();
        for (int seed = 0; seed < 50; seed++) {
            assertEquals(newest, RunnerSelection.choose(List.of(previous, experienced, newest), previous,
                    Map.of(previous, 0, experienced, 4, newest, 1), new Random(seed)).orElseThrow());
        }
    }

    @Test
    void tiedSelectionsAreRandomAndOnePlayerCannotStartAHunt() {
        UUID previous = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        var selected = new java.util.HashSet<UUID>();
        Random random = new Random(1);
        for (int count = 0; count < 100; count++) {
            selected.add(RunnerSelection.choose(List.of(previous, first, second), previous, Map.of(), random).orElseThrow());
        }
        assertEquals(java.util.Set.of(first, second), selected);
        assertTrue(RunnerSelection.choose(List.of(first), previous, Map.of(), random).isEmpty());
        assertTrue(RunnerSelection.choose(List.of(first, first), previous, Map.of(), random).isEmpty());
    }
}
