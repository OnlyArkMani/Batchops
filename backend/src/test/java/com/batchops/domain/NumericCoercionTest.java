package com.batchops.domain;

import static com.batchops.domain.TestTables.h;
import static com.batchops.domain.TestTables.r;
import static com.batchops.domain.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NumericCoercionTest {

    @Test
    void thousandsSeparatorsAndPercentSignsAreCleaned() {
        Table t = table(h("student_id", "score", "notes"),
                r("S1", "1,234", "a"), r("S2", "85%", "b"), r("S3", "x", "c"));
        List<String> converted = NumericCoercion.coerce(t);
        assertEquals(List.of("score"), converted);
        assertEquals("1234", t.cell(0, 1));
        assertEquals("85", t.cell(1, 1));
        assertEquals("", t.cell(2, 1));
        assertTrue(t.isNumeric("score"));
        assertFalse(t.isNumeric("student_id"));
    }

    @Test
    void identifierColumnsAreNeverConverted() {
        Table t = table(h("student_id", "roll"), r("101", "5"), r("102", "6"));
        assertEquals(List.of(), NumericCoercion.coerce(t));
    }

    @Test
    void unhintedColumnsNeedSixtyPercentNumbers() {
        Table low = table(h("items"), r("1"), r("x"), r("y"));
        assertEquals(List.of(), NumericCoercion.coerce(low));
        Table high = table(h("items"), r("1"), r("2"), r("x"));
        assertEquals(List.of("items"), NumericCoercion.coerce(high));
    }

    @Test
    void parseHandlesEdgeInputs() {
        assertNull(Numeric.parse(null));
        assertNull(Numeric.parse("  "));
        assertNull(Numeric.parse("nan"));
        assertNull(Numeric.parse("5d"));
        assertEquals(12.5, Numeric.parse(" 12.5 "), 0.0001);
        assertEquals("100", Numeric.format(100.0));
        assertEquals("0.5", Numeric.format(0.5));
        assertEquals("76.67", Numeric.formatStat(76.6666));
        assertEquals("100", Numeric.formatStat(100.0));
        assertEquals("12.5%", Numeric.formatPercent(0.125));
        assertEquals("1,234", Numeric.formatInt(1234));
    }
}
