package com.batchops.domain;

import static com.batchops.domain.TestTables.h;
import static com.batchops.domain.TestTables.r;
import static com.batchops.domain.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessingPlanTest {

    private Table base() {
        return table(h("student_id", "score"), r("S1", "90"), r("S2", "80"));
    }

    @Test
    void defaultModeIsStandardTransform() {
        ProcessingPlan.Result res = ProcessingPlan.apply(base(), null, null);
        assertEquals("Standard transform applied", res.summary());
        assertTrue(ProcessingPlan.isSummaryMode(null));
        assertFalse(ProcessingPlan.isSummaryMode("delete_record"));
    }

    @Test
    void deleteByColumnAndValue() {
        Table input = base();
        ProcessingPlan.Result res = ProcessingPlan.apply(input, "delete_record",
                Map.of("column", "student_id", "value", "S1"));
        assertEquals(1, res.table().rowCount());
        assertEquals("Removed 1 row(s) where student_id == S1", res.summary());
        assertEquals(2, input.rowCount());
    }

    @Test
    void deleteRulesMatchNumbersLoosely() {
        ProcessingPlan.Result res = ProcessingPlan.apply(base(), "delete_record",
                Map.of("rules", List.of(Map.of("column", "score", "value", 80.0))));
        assertEquals(1, res.table().rowCount());
        assertEquals("Removed 1 row(s) via 1 rule(s)", res.summary());
    }

    @Test
    void deleteReportsMissingColumn() {
        ProcessingPlan.Result res = ProcessingPlan.apply(base(), "delete_record", Map.of("column", "nope", "value", "x"));
        assertEquals("Delete skipped (column 'nope' missing)", res.summary());
        assertEquals(2, res.table().rowCount());
    }

    @Test
    void appendAddsRowsAndNewColumns() {
        ProcessingPlan.Result res = ProcessingPlan.apply(base(), "append_record",
                Map.of("records", List.of(Map.of("student_id", "S3", "score", 70, "extra", "x"))));
        assertEquals(3, res.table().rowCount());
        assertTrue(res.table().hasColumn("extra"));
        assertEquals("70", res.table().cell(2, res.table().indexOf("score")));
        assertEquals("", res.table().cell(0, res.table().indexOf("extra")));
        assertEquals("Appended 1 custom row(s)", res.summary());
    }

    @Test
    void appendWithoutValidRowsIsSkipped() {
        ProcessingPlan.Result res = ProcessingPlan.apply(base(), "append_record", Map.of("records", List.of("bad")));
        assertEquals("Append skipped (no valid rows)", res.summary());
    }

    @Test
    void customRulesOnlyRecordTheNote() {
        assertEquals("Custom instructions noted: check", ProcessingPlan.apply(base(), "custom_rules",
                Map.of("notes", "check")).summary());
        assertEquals("Custom instructions captured", ProcessingPlan.apply(base(), "custom_rules", Map.of()).summary());
    }
}
