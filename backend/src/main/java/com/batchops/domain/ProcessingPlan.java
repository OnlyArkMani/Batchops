package com.batchops.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Operator-selected processing plans: standard transform, append, delete, custom note. */
public final class ProcessingPlan {

    public static final String TRANSFORM = "transform_gradebook";
    public static final String APPEND = "append_record";
    public static final String DELETE = "delete_record";
    public static final String CUSTOM = "custom_rules";

    private ProcessingPlan() {
    }

    public record Result(Table table, String mode, String summary) {
    }

    /** True when the published report is a summary table rather than the processed dataset. */
    public static boolean isSummaryMode(String mode) {
        return TRANSFORM.equals(normalizeMode(mode));
    }

    public static String normalizeMode(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return m.isEmpty() ? TRANSFORM : m;
    }

    public static Result apply(Table input, String modeRaw, Map<String, Object> config) {
        String mode = modeRaw == null || modeRaw.isBlank() ? TRANSFORM : modeRaw.trim();
        String normalized = mode.toLowerCase(Locale.ROOT);
        Map<String, Object> cfg = config == null ? Map.of() : config;
        Table df = input.copy();
        String summary;

        switch (normalized) {
            case APPEND -> summary = append(df, cfg);
            case DELETE -> summary = delete(df, cfg);
            case CUSTOM -> {
                Object note = firstNonNull(cfg.get("notes"), cfg.get("instructions"));
                summary = note == null || String.valueOf(note).isBlank()
                        ? "Custom instructions captured"
                        : "Custom instructions noted: " + note;
            }
            default -> summary = "Standard transform applied";
        }
        return new Result(df, mode, summary);
    }

    private static String append(Table df, Map<String, Object> cfg) {
        Object records = firstNonNull(cfg.get("records"), cfg.get("row"));
        List<Map<?, ?>> payload = new ArrayList<>();
        if (records instanceof Map<?, ?> single) {
            payload.add(single);
        } else if (records instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    payload.add(m);
                }
            }
        }
        if (payload.isEmpty()) {
            return "Append skipped (no valid rows)";
        }
        for (Map<?, ?> rec : payload) {
            for (Object key : rec.keySet()) {
                String name = String.valueOf(key);
                if (!df.hasColumn(name)) {
                    df.addColumn(name);
                }
            }
        }
        for (Map<?, ?> rec : payload) {
            List<String> row = new ArrayList<>();
            for (String header : df.headers()) {
                Object v = rec.get(header);
                row.add(v == null ? "" : String.valueOf(v));
            }
            df.addRow(row);
        }
        return "Appended " + payload.size() + " custom row(s)";
    }

    private static String delete(Table df, Map<String, Object> cfg) {
        Object rules = cfg.get("rules");
        if (rules instanceof List<?> list && !list.isEmpty()) {
            int removed = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> rule)) {
                    continue;
                }
                Object column = rule.get("column");
                if (column == null || String.valueOf(column).isEmpty()) {
                    continue;
                }
                removed += removeMatching(df, String.valueOf(column), rule.get("value"));
            }
            return "Removed " + removed + " row(s) via " + list.size() + " rule(s)";
        }
        Object column = cfg.get("column");
        Object value = cfg.get("value");
        if (column != null && df.hasColumn(String.valueOf(column))) {
            int removed = removeMatching(df, String.valueOf(column), value);
            return "Removed " + removed + " row(s) where " + column + " == " + value;
        }
        if (column != null && !String.valueOf(column).isEmpty()) {
            return "Delete skipped (column '" + column + "' missing)";
        }
        return "Delete skipped (column not specified)";
    }

    private static int removeMatching(Table df, String column, Object value) {
        int idx = df.indexOf(column);
        if (idx < 0) {
            return 0;
        }
        String target = Objects.toString(value, "None");
        return df.removeRows(row -> looselyEqual(row.get(idx), target));
    }

    /** "85" equals "85.0"; everything else compares as trimmed text. */
    static boolean looselyEqual(String a, String b) {
        if (a.trim().equals(b.trim())) {
            return true;
        }
        Double x = Numeric.parse(a);
        Double y = Numeric.parse(b);
        return x != null && y != null && Double.compare(x, y) == 0;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
