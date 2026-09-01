package org.devt.higherworld.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * A small scrolling form container.  Each row owns its controls, so the
 * normal ElementListWidget scissor, mouse dispatch, focus and Tab navigation
 * all continue to work when a page contains more fields than fit on screen.
 */
final class CustomSettingsList extends ElementListWidget<CustomSettingsList.Row> {
    static final int ROW_HEIGHT = 32;
    private static final int ROW_PADDING = 4;
    private static final int LABEL_MIN_WIDTH = 120;
    private static final int EDITOR_MIN_WIDTH = 120;
    private static final int EDITOR_MAX_WIDTH = 360;
    private static final int EDITOR_GROUP_MAX_WIDTH = 560;
    private static final int NUMBER_FIELD_WIDTH = 88;
    private static final int COLUMN_GAP = 4;

    CustomSettingsList(MinecraftClient client, int width, int height, int top, int bottom) {
        // In 1.21.11 the final constructor argument is item height, not the
        // bottom coordinate (the list's height is the third argument).
        super(client, width, height, top, bottom);
        centerListVertically = false;
    }

    @Override
    public int getRowWidth() {
        // EntryListWidget defaults every row to a hard-coded 220 pixels.  The
        // outer list could grow, but its controls therefore stayed squeezed in
        // the middle.  Let rows consume the list width while reserving room for
        // the scrollbar and a small visual gutter.
        return Math.max(1, getWidth() - 24);
    }

    Row addRow(Text label, ClickableWidget... widgets) {
        Row row = new Row(label, false, widgets);
        addEntry(row);
        return row;
    }

    Row addCheckboxRow(ClickableWidget checkbox) {
        Row row = new Row(Text.empty(), false, checkbox);
        addEntry(row);
        return row;
    }

    Row addHeader(Text label) {
        Row row = new Row(label, true);
        addEntry(row, ROW_HEIGHT);
        return row;
    }

    static final class Row extends ElementListWidget.Entry<Row> {
        private final Text label;
        private final boolean header;
        private final List<ClickableWidget> widgets;

        Row(Text label, boolean header, ClickableWidget... widgets) {
            this.label = label;
            this.header = header;
            this.widgets = List.of(widgets);
        }

        @Override
        public void render(
                DrawContext context, int mouseX, int mouseY,
                boolean hovered, float delta) {
            layoutWidgets();
            TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
            if (header) {
                drawLabel(context, textRenderer, getX() + ROW_PADDING,
                        getX() + getWidth() - ROW_PADDING, 0xFFE0E0E0);
            } else if (!label.getString().isEmpty()) {
                int contentLeft = getX() + ROW_PADDING;
                int labelRight = widgets.isEmpty()
                        ? getX() + getWidth() - ROW_PADDING
                        : widgets.get(0).getX() - COLUMN_GAP;
                drawLabel(context, textRenderer, contentLeft, labelRight, 0xFFFFFFFF);
            }
            for (ClickableWidget widget : widgets) {
                widget.render(context, mouseX, mouseY, delta);
            }
        }

        private void layoutWidgets() {
            int contentLeft = getX() + ROW_PADDING;
            int contentRight = getX() + getWidth() - ROW_PADDING;
            int contentWidth = Math.max(0, contentRight - contentLeft);

            // Text fields and cycling buttons share a flexible editor column.
            // Keeping a 120px label reservation prevents their rectangles from
            // ever covering the setting name on normal and narrow layouts.
            if (widgets.size() == 1
                    && (widgets.get(0) instanceof TextFieldWidget
                    || widgets.get(0) instanceof CyclingButtonWidget<?>)) {
                widgets.get(0).setWidth(editorWidth(contentWidth));
            } else if (widgets.size() == 2
                    && widgets.get(0) instanceof SliderWidget
                    && widgets.get(1) instanceof TextFieldWidget) {
                int available = contentWidth >= LABEL_MIN_WIDTH + COLUMN_GAP + 100
                        ? contentWidth - LABEL_MIN_WIDTH - COLUMN_GAP
                        : Math.max(1, Math.round(contentWidth * 0.62f));
                int groupWidth = Math.min(EDITOR_GROUP_MAX_WIDTH, available);
                int fieldWidth = Math.min(NUMBER_FIELD_WIDTH,
                        Math.max(48, groupWidth / 3));
                widgets.get(1).setWidth(fieldWidth);
                widgets.get(0).setWidth(Math.max(1,
                        groupWidth - fieldWidth - COLUMN_GAP));
            }

            if (widgets.isEmpty()) {
                return;
            }
            int widgetsWidth = 0;
            for (ClickableWidget widget : widgets) {
                widgetsWidth += widget.getWidth();
            }
            widgetsWidth += COLUMN_GAP * Math.max(0, widgets.size() - 1);
            if (widgetsWidth > contentWidth) {
                int widthEach = Math.max(1,
                        (contentWidth - COLUMN_GAP * Math.max(0, widgets.size() - 1))
                                / widgets.size());
                for (ClickableWidget widget : widgets) {
                    widget.setWidth(widthEach);
                }
                widgetsWidth = widthEach * widgets.size()
                        + COLUMN_GAP * Math.max(0, widgets.size() - 1);
            }
            int widgetStart = contentRight - widgetsWidth;
            int rowHeight = getHeight();
            int checkboxY = getY() + Math.max(0, (rowHeight - widgets.get(0).getHeight()) / 2);
            if (widgets.size() == 1 && widgets.get(0) instanceof CheckboxWidget) {
                ClickableWidget checkbox = widgets.get(0);
                checkbox.setWidth(Math.min(checkbox.getWidth(), contentWidth));
                checkbox.setX(contentLeft);
                checkbox.setY(checkboxY);
                return;
            }

            int widgetX = widgetStart;
            for (ClickableWidget widget : widgets) {
                widget.setX(widgetX);
                widget.setY(getY() + Math.max(0, (rowHeight - widget.getHeight()) / 2));
                widgetX += widget.getWidth() + COLUMN_GAP;
            }
        }

        private int editorWidth(int contentWidth) {
            int preferred = Math.round(contentWidth * 0.45f);
            if (contentWidth >= LABEL_MIN_WIDTH + COLUMN_GAP + EDITOR_MIN_WIDTH) {
                return Math.min(EDITOR_MAX_WIDTH, Math.max(EDITOR_MIN_WIDTH, preferred));
            }
            // At very narrow widths the label may be clipped, but the editor
            // remains inside the row and never overlaps it.
            return Math.max(1, Math.min(EDITOR_MIN_WIDTH, contentWidth - COLUMN_GAP));
        }

        private void drawLabel(DrawContext context, TextRenderer textRenderer,
                               int left, int right, int color) {
            int maxWidth = Math.max(0, right - left);
            if (maxWidth == 0 || label.getString().isEmpty()) {
                return;
            }
            String raw = label.getString();
            String visible = textRenderer.trimToWidth(raw, maxWidth);
            if (textRenderer.getWidth(raw) > maxWidth) {
                String ellipsis = "...";
                int ellipsisWidth = textRenderer.getWidth(ellipsis);
                if (maxWidth > ellipsisWidth) {
                    visible = textRenderer.trimToWidth(raw, maxWidth - ellipsisWidth) + ellipsis;
                }
            }
            int baseline = getY() + Math.max(0, (getHeight() - 9) / 2);
            context.drawTextWithShadow(textRenderer, visible, left, baseline, color);
        }

        @Override
        public List<? extends Element> children() {
            return widgets;
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return new ArrayList<>(widgets);
        }
    }
}
