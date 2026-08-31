package org.devt.higherworld.client.mixin;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.screen.world.LevelScreenProvider;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.gen.WorldPreset;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.client.CustomWorldScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers the custom editor so vanilla keeps the custom preset selected. */
@Mixin(LevelScreenProvider.class)
abstract class LevelScreenProviderMixin {
    @Shadow
    @Final
    @Mutable
    private static Map<Optional<RegistryKey<WorldPreset>>, LevelScreenProvider>
            WORLD_PRESET_TO_SCREEN_PROVIDER;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void higherworld$registerCustomWorldEditor(CallbackInfo callbackInfo) {
        Map<Optional<RegistryKey<WorldPreset>>, LevelScreenProvider> providers =
                new HashMap<>(WORLD_PRESET_TO_SCREEN_PROVIDER);
        providers.put(Optional.of(Higherworld.CUSTOM_WORLD), CustomWorldScreen::new);
        WORLD_PRESET_TO_SCREEN_PROVIDER = Map.copyOf(providers);
    }
}
