package org.devt.higherworld.world;

/**
 * Immutable terrain output produced before a cube is touched by the server
 * thread.  Implementations must only copy their data into the supplied cube;
 * they must not retain or call back into a world object.
 */
interface CubeTerrainSnapshot {
    void applyTo(LoadedCube cube);
}
