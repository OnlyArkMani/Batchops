package com.batchops.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Renders a simple paginated table PDF (title, header row, grid rows). */
public final class PdfReportWriter {

    private static final float MARGIN = 36f;
    private static final float ROW_H = 16f;
    private static final float FONT = 9f;

    private PdfReportWriter() {
    }

    public static byte[] render(String title, List<String> columns, List<List<String>> rows) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDRectangle size = columns.size() > 6 ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                : PDRectangle.A4;
        float usable = size.getWidth() - 2 * MARGIN;
        float colW = usable / Math.max(1, columns.size());
        int maxChars = Math.max(4, (int) (colW / (FONT * 0.52f)));

        try (PDDocument doc = new PDDocument()) {
            PDPageContentStream cs = null;
            float y = 0;
            boolean firstPage = true;
            try {
                for (int i = -1; i < rows.size(); i++) {
                    if (cs == null || y < MARGIN + ROW_H) {
                        if (cs != null) {
                            cs.close();
                        }
                        PDPage page = new PDPage(size);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                        y = size.getHeight() - MARGIN;
                        if (firstPage) {
                            text(cs, bold, 13, MARGIN, y, clean(title, 120));
                            y -= 26;
                            firstPage = false;
                        }
                        if (i >= 0) {
                            drawRow(cs, bold, columns, y, colW, maxChars, true);
                            y -= ROW_H;
                        }
                    }
                    if (i == -1) {
                        drawRow(cs, bold, columns, y, colW, maxChars, true);
                    } else {
                        drawRow(cs, font, rows.get(i), y, colW, maxChars, false);
                    }
                    y -= ROW_H;
                }
            } finally {
                if (cs != null) {
                    cs.close();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void drawRow(PDPageContentStream cs, PDType1Font font, List<String> cells, float y, float colW,
                                int maxChars, boolean header) throws IOException {
        for (int c = 0; c < cells.size(); c++) {
            text(cs, font, FONT, MARGIN + c * colW + 2, y - 11, clean(cells.get(c), maxChars));
        }
        cs.setLineWidth(header ? 0.8f : 0.3f);
        cs.moveTo(MARGIN, y - 13);
        cs.lineTo(MARGIN + colW * cells.size(), y - 13);
        cs.stroke();
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    /** Standard-14 fonts only encode WinAnsi; anything else becomes '?'. */
    static String clean(String s, int max) {
        String v = s == null ? "" : s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length() && sb.length() < max; i++) {
            char ch = v.charAt(i);
            sb.append(ch >= 32 && ch < 127 ? ch : (ch >= 160 && ch <= 255 ? ch : '?'));
        }
        if (v.length() > max && max > 1) {
            sb.setLength(max - 1);
            sb.append('~');
        }
        return sb.toString();
    }
}
