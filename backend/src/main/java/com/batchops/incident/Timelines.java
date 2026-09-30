package com.batchops.incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable-style helper for the JSON timelines on incidents and tickets. */
public final class Timelines {

    private Timelines() {
    }

    public static List<Map<String, Object>> with(List<Map<String, Object>> current, String event, String actor,
                                                 String notes) {
        List<Map<String, Object>> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("timestamp", Instant.now().toString());
        e.put("event", event);
        e.put("actor", actor);
        e.put("notes", notes);
        next.add(e);
        return next;
    }

    public static String trunc(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
