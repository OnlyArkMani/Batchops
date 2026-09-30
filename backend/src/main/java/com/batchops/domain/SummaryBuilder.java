package com.batchops.domain;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the executive summary report: profile, completeness and numeric statistics. */
public final class SummaryBuilder {

    private static final Set<String> EXCLUDE_TOKENS = Set.of(
            "id", "code", "name", "student", "roll", "admission", "registration", "reg", "enroll");

    private SummaryBuilder() {
    }

    public record Summary(
            List<List<String>> rows,
            List<String> numericColumns,
            int rowCount,
            int colCount,
            int missingCells,
            double missingRate,
            int duplicateRows,
            Integer duplicateStudentIds,
            Map<String, Map<String, Double>> describe) {
    }

    public static Summary build(Table df, String department, String filename, String mode, String planNotes,
                                ZonedDateTime processedAt) {
        int rowCount = df.rowCount();
        int colCount = df.colCount();
        long totalCells = (long) rowCount * colCount;

        int[] blanksPerColumn = new int[colCount];
        int missing = 0;
        for (List<String> row : df.rows()) {
            for (int c = 0; c < colCount; c++) {
                if (Table.isBlank(row.get(c))) {
                    blanksPerColumn[c]++;
                    missing++;
                }
            }
        }
        double missingRate = totalCells > 0 ? (double) missing / totalCells : 0.0;

        int duplicateRows = 0;
        Set<String> seenRows = new HashSet<>();
        for (List<String> row : df.rows()) {
            if (!seenRows.add(String.join("\u0000", row))) {
                duplicateRows++;
            }
        }

        Integer duplicateStudents = null;
        int idIdx = df.indexOf("student_id");
        if (idIdx >= 0) {
            int dups = 0;
            Set<String> seenIds = new HashSet<>();
            for (List<String> row : df.rows()) {
                if (!seenIds.add(row.get(idIdx).trim())) {
                    dups++;
                }
            }
            duplicateStudents = dups;
        }

        List<String> numericCols = new ArrayList<>();
        Map<String, Map<String, Double>> describe = new LinkedHashMap<>();
        Map<String, double[]> sortedValues = new LinkedHashMap<>();
        for (String col : df.headers()) {
            if (!df.isNumeric(col)) {
                continue;
            }
            Set<String> tokens = NumericCoercion.tokens(col);
            boolean excluded = false;
            for (String t : tokens) {
                if (EXCLUDE_TOKENS.contains(t)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) {
                continue;
            }
            int idx = df.indexOf(col);
            List<Double> values = new ArrayList<>();
            Set<String> distinct = new HashSet<>();
            for (List<String> row : df.rows()) {
                Double v = Numeric.parse(row.get(idx));
                if (v != null) {
                    values.add(v);
                    distinct.add(Numeric.format(v));
                }
            }
            if (values.isEmpty() || distinct.size() <= 1) {
                continue;
            }
            numericCols.add(col);
            double[] arr = values.stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(arr);
            sortedValues.put(col, arr);
            describe.put(col, describe(arr));
        }

        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("section", "Executive summary"));
        rows.add(List.of("status", "Completed"));
        rows.add(List.of("processed_at", processedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        rows.add(List.of("processing_mode", mode));
        rows.add(List.of("plan_notes", planNotes == null || planNotes.isBlank() ? "Standard batch processing" : planNotes));
        rows.add(List.of("rows", Numeric.formatInt(rowCount)));
        rows.add(List.of("cols", Numeric.formatInt(colCount)));
        rows.add(List.of("total_cells", Numeric.formatInt(totalCells)));
        rows.add(List.of("missing_cells", Numeric.formatInt(missing)));
        rows.add(List.of("missing_cell_rate", Numeric.formatPercent(missingRate)));
        rows.add(List.of("duplicate_rows", Numeric.formatInt(duplicateRows)));
        rows.add(List.of("duplicate_student_ids",
                duplicateStudents == null ? "Not applicable" : Numeric.formatInt(duplicateStudents)));
        rows.add(List.of("numeric_columns", numericCols.isEmpty() ? "None detected" : String.join(", ", numericCols)));
        rows.add(List.of("section", "Dataset profile"));
        rows.add(List.of("department", department == null ? "" : department));
        rows.add(List.of("filename", filename == null ? "" : filename));
        rows.add(List.of("columns", String.join(", ", df.headers())));

        if (rowCount > 0 && colCount > 0) {
            List<Integer> order = new ArrayList<>();
            for (int c = 0; c < colCount; c++) {
                order.add(c);
            }
            order.sort(Comparator.comparingDouble(c -> (double) (rowCount - blanksPerColumn[c]) / rowCount));
            rows.add(List.of("section", "Column completeness (lowest)"));
            for (int i = 0; i < Math.min(6, order.size()); i++) {
                int c = order.get(i);
                double pct = (double) (rowCount - blanksPerColumn[c]) / rowCount;
                rows.add(List.of(df.headers().get(c), Numeric.formatPercent(pct)));
            }
        }

        if (!numericCols.isEmpty()) {
            rows.add(List.of("section", "Numeric statistics"));
            for (String col : numericCols) {
                Map<String, Double> d = describe.get(col);
                rows.add(List.of(col + ".count", Numeric.formatStat(d.get("count"))));
                rows.add(List.of(col + ".mean", Numeric.formatStat(d.get("mean"))));
                rows.add(List.of(col + ".median", Numeric.formatStat(d.get("50%"))));
                rows.add(List.of(col + ".min", Numeric.formatStat(d.get("min"))));
                rows.add(List.of(col + ".max", Numeric.formatStat(d.get("max"))));
            }
        }
        return new Summary(rows, numericCols, rowCount, colCount, missing, missingRate, duplicateRows,
                duplicateStudents, describe);
    }

    /** pandas-style describe(): count, mean, sample std, min, quartiles (linear interpolation), max. */
    static Map<String, Double> describe(double[] sorted) {
        Map<String, Double> out = new LinkedHashMap<>();
        int n = sorted.length;
        double sum = 0;
        for (double v : sorted) {
            sum += v;
        }
        double mean = sum / n;
        double sq = 0;
        for (double v : sorted) {
            sq += (v - mean) * (v - mean);
        }
        out.put("count", (double) n);
        out.put("mean", mean);
        out.put("std", n > 1 ? Math.sqrt(sq / (n - 1)) : Double.NaN);
        out.put("min", sorted[0]);
        out.put("25%", percentile(sorted, 0.25));
        out.put("50%", percentile(sorted, 0.50));
        out.put("75%", percentile(sorted, 0.75));
        out.put("max", sorted[n - 1]);
        return out;
    }

    static double percentile(double[] sorted, double q) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }
}
