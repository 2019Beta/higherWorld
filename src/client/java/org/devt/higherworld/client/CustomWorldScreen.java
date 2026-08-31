package org.devt.higherworld.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import org.devt.higherworld.world.CustomWorldSettings;

/** Small, dependency-free editor for the custom world's vertical generation range. */
public final class CustomWorldScreen extends Screen {
    private static final Text TITLE = Text.translatable("screen.higherworld.custom.title");
    private static final Text DESCRIPTION = Text.translatable("screen.higherworld.custom.description");

    private final Screen parent;
    private CustomWorldSettings draft;

    public CustomWorldScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
        this.draft = CustomWorldSettings.clientSelection();
    }

    @Override
    protected void init() {
        CyclingButtonWidget<Integer> depthButton = CyclingButtonWidget.<Integer>builder(
                        CustomWorldSettings::depthText, draft.generationDepth())
                .values(CustomWorldSettings.depthOptions())
                .tooltip(value -> Tooltip.of(Text.translatable(
                        "option.higherworld.generation_depth.tooltip")))
                .build(Text.translatable("option.higherworld.generation_depth"),
                        (button, value) -> draft = draft.withGenerationDepth(value));
        depthButton.setPosition(width / 2 - 155, 74);
        depthButton.setWidth(310);
        addDrawableChild(depthButton);

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> saveAndClose())
                .dimensions(width / 2 - 154, height - 28, 150, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
                .dimensions(width / 2 + 4, height - 28, 150, 20)
                .build());
    }

    private void saveAndClose() {
        CustomWorldSettings.setClientSelection(draft);
        close();
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, TITLE, width / 2, 20, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, DESCRIPTION, width / 2, 43, 0xA0A0A0);
    }
}
