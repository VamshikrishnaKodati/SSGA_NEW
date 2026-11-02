package SSGA_Backend.statement;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfEncryption;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfString;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Renders a salary-statement snapshot to a styled A4 PDF.
 *
 * <p>Output is deterministic for identical input: no timestamps are written,
 * fonts are the built-in Type1 Helvetica family, and only the stored snapshot
 * is used. Reissuing the same snapshot therefore reproduces identical bytes.
 */
final class StatementPdfRenderer {

    private static final Color GREEN = new Color(0x06, 0x47, 0x34);
    private static final Color HIGHLIGHT = new Color(0xE0, 0xFF, 0xC2);
    private static final Color CHARCOAL = new Color(0x26, 0x32, 0x38);
    private static final Color MUTED = new Color(0x60, 0x6E, 0x67);
    private static final Color BORDER = new Color(0xE8, 0xEC, 0xE9);
    private static final Color ZEBRA = new Color(0xE8, 0xEC, 0xE9);
    private static final Color DETAIL_BG = new Color(0xF4, 0xF8, 0xF1);
    private static final Color WHITE = Color.WHITE;

    private static final float MARGIN_LEFT = 36f;
    private static final float MARGIN_RIGHT = 36f;
    private static final float MARGIN_TOP = 42f;
    private static final float MARGIN_BOTTOM = 42f;

