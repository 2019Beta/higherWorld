package org.devt.higherworld.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.devt.higherworld.world.CustomWorldSettings;

/** Pure parsing/formatting helpers shared by the custom-world editor pages. */
public final class CustomSettingsParsers {
    private CustomSettingsParsers() {
    }

    /** Parses a comma-separated identifier list, preserving insertion order. */
    public static List<String> csv(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : text.split(",", -1)) {
            String value = raw.trim();
            if (!value.isEmpty() && seen.add(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    /** Empty CSV is the editor's null/all-biomes spelling. */
    public static Set<String> csvSetOrNull(String text) {
        List<String> values = csv(text);
        return values.isEmpty() ? null : new LinkedHashSet<>(values);
    }

    public static String formatCsv(Iterable<String> values) {
        if (values == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(value);
        }
        return result.toString();
    }

    /** Parses the compact y:value syntax used for lake probability curves. */
    public static CustomWorldSettings.UserFunction curve(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("curve must contain at least one point");
        }
        List<CustomWorldSettings.UserFunctionPoint> points = new ArrayList<>();
        for (String rawPoint : text.split(",", -1)) {
            String point = rawPoint.trim();
            if (point.isEmpty()) {
                throw new IllegalArgumentException("curve contains an empty point");
            }
            String[] pair = point.split(":", -1);
            if (pair.length != 2) {
                throw new IllegalArgumentException("curve points must use y:v");
            }
            double y = finite(pair[0].trim(), "curve y");
            double value = finite(pair[1].trim(), "curve probability");
            if (value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("curve probability must be between 0 and 1");
            }
            points.add(new CustomWorldSettings.UserFunctionPoint(y, value));
        }
        return new CustomWorldSettings.UserFunction(points);
    }

    public static String formatCurve(CustomWorldSettings.UserFunction function) {
        StringBuilder result = new StringBuilder();
        for (CustomWorldSettings.UserFunctionPoint point : function.points()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(formatNumber(point.y())).append(':').append(formatNumber(point.v()));
        }
        return result.toString();
    }

    public static double finite(String text, String field) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        final double value;
        try {
            value = Double.parseDouble(text.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " is not a number", exception);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
        return value;
    }

    public static int integer(String text, String field) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            // BigDecimal accepts the same decimal/scientific forms as the
            // floating-point fields, while intValueExact still enforces the
            // model's integral/32-bit constraint.
            return new BigDecimal(text.trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    /** Empty bounds are the same as the corresponding unbounded JSON null. */
    public static double bound(String text, boolean minimum, String field) {
        if (text == null || text.isBlank()) {
            return minimum ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        String value = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (minimum && (value.equals("-inf") || value.equals("-infinity"))) {
            return Double.NEGATIVE_INFINITY;
        }
        if (!minimum && (value.equals("+inf") || value.equals("inf")
                || value.equals("infinity") || value.equals("+infinity"))) {
            return Double.POSITIVE_INFINITY;
        }
        if (value.equals("-infinity") || value.equals("+infinity")
                || value.equals("infinity") || value.equals("inf") || value.equals("-inf")
                || value.equals("+inf")) {
            throw new IllegalArgumentException(field + " has the wrong infinity sign");
        }
        double result = finite(value, field);
        return result;
    }

    public static String formatBound(double value, boolean minimum) {
        if (value == Double.NEGATIVE_INFINITY && minimum) {
            return "-inf";
        }
        if (value == Double.POSITIVE_INFINITY && !minimum) {
            return "+inf";
        }
        return formatNumber(value);
    }

    public static String formatNumber(double value) {
        if (value == 0.0) {
            return "0";
        }
        return Double.toString(value);
    }
}
