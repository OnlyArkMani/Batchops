package com.batchops.domain;

import static com.batchops.domain.TestTables.h;
import static com.batchops.domain.TestTables.r;
import static com.batchops.domain.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryBuilderTest {

    @Test
    void describeUsesLinearInterpolationPercentiles() {
        Map<String, Double> d = SummaryBuilder.describe(new double[] {1, 2, 3, 4});
        assertEquals(1.75, d.get("25%"), 1e-9);
        assertEquals(2.5, d.get("50%"), 1e-9);
        assertEquals(3.25, d.get("75%"), 1e-9);
        assertEquals(2.5, d.get("mean"), 1e-9);
        assertEquals(1.0, d.get("min"), 1e-9);
        assertEquals(4.0, d.get("max"), 1e-9);
    }

    @Test
    void countsMissingAndDuplicates() {
        Table t = table(h("student_id", "attendance"), r("S1", "90"), r("S1", "90"), r("S2", ""), r("S3", "80"));
        t.markNumeric("attendance");
        SummaryBuilder.Summary s = SummaryBuilder.build(t, "Examination", "f.csv", "transform", "",
                ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertEquals(4, s.rowCount());
        assertEquals(2, s.colCount());
        assertEquals(1, s.missingCells());
        assertEquals(1, s.duplicateRows());
        assertEquals(1.0 / 8.0, s.missingRate(), 1e-9);
        assertTrue(s.numericColumns().contains("attendance"));
        assertTrue(!s.rows().isEmpty());
    }

    @Test
    void emptyTableDoesNotDivideByZero() {
        SummaryBuilder.Summary s = SummaryBuilder.build(Table.empty(), "X", "f.csv", "transform", "",
                ZonedDateTime.now(ZoneOffset.UTC));
        assertEquals(0.0, s.missingRate(), 0.0);
        assertEquals(0, s.rowCount());
    }
}