    private static final Font COMPANY = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 16f, Font.NORMAL, GREEN);
    private static final Font DOCUMENT_TITLE = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 13f, Font.NORMAL, GREEN);
    private static final Font MONTH_LABEL = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 7f, Font.NORMAL, MUTED);
    private static final Font MONTH_VALUE = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 10.5f, Font.NORMAL, GREEN);
    private static final Font SECTION = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 10.5f, Font.NORMAL, GREEN);
    private static final Font MONOGRAM = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 8.5f, Font.NORMAL, WHITE);
    private static final Font LABEL = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 7.2f, Font.NORMAL, MUTED);
    private static final Font VALUE = FontFactory.getFont(
            FontFactory.HELVETICA, 8.8f, Font.NORMAL, CHARCOAL);
    private static final Font VALUE_BOLD = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 8.8f, Font.NORMAL, CHARCOAL);
    private static final Font GROUP = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 9.5f, Font.NORMAL, WHITE);
    private static final Font COLUMN = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 7.2f, Font.NORMAL, WHITE);
    private static final Font TABLE = FontFactory.getFont(
            FontFactory.HELVETICA, 8.5f, Font.NORMAL, CHARCOAL);
    private static final Font BLOCK_LABEL = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 7.5f, Font.NORMAL, MUTED);
    private static final Font BLOCK_VALUE = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 11.5f, Font.NORMAL, CHARCOAL);
    private static final Font NET_LABEL = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 11f, Font.NORMAL, WHITE);
    private static final Font NET_VALUE = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 15f, Font.NORMAL, WHITE);
    private static final Font FOOTER = FontFactory.getFont(
            FontFactory.HELVETICA, 8f, Font.NORMAL, MUTED);
    private static final Font FOOTER_BOLD = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 8f, Font.NORMAL, MUTED);

    private static final String[] MONTHS = {
            "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
            "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
    };

    private static final byte[] LOGO = loadLogo();

    private StatementPdfRenderer() {
    }

    static byte[] render(Map<?, ?> payload) {
        byte[] probe = render(payload, 0);
        return render(payload, pageCount(probe));
    }

    private static byte[] render(Map<?, ?> payload, int totalPages) {
        Map<?, ?> period = asMap(payload.get("period"));
        Map<?, ?> employee = asMap(payload.get("employee"));
        Map<?, ?> division = asMap(payload.get("division"));
        Map<?, ?> totals = asMap(payload.get("totals"));

        String periodHeading = periodHeading(period);
        String reference = reference(payload);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(
                PageSize.A4, MARGIN_LEFT, MARGIN_RIGHT, MARGIN_TOP, MARGIN_BOTTOM);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.getInfo().put(PdfName.CREATIONDATE,
                    new PdfString("D:20000101000000Z"));
            byte[] documentId = "SSGA-STATEMENT01".getBytes(StandardCharsets.US_ASCII);
            writer.getInfo().put(PdfName.FILEID,
                    PdfEncryption.createInfoId(documentId, documentId));
            writer.setPageEvent(new Decorations(periodHeading, reference, totalPages));
            document.open();

            document.add(masthead(periodHeading));
            document.add(rule());
            document.add(details(employee, division, periodHeading, payload));
            document.add(components(payload));
            document.add(summary(totals));

            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException(
                    "Unable to render salary statement PDF.", e);
        }

        return output.toByteArray();
    }

    private static PdfPTable masthead(String periodHeading) {
        PdfPTable table = new PdfPTable(new float[]{46f, 383f, 94f});
        table.setWidthPercentage(100);

        table.addCell(logoCell());

        PdfPCell identity = new PdfPCell();
        identity.setBorder(Rectangle.NO_BORDER);
        identity.setPaddingLeft(8f);
        identity.setPaddingRight(8f);
        identity.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph firstLine = new Paragraph(
                "HYDERABAD METROPOLITAN WATER SUPPLY", COMPANY);
        firstLine.setLeading(17f);
        Paragraph secondLine = new Paragraph(
                "WATER SUPPLY & SEWERAGE BOARD", COMPANY);
        secondLine.setLeading(17f);
        Paragraph title = new Paragraph("SALARY STATEMENT", DOCUMENT_TITLE);
        title.setLeading(16f);
        title.setSpacingBefore(2f);

        identity.addElement(firstLine);
        identity.addElement(secondLine);
        identity.addElement(title);
        table.addCell(identity);

        PdfPCell month = new PdfPCell();
        month.setBorder(Rectangle.NO_BORDER);
        month.setPaddingTop(4f);
        month.setVerticalAlignment(Element.ALIGN_TOP);

        Paragraph monthLabel = new Paragraph("PAY PERIOD", MONTH_LABEL);
        monthLabel.setAlignment(Element.ALIGN_RIGHT);
        monthLabel.setLeading(11f);
        Paragraph monthValue = new Paragraph(periodHeading, MONTH_VALUE);
        monthValue.setAlignment(Element.ALIGN_RIGHT);
        monthValue.setLeading(15f);
        monthValue.setSpacingBefore(2f);

        month.addElement(monthLabel);
        month.addElement(monthValue);
        table.addCell(month);

        return table;
    }

    private static PdfPCell logoCell() {
        PdfPCell cell;
        Image logo = logoImage();

        if (logo != null) {
            logo.scaleToFit(48f, 40f);
            logo.setAlignment(Element.ALIGN_LEFT);
            cell = new PdfPCell(logo);
        } else {
            cell = new PdfPCell(new Phrase("HMWSSB", MONOGRAM));
            cell.setBackgroundColor(GREEN);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setFixedHeight(40f);
            cell.setNoWrap(true);
        }

        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0f);
        cell.setPaddingTop(2f);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPTable rule() {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(7f);
        table.setSpacingAfter(11f);

        PdfPCell cell = new PdfPCell(new Phrase(" ", FOOTER));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(GREEN);
        cell.setBorderWidth(1f);
        cell.setPadding(0f);
        cell.setMinimumHeight(1f);
        table.addCell(cell);
        return table;
    }

    private static PdfPTable details(
            Map<?, ?> employee,
            Map<?, ?> division,
            String periodHeading,
            Map<?, ?> payload
    ) {
        PdfPTable table = new PdfPTable(new float[]{19f, 31f, 19f, 31f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(10f);

        addDetail(table, "EMPLOYEE", value(employee.get("name")), true);
        addDetail(table, "EMPLOYEE CODE", value(employee.get("code")), true);
        addDetail(table, "HRMS ID", value(employee.get("hrms_id")), false);
        addDetail(table, "DIVISION", value(division.get("name")), false);
        addDetail(table, "PAY PERIOD", periodHeading, false);
        addDetail(table, "STATEMENT REF",
                "TXN " + value(transactionId(payload)), false);

        return table;
    }

    private static void addDetail(
            PdfPTable table,
            String label,
            String value,
            boolean emphasize
    ) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, LABEL));
        labelCell.setBackgroundColor(DETAIL_BG);
        labelCell.setPaddingTop(4f);
        labelCell.setPaddingBottom(4f);
        labelCell.setPaddingLeft(5f);
        labelCell.setPaddingRight(4f);
        labelCell.setBorderColor(BORDER);
        labelCell.setBorderWidth(0.5f);
        labelCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        PdfPCell valueCell = new PdfPCell(
                new Phrase(value, emphasize ? VALUE_BOLD : VALUE));
        valueCell.setBackgroundColor(DETAIL_BG);
        valueCell.setPaddingTop(4f);
        valueCell.setPaddingBottom(4f);
        valueCell.setPaddingLeft(5f);
        valueCell.setPaddingRight(5f);
        valueCell.setBorderColor(BORDER);
        valueCell.setBorderWidth(0.5f);
        valueCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private static PdfPTable components(Map<?, ?> payload) {
        List<Object> earnings = asList(payload.get("earnings"));
        List<Object> deductions = asList(payload.get("deductions"));

        PdfPTable table = new PdfPTable(new float[]{35f, 15f, 35f, 15f});
        table.setWidthPercentage(100);
        table.setHeaderRows(2);
        table.setSplitLate(false);
        table.setSplitRows(true);
        table.setSpacingAfter(6f);

        PdfPCell earningsGroup = new PdfPCell(new Phrase("EARNINGS", GROUP));
        earningsGroup.setColspan(2);
        earningsGroup.setBackgroundColor(GREEN);
        earningsGroup.setBorder(Rectangle.NO_BORDER);
        earningsGroup.setPaddingTop(5f);
        earningsGroup.setPaddingBottom(5f);
        earningsGroup.setPaddingLeft(6f);
        table.addCell(earningsGroup);

        PdfPCell deductionsGroup = new PdfPCell(new Phrase("DEDUCTIONS", GROUP));
        deductionsGroup.setColspan(2);
        deductionsGroup.setBackgroundColor(GREEN);
        deductionsGroup.setBorder(Rectangle.NO_BORDER);
        deductionsGroup.setPaddingTop(5f);
        deductionsGroup.setPaddingBottom(5f);
        deductionsGroup.setPaddingLeft(6f);
        table.addCell(deductionsGroup);

        addColumnHeader(table, "COMPONENT", Element.ALIGN_LEFT, false);
        addColumnHeader(table, "AMOUNT (INR)", Element.ALIGN_RIGHT, false);
        addColumnHeader(table, "COMPONENT", Element.ALIGN_LEFT, true);
        addColumnHeader(table, "AMOUNT (INR)", Element.ALIGN_RIGHT, false);

        int rows = Math.max(earnings.size(), deductions.size());

        for (int index = 0; index < rows; index++) {
            Map<?, ?> earning = index < earnings.size()
                    ? asMap(earnings.get(index)) : Map.of();
            Map<?, ?> deduction = index < deductions.size()
                    ? asMap(deductions.get(index)) : Map.of();
            Color background = index % 2 == 0 ? WHITE : ZEBRA;

            addComponent(table, value(earning.get("name")),
                    money(earning.get("amount")), background, 0);
            addComponent(table, value(deduction.get("name")),
                    money(deduction.get("amount")), background, 2);
        }

        if (rows == 0) {
            addComponent(table, "No components recorded", "", WHITE, 0);
            addComponent(table, "", "", WHITE, 2);
        }

        return table;
    }

    private static void addColumnHeader(
            PdfPTable table,
            String text,
            int alignment,
            boolean divider
    ) {
        PdfPCell cell = new PdfPCell(new Phrase(text, COLUMN));
        cell.setBackgroundColor(GREEN);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPaddingTop(3f);
        cell.setPaddingBottom(4f);
        cell.setPaddingLeft(6f);
        cell.setPaddingRight(6f);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(BORDER);
        cell.setBorderWidth(0.5f);

        if (divider) {
            cell.setBorder(Rectangle.BOTTOM | Rectangle.LEFT);
            cell.setBorderColorLeft(WHITE);
            cell.setBorderWidthLeft(0.8f);
        }

        table.addCell(cell);
    }

    private static void addComponent(
            PdfPTable table,
            String name,
            String amount,
            Color background,
            int column
    ) {
        addComponentCell(table, name, Element.ALIGN_LEFT, background, column);
        addComponentCell(table, amount, Element.ALIGN_RIGHT, background, column + 1);
    }

    private static void addComponentCell(
            PdfPTable table,
            String text,
            int alignment,
            Color background,
            int column
    ) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE));
        cell.setBackgroundColor(background);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPaddingTop(3f);
        cell.setPaddingBottom(3f);
        cell.setPaddingLeft(6f);
        cell.setPaddingRight(6f);
        cell.setMinimumHeight(15f);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(BORDER);
        cell.setBorderWidth(0.4f);

        if (column == 2) {
            cell.setBorder(Rectangle.BOTTOM | Rectangle.LEFT);
            cell.setBorderColorLeft(GREEN);
            cell.setBorderWidthLeft(0.7f);
        }

        table.addCell(cell);
    }

    private static PdfPTable summary(Map<?, ?> totals) {
        PdfPTable wrapper = new PdfPTable(1);
        wrapper.setWidthPercentage(100);
        wrapper.setKeepTogether(true);

        PdfPCell content = new PdfPCell();
        content.setBorder(Rectangle.NO_BORDER);
        content.setPadding(0f);

        Paragraph heading = new Paragraph("SALARY SUMMARY", SECTION);
        heading.setLeading(13f);
        heading.setSpacingAfter(4f);
        content.addElement(heading);

        PdfPTable blocks = new PdfPTable(new float[]{49f, 2f, 49f});
        blocks.setWidthPercentage(100);

        blocks.addCell(summaryBlock("GROSS SALARY", totals.get("gross")));
        blocks.addCell(spacer());
        blocks.addCell(summaryBlock("TOTAL DEDUCTIONS", totals.get("deductions")));

        content.addElement(blocks);
        content.addElement(netBand(totals));

        wrapper.addCell(content);
        return wrapper;
    }

    private static PdfPCell summaryBlock(String label, Object amount) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(HIGHLIGHT);
        cell.setBorderColor(BORDER);
        cell.setBorderWidth(0.5f);
        cell.setPaddingTop(6f);
        cell.setPaddingBottom(6f);
        cell.setPaddingLeft(7f);
        cell.setPaddingRight(7f);
        cell.setMinimumHeight(38f);

        Paragraph labelParagraph = new Paragraph(label, BLOCK_LABEL);
        labelParagraph.setLeading(10f);
        Paragraph amountParagraph = new Paragraph(money(amount), BLOCK_VALUE);
        amountParagraph.setAlignment(Element.ALIGN_RIGHT);
        amountParagraph.setLeading(14f);
        amountParagraph.setSpacingBefore(3f);

        cell.addElement(labelParagraph);
        cell.addElement(amountParagraph);
        return cell;
    }

    private static PdfPCell spacer() {
        PdfPCell cell = new PdfPCell(new Phrase("", TABLE));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0f);
        return cell;
    }

    private static PdfPTable netBand(Map<?, ?> totals) {
        PdfPTable table = new PdfPTable(new float[]{68f, 32f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(0f);
        table.setKeepTogether(true);

        PdfPCell label = new PdfPCell(new Phrase("NET SALARY PAYABLE", NET_LABEL));
        label.setBackgroundColor(GREEN);
        label.setBorder(Rectangle.NO_BORDER);
        label.setPaddingTop(9f);
        label.setPaddingBottom(9f);
        label.setPaddingLeft(8f);
        label.setPaddingRight(8f);
        label.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(label);

        PdfPCell amount = new PdfPCell(new Phrase(money(totals.get("net")), NET_VALUE));
        amount.setBackgroundColor(GREEN);
        amount.setBorder(Rectangle.NO_BORDER);
        amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
        amount.setVerticalAlignment(Element.ALIGN_MIDDLE);
        amount.setPaddingTop(8f);
        amount.setPaddingBottom(8f);
        amount.setPaddingLeft(8f);
        amount.setPaddingRight(8f);
        table.addCell(amount);

        return table;
    }

    private static int pageCount(byte[] pdf) {
        try {
            PdfReader reader = new PdfReader(pdf);
            try {
                return reader.getNumberOfPages();
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to count salary statement pages.", e);
        }
    }

    private static Object transactionId(Map<?, ?> payload) {
        return asMap(payload.get("transaction")).get("id");
    }

    private static String reference(Map<?, ?> payload) {
        return "Ref " + value(payload.get("template_version"))
                + "  |  Txn " + value(transactionId(payload))
                + "  |  Batch " + value(payload.get("batch_id"));
    }

    private static String periodHeading(Map<?, ?> period) {
        Object month = period.get("month");
        Object year = period.get("year");

        if (month instanceof Number number && year != null) {
            int index = number.intValue() - 1;

            if (index >= 0 && index < MONTHS.length) {
                return MONTHS[index] + " " + value(year);
            }
        }

        return value(period.get("label"));
    }

    private static String money(Object amount) {
        if (amount == null) {
            return "";
        }

        try {
            BigDecimal value = amount instanceof BigDecimal decimal
                    ? decimal
                    : new BigDecimal(String.valueOf(amount));
            DecimalFormat format = new DecimalFormat(
                    "#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
            return format.format(value);
        } catch (NumberFormatException e) {
            return value(amount);
        }
    }

    private static String value(Object object) {
        return object == null ? "" : String.valueOf(object);
    }

    private static Map<?, ?> asMap(Object object) {
        return object instanceof Map<?, ?> map ? map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object object) {
        return object instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static byte[] loadLogo() {
        try (InputStream input = StatementPdfRenderer.class.getResourceAsStream(
                "/statements/hmwssb-logo.png")) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static Image logoImage() {
        if (LOGO == null) {
            return null;
        }

        try {
            return Image.getInstance(LOGO);
        } catch (IOException | DocumentException e) {
            return null;
        }
    }

    private static final class Decorations extends PdfPageEventHelper {

        private static final Font HEADER = FontFactory.getFont(
                FontFactory.HELVETICA_BOLD, 8f, Font.NORMAL, MUTED);

        private final String periodHeading;
        private final String reference;
        private final int totalPages;

        private Decorations(
                String periodHeading,
                String reference,
                int totalPages
        ) {
            this.periodHeading = periodHeading;
            this.reference = reference;
            this.totalPages = totalPages;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            float width = page.getWidth();
            float height = page.getHeight();
            float left = document.leftMargin();
            float right = width - document.rightMargin();
            PdfContentByte canvas = writer.getDirectContent();

            if (writer.getPageNumber() > 1) {
                canvas.saveState();
                canvas.setColorFill(MUTED);
                canvas.beginText();
                canvas.setFontAndSize(HEADER.getBaseFont(), HEADER.getSize());
                canvas.showTextAligned(Element.ALIGN_LEFT,
                        "HMWSSB - Salary Statement (continued)",
                        left, height - 26f, 0f);
                canvas.showTextAligned(Element.ALIGN_RIGHT,
                        periodHeading, right, height - 26f, 0f);
                canvas.endText();
                canvas.setColorStroke(GREEN);
                canvas.setLineWidth(0.8f);
                canvas.moveTo(left, height - 32f);
                canvas.lineTo(right, height - 32f);
                canvas.stroke();
                canvas.restoreState();
            }

            canvas.saveState();
            canvas.setColorStroke(BORDER);
            canvas.setLineWidth(0.6f);
            canvas.moveTo(left, 36f);
            canvas.lineTo(right, 36f);
            canvas.stroke();

            canvas.setColorFill(MUTED);
            canvas.beginText();
            canvas.setFontAndSize(FOOTER_BOLD.getBaseFont(), FOOTER_BOLD.getSize());
            canvas.showTextAligned(Element.ALIGN_LEFT,
                    reference, left, 25f, 0f);
            String pageNumber = totalPages > 0
                    ? "Page " + writer.getPageNumber() + " of " + totalPages
                    : "Page " + writer.getPageNumber();
            canvas.showTextAligned(Element.ALIGN_RIGHT, pageNumber,
                    right, 25f, 0f);
            canvas.endText();

            canvas.setColorFill(MUTED);
            canvas.beginText();
            canvas.setFontAndSize(FOOTER.getBaseFont(), FOOTER.getSize());
            canvas.showTextAligned(Element.ALIGN_LEFT,
                    "System-generated from an immutable payroll snapshot. "
                            + "No signature is required. Amounts are in Indian Rupees (INR).",
                    left, 14f, 0f);
            canvas.endText();

            canvas.restoreState();
        }
    }
}
