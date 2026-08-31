package org.devt.higherworld.client.mixin;

import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import org.devt.higherworld.world.CustomWorldSettings;
import org.devt.higherworld.world.StructureGenerationSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
abstract class CreateWorldScreenMixin {
    @Inject(method = "createLevel", at = @At("HEAD"))
    private void higherworld$captureStructureSelection(CallbackInfo callbackInfo) {
        StructureGenerationSettings.markWorldCreationStarted();
        CustomWorldSettings.markWorldCreationStarted();
    }
}
