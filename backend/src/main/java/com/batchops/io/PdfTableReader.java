package com.batchops.io;

import com.batchops.domain.StageException;
import com.batchops.domain.Table;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Best-effort table extraction from text PDFs: comma-separated lines first, then column gaps
 * (two or more spaces), then single-space tokens, aligning ragged rows to the header width.
 */
public final class PdfTableReader {

    private static final Pattern GAP = Pattern.compile("\\s{2,}");
    private static final Pattern NUMERIC = Pattern.compile("^[-+]?\\d+([.,]\\d+)?%?$");

    private PdfTableReader() {
    }

    public static Table read(byte[] data) throws IOException {
        String text;
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(doc);
        }
        return fromText(text);
    }

    static Table fromText(String text) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        if (lines.isEmpty()) {
            throw StageException.permanent("No table found in PDF pages");
        }

        List<List<String>> rows = new ArrayList<>();
        boolean commas = lines.stream().limit(10).anyMatch(l -> l.contains(","));
        if (commas) {
            try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(String.join("\n", lines)))) {
                for (CSVRecord rec : parser) {
                    List<String> row = new ArrayList<>();
                    for (String s : rec) {
                        row.add(s.strip());
                    }
                    if (row.stream().anyMatch(s -> !s.isEmpty())) {
                        rows.add(row);
                    }
                }
            }
        }
        if (rows.isEmpty() || rows.get(0).size() < 2) {
            rows.clear();
            for (String line : lines) {
                List<String> cols = new ArrayList<>();
                for (String c : GAP.split(line)) {
                    if (!c.isBlank()) {
                        cols.add(c.strip());
                    }
                }
                if (cols.size() <= 1) {
                    cols = new ArrayList<>();
                    for (String c : line.split("\\s+")) {
                        if (!c.isBlank()) {
                            cols.add(c);
                        }
                    }
                }
                if (!cols.isEmpty()) {
                    rows.add(cols);
                }
            }
        }
        if (rows.size() < 2 || rows.get(0).size() < 2) {
            throw StageException.permanent("No table found in PDF pages");
        }
        List<String> header = rows.get(0);
        int width = header.size();
        List<List<String>> body = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            body.add(align(rows.get(i), width));
        }
        return new Table(new ArrayList<>(header), body);
    }

    /** Pads short rows; merges surplus tokens into the first text column after the id (names with spaces). */
    static List<String> align(List<String> row, int width) {
        List<String> out = new ArrayList<>(row);
        if (out.size() > width) {
            int surplus = out.size() - width;
            int at = 1;
            for (int i = 1; i < out.size(); i++) {
                if (!NUMERIC.matcher(out.get(i)).matches()) {
                    at = i;
                    break;
                }
            }
            StringBuilder merged = new StringBuilder(out.get(at));
            for (int k = 0; k < surplus; k++) {
                merged.append(' ').append(out.get(at + 1));
                out.remove(at + 1);
            }
            out.set(at, merged.toString());
        }
        while (out.size() < width) {
            out.add("");
        }
        return out;
    }
}
