package com.batchops.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Real data validation with row-level evidence.
 *
 * <p>The original engine only checked that columns existed, so its "score out of range" and
 * "duplicate rows" known errors could never fire. These rules make every known error reachable and
 * give operators the exact rows to fix.
 */
public final class SchemaValidator {

    private static final int MAX_ROW_ISSUES = 200;

    private SchemaValidator() {
    }

    public record Settings(
            List<String> requiredColumns,
            boolean enforceKeys,
            boolean enforceScoreRange,
            double scoreMin,
            double scoreMax,
            boolean enforceUniqueness) {

        public static Settings defaults() {
            return new Settings(List.of("student_id", "score"), true, true, 0, 100, true);
        }

        public Settings withRequired(List<String> required) {
            return new Settings(required, enforceKeys, enforceScoreRange, scoreMin, scoreMax, enforceUniqueness);
        }
    }

    /** {@code row} is the 1-based data row number (header excluded). */
    public record RowIssue(int row, String column, String rule, String value) {
    }

    public record Report(List<String> errors, List<String> warnings, List<RowIssue> rowIssues, int totalRowIssues) {
        public boolean ok() {
            return errors.isEmpty();
        }

        public String errorMessage() {
            return String.join("; ", errors);
        }
    }

    public static Report validate(Table t, Settings s) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<RowIssue> issues = new ArrayList<>();
        int[] total = {0};

        if (t.colCount() == 0) {
            errors.add("No columns detected");
        }
        if (t.rowCount() == 0) {
            errors.add("No rows detected");
        }
        if (t.colCount() > 0) {
            Set<String> lower = new HashSet<>();
            for (String h : t.headers()) {
                lower.add(h.toLowerCase(Locale.ROOT));
            }
            List<String> missing = new ArrayList<>();
            for (String req : s.requiredColumns()) {
                if (!lower.contains(req)) {
                    missing.add(req);
                }
            }
            if (!missing.isEmpty()) {
                errors.add("Required columns missing: " + String.join(", ", missing));
            }
        }

        if (errors.isEmpty()) {
            checkKeys(t, s, errors, issues, total);
            checkScoreRange(t, s, errors, warnings, issues, total);
            checkUniqueness(t, s, errors, issues, total);
            checkEmptiness(t, s, warnings);
        }
        return new Report(errors, warnings, issues, total[0]);
    }

    private static void record(List<RowIssue> issues, int[] total, int row, String col, String rule, String value) {
        total[0]++;
        if (issues.size() < MAX_ROW_ISSUES) {
            issues.add(new RowIssue(row, col, rule, value));
        }
    }

    private static void checkKeys(Table t, Settings s, List<String> errors, List<RowIssue> issues, int[] total) {
        int idx = t.indexOf("student_id");
        if (!s.enforceKeys() || idx < 0) {
            return;
        }
        int bad = 0;
        for (int r = 0; r < t.rowCount(); r++) {
            if (Table.isBlank(t.cell(r, idx))) {
                bad++;
                record(issues, total, r + 1, "student_id", "key_empty", "");
            }
        }
        if (bad > 0) {
            errors.add("Critical fields empty: student_id (" + bad + " row(s))");
        }
    }

    private static void checkScoreRange(Table t, Settings s, List<String> errors, List<String> warnings,
                                        List<RowIssue> issues, int[] total) {
        int idx = t.indexOf("score");
        if (idx < 0) {
            return;
        }
        int out = 0;
        int notNumeric = 0;
        for (int r = 0; r < t.rowCount(); r++) {
            String raw = t.cell(r, idx);
            if (Table.isBlank(raw)) {
                continue;
            }
            Double v = Numeric.parse(raw);
            if (v == null) {
                notNumeric++;
                record(issues, total, r + 1, "score", "score_not_numeric", raw);
            } else if (s.enforceScoreRange() && (v < s.scoreMin() || v > s.scoreMax())) {
                out++;
                record(issues, total, r + 1, "score", "score_out_of_range", raw);
            }
        }
        if (out > 0) {
            errors.add("score must be between " + Numeric.format(s.scoreMin()) + " and "
                    + Numeric.format(s.scoreMax()) + " (" + out + " row(s) out of range)");
        }
        if (notNumeric > 0) {
            warnings.add("score has " + notNumeric + " non-numeric value(s)");
        }
    }

    private static void checkUniqueness(Table t, Settings s, List<String> errors, List<RowIssue> issues, int[] total) {
        int idIdx = t.indexOf("student_id");
        if (!s.enforceUniqueness() || idIdx < 0) {
            return;
        }
        int subjectIdx = t.indexOf("subject");
        Set<String> seen = new HashSet<>();
        int dups = 0;
        for (int r = 0; r < t.rowCount(); r++) {
            String id = t.cell(r, idIdx).trim();
            if (id.isEmpty()) {
                continue;
            }
            String key = subjectIdx >= 0 ? id + "\u0000" + t.cell(r, subjectIdx).trim() : id;
            if (!seen.add(key)) {
                dups++;
                record(issues, total, r + 1, "student_id", "duplicate_key", id);
            }
        }
        if (dups > 0) {
            errors.add("Duplicate rows detected: " + dups + " duplicate "
                    + (subjectIdx >= 0 ? "student_id/subject pair(s)" : "student_id value(s)"));
        }
    }

    private static void checkEmptiness(Table t, Settings s, List<String> warnings) {
        for (String col : s.requiredColumns()) {
            int idx = t.indexOf(col);
            if (idx < 0 || t.rowCount() == 0) {
                continue;
            }
            int blank = 0;
            for (int r = 0; r < t.rowCount(); r++) {
                if (Table.isBlank(t.cell(r, idx))) {
                    blank++;
                }
            }
            double ratio = (double) blank / t.rowCount();
            if (ratio > 0.5) {
                warnings.add("Column '" + col + "' is " + Numeric.formatPercent(ratio) + " empty");
            }
        }
    }
}
