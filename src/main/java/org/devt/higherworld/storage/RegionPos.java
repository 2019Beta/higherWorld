package org.devt.higherworld.storage;

record RegionPos(int x, int y, int z) {
    String fileName() {
        return "r." + x + "." + y + "." + z + ".hwr";
    }
}
