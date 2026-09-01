package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubePersistenceNetworkTest {
    @Test
    void cubeDataPayloadOwnsItsBytes() {
        byte[] source = {1, 2, 3};
        CubeDataPayload payload = new CubeDataPayload(new CubePos(0, 0, 0), source);

        source[0] = 9;
        byte[] snapshot = payload.data();
        snapshot[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, payload.data());
    }

    @Test
    void cubeDataPayloadRejectsOversizedRecordsBeforeEncoding() {
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];

        assertThrows(IllegalArgumentException.class,
                () -> new CubeDataPayload(new CubePos(0, 0, 0), oversized));
    }

    @Test
    void cubeDataPayloadRejectsCubeCoordinatesOutsideBlockIntegerRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new CubeDataPayload(new CubePos(Integer.MAX_VALUE, 0, 0), new byte[] {1}));
    }
}
