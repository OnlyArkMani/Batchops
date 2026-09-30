package com.batchops.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tiny builders so tests read like the data they describe. */
final class TestTables {

    private TestTables() {
    }

    static Table table(String[] headers, String[]... rows) {
        List<List<String>> body = new ArrayList<>();
        for (String[] r : rows) {
            body.add(new ArrayList<>(Arrays.asList(r)));
        }
        return new Table(Arrays.asList(headers), body);
    }

    static String[] h(String... headers) {
        return headers;
    }

    static String[] r(String... cells) {
        return cells;
    }
}
