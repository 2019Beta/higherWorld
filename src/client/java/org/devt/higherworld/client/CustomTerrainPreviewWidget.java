package org.devt.higherworld.client;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import org.devt.higherworld.world.CustomTerrainPreview;
import org.devt.higherworld.world.CustomWorldSettings;

/**
 * Cached X/Y cross-section of the custom terrain equation.  It intentionally
 * samples through the public world facade so the preview cannot drift from
 * server generation when the terrain formula changes.
 */
public final class CustomTerrainPreviewWidget extends ClickableWidget {
    public static final long PREVIEW_SEED = 0x484947484552574CL;
    // This is an editing aid rather than a block-accurate map.  A deliberately
    // coarse grid keeps both terrain-noise evaluation and per-frame GUI draw
    // calls cheap while still showing the overall terrain silhouette.
    private static final int SAMPLE_WIDTH = 48;
    private static final int SAMPLE_HEIGHT = 32;
    private static final double X_MIN = -256.0;
    private static final double X_MAX = 256.0;
    private static final long DEBOUNCE_NANOS = 150_000_000L;
    private static final int FRAME_COLOR = 0xFFE0E0E0;

    private final Supplier<CustomWorldSettings> settingsSupplier;
    private int[] pixels = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private String observedKey;
    private boolean dirty = true;
    private boolean settingsCaptureRequested = true;
    private long dirtyAtNanos;
    private CustomWorldSettings lastSettings;
    private double minY = -64.0;
    private double maxY = 192.0;

