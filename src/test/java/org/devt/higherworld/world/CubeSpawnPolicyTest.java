package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubeSpawnPolicyTest {
    @Test
    void spawningRequiresFullStatusAndSimulationDistance() {
        CubeSpawnPolicy.SimulationWindow window = new CubeSpawnPolicy.SimulationWindow(
                new CubePos(4, -2, 8), 2, 1);
        CubePos inside = new CubePos(6, -1, 8);
        assertTrue(CubeSpawnPolicy.eligible(inside, CubeStatus.FULL, window));
        assertFalse(CubeSpawnPolicy.eligible(inside, CubeStatus.LIGHT, window));
        assertFalse(CubeSpawnPolicy.eligible(new CubePos(7, -1, 8), CubeStatus.FULL, window));
        assertFalse(CubeSpawnPolicy.eligible(new CubePos(4, 0, 8), CubeStatus.FULL, window));
    }

    @Test
    void candidatesAndTicketsAreStableAndExplicit() {
        CubePos cube = new CubePos(-2, -3, 7);
        CubeSpawnPolicy.SimulationWindow window = new CubeSpawnPolicy.SimulationWindow(cube, 2, 1);
        List<CubeSpawnPolicy.Candidate> first = CubeSpawnPolicy.candidates(
                1234L, 20L, cube, CubeStatus.FULL, window, 32);
        List<CubeSpawnPolicy.Candidate> second = CubeSpawnPolicy.candidates(
                1234L, 20L, cube, CubeStatus.FULL, window, 32);
        assertEquals(first, second);
        assertTrue(first.stream().allMatch(candidate -> candidate.blockY() >= cube.minBlockY()));
        assertEquals(32, first.size());

        CubeSpawnPolicy.Ticket ticket = CubeSpawnPolicy.ticketFor(
                "mob", cube, CubeStatus.FULL, window).orElseThrow();
        assertEquals(CubeTicketType.ENTITY, ticket.asCubeTicket().type());
        assertEquals(CubeStatus.FULL, ticket.asCubeTicket().targetStatus());
        assertEquals(CubeDependencyRadius.NONE, ticket.asCubeTicket().radius());
        assertTrue(CubeSpawnPolicy.ticketFor("mob", cube, CubeStatus.FEATURES, window).isEmpty());
    }

    @Test
    void candidatePredicateCanRejectUnsafeLocationsWithoutChangingSeedOrder() {
        CubePos cube = new CubePos(0, 0, 0);
        CubeSpawnPolicy.SimulationWindow window = new CubeSpawnPolicy.SimulationWindow(cube, 0, 0);
        List<CubeSpawnPolicy.Candidate> candidates = CubeSpawnPolicy.candidates(
                55L, 1L, cube, CubeStatus.FULL, window, 20,
                candidate -> candidate.localY() == 0);
        assertTrue(candidates.stream().allMatch(candidate -> candidate.localY() == 0));
        assertTrue(candidates.size() <= 20);
    }
}
