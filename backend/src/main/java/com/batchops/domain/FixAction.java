package com.batchops.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Automatic remediation actions.
 *
 * <p>Destructive actions change or drop the operator's data. The original engine applied them
 * silently and even inserted a blank placeholder row so an empty file would "pass". Here they are
 * proposed and need an explicit approval unless the deployment opts in to auto-apply.
 */
public enum FixAction {
    PROMOTE_HEADER("promote_header", false),
    ALIAS_COLUMNS("alias_columns", false),
    REENCODE_UTF8("reencode_utf8", false),
    CONVERT_TO_CSV("convert_to_csv", false),
    DEDUPE_STUDENTS("dedupe_students", true),
    CLIP_SCORES("clip_scores", true),
    DROP_EMPTY_KEYS("drop_empty_keys", true);

    private final String wire;
    private final boolean destructive;

    FixAction(String wire, boolean destructive) {
        this.wire = wire;
        this.destructive = destructive;
    }

    public String wire() {
        return wire;
    }

    public boolean destructive() {
        return destructive;
    }

    public static Optional<FixAction> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (FixAction a : values()) {
            if (a.wire.equals(n)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** Fallback when no known error carries explicit actions: infer from the error text. */
    public static List<FixAction> fromError(String errorText) {
        String t = errorText == null ? "" : errorText.toLowerCase(Locale.ROOT);
        List<FixAction> out = new ArrayList<>();
        addIf(t, "no columns detected", FixAction.PROMOTE_HEADER, out);
        addIf(t, "required columns missing", FixAction.ALIAS_COLUMNS, out);
        addIf(t, "unsupported file type", FixAction.CONVERT_TO_CSV, out);
        addIf(t, "unicode", FixAction.REENCODE_UTF8, out);
        addIf(t, "codec can't decode", FixAction.REENCODE_UTF8, out);
        addIf(t, "duplicate rows detected", FixAction.DEDUPE_STUDENTS, out);
        addIf(t, "score must be between", FixAction.CLIP_SCORES, out);
        addIf(t, "value out of range", FixAction.CLIP_SCORES, out);
        addIf(t, "critical fields empty", FixAction.DROP_EMPTY_KEYS, out);
        return out;
    }

    private static void addIf(String text, String needle, FixAction action, List<FixAction> out) {
        if (text.contains(needle) && !out.contains(action)) {
            out.add(action);
        }
    }

    /** Splits actions into those that may run now and those waiting for human approval. */
    public record Plan(List<FixAction> auto, List<FixAction> needsApproval) {
    }

    public static Plan plan(List<FixAction> actions, boolean allowDestructive) {
        List<FixAction> auto = new ArrayList<>();
        List<FixAction> approval = new ArrayList<>();
        for (FixAction a : actions) {
            if (a.destructive() && !allowDestructive) {
                approval.add(a);
            } else {
                auto.add(a);
            }
        }
        return new Plan(auto, approval);
    }
}
