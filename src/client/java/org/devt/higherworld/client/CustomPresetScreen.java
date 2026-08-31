package org.devt.higherworld.client;

import com.google.gson.JsonParser;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import org.devt.higherworld.world.CustomWorldSettings;

/** Single-line JSON import/export screen for advanced preset editing. */
public final class CustomPresetScreen extends Screen {
    private final CustomWorldScreen parent;
    private final CustomWorldSettings draft;
    private CustomSettingsList settingsList;
    private TextFieldWidget jsonField;
    private String error;

    CustomPresetScreen(CustomWorldScreen parent, CustomWorldSettings draft) {
        super(Text.translatable("custom.preset.title"));
        this.parent = parent;
        this.draft = draft;
    }

    @Override
    protected void init() {
        int contentTop = 52;
        int footerTop = height - 42;
        int listWidth = Math.min(860, Math.max(300, width - 20));
        settingsList = new CustomSettingsList(client, listWidth,
                Math.max(40, footerTop - contentTop), contentTop, CustomSettingsList.ROW_HEIGHT);
        settingsList.setX((width - listWidth) / 2);
        addDrawableChild(settingsList);
        settingsList.addHeader(Text.translatable("custom.preset.json"));
        jsonField = new TextFieldWidget(textRenderer, 0, 0,
                Math.max(260, listWidth - 20), 20, Text.empty());
        jsonField.setMaxLength(1_000_000);
        jsonField.setText(JsonParser.parseString(draft.toJson()).toString());
        jsonField.setTooltip(Tooltip.of(Text.translatable("custom.preset.json.tooltip")));
        settingsList.addRow(Text.translatable("custom.preset.json"), jsonField);

        int gap = 4;
        int buttonWidth = Math.max(70, Math.min(100, (width - 40 - gap * 4) / 5));
        int total = buttonWidth * 5 + gap * 4;
        int left = (width - total) / 2;
        addDrawableChild(button("custom.preset.apply", "custom.preset.apply.tooltip",
                left, buttonWidth, this::apply));
        addDrawableChild(button("custom.preset.copy", "custom.preset.copy.tooltip",
                left + buttonWidth + gap, buttonWidth, this::copy));
        addDrawableChild(button("custom.preset.paste", "custom.preset.paste.tooltip",
                left + (buttonWidth + gap) * 2, buttonWidth, this::paste));
        addDrawableChild(button("custom.preset.reset_json", "custom.preset.reset_json.tooltip",
                left + (buttonWidth + gap) * 3, buttonWidth, this::resetJson));
        addDrawableChild(button("gui.cancel", "custom.cancel.tooltip",
                left + (buttonWidth + gap) * 4, buttonWidth, this::close));
    }

    private ButtonWidget button(String labelKey, String tooltipKey, int x, int buttonWidth,
                                Runnable action) {
        return ButtonWidget.builder(Text.translatable(labelKey), ignored -> action.run())
                .dimensions(x, height - 28, buttonWidth, 20)
                .tooltip(Tooltip.of(Text.translatable(tooltipKey)))
                .build();
    }

    private void apply() {
        try {
            CustomWorldSettings value = CustomWorldSettings.fromJson(jsonField.getText());
            parent.applyPreset(value);
            close();
        } catch (RuntimeException exception) {
            error = exception.getMessage() == null
                    ? Text.translatable("custom.error.invalid").getString() : exception.getMessage();
        }
    }

    private void copy() {
        if (client != null) {
            client.keyboard.setClipboard(jsonField.getText());
        }
        error = null;
    }

    private void paste() {
        if (client != null) {
            jsonField.setText(client.keyboard.getClipboard());
        }
        error = null;
    }

    private void resetJson() {
        jsonField.setText(JsonParser.parseString(CustomWorldSettings.customDefaults().toJson()).toString());
        error = null;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("custom.preset.title"), width / 2, 20, 0xFFFFFF);
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(error), width / 2,
                    height - 47, 0xFFFF5555);
        }
    }
}
