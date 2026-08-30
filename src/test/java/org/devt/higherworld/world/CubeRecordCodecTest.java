package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CubeRecordCodecTest {
    @Test
    void readsGenerationVersionFromHwc3Header() {
        byte[] payload = {0x48, 0x57, 0x43, 0x33, 0, 0, 0, 5};
        assertEquals(5, CubeRecordCodec.generationVersion(payload));
    }

    @Test
    void treatsOlderAndTruncatedRecordsAsUnversioned() {
        assertEquals(0, CubeRecordCodec.generationVersion(
                new byte[] {0x48, 0x57, 0x43, 0x32, 0, 0, 0, 9}));
        assertEquals(0, CubeRecordCodec.generationVersion(new byte[] {0x48, 0x57, 0x43}));
    }
}
