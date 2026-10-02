package org.devt.higherworld.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Shared setup and validation feedback for the custom entry form screens. */
final class CustomEntryFormSupport {
    private static final int DEFAULT_EDITABLE_COLOR = 0xE0E0E0;
    private static final int INVALID_EDITABLE_COLOR = 0xFFFF5555;
    private static final int MAX_FIELD_LENGTH = 1_000_000;

    private CustomEntryFormSupport() {
    }

    static TextFieldWidget createTextField(TextRenderer textRenderer, String labelKey, String value) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, 0, 0, 230, 20, Text.empty());
        field.setMaxLength(MAX_FIELD_LENGTH);
        field.setText(value);
        field.setTooltip(Tooltip.of(Text.translatable(labelKey + ".tooltip")));
        return field;
    }

    static String markInvalid(Iterable<TextFieldWidget> fields, RuntimeException exception) {
        for (TextFieldWidget field : fields) {
            field.setEditableColor(INVALID_EDITABLE_COLOR);
        }
        return exception.getMessage() == null
                ? Text.translatable("custom.error.invalid").getString() : exception.getMessage();
    }

    static void clearFieldColors(Iterable<TextFieldWidget> fields) {
        for (TextFieldWidget field : fields) {
            field.setEditableColor(DEFAULT_EDITABLE_COLOR);
        }
    }
}
