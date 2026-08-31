package org.devt.higherworld.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import org.devt.higherworld.world.CustomWorldSettings;

/** Full editor for one ordinary or periodic-Gaussian ore entry. */
public final class CustomOreEntryScreen extends Screen {
    public record Result(boolean periodic, boolean originalPeriodic, int index,
                         CustomWorldSettings.OreSettings settings) {
    }

    private final CustomWorldScreen parent;
    private final boolean originalPeriodic;
    private final int index;
    private final Consumer<Result> onSave;
    private CustomWorldSettings.OreSettings working;
    private boolean periodic;
    private CustomSettingsList settingsList;
    private final Map<String, TextFieldWidget> fields = new LinkedHashMap<>();
    private String error;

    CustomOreEntryScreen(CustomWorldScreen parent, boolean periodic, int index,
                         CustomWorldSettings.OreSettings initial, Consumer<Result> onSave) {
        super(Text.translatable("custom.ore.editor.title"));
        this.parent = parent;
        this.periodic = periodic;
        this.originalPeriodic = periodic;
        this.index = index;
        this.working = initial;
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
        settingsList.addHeader(Text.translatable("custom.ore.editor.title"));
        CyclingButtonWidget<Boolean> type = CyclingButtonWidget.<Boolean>builder(
                        value -> Text.translatable(value
                                ? "custom.ore.type.periodic" : "custom.ore.type.uniform"), periodic)
                .values(List.of(false, true))
                .tooltip(value -> Tooltip.of(Text.translatable("custom.ore.field.type.tooltip")))
                .build(Text.translatable("custom.ore.field.type"), (widget, value) -> {
                    if (!commit()) {
                        widget.setValue(periodic);
                        return;
                    }
                    working = convertedForType(working, value);
                    periodic = value;
                    error = null;
                    clearAndInit();
                });
        type.setWidth(230);
        settingsList.addRow(Text.translatable("custom.ore.field.type"), type);
        addText("blockstate", "custom.ore.field.blockstate", working.blockstate());
        addText("biomes", "custom.ore.field.biomes", CustomSettingsParsers.formatCsv(working.biomes()));
        addInteger("spawnSize", "custom.ore.field.spawn_size", working.spawnSize());
        addInteger("spawnTries", "custom.ore.field.spawn_tries", working.spawnTries());
        addNumber("spawnProbability", "custom.ore.field.spawn_probability", working.spawnProbability());
        addText("minHeight", "custom.ore.field.min_height",
                CustomSettingsParsers.formatBound(working.minHeight(), true));
        addText("maxHeight", "custom.ore.field.max_height",
                CustomSettingsParsers.formatBound(working.maxHeight(), false));
        if (periodic) {
            addNumber("heightMean", "custom.ore.field.height_mean", working.heightMean());
            addNumber("heightStdDeviation", "custom.ore.field.height_std_deviation",
                    working.heightStdDeviation());
            addNumber("heightSpacing", "custom.ore.field.height_spacing", working.heightSpacing());
        }
    }

    private CustomWorldSettings.OreSettings convertedForType(
            CustomWorldSettings.OreSettings source, boolean targetPeriodic) {
        if (targetPeriodic) {
            return new CustomWorldSettings.OreSettings(source.blockstate(),
                    source.biomes() == null ? null : new ArrayList<>(source.biomes()),
                    source.spawnSize(), source.spawnTries(), source.spawnProbability(),
                    source.minHeight(), source.maxHeight(), -.75, .11231704455, 3.0);
        }
        return new CustomWorldSettings.OreSettings(source.blockstate(),
                source.biomes() == null ? null : new ArrayList<>(source.biomes()),
                source.spawnSize(), source.spawnTries(), source.spawnProbability(),
                source.minHeight(), source.maxHeight(), 0.0, 0.0, 1.0);
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
            String blockstate = fields.get("blockstate").getText().trim();
            List<String> biomes = CustomSettingsParsers.csv(fields.get("biomes").getText());
            int spawnSize = CustomSettingsParsers.integer(fields.get("spawnSize").getText(), "spawnSize");
            int spawnTries = CustomSettingsParsers.integer(fields.get("spawnTries").getText(), "spawnTries");
            double probability = CustomSettingsParsers.finite(
                    fields.get("spawnProbability").getText(), "spawnProbability");
            double min = CustomSettingsParsers.bound(
                    fields.get("minHeight").getText(), true, "minHeight");
            double max = CustomSettingsParsers.bound(
                    fields.get("maxHeight").getText(), false, "maxHeight");
            double mean = periodic
                    ? CustomSettingsParsers.finite(fields.get("heightMean").getText(), "heightMean") : 0.0;
            double standardDeviation = periodic
                    ? CustomSettingsParsers.finite(fields.get("heightStdDeviation").getText(), "heightStdDeviation") : 0.0;
            double spacing = periodic
                    ? CustomSettingsParsers.finite(fields.get("heightSpacing").getText(), "heightSpacing") : 1.0;
            CustomWorldSettings.OreSettings parsed = new CustomWorldSettings.OreSettings(
                    blockstate, biomes.isEmpty() ? null : biomes, spawnSize, spawnTries,
                    probability, min, max, mean, standardDeviation, spacing);
            CustomWorldSettings.Builder validation = CustomWorldSettings.builder();
            if (periodic) {
                validation.periodicGaussianOres(List.of(parsed));
            } else {
                validation.standardOres(List.of(parsed));
            }
            validation.build();
            working = parsed;
            error = null;
            clearFieldColors();
            return true;
        } catch (RuntimeException exception) {
            markInvalid(exception);
            return false;
        }
    }

    private void save() {
        if (!commit()) return;
        try {
            onSave.accept(new Result(periodic, originalPeriodic, index, working));
            close();
        } catch (RuntimeException exception) {
            markInvalid(exception);
        }
    }

    private void markInvalid(RuntimeException exception) {
        for (TextFieldWidget field : fields.values()) {
            field.setEditableColor(0xFFFF5555);
        }
        error = exception.getMessage() == null
                ? Text.translatable("custom.error.invalid").getString() : exception.getMessage();
    }

    private void clearFieldColors() {
        for (TextFieldWidget field : fields.values()) {
            field.setEditableColor(0xE0E0E0);
        }
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("custom.ore.editor.title"), width / 2, 20, 0xFFFFFF);
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(error), width / 2,
                    height - 47, 0xFFFF5555);
        }
    }
}
