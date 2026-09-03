package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubePoiIndexTest {
    @Test
    void addRemoveQueryAndTicketReservationAreAtomic() {
        CubePoiIndex index = new CubePoiIndex();
        CubePoiRecord village = new CubePoiRecord(
                new CubePos(0, -2, 0), 1, 4, 3, "minecraft:home", 2);
        CubePoiRecord jobSite = new CubePoiRecord(
                new CubePos(1, -2, 0), 1, 4, 3, "minecraft:job_site", 1);
        CubePoiIndex.Key key = CubePoiIndex.Key.of(village);

        assertTrue(index.add(village).isEmpty());
        assertTrue(index.add(jobSite).isEmpty());
        assertEquals(List.of(village), index.query(
                village.cube(), CubeDependencyRadius.NONE, poi -> poi.type().equals("minecraft:home")));
        assertEquals(2, index.size());

        CubePoiRecord reserved = index.reserveTicket(key).orElseThrow();
        assertEquals(1, reserved.freeTickets());
        assertTrue(reserved.isOccupied());
        assertEquals(1, index.get(key).orElseThrow().freeTickets());
        assertEquals(2, index.releaseTicket(key).orElseThrow().freeTickets());
        assertEquals(village, index.remove(key).orElseThrow());
        assertFalse(index.get(key).isPresent());
    }

    @Test
    void persistenceIsDeterministicAndPreservesExtremeRepresentablePosition() {
        CubePoiIndex index = new CubePoiIndex();
        CubePos extreme = new CubePos(134_217_727, -134_217_728, -134_217_728);
        CubePoiRecord poi = new CubePoiRecord(extreme, 15, 0, 0, "higherworld:village", 3, 1);
        index.add(poi);

        byte[] first = index.encode();
        byte[] second = index.encode();
        assertEquals(java.util.HexFormat.of().formatHex(first),
                java.util.HexFormat.of().formatHex(second));
        CubePoiRecord decoded = CubePoiIndex.decode(first).snapshot().get(0);
        assertEquals(poi, decoded);
        assertEquals(Integer.MAX_VALUE, decoded.blockPos().getX());
        assertEquals(Integer.MIN_VALUE, decoded.blockPos().getY());
    }

    @Test
    void malformedAndUnboundedQueriesAreRejected() {
        CubePoiIndex index = new CubePoiIndex();
        assertThrows(IllegalArgumentException.class, () -> CubePoiIndex.decode(new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> index.query(
                new CubePos(0, 0, 0), new CubeDependencyRadius(129, 0, 0), ignored -> true));
        assertThrows(IllegalArgumentException.class, () -> new CubePoiRecord(
                new CubePos(134_217_728, 0, 0), 0, 0, 0, "bad", 1));
    }
}
