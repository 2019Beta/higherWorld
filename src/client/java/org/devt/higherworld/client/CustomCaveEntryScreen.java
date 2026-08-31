package org.devt.higherworld.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import org.devt.higherworld.world.CustomWorldSettings;

/** Full, non-collapsible editor for every configurable cave walker parameter. */
public final class CustomCaveEntryScreen extends Screen {
    private final CustomWorldScreen parent;
    private final int index;
    private final CustomWorldSettings.CaveSettings initial;
    private final BiConsumer<Integer, CustomWorldSettings.CaveSettings> onSave;
    private CustomSettingsList settingsList;
    private final Map<String, TextFieldWidget> fields = new LinkedHashMap<>();
    private String error;

    CustomCaveEntryScreen(CustomWorldScreen parent, int index,
                          CustomWorldSettings.CaveSettings initial,
                          BiConsumer<Integer, CustomWorldSettings.CaveSettings> onSave) {
        super(Text.translatable("custom.cave.editor.title"));
        this.parent = parent;
        this.index = index;
        this.initial = initial;
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        fields.clear();
        int contentTop = 52;
        int footerTop = height - 42;
        int listWidth = Math.min(760, Math.max(300, width - 20));
        settingsList = new CustomSettingsList(client, listWidth,
                Math.max(40, footerTop - contentTop), contentTop, CustomSettingsList.ROW_HEIGHT);
        settingsList.setX((width - listWidth) / 2);
        addDrawableChild(settingsList);
        buildForm();
        int buttonWidth = 120;
        int gap = 6;
        int left = (width - buttonWidth * 2 - gap) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> save())
                .dimensions(left, height - 28, buttonWidth, 20)
                .tooltip(Tooltip.of(Text.translatable("custom.done.tooltip"))).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), ignored -> close())
                .dimensions(left + buttonWidth + gap, height - 28, buttonWidth, 20)
                .tooltip(Tooltip.of(Text.translatable("custom.cancel.tooltip"))).build());
    }

    private void buildForm() {
        settingsList.addHeader(Text.translatable("custom.cave.editor.title"));
        addText("caveBlock", "custom.cave.field.cave_block", initial.caveBlock());
        addInteger("caveMinHeight", "custom.cave.field.cave_min_height", initial.caveMinHeight());
        addInteger("caveMaxHeight", "custom.cave.field.cave_max_height", initial.caveMaxHeight());
        addInteger("caveRarity", "custom.cave.field.cave_rarity", initial.caveRarity());
        addInteger("maxInitNodes", "custom.cave.field.max_init_nodes", initial.maxInitNodes());
        addInteger("largeNodeRarity", "custom.cave.field.large_node_rarity", initial.largeNodeRarity());
        addInteger("largeNodeMaxBranches", "custom.cave.field.large_node_max_branches", initial.largeNodeMaxBranches());
        addInteger("bigCaveRarity", "custom.cave.field.big_cave_rarity", initial.bigCaveRarity());
        addNumber("caveSizeAdd", "custom.cave.field.cave_size_add", initial.caveSizeAdd());
        addInteger("steepStepRarity", "custom.cave.field.steep_step_rarity", initial.steepStepRarity());
        addNumber("flattenFactor", "custom.cave.field.flatten_factor", initial.flattenFactor());
        addNumber("steeperFlattenFactor", "custom.cave.field.steeper_flatten_factor", initial.steeperFlattenFactor());
        addNumber("directionChangeFactor", "custom.cave.field.direction_change_factor", initial.directionChangeFactor());
        addNumber("prevHorizDirectionChangeWeight", "custom.cave.field.prev_horiz_direction_change_weight", initial.prevHorizDirectionChangeWeight());
        addNumber("prevVertDirectionChangeWeight", "custom.cave.field.prev_vert_direction_change_weight", initial.prevVertDirectionChangeWeight());
        addNumber("maxAddDirectionChangeHoriz", "custom.cave.field.max_add_direction_change_horiz", initial.maxAddDirectionChangeHoriz());
        addNumber("maxAddDirectionChangeVert", "custom.cave.field.max_add_direction_change_vert", initial.maxAddDirectionChangeVert());
        addInteger("carveStepRarity", "custom.cave.field.carve_step_rarity", initial.carveStepRarity());
        addNumber("caveFloorDepth", "custom.cave.field.cave_floor_depth", initial.caveFloorDepth());
        addText("isBlockReplaceable", "custom.cave.field.replaceable",
                CustomSettingsParsers.formatCsv(initial.isBlockReplaceable()));
    }

    private void addText(String id, String labelKey, String value) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, 0, 0, 230, 20, Text.empty());
        field.setMaxLength(1_000_000);
        field.setText(value);
        field.setTooltip(Tooltip.of(Text.translatable(labelKey + ".tooltip")));
        fields.put(id, field);
        settingsList.addRow(Text.translatable(labelKey), field);
    }

    private void addInteger(String id, String labelKey, int value) {
        addText(id, labelKey, Integer.toString(value));
    }

    private void addNumber(String id, String labelKey, double value) {
        addText(id, labelKey, CustomSettingsParsers.formatNumber(value));
    }

    private boolean commit() {
        try {
            CustomWorldSettings.CaveSettings parsed = new CustomWorldSettings.CaveSettings(
                    fields.get("caveBlock").getText().trim(),
                    integer("caveMinHeight"), integer("caveMaxHeight"), integer("caveRarity"),
                    integer("maxInitNodes"), integer("largeNodeRarity"), integer("largeNodeMaxBranches"),
                    integer("bigCaveRarity"), number("caveSizeAdd"), integer("steepStepRarity"),
                    number("flattenFactor"), number("steeperFlattenFactor"),
                    number("directionChangeFactor"), number("prevHorizDirectionChangeWeight"),
                    number("prevVertDirectionChangeWeight"), number("maxAddDirectionChangeHoriz"),
                    number("maxAddDirectionChangeVert"), integer("carveStepRarity"),
                    number("caveFloorDepth"), CustomSettingsParsers.csv(
                            fields.get("isBlockReplaceable").getText()));
            CustomWorldSettings.builder().caves(List.of(parsed)).build();
            onSave.accept(index, parsed);
            error = null;
            close();
            return true;
        } catch (RuntimeException exception) {
            for (TextFieldWidget field : fields.values()) {
                field.setEditableColor(0xFFFF5555);
            }
            error = exception.getMessage() == null
                    ? Text.translatable("custom.error.invalid").getString() : exception.getMessage();
            return false;
        }
    }

    private int integer(String id) {
        return CustomSettingsParsers.integer(fields.get(id).getText(), id);
    }

    private double number(String id) {
        return CustomSettingsParsers.finite(fields.get(id).getText(), id);
    }

    private void save() {
        commit();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("custom.cave.editor.title"), width / 2, 20, 0xFFFFFF);
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(error), width / 2,
                    height - 47, 0xFFFF5555);
        }
    }
}
