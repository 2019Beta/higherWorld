package org.devt.higherworld.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import org.devt.higherworld.world.CustomWorldSettings;

/** Full editor for one configured lake entry and both probability curves. */
public final class CustomLakeEntryScreen extends Screen {
    private final CustomWorldScreen parent;
    private final int index;
    private final CustomWorldSettings.LakeSettings initial;
    private final BiConsumer<Integer, CustomWorldSettings.LakeSettings> onSave;
    private CustomWorldSettings.FilterType biomeSelect;
    private CustomSettingsList settingsList;
    private final Map<String, TextFieldWidget> fields = new LinkedHashMap<>();
    private String error;

    CustomLakeEntryScreen(CustomWorldScreen parent, int index,
                          CustomWorldSettings.LakeSettings initial,
                          BiConsumer<Integer, CustomWorldSettings.LakeSettings> onSave) {
        super(Text.translatable("custom.lake.editor.title"));
        this.parent = parent;
        this.index = index;
        this.initial = initial;
        this.biomeSelect = initial.biomeSelect();
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
        settingsList.addHeader(Text.translatable("custom.lake.editor.title"));
        CyclingButtonWidget<CustomWorldSettings.FilterType> select = CyclingButtonWidget.<CustomWorldSettings.FilterType>builder(
                        value -> Text.translatable("custom.lake.filter." + value.name().toLowerCase()),
                        biomeSelect)
                .values(List.of(CustomWorldSettings.FilterType.INCLUDE,
                        CustomWorldSettings.FilterType.EXCLUDE))
                .tooltip(value -> Tooltip.of(Text.translatable("custom.lake.field.filter.tooltip")))
                .build(Text.translatable("custom.lake.field.filter"),
                        (widget, value) -> biomeSelect = value);
        select.setWidth(230);
        settingsList.addRow(Text.translatable("custom.lake.field.filter"), select);
        addText("block", "custom.lake.field.block", initial.block());
        addText("biomes", "custom.lake.field.biomes",
                CustomSettingsParsers.formatCsv(initial.biomes()));
        addText("mainProbability", "custom.lake.field.main_probability",
                CustomSettingsParsers.formatCurve(initial.mainProbability()));
        addText("surfaceProbability", "custom.lake.field.surface_probability",
                CustomSettingsParsers.formatCurve(initial.surfaceProbability()));
    }

    private void addText(String id, String labelKey, String value) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, 0, 0, 230, 20, Text.empty());
        field.setMaxLength(1_000_000);
        field.setText(value);
        field.setTooltip(Tooltip.of(Text.translatable(labelKey + ".tooltip")));
        fields.put(id, field);
        settingsList.addRow(Text.translatable(labelKey), field);
    }

    private boolean commit() {
        try {
            String block = fields.get("block").getText().trim();
            List<String> biomeValues = CustomSettingsParsers.csv(fields.get("biomes").getText());
            Set<String> biomes = new LinkedHashSet<>(biomeValues);
            CustomWorldSettings.UserFunction main = CustomSettingsParsers.curve(
                    fields.get("mainProbability").getText());
            CustomWorldSettings.UserFunction surface = CustomSettingsParsers.curve(
                    fields.get("surfaceProbability").getText());
            CustomWorldSettings.LakeSettings parsed = new CustomWorldSettings.LakeSettings(
                    block, biomeSelect, biomes, surface, main);
            CustomWorldSettings.builder().lakes(List.of(parsed)).build();
            // Keep the immutable entry only after all validation has succeeded.
            fields.get("block").setEditableColor(0xE0E0E0);
            error = null;
            onSave.accept(index, parsed);
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
                Text.translatable("custom.lake.editor.title"), width / 2, 20, 0xFFFFFF);
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(error), width / 2,
                    height - 47, 0xFFFF5555);
        }
    }
}
