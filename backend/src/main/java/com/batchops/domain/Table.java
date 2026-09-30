package com.batchops.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Minimal in-memory tabular data: named columns, string cells.
 *
 * <p>Deliberately framework-free so every data rule in the pipeline can be unit-tested with
 * plain JUnit and no Spring context. A column becomes "numeric" only after
 * {@link NumericCoercion} converts it, which is tracked in {@link #numericColumns()}.
 */
public final class Table {

    private final List<String> headers;
    private final List<List<String>> rows;
    private final Set<String> numeric = new LinkedHashSet<>();

    public Table(List<String> headers, List<List<String>> rows) {
        this.headers = new ArrayList<>(headers);
        this.rows = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            this.rows.add(fit(row, headers.size()));
        }
    }

    private static List<String> fit(List<String> row, int width) {
        List<String> out = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            String v = i < row.size() ? row.get(i) : null;
            out.add(v == null ? "" : v);
        }
        return out;
    }

    public static Table empty() {
        return new Table(List.of(), List.of());
    }

    public List<String> headers() {
        return Collections.unmodifiableList(headers);
    }

    public List<List<String>> rows() {
        return Collections.unmodifiableList(rows);
    }

    public int rowCount() {
        return rows.size();
    }

    public int colCount() {
        return headers.size();
    }

    /** Index of a column by exact name, or -1. */
    public int indexOf(String name) {
        return headers.indexOf(name);
    }

    public boolean hasColumn(String name) {
        return indexOf(name) >= 0;
    }

    public String cell(int row, int col) {
        return rows.get(row).get(col);
    }

    public void set(int row, int col, String value) {
        rows.get(row).set(col, value == null ? "" : value);
    }

    public void renameColumn(int col, String newName) {
        String old = headers.get(col);
        headers.set(col, newName);
        if (numeric.remove(old)) {
            numeric.add(newName);
        }
    }

    public void addColumn(String name) {
        headers.add(name);
        for (List<String> row : rows) {
            row.add("");
        }
    }

    public void addRow(List<String> row) {
        rows.add(fit(row, headers.size()));
    }

    /** Removes rows matching the predicate; returns how many were removed. */
    public int removeRows(Predicate<List<String>> matcher) {
        int before = rows.size();
        rows.removeIf(matcher);
        return before - rows.size();
    }

    public List<String> column(String name) {
        int idx = indexOf(name);
        List<String> out = new ArrayList<>(rows.size());
        if (idx < 0) {
            return out;
        }
        for (List<String> row : rows) {
            out.add(row.get(idx));
        }
        return out;
    }

    public void markNumeric(String column) {
        numeric.add(column);
    }

    public boolean isNumeric(String column) {
        return numeric.contains(column);
    }

    public Set<String> numericColumns() {
        return Collections.unmodifiableSet(numeric);
    }

    public Table copy() {
        Table t = new Table(headers, rows);
        t.numeric.addAll(numeric);
        return t;
    }

    public static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
