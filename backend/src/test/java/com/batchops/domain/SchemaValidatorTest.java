package com.batchops.domain;

import static com.batchops.domain.TestTables.h;
import static com.batchops.domain.TestTables.r;
import static com.batchops.domain.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    private final SchemaValidator.Settings settings = SchemaValidator.Settings.defaults();

    @Test
    void cleanFilePasses() {
        Table t = table(h("student_id", "score"), r("S1", "90"), r("S2", "75.5"));
        assertTrue(SchemaValidator.validate(t, settings).ok());
    }

    @Test
    void emptyTableReportsBothProblems() {
        SchemaValidator.Report report = SchemaValidator.validate(Table.empty(), settings);
        assertEquals(List.of("No columns detected", "No rows detected"), report.errors());
    }

    @Test
    void headerOnlyFileHasNoRows() {
        Table t = table(h("student_id", "score"));
        assertEquals(List.of("No rows detected"), SchemaValidator.validate(t, settings).errors());
    }

    @Test
    void missingRequiredColumnsAreListed() {
        Table t = table(h("student_id", "name"), r("S1", "Asha"));
        assertEquals(List.of("Required columns missing: score"), SchemaValidator.validate(t, settings).errors());
    }

    @Test
    void scoreOutOfRangeGivesRowLevelEvidence() {
        Table t = table(h("student_id", "score"), r("S1", "101"), r("S2", "-1"), r("S3", "50"));
        SchemaValidator.Report report = SchemaValidator.validate(t, settings);
        assertEquals(List.of("score must be between 0 and 100 (2 row(s) out of range)"), report.errors());
        assertEquals(2, report.rowIssues().size());
        assertEquals("score_out_of_range", report.rowIssues().get(0).rule());
        assertEquals(1, report.rowIssues().get(0).row());
        assertEquals(2, report.rowIssues().get(1).row());
    }

    @Test
    void duplicateStudentIdsFail() {
        Table t = table(h("student_id", "score"), r("S1", "80"), r("S1", "90"));
        assertEquals(List.of("Duplicate rows detected: 1 duplicate student_id value(s)"),
                SchemaValidator.validate(t, settings).errors());
    }

    @Test
    void sameStudentInDifferentSubjectsIsNotADuplicate() {
        Table t = table(h("student_id", "score", "subject"), r("S1", "80", "math"), r("S1", "90", "science"));
        assertTrue(SchemaValidator.validate(t, settings).ok());
        Table dup = table(h("student_id", "score", "subject"), r("S1", "80", "math"), r("S1", "90", "math"));
        assertFalse(SchemaValidator.validate(dup, settings).ok());
    }

    @Test
    void blankKeysFailWithoutBeingCountedAsDuplicates() {
        Table t = table(h("student_id", "score"), r("", "80"), r(" ", "70"));
        assertEquals(List.of("Critical fields empty: student_id (2 row(s))"),
                SchemaValidator.validate(t, settings).errors());
    }

    @Test
    void mostlyEmptyRequiredColumnWarnsButPasses() {
        Table t = table(h("student_id", "score"), r("S1", ""), r("S2", ""), r("S3", "50"));
        SchemaValidator.Report report = SchemaValidator.validate(t, settings);
        assertTrue(report.ok());
        assertEquals(List.of("Column 'score' is 66.7% empty"), report.warnings());
    }

    @Test
    void rangeCheckCanBeDisabledForMarksOutOfFiveHundred() {
        SchemaValidator.Settings lenient = new SchemaValidator.Settings(
                List.of("student_id", "score"), true, false, 0, 100, true);
        Table t = table(h("student_id", "score"), r("S1", "450"));
        assertTrue(SchemaValidator.validate(t, lenient).ok());
    }
}
