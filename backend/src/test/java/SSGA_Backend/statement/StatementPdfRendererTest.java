package SSGA_Backend.statement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class StatementPdfRendererTest {

    @Test
    void rendersDeterministicStyledPdf() throws IOException {
        Map<String, Object> payload = payload(4, 3);

        byte[] first = StatementPdfRenderer.render(payload);
        byte[] second = StatementPdfRenderer.render(payload);

        Files.write(Path.of("target", "statement-sample-single.pdf"), first);

        assertArrayEquals(first, second, "PDF output must be deterministic");

        String text = extractText(first);

        assertTrue(text.contains("HMWSSB")
                || text.contains("HYDERABAD METROPOLITAN")
                || text.contains("WATER SUPPLY"), "missing institution header");
        assertTrue(text.contains("HYDERABAD METROPOLITAN")
                || text.contains("WATER SUPPLY"), "missing board name");
        assertTrue(text.contains("NET SALARY PAYABLE"), "missing net band");
        assertTrue(text.contains("42,000.00"), "amounts must use 2 decimals with grouping");
        assertTrue(text.contains("SSGA-STMT-v1"), "missing template reference");
        assertTrue(text.contains("Page 1 of 1"), "footer must show the page total");
    }

    @Test
    void paginatesLongContentWithRepeatedHeaders() throws IOException {
        byte[] pdf = StatementPdfRenderer.render(payload(60, 44));

        Files.write(Path.of("target", "statement-sample-multipage.pdf"), pdf);

        PdfReader reader = new PdfReader(pdf);
        try {
            assertTrue(reader.getNumberOfPages() >= 2,
                    "expected multiple pages, got " + reader.getNumberOfPages());
            assertTrue(extractText(pdf).contains("Salary Statement (continued)"),
                    "continuation pages must repeat the header");
            assertTrue(extractText(pdf).contains("Page 2 of " + reader.getNumberOfPages()),
                    "footer must show the page total");
        } finally {
            reader.close();
        }
    }

    @Test
    void handlesEmptyPayloadWithoutFailing() throws IOException {
        byte[] pdf = StatementPdfRenderer.render(Map.of());

        PdfReader reader = new PdfReader(pdf);
        try {
            assertEquals(1, reader.getNumberOfPages());
        } finally {
            reader.close();
        }
    }

    private static String extractText(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder text = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);

            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }

            return text.toString();
        } finally {
            reader.close();
        }
    }

    private static Map<String, Object> payload(int earningCount, int deductionCount) {
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("id", 1);
        period.put("label", "2026-09");
        period.put("year", 2026);
        period.put("month", 9);

        Map<String, Object> employee = new LinkedHashMap<>();
        employee.put("id", 1);
        employee.put("code", "EMP-0001");
        employee.put("hrms_id", "T-1001");
        employee.put("name", "Ravi Kumar");

        Map<String, Object> division = new LinkedHashMap<>();
        division.put("id", 5);
        division.put("name", "ENG-A1");

        List<Map<String, Object>> earnings = new ArrayList<>();
        List<Map<String, Object>> deductions = new ArrayList<>();

        for (int index = 0; index < earningCount; index++) {
            earnings.add(item("E" + index, "Earning " + (index + 1), 4200.5));
        }

        for (int index = 0; index < deductionCount; index++) {
            deductions.add(item("D" + index, "Deduction " + (index + 1), 125.25));
        }

        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("id", 9001);
        transaction.put("gross_salary", 42000);
        transaction.put("total_deductions", 3500);
        transaction.put("net_salary", 38500);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("gross", 42000);
        totals.put("deductions", 3500);
        totals.put("net", 38500);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("template_version", "SSGA-STMT-v1");
        payload.put("batch_id", 12);
        payload.put("period", period);
        payload.put("employee", employee);
        payload.put("division", division);
        payload.put("wing_id", 5);
        payload.put("transaction", transaction);
        payload.put("earnings", earnings);
        payload.put("deductions", deductions);
        payload.put("totals", totals);
        return payload;
    }

    private static Map<String, Object> item(String code, String name, double amount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", name);
        item.put("category", "EARNING");
        item.put("amount", amount);
        return item;
    }
}
