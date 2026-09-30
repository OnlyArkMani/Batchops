package com.batchops.domain;

/** Schedules are stored as classic 5-field cron ("minute hour day month weekday"). */
public final class CronSupport {

    private CronSupport() {
    }

    /**
     * Converts a 5-field cron to Spring's 6-field form by prepending a zero seconds field.
     * A 6-field expression is passed through untouched.
     */
    public static String toSpring(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("Cron expression is empty");
        }
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 5) {
            return "0 " + String.join(" ", parts);
        }
        if (parts.length == 6) {
            return String.join(" ", parts);
        }
        throw new IllegalArgumentException("Cron must have 5 fields: minute hour day month weekday");
    }
}
