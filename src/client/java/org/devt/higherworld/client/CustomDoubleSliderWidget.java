package org.devt.higherworld.client;

import java.util.Objects;
import java.util.function.DoubleConsumer;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/** A bounded helper slider paired with an unrestricted exact-value text field. */
final class CustomDoubleSliderWidget extends SliderWidget {
    private final double min;
    private final double max;
    private final double step;
    private final DoubleConsumer valueConsumer;

    CustomDoubleSliderWidget(
            int width, double initialValue, double min, double max, double step,
            DoubleConsumer valueConsumer) {
        super(0, 0, width, 20, Text.empty(), normalize(initialValue, min, max));
        if (!Double.isFinite(min) || !Double.isFinite(max) || min >= max) {
            throw new IllegalArgumentException("invalid slider range");
        }
        this.min = min;
        this.max = max;
        this.step = step;
        this.valueConsumer = Objects.requireNonNull(valueConsumer);
        updateMessage();
    }

    /** Moves the thumb to a typed value without writing back into the field. */
    void syncFromText(double actualValue) {
        value = normalize(actualValue, min, max);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Text.literal(CustomSettingsParsers.formatNumber(actualValue())));
    }

    @Override
    protected void applyValue() {
        double actual = actualValue();
        if (Double.isFinite(step) && step > 0.0) {
            actual = Math.rint(actual / step) * step;
        }
        actual = MathHelper.clamp(actual, min, max);
        value = normalize(actual, min, max);
        updateMessage();
        valueConsumer.accept(actual);
    }

    private double actualValue() {
        return min + (max - min) * value;
    }

    private static double normalize(double actualValue, double min, double max) {
        if (!Double.isFinite(actualValue)) {
            return 0.0;
        }
        return MathHelper.clamp((actualValue - min) / (max - min), 0.0, 1.0);
    }
}
