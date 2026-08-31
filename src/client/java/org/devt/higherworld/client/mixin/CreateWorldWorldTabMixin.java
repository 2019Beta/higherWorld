package org.devt.higherworld.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.tab.GridScreenTab;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.WorldPreset;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.client.StructureGenerationScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screen.world.CreateWorldScreen$WorldTab")
abstract class CreateWorldWorldTabMixin extends GridScreenTab {
    private static final RegistryKey<WorldPreset> HIGHERWORLD_INFINITE_DOWNWARD = RegistryKey.of(
            RegistryKeys.WORLD_PRESET, Identifier.of("higherworld", "infinite_downward"));

    private CreateWorldWorldTabMixin() {
        super(Text.empty());
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void higherworld$addStructureSettingsButton(
            CreateWorldScreen screen, CallbackInfo callbackInfo) {
        ButtonWidget button = ButtonWidget.builder(
                        Text.translatable("button.higherworld.structure_settings"),
                        ignored -> MinecraftClient.getInstance().setScreen(
                                new StructureGenerationScreen(screen)))
                .width(310)
                .tooltip(Tooltip.of(Text.translatable("button.higherworld.structure_settings.tooltip")))
                .build();
        button.active = higherworld$isInfiniteDownward(screen.getWorldCreator());
        screen.getWorldCreator().addListener(
                creator -> button.active = higherworld$isInfiniteDownward(creator));
        grid.add(button, 3, 0, 1, 2);
    }

    private static boolean higherworld$isInfiniteDownward(WorldCreator creator) {
        WorldCreator.WorldType type = creator.getWorldType();
        return type != null && type.preset() != null
                && (type.preset().matchesKey(HIGHERWORLD_INFINITE_DOWNWARD)
                        || type.preset().matchesKey(Higherworld.CUSTOM_WORLD));
    }
}
