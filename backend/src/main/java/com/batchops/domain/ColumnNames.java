package com.batchops.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Column label normalisation and alias resolution (roll_no to student_id, marks to score, ...). */
public final class ColumnNames {

    private static final Pattern STRIP = Pattern.compile("[^\\w\\s-]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]+");

    /** Ordered on purpose: "grade" resolves to score before class, exactly like the original engine. */
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("student_id", List.of(
                "student_id", "studentid", "student_no", "student_number", "student_num", "student_code",
                "student_roll", "roll_no", "roll_number", "rollno", "roll", "admission_no", "admission_id",
                "admission_number", "admission", "registration_no", "registration_id", "reg_no", "reg_id",
                "enrollment_no", "enrollment_id", "enroll_no", "enroll_id", "id", "id_no", "id_number",
                "unique_id"));
        ALIASES.put("student_name", List.of(
                "student_name", "studentname", "student_full_name", "name", "full_name",
                "full_name_of_student", "candidate_name", "pupil_name"));
        ALIASES.put("score", List.of(
                "score", "marks", "mark", "total_marks", "total_mark", "total_score", "score_total", "points",
                "point", "grade", "percentage", "percent", "score_percent", "score_percentage", "final_score",
                "overall_score"));
        ALIASES.put("class", List.of(
                "class", "class_name", "class_level", "grade_level", "grade", "level", "section",
                "class_section", "class_section_name"));
        ALIASES.put("subject", List.of("subject", "subject_name", "course", "course_name", "paper", "paper_name"));
    }

    private ColumnNames() {
    }

    public static String normalize(String label) {
        String text = label == null ? "" : label.trim();
        text = String.join(" ", text.split("\\s+")).toLowerCase(Locale.ROOT);
        text = STRIP.matcher(text).replaceAll("");
        text = SEPARATORS.matcher(text).replaceAll("_");
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '_') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '_') {
            end--;
        }
        return text.substring(start, end);
    }

    /** Normalises every header in place. */
    public static void normalizeAll(Table table) {
        for (int i = 0; i < table.colCount(); i++) {
            table.renameColumn(i, normalize(table.headers().get(i)));
        }
    }

    public record AliasResult(List<String> matched) {
        public boolean changed() {
            return !matched.isEmpty();
        }
    }

    /** Renames aliased columns to their canonical name in place; returns what was renamed. */
    public static AliasResult applyAliases(Table table) {
        List<String> original = new ArrayList<>(table.headers());
        Set<String> claimedTargets = new HashSet<>();
        Map<Integer, String> renames = new LinkedHashMap<>();
        List<String> matched = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) {
            String norm = normalize(original.get(i));
            for (Map.Entry<String, List<String>> e : ALIASES.entrySet()) {
                String target = e.getKey();
                if (e.getValue().contains(norm) && !original.contains(target) && !claimedTargets.contains(target)) {
                    renames.put(i, target);
                    claimedTargets.add(target);
                    matched.add(original.get(i) + "->" + target);
                    break;
                }
            }
        }
        for (Map.Entry<Integer, String> e : renames.entrySet()) {
            table.renameColumn(e.getKey(), e.getValue());
        }
        return new AliasResult(matched);
    }
}
