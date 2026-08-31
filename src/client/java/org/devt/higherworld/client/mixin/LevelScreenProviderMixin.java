package org.devt.higherworld.client.mixin;

import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.client.CustomWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;

/** Registers the custom editor so vanilla keeps the custom preset selected. */
@Mixin(LevelScreenProvider.class)
interface LevelScreenProviderMixin {
    /**
     * LevelScreenProvider is an interface in 1.21.11, so its map is initialized
     * by Map.of in the interface's clinit. Redirect that construction instead
     * of trying to mutate the final map after initialization.
     */
    @Redirect(
            method = "<clinit>",
            at = @At(value = "INVOKE", target =
                    "Ljava/util/Map;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"))
    private static Map<Object, Object> higherworld$registerCustomWorldEditor(
            Object firstKey, Object firstProvider, Object secondKey, Object secondProvider) {
        return Map.of(
                firstKey, firstProvider,
                secondKey, secondProvider,
                Optional.of(Higherworld.CUSTOM_WORLD),
                (LevelScreenProvider) (screen, holder) -> new CustomWorldScreen(screen));
    }
}
