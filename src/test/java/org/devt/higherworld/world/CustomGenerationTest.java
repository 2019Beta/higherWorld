package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Random;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.devt.higherworld.storage.CubePos;

/** Pure checks for the custom generation math; registry bootstrap is
 * required because {@link CustomCubeGenerator} holds static block constants. */
class CustomGenerationTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void workerTerrainSnapshotIsDeterministicAndContainsOneCube() {
        CustomWorldSettings settings = CustomWorldSettings.defaults();
        var first = CustomCubeGenerator.prepareTerrain(
                42L, new org.devt.higherworld.storage.CubePos(2, -10, 3), settings);
        var second = CustomCubeGenerator.prepareTerrain(
                42L, new org.devt.higherworld.storage.CubePos(2, -10, 3), settings);
        assertEquals(4096, first.solid().length);
        assertArrayEquals(first.solid(), second.solid());
    }

    @Test
    void dungeonBudgetIsDistributedAcrossOneVerticalBand() {
        int bottomSection = -4;
        int dungeonCount = 7;
        int planned = 0;
        for (int offset = 0; offset < CustomDungeonGenerator.DUNGEON_BAND_CUBES; offset++) {
            planned += CustomDungeonGenerator.plannedAttemptsForCube(
                    42L, bottomSection, new CubePos(2, bottomSection - 1 - offset, 3),
                    dungeonCount);
        }

        assertEquals(dungeonCount, planned);
        assertEquals(0, CustomDungeonGenerator.plannedAttemptsForCube(
                42L, bottomSection, new CubePos(2, bottomSection, 3), dungeonCount));
    }

    @Test
    void terrainNoiseIsDeterministicAndParametersMatter() {
        CustomWorldSettings defaults = CustomWorldSettings.customDefaults();
        double first = CustomCubeGenerator.terrainDensity(12345L, defaults, 18.0, -96.0, -7.0);
        double second = CustomCubeGenerator.terrainDensity(12345L, defaults, 18.0, -96.0, -7.0);
        assertEquals(first, second, 0.0);
        CustomWorldSettings changed = defaults.toBuilder()
                .heightFactor(defaults.heightFactor() + 23.0).build();
        assertNotEquals(first,
                CustomCubeGenerator.terrainDensity(12345L, changed, 18.0, -96.0, -7.0));
    }

    @Test
    void interpolationUsesConfiguredSampleSpacing() {
        double[] samples = {0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0};
        // (1, 1, 0) is the midpoint of the first cell on a step-2 grid: the
        // trilinear result is the average of the z=0 face corners.
        assertEquals(2.5, CustomCubeGenerator.interpolate(
                samples, 1, 1, 0, 2, 2, 2, 1, 1, 1), 1.0e-9);
        assertEquals(0.0, CustomCubeGenerator.interpolate(
                samples, 0, 0, 0, 1, 1, 1, 1, 1, 1), 1.0e-9);
    }

    @Test
    void optimizedRasterizerMatchesReferenceInterpolation() {
        Random random = new Random(0x48494748574F524CL);
        int[] steps = {1, 2, 4, 8, 16};
        for (int stepX : steps) {
            for (int stepY : steps) {
                for (int stepZ : steps) {
                    int cellsX = 16 / stepX;
                    int cellsY = 16 / stepY;
                    int cellsZ = 16 / stepZ;
                    double[] samples = new double[
                            (cellsX + 1) * (cellsY + 1) * (cellsZ + 1)];
                    for (int index = 0; index < samples.length; index++) {
                        samples[index] = random.nextDouble() * 2.0 - 1.0;
                    }
                    boolean[] optimized = new boolean[16 * 16 * 16];
                    TerrainInterpolation.fillSolid(
                            samples, stepX, stepY, stepZ,
                            cellsX, cellsY, cellsZ, optimized);
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                boolean expected = CustomCubeGenerator.interpolate(
                                        samples, x, y, z, stepX, stepY, stepZ,
                                        cellsX, cellsY, cellsZ) > 0.0;
                                int index = (y * 16 + z) * 16 + x;
                                assertEquals(expected, optimized[index],
                                        "spacing=" + stepX + "/" + stepY + "/" + stepZ
                                                + " voxel=" + x + "/" + y + "/" + z);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void optimizedCpuTerrainMatchesPointwiseReference() {
        CustomWorldSettings settings = CustomWorldSettings.customDefaults();
        CubePos pos = new CubePos(3, -6, -2);
        boolean[] optimized = CustomCubeGenerator.prepareTerrainCpu(42L, pos, settings).solid();
        int stepX = settings.noiseSampleSizeX();
        int stepY = settings.noiseSampleSizeY();
        int stepZ = settings.noiseSampleSizeZ();
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = CubePos.SIZE / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        double[] samples = new double[(cellsX + 1) * (cellsY + 1) * (cellsZ + 1)];
        for (int gridY = 0; gridY <= cellsY; gridY++) {
            for (int gridZ = 0; gridZ <= cellsZ; gridZ++) {
                for (int gridX = 0; gridX <= cellsX; gridX++) {
                    samples[(gridY * (cellsZ + 1) + gridZ) * (cellsX + 1) + gridX] =
                            CustomCubeGenerator.terrainDensity(
                                    42L, settings,
                                    pos.minBlockX() + gridX * stepX,
                                    pos.minBlockY() + gridY * stepY,
                                    pos.minBlockZ() + gridZ * stepZ);
                }
            }
        }
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    boolean expected = CustomCubeGenerator.interpolate(
                            samples, x, y, z, stepX, stepY, stepZ,
                            cellsX, cellsY, cellsZ) > 0.0;
                    assertEquals(expected, optimized[(y * CubePos.SIZE + z) * CubePos.SIZE + x],
                            "voxel=" + x + "/" + y + "/" + z);
                }
            }
        }
    }

    @Test
    void tallNegativeHeightRasterLayoutMatchesPointwiseReference() {
        int[] steps = {1, 2, 4, 8, 16};
        int minimumY = -320;
        int seaLevel = -64;
        for (int stepX : steps) {
            for (int stepY : steps) {
                for (int stepZ : steps) {
                    int cellsX = CubePos.SIZE / stepX;
                    int cellsY = 64 / stepY;
                    int cellsZ = CubePos.SIZE / stepZ;
                    double[] samples = new double[
                            (cellsX + 1) * (cellsY + 1) * (cellsZ + 1)];
                    for (int gridY = 0; gridY <= cellsY; gridY++) {
                        for (int gridZ = 0; gridZ <= cellsZ; gridZ++) {
                            for (int gridX = 0; gridX <= cellsX; gridX++) {
                                int x = gridX * stepX;
                                int y = minimumY + gridY * stepY;
                                int z = gridZ * stepZ;
                                samples[(gridY * (cellsZ + 1) + gridZ) * (cellsX + 1) + gridX] =
                                        (y - seaLevel) / 32.0
                                                + Math.sin(x * 0.17 + z * 0.11)
                                                - Math.cos((x - z) * 0.07);
                            }
                        }
                    }

                    boolean[] actual = new boolean[CubePos.SIZE * 64 * CubePos.SIZE];
                    for (int section = 0; section < 4; section++) {
                        int sectionCellsY = CubePos.SIZE / stepY;
                        int rowLength = (cellsX + 1) * (cellsZ + 1);
                        double[] sectionSamples = new double[rowLength * (sectionCellsY + 1)];
                        int sourceOffset = section * sectionCellsY * rowLength;
                        for (int row = 0; row <= sectionCellsY; row++) {
                            System.arraycopy(samples, sourceOffset + row * rowLength,
                                    sectionSamples, row * rowLength, rowLength);
                        }
                        boolean[] sectionSolid = new boolean[CubePos.SIZE * CubePos.SIZE * CubePos.SIZE];
                        TerrainInterpolation.fillSolid(
                                sectionSamples, stepX, stepY, stepZ,
                                cellsX, sectionCellsY, cellsZ, sectionSolid);
                        for (int localY = 0; localY < CubePos.SIZE; localY++) {
                            int targetOffset = (section * CubePos.SIZE + localY)
                                    * CubePos.SIZE * CubePos.SIZE;
                            int sourceSectionOffset = localY * CubePos.SIZE * CubePos.SIZE;
                            System.arraycopy(sectionSolid, sourceSectionOffset,
                                    actual, targetOffset, CubePos.SIZE * CubePos.SIZE);
                        }
                    }

                    boolean[] expected = new boolean[actual.length];
                    for (int y = 0; y < 64; y++) {
                        for (int z = 0; z < CubePos.SIZE; z++) {
                            for (int x = 0; x < CubePos.SIZE; x++) {
                                expected[(y * CubePos.SIZE + z) * CubePos.SIZE + x] =
                                        CustomCubeGenerator.interpolate(
                                                samples, x, y, z, stepX, stepY, stepZ,
                                                cellsX, cellsY, cellsZ) > 0.0;
                            }
                        }
                    }
                    assertTrue(Arrays.equals(expected, actual),
                            "spacing=" + stepX + "/" + stepY + "/" + stepZ);
                }
            }
        }
    }

    @Test
    void oreHeightAndCyclicCurveFollowReferenceMath() {
        CustomWorldSettings settings = CustomWorldSettings.customDefaults();
        assertEquals(32, CustomOreGenerator.absoluteHeight(-.5, settings));
        double peak = CustomOreGenerator.cyclicBellCurveProbability(16.0, 16.0, 7.0, 192.0);
        assertEquals(1.0, peak, 1.0e-12);
        assertEquals(peak, CustomOreGenerator.cyclicBellCurveProbability(208.0, 16.0, 7.0, 192.0), 1.0e-12);
        assertTrue(CustomOreGenerator.cyclicBellCurveProbability(80.0, 16.0, 7.0, 192.0) < peak);
    }

    @Test
    void structureIdentifierFamiliesAreStable() {
        assertTrue(VanillaStructureGenerator.isMineshaft("mineshaft_mesa"));
        assertTrue(VanillaStructureGenerator.isVillage("village_taiga"));
        assertTrue(VanillaStructureGenerator.isTemple("jungle_pyramid"));
        assertTrue(VanillaStructureGenerator.isTemple("ocean_ruin_warm"));
    }

    @Test
    void modelRejectsZeroRandomDivisors() {
        CustomWorldSettings defaults = CustomWorldSettings.customDefaults();
        CustomWorldSettings.CaveSettings cave = defaults.caves().get(0);
        CustomWorldSettings.CaveSettings invalid = new CustomWorldSettings.CaveSettings(
                cave.caveBlock(), cave.caveMinHeight(), cave.caveMaxHeight(), 0,
                cave.maxInitNodes(), cave.largeNodeRarity(), cave.largeNodeMaxBranches(),
                cave.bigCaveRarity(), cave.caveSizeAdd(), cave.steepStepRarity(),
                cave.flattenFactor(), cave.steeperFlattenFactor(), cave.directionChangeFactor(),
                cave.prevHorizDirectionChangeWeight(), cave.prevVertDirectionChangeWeight(),
                cave.maxAddDirectionChangeHoriz(), cave.maxAddDirectionChangeVert(),
                cave.carveStepRarity(), cave.caveFloorDepth(), cave.isBlockReplaceable());
        assertThrows(IllegalArgumentException.class,
                () -> defaults.toBuilder().caves(java.util.List.of(invalid)).build());

        CustomWorldSettings.OreSettings periodic = defaults.periodicGaussianOres().get(0);
        CustomWorldSettings.OreSettings invalidPeriodic = new CustomWorldSettings.OreSettings(
                periodic.blockstate(), null, periodic.spawnSize(), periodic.spawnTries(),
                periodic.spawnProbability(), periodic.minHeight(), periodic.maxHeight(),
                periodic.heightMean(), 0.0, periodic.heightSpacing());
        assertThrows(IllegalArgumentException.class,
                () -> defaults.toBuilder()
                        .periodicGaussianOres(java.util.List.of(invalidPeriodic)).build());
    }
}
