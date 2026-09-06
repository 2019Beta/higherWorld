package org.devt.higherworld.world;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureKeys;
import org.devt.higherworld.storage.CubePos;

/** Places translated copies of vanilla-generated structure starts into sparse cubes. */
final class VanillaStructureGenerator {
    private static final int VERTICAL_PERIOD = 128;
    /**
     * {@code getStructureStarts} re-probes every registered structure for
     * every call, and a cube column commits its feature pass cube by cube.
     * The starts are treated read-only (each placement works on an NBT copy),
     * so one bounded per-world cache removes the repeated probing without
     * changing placement output.
     */
    private static final int MAX_START_CACHES = 4_096;
    private static final Map<ServerWorld, StartCache> START_CACHES = new java.util.concurrent.ConcurrentHashMap<>();

    private VanillaStructureGenerator() {
    }

    static void release(ServerWorld world) {
        START_CACHES.remove(world);
    }

    static void generate(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings settings) {
        generate(world, cube, settings, null);
    }

    /**
     * Copies vanilla structure starts for a custom world.  The registry key is
     * deliberately classified by its path instead of depending on optional
     * StructureKeys constants: this keeps the custom flags stable when Mojang
     * adds another structure family in a later mapping.
     */
    static void generate(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings settings,
            CustomWorldSettings customSettings) {
        CubePos cubePos = cube.pos();
        ChunkPos chunkPos = new ChunkPos(cubePos.x(), cubePos.z());
        Registry<Structure> registry = world.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);
        List<StructureStart> starts = START_CACHES.computeIfAbsent(
                world, ignored -> new StartCache()).starts(
                        world, chunkPos, registry, settings, customSettings);
        if (starts.isEmpty()) {
            return;
        }

        BlockBox cubeBox = new BlockBox(
                cubePos.minBlockX(), cubePos.minBlockY(), cubePos.minBlockZ(),
                cubePos.minBlockX() + CubePos.SIZE - 1,
                cubePos.minBlockY() + CubePos.SIZE - 1,
                cubePos.minBlockZ() + CubePos.SIZE - 1);
        StructureWorldAccess access = cubeAccess(world, cube, cubeBox);
        StructureContext context = StructureContext.from(world);

