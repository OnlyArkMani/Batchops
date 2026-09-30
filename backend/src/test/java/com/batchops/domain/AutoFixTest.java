package com.batchops.domain;

import static com.batchops.domain.TestTables.h;
import static com.batchops.domain.TestTables.r;
import static com.batchops.domain.TestTables.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutoFixTest {

    @Test
    void clipScoresIntoRangeAndReportsNote() {
        Table t = table(h("student_id", "score"), r("S1", "120"), r("S2", "-5"), r("S3", "50"));
        AutoFix.Outcome out = AutoFix.apply(t, List.of(FixAction.CLIP_SCORES), 0, 100);
        assertTrue(out.changed());
        assertTrue(out.notes().contains("Clipped 2 score value(s) into 0-100"));
        assertEquals("100", out.table().cell(0, 1));
        assertEquals("0", out.table().cell(1, 1));
        assertEquals("120", t.cell(0, 1));
    }

    @Test
    void dedupeKeepsFirstOccurrence() {
        Table t = table(h("student_id", "score"), r("S1", "80"), r("S1", "90"), r("S2", "70"));
        AutoFix.Outcome out = AutoFix.apply(t, List.of(FixAction.DEDUPE_STUDENTS), 0, 100);
        assertEquals(2, out.table().rowCount());
        assertEquals("80", out.table().cell(0, 1));
        assertEquals(1, out.affectedRows());
    }

    @Test
    void aliasFixRenamesColumns() {
        Table t = table(h("roll_no", "marks"), r("S1", "80"));
        AutoFix.Outcome out = AutoFix.apply(t, List.of(FixAction.ALIAS_COLUMNS), 0, 100);
        assertEquals(List.of("student_id", "score"), out.table().headers());
    }

    @Test
    void noOpFixDoesNotReportChange() {
        Table t = table(h("student_id", "score"), r("S1", "80"));
        assertFalse(AutoFix.apply(t, List.of(FixAction.ALIAS_COLUMNS), 0, 100).changed());
    }

    @Test
    void promoteHeaderUsesFirstRow() {
        Table t = table(h("c0", "c1"), r("student_id", "score"), r("S1", "80"));
        Table p = AutoFix.promoteHeader(t);
        assertEquals(List.of("student_id", "score"), p.headers());
        assertEquals(1, p.rowCount());
    }

    @Test
    void destructiveActionsNeedApprovalUnlessAllowed() {
        List<FixAction> all = List.of(FixAction.ALIAS_COLUMNS, FixAction.DEDUPE_STUDENTS);
        FixAction.Plan strict = FixAction.plan(all, false);
        assertEquals(List.of(FixAction.ALIAS_COLUMNS), strict.auto());
        assertEquals(List.of(FixAction.DEDUPE_STUDENTS), strict.needsApproval());
        FixAction.Plan open = FixAction.plan(all, true);
        assertEquals(all, open.auto());
        assertTrue(open.needsApproval().isEmpty());
    }
}
