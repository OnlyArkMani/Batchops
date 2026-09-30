package com.batchops.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Seed catalogue of known errors. Patterns are regexes matched case-insensitively against the
 * failure text. Two patterns from the original catalogue could never match the messages the
 * pipeline actually produced ("No table found in first PDF page" versus "No table found in PDF
 * pages", and the score/duplicate rules that were never validated); they are fixed here.
 */
public final class KnownErrorCatalog {

    public record Definition(String name, String pattern, Map<String, Object> fix) {
    }

    private KnownErrorCatalog() {
    }

    public static List<Definition> defaults() {
        List<Definition> d = new ArrayList<>();
        d.add(def("No columns detected", "No columns detected", "high", "ingest",
                "The uploaded file has no header row or could not be parsed into columns.",
                "Ensure the first row contains column names and re-export the file as a well-formed CSV or Excel file.",
                "Pipeline rejected the file before validation because the schema was empty.",
                List.of(FixAction.PROMOTE_HEADER)));
        d.add(def("No rows detected", "No rows detected", "medium", "ingest",
                "The uploaded file is empty or only contains a header row.",
                "Verify the source system is exporting data and re-upload a file with at least one data row.",
                "Upload completed but zero records were available for processing.",
                List.of()));
        d.add(def("Required columns missing", "Required columns missing", "high", "schema",
                "The file schema does not match the expected template for this department.",
                "Update the export to include all required columns (e.g. student_id, score) and re-upload.",
                "Schema validation blocked the job until the template is fixed.",
                List.of(FixAction.ALIAS_COLUMNS)));
        d.add(def("Unsupported file type", "Unsupported file type", "low", "ingest",
                "The file extension is not supported by the pipeline loader.",
                "Convert the file to CSV, XLSX/XLS or a tabular PDF and try again.",
                "Rejected because the parser could not infer a loader.",
                List.of(FixAction.CONVERT_TO_CSV)));
        d.add(def("No table found in PDF", "No table found in PDF", "medium", "ingest",
                "The PDF does not contain extractable tabular text.",
                "Export the results as a table-based PDF or use CSV/Excel instead.",
                "PDF extraction returned no rows.",
                List.of()));
        d.add(def("File not found", "(File not found|Artifact not found)", "critical", "storage",
                "The stored file for this upload is missing from object storage.",
                "Re-upload the original file so the pipeline can access it again.",
                "Pipeline could not read its input artifact.",
                List.of()));
        d.add(def("Temporary storage lock", "(Resource temporarily unavailable|share violation|Connection reset|timed out)",
                "medium", "infrastructure",
                "The storage layer was briefly unavailable and the retry budget was exhausted.",
                "Check object storage health, then retry the upload.",
                "Transient infrastructure failure persisted through all retry tiers.",
                List.of()));
        d.add(def("Encoding mismatch", "(UnicodeDecodeError|codec can't decode|Invalid UTF-8)", "high", "ingest",
                "The CSV encoding differs from UTF-8.",
                "Re-export the source file as UTF-8 or specify UTF-8 BOM.",
                "Parser failed while decoding file contents.",
                List.of(FixAction.REENCODE_UTF8)));
        d.add(def("Grade outside range", "(score must be between|value out of range)", "medium", "validation",
                "One or more score values are outside the permitted range.",
                "Review the flagged rows and correct the data, or approve clipping to the valid range.",
                "Validation rejected the payload due to data quality issues.",
                List.of(FixAction.CLIP_SCORES)));
        d.add(def("Duplicate student rows", "Duplicate rows detected", "medium", "validation",
                "The upload contains duplicate student identifiers.",
                "Deduplicate records in the source file, or approve removing the duplicates.",
                "Uniqueness validation failed.",
                List.of(FixAction.DEDUPE_STUDENTS)));
        d.add(def("Empty key fields", "Critical fields empty", "high", "validation",
                "Some rows have no student identifier, so they cannot be attributed.",
                "Fill in the missing identifiers, or approve dropping the rows.",
                "Key-field validation failed.",
                List.of(FixAction.DROP_EMPTY_KEYS)));
        return d;
    }

    private static Definition def(String name, String pattern, String severity, String category, String rootCause,
                                  String corrective, String report, List<FixAction> actions) {
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("severity", severity);
        fix.put("category", category);
        fix.put("root_cause", rootCause);
        fix.put("corrective_action", corrective);
        fix.put("resolution_report", report);
        if (!actions.isEmpty()) {
            List<String> wire = new ArrayList<>();
            for (FixAction a : actions) {
                wire.add(a.wire());
            }
            Map<String, Object> auto = new LinkedHashMap<>();
            auto.put("actions", wire);
            fix.put("auto_fix", auto);
        }
        return new Definition(name, pattern, fix);
    }

    /** Invalid regexes in the database must never break failure handling. */
    public static boolean matches(String pattern, String text) {
        if (pattern == null || text == null) {
            return false;
        }
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    public static List<FixAction> actionsOf(Map<String, Object> fix) {
        List<FixAction> out = new ArrayList<>();
        if (fix == null) {
            return out;
        }
        Object auto = fix.get("auto_fix");
        if (!(auto instanceof Map<?, ?> autoMap)) {
            return out;
        }
        Object raw = autoMap.get("actions");
        List<?> list = raw instanceof List<?> l ? l : raw == null ? List.of() : List.of(raw);
        for (Object o : list) {
            FixAction.parse(String.valueOf(o)).ifPresent(a -> {
                if (!out.contains(a)) {
                    out.add(a);
                }
            });
        }
        return out;
    }
}
