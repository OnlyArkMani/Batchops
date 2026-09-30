package com.batchops.io;

import com.batchops.domain.Table;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/** CSV (de)serialization of {@link Table}. Artifacts between pipeline stages are plain CSV. */
public final class TableIO {

    private TableIO() {
    }

    /** Strict decode so a wrong encoding surfaces as a classifiable error instead of mojibake. */
    public static String decode(byte[] data, Charset charset) throws CharacterCodingException {
        String text = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    public static Table parseCsv(String text) throws IOException {
        List<List<String>> all = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).setTrim(false).build()
                .parse(new StringReader(text))) {
            for (CSVRecord rec : parser) {
                List<String> row = new ArrayList<>(rec.size());
                for (String s : rec) {
                    row.add(s == null ? "" : s);
                }
                all.add(row);
            }
        }
        return fromRows(all);
    }

    public static Table fromRows(List<List<String>> input) {
        // Leading rows that are entirely blank (",,," exports) are skipped so the next row is the header.
        int start = 0;
        while (start < input.size() && input.get(start).stream().allMatch(c -> c == null || c.isBlank())) {
            start++;
        }
        if (start >= input.size()) {
            return Table.empty();
        }
        List<List<String>> all = input.subList(start, input.size());
        List<String> header = new ArrayList<>();
        for (String h : all.get(0)) {
            header.add(h == null ? "" : h.trim());
        }
        int width = header.size();
        List<List<String>> body = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) {
            List<String> row = new ArrayList<>(all.get(i));
            while (row.size() < width) {
                row.add("");
            }
            if (row.size() > width) {
                row = new ArrayList<>(row.subList(0, width));
            }
            body.add(row);
        }
        return new Table(header, body);
    }

    public static byte[] toCsvBytes(List<String> headers, List<List<String>> rows) {
        return toCsv(headers, rows).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] toCsvBytes(Table t) {
        return toCsvBytes(t.headers(), t.rows());
    }

    public static String toCsv(List<String> headers, List<List<String>> rows) {
        StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            printer.printRecord(headers);
            for (List<String> r : rows) {
                printer.printRecord(r);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return sw.toString();
    }

    public static byte[] utf8WithBom(String csv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        out.writeBytes(csv.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
