package org.devt.higherworld.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.noise.DoublePerlinNoiseSampler;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.GenerationShapeConfig;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;
import org.devt.higherworld.mixin.NoiseChunkGeneratorInvoker;
import org.devt.higherworld.storage.CubePos;

/** Runs vanilla's synchronous noise-chunk implementation for sparse cube batches. */
final class VanillaCubeTerrainGenerator {
    private static final int SECTIONS_PER_BATCH = 4;
    private static final int BATCH_HEIGHT = CubePos.SIZE * SECTIONS_PER_BATCH;
    private static final int MAX_CACHED_BATCHES = 256;
    private static final int MAX_CACHED_GENERATORS = 64;
    private static final Map<ServerWorld, Context> CONTEXTS = new ConcurrentHashMap<>();

    private VanillaCubeTerrainGenerator() {
    }

    static boolean generate(ServerWorld world, LoadedCube cube) {
        ChunkGenerator source = world.getChunkManager().getChunkGenerator();
        if (!(source instanceof NoiseChunkGenerator noiseGenerator)) {
            return false;
        }

        Context context = CONTEXTS.computeIfAbsent(
                world, ignored -> createContext(world, noiseGenerator));
        int batchSectionY = Math.floorDiv(cube.pos().y(), SECTIONS_PER_BATCH)
                * SECTIONS_PER_BATCH;
        int minimumY = batchSectionY * CubePos.SIZE;
        NoiseChunkGenerator generator = context.generatorFor(noiseGenerator, minimumY);
        BatchPos batchPos = new BatchPos(cube.pos().x(), batchSectionY, cube.pos().z());
        ChunkSection generated = context.getOrGenerate(
                batchPos, () -> generateBatch(world, cube, minimumY, generator, context.noiseConfig))
                .section(cube.pos().y() - batchSectionY);
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    BlockState state = generated.getBlockState(x, y, z);
                    cube.setGeneratedBlockState(x, y, z, state);
                }
            }
        }
        return true;
    }

    static void release(ServerWorld world) {
        CONTEXTS.remove(world);
    }

    private static Batch generateBatch(
            ServerWorld world, LoadedCube cube, int minimumY,
            NoiseChunkGenerator generator, NoiseConfig noiseConfig) {
        HeightLimitView height = HeightLimitView.create(minimumY, BATCH_HEIGHT);
        ProtoChunk protoChunk = new ProtoChunk(
                new ChunkPos(cube.pos().x(), cube.pos().z()), UpgradeData.NO_UPGRADE_DATA,
                height, world.getPalettesFactory(), null);
        GenerationShapeConfig shape = generator.getSettings().value()
                .generationShapeConfig().trimHeight(height);
        int verticalCell = shape.verticalCellBlockCount();
        int minimumCellY = Math.floorDiv(shape.minimumY(), verticalCell);
        int cellHeight = Math.floorDiv(shape.height(), verticalCell);

        ChunkSection[] sections = protoChunk.getSectionArray();
        for (ChunkSection section : sections) {
            section.lock();
        }
        try {
            ((NoiseChunkGeneratorInvoker) (Object) generator)
                    .higherworld$populateNoiseSynchronously(
                            Blender.getNoBlending(), world.getStructureAccessor(), noiseConfig,
                            protoChunk, minimumCellY, cellHeight);
        } finally {
            for (ChunkSection section : sections) {
                section.unlock();
            }
        }
        return new Batch(sections);
    }

    private static Context createContext(ServerWorld world, NoiseChunkGenerator source) {
        ChunkGeneratorSettings original = source.getSettings().value();
        NoiseRouter router = InfiniteDownwardGenerator.removeVanillaBottomSlide(
                original.noiseRouter());
        ChunkGeneratorSettings settings = InfiniteNoiseSettings.createSparseSettings(
                original, new GenerationShapeConfig(-128, BATCH_HEIGHT,
                        original.generationShapeConfig().horizontalSize(),
                        original.generationShapeConfig().verticalSize()), router);
        RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParameters =
                world.getRegistryManager().getOrThrow(RegistryKeys.NOISE_PARAMETERS);
        NoiseConfig noiseConfig = NoiseConfig.create(settings, noiseParameters, world.getSeed());
        return new Context(settings, noiseConfig,
                new LinkedHashMap<>(16, 0.75f, true),
                new LinkedHashMap<>(32, 0.75f, true));
    }

    private static NoiseChunkGenerator createGenerator(
            NoiseChunkGenerator source, ChunkGeneratorSettings template, int minimumY) {
        GenerationShapeConfig originalShape = template.generationShapeConfig();
        GenerationShapeConfig shape = new GenerationShapeConfig(
                minimumY, BATCH_HEIGHT,
                originalShape.horizontalSize(), originalShape.verticalSize());
        ChunkGeneratorSettings settings = InfiniteNoiseSettings.createSparseSettings(
                template, shape, template.noiseRouter());
        NoiseChunkGenerator generator = new NoiseChunkGenerator(
                source.getBiomeSource(), RegistryEntry.of(settings));
        InfiniteNoiseSettings.useContinuousAquifers(generator);
        return generator;
    }

    private static final class Context {
        private final ChunkGeneratorSettings settings;
        private final NoiseConfig noiseConfig;
        private final LinkedHashMap<Integer, NoiseChunkGenerator> generators;
        private final LinkedHashMap<BatchPos, Batch> batches;

        private Context(
                ChunkGeneratorSettings settings, NoiseConfig noiseConfig,
                LinkedHashMap<Integer, NoiseChunkGenerator> generators,
                LinkedHashMap<BatchPos, Batch> batches) {
            this.settings = settings;
            this.noiseConfig = noiseConfig;
            this.generators = generators;
            this.batches = batches;
        }

        private synchronized NoiseChunkGenerator generatorFor(
                NoiseChunkGenerator source, int minimumY) {
            NoiseChunkGenerator generator = generators.get(minimumY);
            if (generator == null) {
                generator = createGenerator(source, settings, minimumY);
                generators.put(minimumY, generator);
                trim(generators, MAX_CACHED_GENERATORS);
            }
            return generator;
        }

        private synchronized Batch getOrGenerate(
                BatchPos pos, java.util.function.Supplier<Batch> factory) {
            Batch batch = batches.get(pos);
            if (batch == null) {
                batch = factory.get();
                batches.put(pos, batch);
                trim(batches, MAX_CACHED_BATCHES);
            }
            return batch;
        }

        private static <K, V> void trim(LinkedHashMap<K, V> values, int maximum) {
            while (values.size() > maximum) {
                values.remove(values.keySet().iterator().next());
            }
        }
    }

    private record BatchPos(int x, int sectionY, int z) {
    }

    private record Batch(ChunkSection[] sections) {
        private ChunkSection section(int index) {
            return sections[index];
        }
    }
}
