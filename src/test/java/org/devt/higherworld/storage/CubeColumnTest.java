package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CubeColumnTest {
    @Test
    void keepsSparseExtremeCubeCoordinatesOrdered() {
        CubeColumn<String> column = new CubeColumn<>();
        column.put(Integer.MAX_VALUE, "top");
        column.put(0, "origin");
        column.put(Integer.MIN_VALUE, "bottom");

        assertEquals(List.of("bottom", "origin", "top"), column.loaded());
        assertEquals(3, column.size());
    }

    @Test
    void createsEachCoordinateOnceAndSupportsRanges() {
        CubeColumn<String> column = new CubeColumn<>();
        assertEquals("cube--20", column.getOrCreate(-20, y -> "cube-" + y));
        assertEquals("cube--20", column.getOrCreate(-20, y -> "different"));
        column.put(4, "four");
        column.put(1000, "far");

        assertEquals(List.of("cube--20", "four"), column.between(-20, 4));
        assertEquals("four", column.remove(4));
        assertNull(column.get(4));
    }

    @Test
    void rejectsReplacingALoadedCube() {
        CubeColumn<Object> column = new CubeColumn<>();
        column.put(7, new Object());
        assertThrows(IllegalArgumentException.class, () -> column.put(7, new Object()));
    }
}
