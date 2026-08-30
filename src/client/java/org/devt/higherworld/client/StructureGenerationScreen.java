package org.devt.higherworld.client;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.text.Text;
import org.devt.higherworld.world.StructureGenerationSettings;
import org.devt.higherworld.world.UndergroundStructure;

/** Per-world checklist opened from the World tab of Create World. */
public final class StructureGenerationScreen extends Screen {
    private static final Text TITLE = Text.translatable("screen.higherworld.structures.title");
    private static final Text DESCRIPTION = Text.translatable("screen.higherworld.structures.description");
    private final Screen parent;
    private final EnumSet<UndergroundStructure> draft;
    private final Map<UndergroundStructure, CheckboxWidget> checkboxes =
            new EnumMap<>(UndergroundStructure.class);

    public StructureGenerationScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
        this.draft = StructureGenerationSettings.clientSelection();
    }

    @Override
    protected void init() {
        checkboxes.clear();
        int left = width / 2 - 155;
        int top = 72;
        int columnWidth = 150;
        int index = 0;
        for (UndergroundStructure structure : UndergroundStructure.values()) {
            int x = left + index % 2 * 160;
            int y = top + index / 2 * 32;
            CheckboxWidget checkbox = CheckboxWidget.builder(structure.displayName(), textRenderer)
                    .pos(x, y)
                    .maxWidth(columnWidth)
                    .checked(draft.contains(structure))
                    .tooltip(Tooltip.of(Text.translatable(
                            "structure.higherworld." + structure.id() + ".description")))
                    .callback((widget, checked) -> {
                        if (checked) {
                            draft.add(structure);
                        } else {
                            draft.remove(structure);
                        }
                    })
                    .build();
            checkboxes.put(structure, addDrawableChild(checkbox));
            index++;
        }

        int buttonY = height - 28;
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> saveAndClose())
                .dimensions(width / 2 - 154, buttonY, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
                .dimensions(width / 2 + 4, buttonY, 150, 20).build());
    }

    private void saveAndClose() {
        StructureGenerationSettings.setClientSelection(draft);
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
