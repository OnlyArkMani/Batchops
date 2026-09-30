package com.batchops.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StateMachineTest {

    @Test
    void uploadTransitionsAreGuarded() {
        assertEquals(UploadStatus.PROCESSING, UploadStatus.PENDING.moveTo(UploadStatus.PROCESSING));
        assertFalse(UploadStatus.PENDING.canMoveTo(UploadStatus.PUBLISHED));
        assertThrows(IllegalTransitionException.class, () -> UploadStatus.PENDING.moveTo(UploadStatus.PUBLISHED));
        assertTrue(UploadStatus.FAILED.canMoveTo(UploadStatus.PROCESSING));
        assertEquals(UploadStatus.FAILED, UploadStatus.parse(" Failed "));
    }

    @Test
    void retryTiersEscalateThenGiveUp() {
        assertEquals("uploads.retry.5s", RetryPolicy.tierAfterFailure(1).orElseThrow().topic());
        assertEquals("uploads.retry.1m", RetryPolicy.tierAfterFailure(2).orElseThrow().topic());
        assertEquals("uploads.retry.10m", RetryPolicy.tierAfterFailure(3).orElseThrow().topic());
        assertEquals(Optional.empty(), RetryPolicy.tierAfterFailure(4));
        assertEquals(Optional.empty(), RetryPolicy.tierAfterFailure(0));
    }

    @Test
    void classificationWalksCauseChain() {
        assertEquals(StageException.Kind.PERMANENT, RetryPolicy.classify(new FileNotFoundException("x")));
        assertEquals(StageException.Kind.TRANSIENT, RetryPolicy.classify(new IOException("disk")));
        assertEquals(StageException.Kind.PERMANENT,
                RetryPolicy.classify(new RuntimeException("wrap", new IllegalArgumentException("bad"))));
        assertEquals(StageException.Kind.PERMANENT, RetryPolicy.classify(StageException.permanent("no")));
        assertEquals(StageException.Kind.TRANSIENT, RetryPolicy.classify(new IllegalStateException("?")));
    }

    @Test
    void fingerprintIgnoresVolatileParts() {
        String a = IncidentFingerprint.of("u1", "validate_results", "Duplicate rows detected: 3 duplicate student_id value(s)");
        String b = IncidentFingerprint.of("u1", "validate_results", "Duplicate rows detected: 9 duplicate student_id value(s)");
        String c = IncidentFingerprint.of("u2", "validate_results", "Duplicate rows detected: 3 duplicate student_id value(s)");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(32, a.length());
    }

    @Test
    void cronIsConvertedToSpringSixFieldForm() {
        assertEquals("0 0 3 * * 0", CronSupport.toSpring("0 3 * * 0"));
        assertEquals("0 0 3 * * 0", CronSupport.toSpring("0 0 3 * * 0"));
        assertThrows(IllegalArgumentException.class, () -> CronSupport.toSpring("* *"));
    }

    @Test
    void stagesChainThroughTopics() {
        assertEquals(Stage.VALIDATOR, Stage.fromInputTopic(Stage.STANDARDIZER.outputTopic()).orElseThrow());
        assertEquals(Stage.PUBLISHER, Stage.fromStep("generate_summary").orElseThrow());
    }

    @Test
    void everyValidatorErrorIsReachableByAKnownErrorPattern() {
        String[] errors = {
            "No columns detected", "No rows detected", "Required columns missing: score",
            "Critical fields empty: student_id (2 row(s))",
            "score must be between 0 and 100 (2 row(s) out of range)",
            "Duplicate rows detected: 1 duplicate student_id value(s)"
        };
        for (String e : errors) {
            boolean hit = KnownErrorCatalog.defaults().stream()
                    .anyMatch(d -> KnownErrorCatalog.matches(d.pattern(), e));
            assertTrue(hit, "unreachable: " + e);
        }
    }

    @Test
    void fixActionsFollowErrorText() {
        assertTrue(FixAction.fromError("No columns detected").contains(FixAction.PROMOTE_HEADER));
        assertTrue(FixAction.parse("clip_scores").isPresent());
        assertTrue(FixAction.parse("nope").isEmpty());
    }
}
