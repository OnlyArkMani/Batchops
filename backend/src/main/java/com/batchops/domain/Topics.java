package com.batchops.domain;

/** Kafka topic names. Every topic is keyed by upload_id so one upload is always processed in order. */
public final class Topics {

    public static final String RECEIVED = "uploads.received";
    public static final String STANDARDIZED = "uploads.standardized";
    public static final String VALIDATED = "uploads.validated";
    public static final String TRANSFORMED = "uploads.transformed";
    public static final String PUBLISHED = "uploads.published";
    public static final String FAILED = "uploads.failed";
    public static final String STATUS = "uploads.status";
    public static final String DLQ = "uploads.dlq";
    public static final String RETRY_PREFIX = "uploads.retry.";
    public static final String RETRY_5S = RETRY_PREFIX + "5s";
    public static final String RETRY_1M = RETRY_PREFIX + "1m";
    public static final String RETRY_10M = RETRY_PREFIX + "10m";

    private Topics() {
    }
}
