package com.batchops.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** DRF-compatible pagination: ?page=, ?page_size= (default and max 5000), ?ordering=-field. */
public final class Paging {

    public static final int DEFAULT_SIZE = 5000;

    private Paging() {
    }

    public static Pageable pageable(HttpServletRequest req, Set<String> orderable, String defaultOrdering) {
        int page = Math.max(1, intParam(req.getParameter("page"), 1));
        int size = Math.min(DEFAULT_SIZE, Math.max(1, intParam(req.getParameter("page_size"), DEFAULT_SIZE)));
        return PageRequest.of(page - 1, size, sort(req.getParameter("ordering"), orderable, defaultOrdering));
    }

    static Sort sort(String ordering, Set<String> orderable, String fallback) {
        List<Sort.Order> orders = new java.util.ArrayList<>();
        String spec = ordering == null || ordering.isBlank() ? fallback : ordering;
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            boolean desc = p.startsWith("-");
            String field = desc ? p.substring(1) : p;
            if (!orderable.contains(field)) {
                continue; // unknown fields are ignored rather than becoming a query-injection surface
            }
            String prop = camel(field);
            orders.add(desc ? Sort.Order.desc(prop) : Sort.Order.asc(prop));
        }
        if (orders.isEmpty()) {
            return sort(null, orderable, fallback.equals(spec) ? orderable.iterator().next() : fallback);
        }
        return Sort.by(orders);
    }

    public static <E> Map<String, Object> envelope(Page<E> page, HttpServletRequest req,
                                                   Function<E, Map<String, Object>> mapper) {
        int current = page.getNumber() + 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", page.getTotalElements());
        out.put("next", page.hasNext() ? link(req, current + 1) : null);
        out.put("previous", page.hasPrevious() ? link(req, current - 1) : null);
        out.put("results", page.getContent().stream().map(mapper).toList());
        return out;
    }

    private static String link(HttpServletRequest req, int page) {
        return ServletUriComponentsBuilder.fromRequest(req).replaceQueryParam("page", page).build().toUriString();
    }

    static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    private static int intParam(String v, int dflt) {
        try {
            return v == null ? dflt : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
