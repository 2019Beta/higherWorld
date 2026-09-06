package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeSimulationServicesTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @TempDir
    Path directory;

    @Test
    void incrementalFullIndexMatchesSnapshotAcrossUnloadAndReload() throws Exception {
        CubePos first = new CubePos(0, -2, 0);
        CubePos second = new CubePos(1, -2, 0);
        var windows = List.of(new CubeSpawnPolicy.SimulationWindow(first, 4, 4));
        try (CubeSimulationServices incremental = new CubeSimulationServices(
                    directory.resolve("incremental.bin"), alwaysFullAccess(), () -> windows);
                CubeSimulationServices reference = new CubeSimulationServices(
                    directory.resolve("reference.bin"), alwaysFullAccess(), () -> windows)) {
            incremental.open();
            reference.open();
            incremental.fullCubeLoaded(second);
            incremental.fullCubeLoaded(first);
            incremental.fullCubeLoaded(first);
            incremental.tick(1L);
            reference.tick(List.of(first, second), 1L);
            assertFalse(reference.spawnCandidates(42L, 1L, 2).isEmpty());
            assertEquals(reference.spawnCandidates(42L, 1L, 2),
                    incremental.spawnCandidates(42L, 1L, 2));
            incremental.fullCubeUnloaded(first);
            incremental.tick(2L);
            reference.tick(List.of(second), 2L);
            assertEquals(reference.spawnCandidates(42L, 2L, 2),
                    incremental.spawnCandidates(42L, 2L, 2));
            incremental.fullCubeLoaded(first);
            incremental.tick(3L);
            reference.tick(List.of(first, second), 3L);
            assertEquals(reference.spawnCandidates(42L, 3L, 2),
                    incremental.spawnCandidates(42L, 3L, 2));
        }
    }

    @Test
    void poiIndexSurvivesServiceCloseAndReopen() throws Exception {
        Path file = directory.resolve("simulation_services.bin");
        CubePathfindingAccess access = alwaysFullAccess();
        CubePoiRecord poi = new CubePoiRecord(
                new CubePos(0, -2, 0), 2, 4, 6, "minecraft:home", 2, 1);

        CubeSimulationServices first = new CubeSimulationServices(file, access);
        first.open();
        first.addPoi(poi);
        first.close();

        CubeSimulationServices second = new CubeSimulationServices(file, access);
        second.open();
        assertEquals(List.of(poi), second.poiSnapshot());
        second.close();
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void sparseBlockStateTransitionsAddAndRemoveKnownPoi() {
        CubeSimulationServices services = new CubeSimulationServices(
                directory.resolve("poi.bin"), alwaysFullAccess());
        services.open();
        BlockPos position = new BlockPos(3, -20, 7);

        services.onBlockStateChanged(
                position, Blocks.AIR.getDefaultState(), Blocks.BELL.getDefaultState());
        List<CubePoiRecord> added = services.poiSnapshot();
        assertEquals(1, added.size());
        assertEquals(position, added.get(0).blockPos());
        assertTrue(added.get(0).type().startsWith("minecraft:"));

        services.onBlockStateChanged(
                position, Blocks.BELL.getDefaultState(), Blocks.AIR.getDefaultState());
        assertTrue(services.poiSnapshot().isEmpty());
    }

    @Test
    void fullCubeScanRebuildsEntriesAndRetainsTicketOccupancy() throws Exception {
        CubePos cube = new CubePos(1, -2, 3);
        CubeSimulationServices services = new CubeSimulationServices(
                directory.resolve("scan.bin"), alwaysFullAccess());
        services.open();
        BlockStateSource source = new BlockStateSource(
                cube, new BlockPos(cube.minBlockX() + 4, cube.minBlockY() + 5, cube.minBlockZ() + 6));

        services.indexFullCube(cube, source::get);
        CubePoiRecord discovered = services.poiSnapshot().get(0);
        services.addPoi(discovered.withFreeTickets(0));
        services.indexFullCube(cube, source::get);
        assertEquals(0, services.poiSnapshot().get(0).freeTickets());
        services.close();

        services = new CubeSimulationServices(
                directory.resolve("scan.bin"), alwaysFullAccess());
        services.open();
        assertEquals(0, services.poiSnapshot().get(0).freeTickets());

        source.clear();
        services.indexFullCube(cube, source::get);
        assertTrue(services.poiSnapshot().isEmpty());
        services.close();
    }

    @Test
    void candidatesAndEntityTicketsHonorFullSimulationWindow() throws Exception {
        CubePos cube = new CubePos(2, 1, -3);
        Map<CubePos, CubeStatus> statuses = new HashMap<>();
        statuses.put(cube, CubeStatus.FULL);
        CubePathfindingAccess access = new CubePathfindingAccess() {
            @Override
            public CubeStatus status(CubePos queried) {
                return statuses.getOrDefault(queried, CubeStatus.EMPTY);
            }

            @Override
            public boolean isPassable(CubePathNode node) {
                return true;
            }
        };
        AtomicReference<List<CubeSpawnPolicy.SimulationWindow>> windows =
                new AtomicReference<>(List.of(
                        new CubeSpawnPolicy.SimulationWindow(cube, 0, 0)));
        List<CubeTicket> replaced = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        CubeSimulationServices services = new CubeSimulationServices(
                directory.resolve("spawn.bin"), access, windows::get,
                new CubeSimulationServices.EntityTicketSink() {
                    @Override
                    public void replace(CubeTicket ticket) {
                        replaced.add(ticket);
                    }

                    @Override
                    public void remove(Object owner) {
                        removed.add(owner);
                    }
                });
        services.open();
        services.tick(List.of(cube), 40L);

        assertFalse(services.spawnCandidates(99L, 40L, 16).isEmpty());
        CubeSpawnPolicy.Ticket ticket = services.acquireEntityTicket("mob", cube).orElseThrow();
        assertEquals(CubeStatus.FULL, ticket.asCubeTicket().targetStatus());
        assertEquals(1, replaced.size());

        statuses.put(cube, CubeStatus.LIGHT);
        assertTrue(services.spawnCandidates(99L, 41L, 16).isEmpty());
        assertTrue(services.acquireEntityTicket("other", cube).isEmpty());
        services.tick(List.of(cube), 41L);
        assertTrue(services.activeEntityTickets().isEmpty());
        assertEquals(List.of("mob"), removed);
        services.close();
    }

    @Test
    void malformedPoiFileIsIgnoredWithoutLoadingCubes() throws Exception {
        Path file = directory.resolve("bad.bin");
        Files.write(file, new byte[] {1, 2, 3, 4});
        CubeSimulationServices services = new CubeSimulationServices(file, alwaysFullAccess());
        services.open();
        assertTrue(services.poiSnapshot().isEmpty());
        services.close();
    }

    private static CubePathfindingAccess alwaysFullAccess() {
        return new CubePathfindingAccess() {
            @Override
            public CubeStatus status(CubePos cube) {
                return CubeStatus.FULL;
            }

            @Override
            public boolean isPassable(CubePathNode node) {
                return true;
            }
        };
    }

    private static final class BlockStateSource {
        private final CubePos cube;
        private final BlockPos poiPosition;
        private boolean present = true;

        private BlockStateSource(CubePos cube, BlockPos poiPosition) {
            this.cube = cube;
            this.poiPosition = poiPosition;
        }

        private net.minecraft.block.BlockState get(int localX, int localY, int localZ) {
            int expectedX = Math.floorMod(poiPosition.getX(), CubePos.SIZE);
            int expectedY = Math.floorMod(poiPosition.getY(), CubePos.SIZE);
            int expectedZ = Math.floorMod(poiPosition.getZ(), CubePos.SIZE);
            return present && localX == expectedX && localY == expectedY && localZ == expectedZ
                    ? Blocks.BELL.getDefaultState() : Blocks.AIR.getDefaultState();
        }

        private void clear() {
            present = false;
        }
    }
}
