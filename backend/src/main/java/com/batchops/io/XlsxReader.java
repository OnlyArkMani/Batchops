package com.batchops.io;

import com.batchops.domain.Table;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Reads the first sheet of an .xls/.xlsx workbook as strings (formulas are evaluated). */
public final class XlsxReader {

    private XlsxReader() {
    }

    public static Table read(byte[] data) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            if (wb.getNumberOfSheets() == 0) {
                return Table.empty();
            }
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            List<List<String>> rows = new ArrayList<>();
            int width = 0;
            for (Row row : sheet) {
                width = Math.max(width, Math.max(0, row.getLastCellNum()));
            }
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>(width);
                for (int c = 0; c < width; c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : fmt.formatCellValue(cell, eval).trim());
                }
                boolean blank = cells.stream().allMatch(String::isEmpty);
                if (!blank) {
                    rows.add(cells);
                }
            }
            return TableIO.fromRows(rows);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Unreadable spreadsheet: " + e.getMessage(), e);
        }
    }
}
