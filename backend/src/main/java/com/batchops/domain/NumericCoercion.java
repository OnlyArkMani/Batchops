package com.batchops.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Converts text columns that really hold numbers ("1,234", "85%") into canonical numeric text. */
public final class NumericCoercion {

    private static final Set<String> NUMERIC_HINTS = Set.of(
            "score", "marks", "mark", "total", "total_marks", "total_score", "points", "point", "percent",
            "percentage", "gpa");
    private static final Set<String> EXCLUDE_HINTS = Set.of(
            "id", "name", "student", "code", "roll", "admission", "registration", "reg", "enroll");

    private NumericCoercion() {
    }

    /** Coerces in place and returns the names of converted columns. */
    public static List<String> coerce(Table table) {
        List<String> converted = new ArrayList<>();
        int total = table.rowCount();
        if (total == 0) {
            return converted;
        }
        for (int col = 0; col < table.colCount(); col++) {
            String name = table.headers().get(col);
            if (table.isNumeric(name)) {
                continue;
            }
            Set<String> tokens = tokens(name);
            boolean hinted = intersects(tokens, NUMERIC_HINTS);
            if (intersects(tokens, EXCLUDE_HINTS) && !hinted) {
                continue;
            }
            int ok = 0;
            Double[] parsed = new Double[total];
            for (int r = 0; r < total; r++) {
                parsed[r] = Numeric.parse(table.cell(r, col));
                if (parsed[r] != null) {
                    ok++;
                }
            }
            double ratio = (double) ok / total;
            double threshold = hinted ? 0.3 : 0.6;
            if (ok > 0 && ratio >= threshold) {
                for (int r = 0; r < total; r++) {
                    table.set(r, col, parsed[r] == null ? "" : Numeric.format(parsed[r]));
                }
                table.markNumeric(name);
                converted.add(name);
            }
        }
        return converted;
    }

    static Set<String> tokens(String columnName) {
        Set<String> out = new HashSet<>();
        for (String t : ColumnNames.normalize(columnName).split("_")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
