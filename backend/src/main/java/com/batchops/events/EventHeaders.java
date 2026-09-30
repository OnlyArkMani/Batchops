package com.batchops.events;

/** Kafka header names used by the pipeline. */
public final class EventHeaders {
    public static final String EVENT_ID = "event-id";
    public static final String ATTEMPT = "attempt";
    public static final String NOT_BEFORE = "not-before";
    public static final String ORIGIN_TOPIC = "origin-topic";
    public static final String LAST_ERROR = "last-error";

    private EventHeaders() {
    }
}
