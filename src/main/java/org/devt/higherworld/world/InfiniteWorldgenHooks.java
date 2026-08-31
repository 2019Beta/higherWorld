package org.devt.higherworld.world;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

/** Identifies world-specific vanilla generators configured for infinite depth. */
public final class InfiniteWorldgenHooks {
    private static final Map<NoiseChunkGenerator, GeneratorRegistration> GENERATORS =
            new IdentityHashMap<>();

    private InfiniteWorldgenHooks() {
    }

    static synchronized void register(NoiseChunkGenerator generator, int bottomY) {
        GeneratorRegistration current = GENERATORS.get(generator);
        int references = current == null ? 1 : current.references() + 1;
        GENERATORS.put(generator, new GeneratorRegistration(bottomY, references));
    }

    static synchronized void unregister(NoiseChunkGenerator generator) {
        GeneratorRegistration current = GENERATORS.get(generator);
        if (current == null) {
            return;
        }
        if (current.references() <= 1) {
            GENERATORS.remove(generator);
        } else {
            GENERATORS.put(generator,
                    new GeneratorRegistration(current.bottomY(), current.references() - 1));
        }
    }

    public static synchronized boolean isInfinite(NoiseChunkGenerator generator) {
        return GENERATORS.containsKey(generator);
    }

    public static synchronized boolean isBelowVanillaFloor(
            NoiseChunkGenerator generator, int y) {
        GeneratorRegistration registration = GENERATORS.get(generator);
        return registration != null && y <= registration.bottomY();
    }

    private record GeneratorRegistration(int bottomY, int references) {
    }
}