    public CustomTerrainPreviewWidget(
            int x, int y, int width, int height,
            Supplier<CustomWorldSettings> settingsSupplier) {
        super(x, y, width, height, Text.translatable("custom.preview.title"));
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier);
        this.lastSettings = CustomWorldSettings.customDefaults();
        this.dirtyAtNanos = System.nanoTime();
    }

    /** Requests one settings capture; changed settings are sampled after the debounce. */
    public void markDirty() {
        settingsCaptureRequested = true;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        CustomWorldSettings settings = lastSettings;
        long now = System.nanoTime();
        if (settingsCaptureRequested || observedKey == null) {
            settings = readSettings();
            String key = settings.toJson();
            settingsCaptureRequested = false;
            if (!key.equals(observedKey)) {
                observedKey = key;
                dirty = true;
                dirtyAtNanos = now;
            }
        }
        if (dirty && now - dirtyAtNanos >= DEBOUNCE_NANOS) {
            rebuild(settings);
        }

        int left = getX();
        int top = getY();
        int right = left + getWidth();
        int bottom = top + getHeight();
        context.fill(left, top, right, bottom, 0xD0101724);

        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(textRenderer,
                Text.translatable("custom.preview.title"), left + 8, top + 5, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("custom.preview.seed", Long.toUnsignedString(PREVIEW_SEED)),
                left + 8, top + 17, 0xFFB8C7D9);

        int plotLeft = left + 5;
        int plotTop = top + 30;
        int plotRight = Math.max(plotLeft + 1, right - 5);
        int plotBottom = Math.max(plotTop + 1, bottom - 18);
        drawPixels(context, plotLeft, plotTop, plotRight, plotBottom);
        drawReferenceLines(context, plotLeft, plotTop, plotRight, plotBottom, settings);
        drawFrame(context, left, top, right, bottom);

        context.drawTextWithShadow(textRenderer,
                Text.translatable("custom.preview.axis_y", formatCoordinate(maxY)),
                plotLeft + 3, plotTop + 2, 0xFFE8EEF5);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("custom.preview.axis_y", formatCoordinate(minY)),
                plotLeft + 3, Math.max(plotTop + 3, plotBottom - 10), 0xFFE8EEF5);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("custom.preview.axis_x", formatCoordinate(X_MIN),
                        formatCoordinate(X_MAX)),
                plotLeft + 3, plotBottom + 3, 0xFFE8EEF5);
    }

    private CustomWorldSettings readSettings() {
        try {
            CustomWorldSettings value = settingsSupplier.get();
            if (value != null) {
                lastSettings = value;
            }
        } catch (RuntimeException ignored) {
            // The editor retains the last valid preview while a field is being
            // edited.  Validation errors remain visible on the form itself.
        }
        return lastSettings;
    }

    private void rebuild(CustomWorldSettings settings) {
        Range range = range(settings);
        minY = range.min();
        maxY = range.max();
        int[] next = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
        for (int row = 0; row < SAMPLE_HEIGHT; row++) {
            double y = maxY - (maxY - minY) * row / (SAMPLE_HEIGHT - 1.0);
            for (int column = 0; column < SAMPLE_WIDTH; column++) {
                double x = X_MIN + (X_MAX - X_MIN) * column / (SAMPLE_WIDTH - 1.0);
                double density = CustomTerrainPreview.density(
                        PREVIEW_SEED, settings, x, y, 0.0);
                next[row * SAMPLE_WIDTH + column] = colorFor(density, row);
            }
        }
        pixels = next;
        dirty = false;
    }

    private void drawPixels(DrawContext context, int left, int top, int right, int bottom) {
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        for (int row = 0; row < SAMPLE_HEIGHT; row++) {
            int y0 = top + row * height / SAMPLE_HEIGHT;
            int y1 = top + (row + 1) * height / SAMPLE_HEIGHT;
            for (int column = 0; column < SAMPLE_WIDTH; column++) {
                int x0 = left + column * width / SAMPLE_WIDTH;
                int x1 = left + (column + 1) * width / SAMPLE_WIDTH;
                context.fill(x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1),
                        pixels[row * SAMPLE_WIDTH + column]);
            }
        }
    }

    private void drawReferenceLines(
            DrawContext context, int left, int top, int right, int bottom,
            CustomWorldSettings settings) {
        drawReferenceLine(context, left, top, right, bottom, 0.0, 0xFF55BBD0);
        drawReferenceLine(context, left, top, right, bottom,
                settings.expectedBaseHeight(), 0xFFE6C85C);
    }

    private void drawReferenceLine(
            DrawContext context, int left, int top, int right, int bottom,
            double y, int color) {
        if (!Double.isFinite(y) || y < minY || y > maxY) {
            return;
        }
        int line = top + (int) Math.round((maxY - y) / (maxY - minY) * (bottom - top - 1));
        context.fill(left, line, right, Math.min(bottom, line + 1), color);
    }

    private void drawFrame(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left, top, right, top + 1, FRAME_COLOR);
        context.fill(left, bottom - 1, right, bottom, FRAME_COLOR);
        context.fill(left, top, left + 1, bottom, FRAME_COLOR);
        context.fill(right - 1, top, right, bottom, FRAME_COLOR);
    }

    private int colorFor(double density, int row) {
        if (!Double.isFinite(density) || density <= 0.0) {
            double heightFraction = row / (double) (SAMPLE_HEIGHT - 1);
            int blue = 36 + (int) Math.round(28.0 * (1.0 - heightFraction));
            int green = 72 + (int) Math.round(36.0 * (1.0 - heightFraction));
            if (Math.abs(density) < 1.5) {
                return 0xFFB8D5C9;
            }
            return argb(255, 12, green, blue);
        }
        if (density < 1.5) {
            return 0xFFFFE39A;
        }
        int shade = 72 + (int) Math.round(140.0 * Math.min(1.0, density / 96.0));
        return argb(255, shade, shade, Math.min(255, shade + 8));
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static Range range(CustomWorldSettings settings) {
        double center = settings.expectedBaseHeight();
        if (!Double.isFinite(center)) {
            center = 64.0;
        }
        center = Math.max(-1_000_000.0, Math.min(1_000_000.0, center));
        double expected = Math.abs(settings.expectedHeightVariation());
        double actual = Math.abs(settings.actualHeight());
        double span = Math.max(128.0, Math.max(actual, 4.0 * expected));
        if (!Double.isFinite(span)) {
            span = 512.0;
        }
        span = Math.min(4096.0, span);
        return new Range(center - span / 2.0, center + span / 2.0);
    }

    private static String formatCoordinate(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001
                && Math.abs(value) <= Long.MAX_VALUE) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, Text.translatable("custom.preview.title"));
        builder.put(NarrationPart.HINT,
                Text.translatable("custom.preview.description"),
                Text.translatable("custom.preview.seed", Long.toUnsignedString(PREVIEW_SEED)));
    }

    private record Range(double min, double max) {
    }
}
