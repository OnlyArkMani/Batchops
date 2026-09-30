package com.batchops.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure data fixes applied to a {@link Table}. Every call returns a new table plus an audit trail;
 * the caller stores the result as a new artifact version and never touches the original upload.
 */
public final class AutoFix {

    private AutoFix() {
    }

    public record Outcome(Table table, List<String> notes, boolean changed, int affectedRows) {
    }

    /**
     * Applies table-level actions. IO-level actions (re-encode, convert) are handled by the loader
     * before the table exists, so they are ignored here.
     */
    public static Outcome apply(Table input, Collection<FixAction> actions, double scoreMin, double scoreMax) {
        Table t = input.copy();
        List<String> notes = new ArrayList<>();
        boolean changed = false;
        int affected = 0;

        if (actions.contains(FixAction.PROMOTE_HEADER)) {
            Table promoted = promoteHeader(t);
            if (promoted != null) {
                t = promoted;
                notes.add("Promoted first row to headers");
                changed = true;
            }
        }
        if (actions.contains(FixAction.ALIAS_COLUMNS)) {
            ColumnNames.normalizeAll(t);
            ColumnNames.AliasResult r = ColumnNames.applyAliases(t);
            if (r.changed()) {
                notes.add("Aliased columns: " + String.join(", ", r.matched()));
                changed = true;
            }
        }
        if (actions.contains(FixAction.DROP_EMPTY_KEYS) && t.hasColumn("student_id")) {
            int idx = t.indexOf("student_id");
            int removed = t.removeRows(row -> Table.isBlank(row.get(idx)));
            if (removed > 0) {
                notes.add("Removed " + removed + " row(s) with an empty student_id");
                changed = true;
                affected += removed;
            }
        }
        if (actions.contains(FixAction.DEDUPE_STUDENTS) && t.hasColumn("student_id")) {
            int removed = dedupe(t);
            if (removed > 0) {
                notes.add("Removed " + removed + " duplicate student row(s)");
                changed = true;
                affected += removed;
            }
        }
        if (actions.contains(FixAction.CLIP_SCORES) && t.hasColumn("score")) {
            int clipped = clip(t, scoreMin, scoreMax);
            if (clipped > 0) {
                notes.add("Clipped " + clipped + " score value(s) into " + Numeric.format(scoreMin) + "-"
                        + Numeric.format(scoreMax));
                changed = true;
                affected += clipped;
            }
        }
        return new Outcome(t, notes, changed, affected);
    }

    /** First row becomes the header. Returns null when that is not possible. */
    static Table promoteHeader(Table t) {
        if (t.rowCount() < 2) {
            return null;
        }
        List<String> first = t.rows().get(0);
        boolean any = false;
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) {
            String label = first.get(i).trim();
            if (!label.isEmpty()) {
                any = true;
            }
            String norm = ColumnNames.normalize(label);
            headers.add(norm.isEmpty() ? "column_" + (i + 1) : norm);
        }
        if (!any) {
            return null;
        }
        List<List<String>> body = new ArrayList<>(t.rows().subList(1, t.rowCount()));
        return new Table(headers, body);
    }

    private static int dedupe(Table t) {
        int idIdx = t.indexOf("student_id");
        int subjectIdx = t.indexOf("subject");
        Set<String> seen = new HashSet<>();
        return t.removeRows(row -> {
            String id = row.get(idIdx).trim();
            if (id.isEmpty()) {
                return false;
            }
            String key = subjectIdx >= 0 ? id + "\u0000" + row.get(subjectIdx).trim() : id;
            return !seen.add(key);
        });
    }

    private static int clip(Table t, double min, double max) {
        int idx = t.indexOf("score");
        int clipped = 0;
        for (int r = 0; r < t.rowCount(); r++) {
            Double v = Numeric.parse(t.cell(r, idx));
            if (v == null) {
                continue;
            }
            double bounded = Math.max(min, Math.min(max, v));
            if (bounded != v) {
                t.set(r, idx, Numeric.format(bounded));
                clipped++;
            }
        }
        return clipped;
    }
}