        for (StructureStart source : starts) {
            if (!source.hasChildren()) {
                continue;
            }
            BlockBox sourceBox = source.getBoundingBox();
            int first = Math.max(1, ceilDiv(sourceBox.getMinY() - cubeBox.getMaxY(), VERTICAL_PERIOD));
            int last = Math.floorDiv(sourceBox.getMaxY() - cubeBox.getMinY(), VERTICAL_PERIOD);
            for (int repetition = first; repetition <= last; repetition++) {
                int offsetY = -repetition * VERTICAL_PERIOD
                        + strongholdPhase(chunkPos, source, registry, customSettings, repetition);
                StructureStart copy = copy(source, context, world.getSeed());
                if (copy == null) {
                    continue;
                }
                for (StructurePiece piece : copy.getChildren()) {
                    piece.translate(0, offsetY, 0);
                }
                if (!copy.getBoundingBox().intersects(cubeBox)) {
                    continue;
                }
                long randomSeed = world.getSeed()
                        ^ chunkPos.toLong()
                        ^ (long) repetition * 0x9E3779B97F4A7C15L;
                copy.place(access, world.getStructureAccessor(),
                        world.getChunkManager().getChunkGenerator(), Random.create(randomSeed),
                        cubeBox, chunkPos);
            }
        }
    }

    private static StructureStart copy(StructureStart source, StructureContext context, long seed) {
        NbtCompound nbt = source.toNbt(context, source.getPos());
        StructureStart copy = StructureStart.fromNbt(context, nbt, seed);
        return copy == null || copy == StructureStart.DEFAULT ? null : copy;
    }

    private static boolean isEnabled(
            Registry<Structure> registry, Structure structure,
            StructureGenerationSettings settings) {
        return isEnabled(registry, structure, settings, null);
    }

    private static boolean isEnabled(
            Registry<Structure> registry, Structure structure,
            StructureGenerationSettings settings, CustomWorldSettings customSettings) {
        Optional<RegistryKey<Structure>> key = registry.getKey(structure);
        if (key.isEmpty()) {
            return false;
        }
        RegistryKey<Structure> value = key.get();
        String path = value.getValue().getPath();
        if (isMineshaft(path)) {
            return settings.enables(UndergroundStructure.MINESHAFT)
                    && (customSettings == null || customSettings.mineshafts());
        }
        if ("stronghold".equals(path)) {
            return settings.enables(UndergroundStructure.STRONGHOLD)
                    && (customSettings == null || customSettings.strongholds());
        }
        if ("ancient_city".equals(path)) {
            return settings.enables(UndergroundStructure.ANCIENT_CITY);
        }
        if ("trial_chambers".equals(path)) {
            return settings.enables(UndergroundStructure.TRIAL_CHAMBERS);
        }
        if (customSettings == null) {
            return false;
        }
        if (isVillage(path)) {
            return customSettings.villages();
        }
        if (isTemple(path)) {
            return customSettings.temples();
        }
        if ("monument".equals(path) || "ocean_monument".equals(path)) {
            return customSettings.oceanMonuments();
        }
        if ("mansion".equals(path) || "woodland_mansion".equals(path)) {
            return customSettings.woodlandMansions();
        }
        return false;
    }

    static boolean isMineshaft(String path) {
        return "mineshaft".equals(path) || "mineshaft_mesa".equals(path);
    }

    static boolean isVillage(String path) {
        return "village".equals(path) || path.startsWith("village_");
    }

    static boolean isTemple(String path) {
        return "desert_pyramid".equals(path) || "jungle_pyramid".equals(path)
                || "swamp_hut".equals(path) || "igloo".equals(path)
                || "ocean_ruin".equals(path) || path.startsWith("ocean_ruin_");
    }

    private static int strongholdPhase(
            ChunkPos chunkPos, StructureStart source, Registry<Structure> registry,
            CustomWorldSettings customSettings, int repetition) {
        if (customSettings == null || !customSettings.alternateStrongholdsPositions()) {
            return 0;
        }
        Optional<RegistryKey<Structure>> key = registry.getKey(source.getStructure());
        if (key.isEmpty() || !"stronghold".equals(key.get().getValue().getPath())) {
            return 0;
        }
        long parity = (long) chunkPos.x * 0x9E3779B97F4A7C15L
                ^ (long) chunkPos.z * 0xC2B2AE3D27D4EB4FL
                ^ repetition;
        return (parity & 1L) == 0L ? 0 : VERTICAL_PERIOD / 2;
    }

    private static StructureWorldAccess cubeAccess(
            ServerWorld world, LoadedCube cube, BlockBox cubeBox) {
        Map<Long, Chunk> structureChunks = new HashMap<>();
        return (StructureWorldAccess) Proxy.newProxyInstance(
                VanillaStructureGenerator.class.getClassLoader(),
                new Class<?>[] {StructureWorldAccess.class},
                (proxy, method, arguments) -> invoke(
                        world, cube, cubeBox, structureChunks, method, arguments));
    }

    private static Object invoke(
            ServerWorld world, LoadedCube cube, BlockBox cubeBox,
            Map<Long, Chunk> structureChunks,
            Method method, Object[] arguments) throws Throwable {
        String name = method.getName();
        if ("getChunk".equals(name) && arguments != null && arguments.length >= 1
                && arguments[0] instanceof BlockPos pos) {
            return structureChunk(world, cube, structureChunks,
                    Math.floorDiv(pos.getX(), CubePos.SIZE),
                    Math.floorDiv(pos.getZ(), CubePos.SIZE));
        }
        if (("getChunk".equals(name) || "getChunkAsView".equals(name))
                && arguments != null && arguments.length >= 2
                && arguments[0] instanceof Integer chunkX
                && arguments[1] instanceof Integer chunkZ) {
            return structureChunk(world, cube, structureChunks, chunkX, chunkZ);
        }
        if ("setBlockState".equals(name) && arguments != null
                && arguments.length >= 2 && arguments[0] instanceof BlockPos pos
                && arguments[1] instanceof BlockState state) {
            return setBlockState(world, cube, cubeBox, pos, state);
        }
        if ("getBlockState".equals(name) && firstPos(arguments) instanceof BlockPos pos) {
            return cubeBox.contains(pos)
                    ? cube.getBlockState(pos)
                    : net.minecraft.block.Blocks.AIR.getDefaultState();
        }
        if ("getFluidState".equals(name) && firstPos(arguments) instanceof BlockPos pos) {
            return cubeBox.contains(pos)
                    ? cube.getFluidState(pos)
                    : Fluids.EMPTY.getDefaultState();
        }
        if ("getBlockEntity".equals(name) && firstPos(arguments) instanceof BlockPos pos) {
            BlockEntity blockEntity = cubeBox.contains(pos) ? cube.getBlockEntity(pos) : null;
            if (arguments.length == 2) {
                return blockEntity != null
                        && arguments[1] instanceof net.minecraft.block.entity.BlockEntityType<?> type
                        && blockEntity.getType() == type
                        ? Optional.of(blockEntity) : Optional.empty();
            }
            return blockEntity;
        }
        if (("removeBlock".equals(name) || "breakBlock".equals(name))
                && firstPos(arguments) instanceof BlockPos pos && cubeBox.contains(pos)) {
            return setBlockState(
                    world, cube, cubeBox, pos, net.minecraft.block.Blocks.AIR.getDefaultState());
        }
        if (("isValidForSetBlock".equals(name) || "isInBuildLimit".equals(name)
                || "isInLoadLimit".equals(name)) && firstPos(arguments) instanceof BlockPos pos) {
            return cubeBox.contains(pos);
        }
        if (("markBlockForPostProcessing".equals(name) || "markForPostProcessing".equals(name))
                || "scheduleBlockTick".equals(name)
                || "scheduleFluidTick".equals(name)
                || "scheduleTick".equals(name)) {
            // Sparse cubes do not have vanilla post-processing or tick lists.
            // Forwarding these calls mutates the unrelated ServerWorld and emits
            // hundreds of warnings while a cube is being generated.
            return null;
        }
        if ("spawnEntity".equals(name)) {
            // Entities need a translated sparse-cube tracker. Never spawn a
            // structure entity at the proxy's untranslated coordinates.
            return false;
        }
        if ("getBottomY".equals(name)) {
            return cubeBox.getMinY();
        }
        if ("getHeight".equals(name)) {
            return CubePos.SIZE;
        }
        if ("getTopYInclusive".equals(name)) {
            return cubeBox.getMaxY();
        }
        if ("getBottomSectionCoord".equals(name)) {
            return cube.pos().y();
        }
        if ("getTopSectionCoord".equals(name)) {
            return cube.pos().y() + 1;
        }
        if ("countVerticalSections".equals(name)) {
            return 1;
        }
        if ("isInHeightLimit".equals(name) && arguments != null && arguments.length == 1) {
            int y = arguments[0] instanceof BlockPos pos ? pos.getY() : (int) arguments[0];
            return y >= cubeBox.getMinY() && y <= cubeBox.getMaxY();
        }
        if ("isOutOfHeightLimit".equals(name) && arguments != null && arguments.length == 1) {
            int y = arguments[0] instanceof BlockPos pos ? pos.getY() : (int) arguments[0];
            return y < cubeBox.getMinY() || y > cubeBox.getMaxY();
        }
        try {
            return method.invoke(world, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Object firstPos(Object[] arguments) {
        return arguments == null || arguments.length == 0 ? null : arguments[0];
    }

    private static boolean setBlockState(
            ServerWorld world, LoadedCube cube, BlockBox cubeBox,
            BlockPos pos, BlockState state) {
        if (!cubeBox.contains(pos)) {
            return false;
        }
        BlockState previous = cube.getBlockState(pos);
        cube.setGeneratedBlockState(
                pos.getX() - cube.pos().minBlockX(),
                pos.getY() - cube.pos().minBlockY(),
                pos.getZ() - cube.pos().minBlockZ(), state);
        if (previous.hasBlockEntity() && !state.hasBlockEntity()) {
            cube.removeBlockEntity(pos);
        }
        if (state.hasBlockEntity() && cube.getBlockEntity(pos) == null
                && state.getBlock() instanceof BlockEntityProvider provider) {
            BlockEntity blockEntity = provider.createBlockEntity(pos, state);
            if (blockEntity != null) {
                blockEntity.setWorld(world);
                cube.putLoadedBlockEntity(blockEntity);
            }
        }
        return previous != state;
    }

    private static Chunk structureChunk(
            ServerWorld world, LoadedCube cube, Map<Long, Chunk> chunks,
            int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        return chunks.computeIfAbsent(key, ignored -> {
            HeightLimitView height = HeightLimitView.create(cube.pos().minBlockY(), CubePos.SIZE);
            ProtoChunk chunk = new ProtoChunk(
                    new ChunkPos(chunkX, chunkZ), UpgradeData.NO_UPGRADE_DATA,
                    height, world.getPalettesFactory(), null);
            if (chunkX == cube.pos().x() && chunkZ == cube.pos().z()) {
                chunk.getSectionArray()[0] = cube.section();
            }
            return chunk;
        });
    }

    private static int ceilDiv(int dividend, int divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }

    /** Access-order LRU of read-only structure-start lists for one world. */
    private static final class StartCache {
        private final Map<ChunkPos, List<StructureStart>> starts =
                new java.util.LinkedHashMap<>(64, 0.75f, true);

        private synchronized List<StructureStart> starts(
                ServerWorld world, ChunkPos chunkPos, Registry<Structure> registry,
                StructureGenerationSettings settings, CustomWorldSettings customSettings) {
            List<StructureStart> existing = starts.get(chunkPos);
            if (existing != null) return existing;
            List<StructureStart> computed = world.getStructureAccessor().getStructureStarts(
                    chunkPos, structure -> isEnabled(registry, structure, settings, customSettings));
            starts.put(chunkPos, computed);
            while (starts.size() > MAX_START_CACHES) {
                starts.remove(starts.keySet().iterator().next());
            }
            return computed;
        }
    }
}
