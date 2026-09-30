package com.batchops.domain;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Upload lifecycle. Legal transitions are enforced so retries and races cannot corrupt state. */
public enum UploadStatus {
    PENDING, PROCESSING, PUBLISHED, FAILED;

    private static final Set<UploadStatus> FROM_PENDING = EnumSet.of(PROCESSING, FAILED);
    private static final Set<UploadStatus> FROM_PROCESSING = EnumSet.of(PROCESSING, PUBLISHED, FAILED);
    private static final Set<UploadStatus> FROM_PUBLISHED = EnumSet.of(PROCESSING);
    private static final Set<UploadStatus> FROM_FAILED = EnumSet.of(PROCESSING);

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static UploadStatus parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public boolean canMoveTo(UploadStatus next) {
        return switch (this) {
            case PENDING -> FROM_PENDING.contains(next);
            case PROCESSING -> FROM_PROCESSING.contains(next);
            case PUBLISHED -> FROM_PUBLISHED.contains(next);
            case FAILED -> FROM_FAILED.contains(next);
        };
    }

    public UploadStatus moveTo(UploadStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalTransitionException("Upload", wire(), next.wire());
        }
        return next;
    }
}
