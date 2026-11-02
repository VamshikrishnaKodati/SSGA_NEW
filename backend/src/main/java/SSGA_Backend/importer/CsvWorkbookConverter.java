package SSGA_Backend.importer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Converts an uploaded CSV file into a single-sheet .xlsx workbook so every
 * downstream import step (analysis, mapping, validation, finalize) can treat it
 * exactly like an Excel upload.
 *
 * ponytail: hand-rolled RFC-4180-ish parser (quoted fields, embedded commas,
 * doubled quotes, CRLF/LF, UTF-8 BOM). Handles the SSGA exports; add a real CSV
 * library only if a producer emits a dialect this cannot read.
 */
final class CsvWorkbookConverter {

    private CsvWorkbookConverter() {
    }

    static void writeWorkbook(
            byte[] bytes,
            String originalName,
            Path destination
    ) throws IOException {

        String text = new String(bytes, StandardCharsets.UTF_8);

        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        List<List<String>> records = parse(text);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName(originalName));

            for (int rowIndex = 0; rowIndex < records.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex);
                List<String> record = records.get(rowIndex);

                for (int column = 0; column < record.size(); column++) {
                    Cell cell = row.createCell(column);
                    cell.setCellValue(record.get(column));
                }
            }

            try (OutputStream output = Files.newOutputStream(destination)) {
                workbook.write(output);
            }
        }
    }

    static List<List<String>> parse(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean started = false;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (quoted) {
                if (character == '"') {
                    if (index + 1 < text.length()
                            && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(character);
                }

                started = true;
                continue;
            }

            switch (character) {
                case '"' -> {
                    quoted = true;
                    started = true;
                }
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    started = true;
                }
                case '\r' -> {
                    // handled with the following \n, if any
                }
                case '\n' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    records.add(fields);
                    fields = new ArrayList<>();
                    started = false;
                }
                default -> {
                    field.append(character);
                    started = true;
                }
            }
        }

        if (started || field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            records.add(fields);
        }

        return records;
    }

    private static String sheetName(String originalName) {
        String base = originalName == null ? "" : originalName;

        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }

        base = base.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();

        if (base.isEmpty()) {
            base = "Sheet1";
        }

        return base.length() > 31 ? base.substring(0, 31) : base;
    }
}
