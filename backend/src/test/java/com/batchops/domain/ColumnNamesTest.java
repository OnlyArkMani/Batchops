package com.batchops.domain;

import static com.batchops.domain.TestTables.h;
import static com.batchops.domain.TestTables.r;
import static com.batchops.domain.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnNamesTest {

    @Test
    void normalizeCollapsesSpacesAndPunctuation() {
        assertEquals("student_id", ColumnNames.normalize(" Student  ID! "));
        assertEquals("total_marks_out_of_100", ColumnNames.normalize("Total-Marks (out of 100)"));
        assertEquals("", ColumnNames.normalize(null));
        assertEquals("a_b", ColumnNames.normalize("__a  b__"));
    }

    @Test
    void aliasesResolveCommonSchoolHeaders() {
        Table t = table(h("Roll No", "Name", "Marks", "Class"), r("S1", "Asha", "90", "10-A"));
        ColumnNames.normalizeAll(t);
        ColumnNames.AliasResult result = ColumnNames.applyAliases(t);
        assertEquals(List.of("student_id", "student_name", "score", "class"), t.headers());
        assertEquals(3, result.matched().size());
        assertTrue(result.changed());
    }

    @Test
    void gradeMapsToScoreBeforeClass() {
        Table t = table(h("id", "grade"), r("S1", "88"));
        ColumnNames.applyAliases(t);
        assertEquals(List.of("student_id", "score"), t.headers());
    }

    @Test
    void existingCanonicalColumnIsNeverOverwritten() {
        Table t = table(h("student_id", "roll_no"), r("S1", "R1"));
        ColumnNames.AliasResult result = ColumnNames.applyAliases(t);
        assertFalse(result.changed());
        assertEquals(List.of("student_id", "roll_no"), t.headers());
    }
}
