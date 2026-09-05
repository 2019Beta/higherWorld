package org.devt.higherworld.client.mixin;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import org.devt.higherworld.client.TerrainGeneratorDebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the server-authoritative terrain backend to the left F3 debug column. */
@Mixin(DebugHud.class)
abstract class DebugHudMixin {
    @Inject(method = "drawText", at = @At("HEAD"))
    private void higherworld$appendTerrainGenerator(
            DrawContext context, List<String> text, boolean left, CallbackInfo callbackInfo) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (left && client.debugHudEntryList.isF3Enabled()) {
            text.add(TerrainGeneratorDebugHud.line());
        }
    }
}
