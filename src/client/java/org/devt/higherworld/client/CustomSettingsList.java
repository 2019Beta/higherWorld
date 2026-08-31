package org.devt.higherworld.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.text.Text;

/**
 * A small scrolling form container.  Each row owns its controls, so the
 * normal ElementListWidget scissor, mouse dispatch, focus and Tab navigation
 * all continue to work when a page contains more fields than fit on screen.
 */
final class CustomSettingsList extends ElementListWidget<CustomSettingsList.Row> {
    static final int ROW_HEIGHT = 28;

    CustomSettingsList(MinecraftClient client, int width, int height, int top, int bottom) {
        // In 1.21.11 the final constructor argument is item height, not the
        // bottom coordinate (the list's height is the third argument).
        super(client, width, height, top, bottom);
        centerListVertically = false;
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
            if (header) {
                context.drawTextWithShadow(
                        MinecraftClient.getInstance().textRenderer, label,
                        getX() + 4, getY() + 8, 0xFFE0E0E0);
            } else if (!label.equals(Text.empty())) {
                context.drawTextWithShadow(
                        MinecraftClient.getInstance().textRenderer, label,
                        getX() + 4, getY() + 8, 0xFFFFFFFF);
            }
            for (ClickableWidget widget : widgets) {
                widget.render(context, mouseX, mouseY, delta);
            }
        }

        private void layoutWidgets() {
            int right = getX() + getWidth() - 4;
            int rowY = getY() + 4;
            for (int index = widgets.size() - 1; index >= 0; index--) {
                ClickableWidget widget = widgets.get(index);
                right -= widget.getWidth();
                widget.setX(right);
                widget.setY(rowY);
                right -= 4;
            }
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
