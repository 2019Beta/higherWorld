package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure revision-ordering tests; no Minecraft client or world is required. */
class CubeRevisionGateTest {
    @Test
    void acceptedNewerDeltaPromotesAndRejectsAnOlderDelta() {
        CubeRevisionGate.Decision promoted = CubeRevisionGate.delta(5L, 7L);
        assertTrue(promoted.accepted());
        assertEquals(7L, promoted.revision());

        CubeRevisionGate.Decision stale = CubeRevisionGate.delta(promoted.revision(), 6L);
        assertFalse(stale.accepted());
        assertEquals(7L, stale.revision());

        CubeRevisionGate.Decision duplicate = CubeRevisionGate.delta(promoted.revision(), 7L);
        assertTrue(duplicate.accepted());
        assertEquals(7L, duplicate.revision());
    }

    @Test
    void legacyUnversionedDeltaDoesNotAdvanceTheRevision() {
        CubeRevisionGate.Decision zero = CubeRevisionGate.delta(7L, 0L);
        CubeRevisionGate.Decision negative = CubeRevisionGate.delta(7L, -1L);

        assertTrue(zero.accepted());
        assertEquals(7L, zero.revision());
        assertTrue(negative.accepted());
        assertEquals(7L, negative.revision());
    }

    @Test
    void snapshotsAndRemovalsRespectARevisionPromotedByADelta() {
        CubeRevisionGate.Decision snapshot = CubeRevisionGate.snapshot(5L, 5L);
        assertTrue(snapshot.accepted());
        assertEquals(5L, snapshot.revision());

        CubeRevisionGate.Decision delta = CubeRevisionGate.delta(snapshot.revision(), 7L);
        assertTrue(delta.accepted());
        assertEquals(7L, delta.revision());

        assertFalse(CubeRevisionGate.snapshot(delta.revision(), 6L).accepted());
        assertFalse(CubeRevisionGate.removal(delta.revision(), 6L).accepted());
        assertTrue(CubeRevisionGate.removal(delta.revision(), 7L).accepted());

        // A fresh snapshot can establish the next lifecycle revision after a
        // removal; the removed entry itself carries no revision forward.
        CubeRevisionGate.Decision reloaded = CubeRevisionGate.snapshot(0L, 8L);
        assertTrue(reloaded.accepted());
        assertEquals(8L, reloaded.revision());
    }
}
