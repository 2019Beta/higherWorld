package org.devt.higherworld.world;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import org.devt.higherworld.Higherworld;

/** Resolves user supplied block-state identifiers without allowing bad presets to crash a server. */
final class CustomBlockStateResolver {
    private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private CustomBlockStateResolver() {
    }

    static BlockState resolve(ServerWorld world, String specification) {
        if (specification == null || specification.isBlank()) {
            warn("blank", "blank block state identifier");
            return null;
        }
        try {
            int bracket = specification.indexOf('[');
            String idText = bracket < 0 ? specification : specification.substring(0, bracket);
            if (idText.isBlank() || (bracket >= 0 && !specification.endsWith("]"))) {
                warn(specification, "invalid block state syntax");
                return null;
            }
            Identifier id = idText.indexOf(':') >= 0
                    ? Identifier.of(idText)
                    : Identifier.of("minecraft", idText);
            Block block = Registries.BLOCK.getOptionalValue(id).orElse(null);
            if (block == null) {
                warn(specification, "unknown block " + id);
                return null;
            }
            BlockState state = block.getDefaultState();
            if (bracket < 0) {
                return state;
            }
            String properties = specification.substring(bracket + 1, specification.length() - 1);
            if (properties.isBlank()) {
                warn(specification, "empty block state properties");
                return null;
            }
            for (String entry : properties.split(",", -1)) {
                String[] pair = entry.split("=", -1);
                if (pair.length != 2 || !PROPERTY_NAME.matcher(pair[0]).matches()
                        || !PROPERTY_NAME.matcher(pair[1]).matches()) {
                    warn(specification, "invalid block state property " + entry);
                    return null;
                }
                Property<?> property = block.getStateManager().getProperty(pair[0]);
                if (property == null) {
                    warn(specification, "unknown block property " + pair[0]);
                    return null;
                }
                state = withProperty(state, property, pair[1], specification);
                if (state == null) {
                    return null;
                }
            }
            return state;
        } catch (RuntimeException exception) {
            warn(specification, "invalid block state identifier: " + exception.getMessage());
            return null;
        }
    }

    static List<BlockState> resolveAll(ServerWorld world, List<String> specifications) {
        if (specifications == null) {
            return List.of();
        }
        java.util.ArrayList<BlockState> states = new java.util.ArrayList<>(specifications.size());
        for (String specification : specifications) {
            BlockState state = resolve(world, specification);
            if (state == null) {
                return null;
            }
            states.add(state);
        }
        return List.copyOf(states);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withProperty(
            BlockState state, Property property, String value, String specification) {
        java.util.Optional parsed = property.parse(value);
        if (parsed.isEmpty()) {
            warn(specification, "invalid value " + value + " for property " + property.getName());
            return null;
        }
        return state.with(property, (Comparable) parsed.get());
    }

    private static void warn(String key, String message) {
        if (WARNED.add(key)) {
            Higherworld.LOGGER.warn("Skipping custom world block state '{}': {}", key, message);
        }
    }
}
