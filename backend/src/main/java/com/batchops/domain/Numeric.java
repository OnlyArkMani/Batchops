package com.batchops.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/** Number parsing and formatting shared by coercion, validation and summaries. */
public final class Numeric {

    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private Numeric() {
    }

    public static boolean looksNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return NUMBER.matcher(strip(value)).matches();
    }

    /** Parses "1,234", "85%" and plain numbers; blank, "nan" and "None" yield null. */
    public static Double parse(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("nan") || t.equals("None")) {
            return null;
        }
        String cleaned = strip(t);
        if (!NUMBER.matcher(cleaned).matches()) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String strip(String v) {
        return v.replace(",", "").replace("%", "").trim();
    }

    /** Canonical cell text: whole numbers without a decimal point, others without trailing zeros. */
    public static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** Two decimals with trailing zeros trimmed: 100.00 becomes 100, 3.50 becomes 3.5. */
    public static String formatStat(Double v) {
        if (v == null || v.isNaN()) {
            return "-";
        }
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.contains(".")) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && s.charAt(end - 1) == '.') {
                end--;
            }
            s = s.substring(0, end);
        }
        return s.isEmpty() ? "0" : s;
    }

    public static String formatInt(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    public static String formatPercent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }
}
