package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.junit.jupiter.api.Test;

class SparseLightVolumeTest {
    @Test
    void uniformVolumesDoNotAllocatePackedStorage() {
        SparseLightVolume volume = new SparseLightVolume(SparseLightVolume.Snapshot.uniform(15));

        assertEquals(15, volume.getVisible(3, 7, 12));
        assertNull(volume.snapshot().packedCopy());
        assertFalse(volume.publish());
    }

    @Test
    void workingChangesAreInvisibleUntilPublished() {
        SparseLightVolume volume = new SparseLightVolume();

        assertTrue(volume.setWorking(1, 2, 3, 11));
        assertEquals(0, volume.getVisible(1, 2, 3));
        assertTrue(volume.publish());
        assertEquals(11, volume.getVisible(1, 2, 3));
        assertEquals(SparseLightVolume.BYTE_COUNT, volume.snapshot().packedCopy().length);
    }

    @Test
    void publishingCompactsAUniformWorkingArray() {
        SparseLightVolume volume = new SparseLightVolume(SparseLightVolume.Snapshot.uniform(15));
        volume.setWorking(0, 0, 0, 0);
        volume.setWorking(0, 0, 0, 15);

        assertFalse(volume.publish());
        assertTrue(volume.snapshot().isUniform());
        assertEquals(15, volume.snapshot().uniformValue());
    }

    @Test
    void cubeLightRoundTripsUniformAndPackedVolumes() throws Exception {
        CubeLightData light = new CubeLightData();
        light.setWorkingBlock(4, 5, 6, 12);
        light.setWorkingSky(0, 0, 0, 15);
        light.publish();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CubeLightData.write(new DataOutputStream(bytes), light.snapshot());

        CubeLightData decoded = new CubeLightData(CubeLightData.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));

        assertEquals(12, decoded.block(4, 5, 6));
        assertEquals(15, decoded.sky(0, 0, 0));
        assertEquals(0, decoded.block(0, 0, 0));
    }
}
