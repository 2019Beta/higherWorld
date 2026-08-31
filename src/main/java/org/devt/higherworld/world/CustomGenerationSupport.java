package org.devt.higherworld.world;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import org.devt.higherworld.Higherworld;

/** Small shared helpers for the order-independent custom feature generators. */
final class CustomGenerationSupport {
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private CustomGenerationSupport() {
    }

    static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            Higherworld.LOGGER.warn("{}", message);
        }
    }

    static String biomeId(ServerWorld world, BlockPos pos, String fixedBiome) {
        if (fixedBiome != null) {
            return normalizeBiomeId(fixedBiome);
        }
        RegistryEntry<Biome> entry = world.getBiome(pos);
        return entry.getKey().map(key -> key.getValue().toString()).orElse("");
    }

    /** Explicit aliases used by the 1.12 defaults whose modern biome was renamed. */
    static String normalizeBiomeId(String value) {
        String id = value.indexOf(':') >= 0 ? value : "minecraft:" + value;
        return switch (id) {
            case "minecraft:desert_hills", "minecraft:mutated_desert" -> "minecraft:desert";
            case "minecraft:extreme_hills", "minecraft:mutated_extreme_hills" -> "minecraft:windswept_hills";
            case "minecraft:mesa", "minecraft:mesa_bryce", "minecraft:mutated_badlands" -> "minecraft:badlands";
            default -> id;
        };
    }

    static boolean biomeMatches(
            ServerWorld world, BlockPos pos, Set<String> configured, String fixedBiome) {
        if (configured == null) {
            return true;
        }
        String actual = biomeId(world, pos, fixedBiome);
        for (String value : configured) {
            String normalized = normalizeBiomeId(value);
            if (normalized.equals(actual)) {
                return true;
            }
        }
        return false;
    }
}
