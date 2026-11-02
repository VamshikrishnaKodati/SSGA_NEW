package SSGA_Backend.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pure unit checks for the intelligent column-mapping catalogue (no database).
 */
class ColumnMappingCatalogTest {

    private static ColumnMappingCatalog.SheetField field(
            List<ColumnMappingCatalog.SheetField> fields,
            String key
    ) {
        return fields.stream()
                .filter(item -> item.field().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void highConfidenceForExactAliasWithMatchingType() {
        List<ColumnMappingCatalog.SheetField> fields =
                ColumnMappingCatalog.suggestFields(
                        "SALARY_ENTITLEMENTS",
                        List.of("Emp Code", "Amount"),
                        Map.of("Emp Code", List.of("E123"), "Amount", List.of("1000", "2500"))
                );

        // Amount is not an identifier, so an exact match with numeric samples is HIGH.
        assertEquals(ColumnMappingCatalog.HIGH, field(fields, "amount").confidence());
        assertEquals("Amount", field(fields, "amount").sourceHeader());

        // Employee code is an identifier, so it is capped at REVIEW.
        assertEquals(ColumnMappingCatalog.REVIEW, field(fields, "employeeCode").confidence());
        assertEquals("Emp Code", field(fields, "employeeCode").sourceHeader());
    }

    @Test
    void unmatchedWhenNoHeaderMatches() {
        List<ColumnMappingCatalog.SheetField> fields =
                ColumnMappingCatalog.suggestFields(
                        "EMPLOYEE_MASTER",
                        List.of("Something Else"),
                        Map.of()
                );

        ColumnMappingCatalog.SheetField pan = field(fields, "pan");
        assertEquals(ColumnMappingCatalog.UNMATCHED, pan.confidence());
        assertNull(pan.sourceHeader());
    }

    @Test
    void reviewWhenSampleTypeDoesNotMatchExpected() {
        List<ColumnMappingCatalog.SheetField> fields =
                ColumnMappingCatalog.suggestFields(
                        "EMPLOYEE_MASTER",
                        List.of("Basic Pay"),
                        Map.of("Basic Pay", List.of("not-a-number", "still-text"))
                );

        assertEquals(ColumnMappingCatalog.REVIEW, field(fields, "basicPay").confidence());
    }

    @Test
    void reviewWhenTwoColumnsNormalizeToSameAlias() {
        // "Amount" and "AMOUNT!" both normalize to AMOUNT -> ambiguous.
        List<ColumnMappingCatalog.SheetField> fields =
                ColumnMappingCatalog.suggestFields(
                        "SALARY_ENTITLEMENTS",
                        List.of("Amount", "AMOUNT!"),
                        Map.of("Amount", List.of("1"), "AMOUNT!", List.of("2"))
                );

        assertEquals(ColumnMappingCatalog.REVIEW, field(fields, "amount").confidence());
    }

    @Test
    void observedTypeDetectsNumbersDatesAndText() {
        assertEquals(ColumnMappingCatalog.NUMBER,
                ColumnMappingCatalog.observedType(List.of("1,200.50", "300")));
        assertEquals(ColumnMappingCatalog.DATE,
                ColumnMappingCatalog.observedType(List.of("12/03/2024", "2024-03-12")));
        assertEquals(ColumnMappingCatalog.TEXT,
                ColumnMappingCatalog.observedType(List.of("alpha", "beta")));
        assertEquals(ColumnMappingCatalog.UNKNOWN,
                ColumnMappingCatalog.observedType(List.of("", "  ")));
    }

    @Test
    void dateFieldRejectsNumericSamples() {
        List<ColumnMappingCatalog.SheetField> fields =
                ColumnMappingCatalog.suggestFields(
                        "SALARY_ENTITLEMENTS",
                        List.of("Effective From"),
                        Map.of("Effective From", List.of("100", "200"))
                );

        assertEquals(ColumnMappingCatalog.REVIEW,
                field(fields, "effectiveFrom").confidence());
        assertTrue(ColumnMappingCatalog.supportsType("SALARY_ENTITLEMENTS"));
    }

    @Test
    void payrollHistoryMapsSourceTransactionStatus() {
        List<ColumnMappingCatalog.SheetField> fields =
                ColumnMappingCatalog.suggestFields(
                        "PAYROLL_HISTORY",
                        List.of("Status"),
                        Map.of("Status", List.of("PAID"))
                );

        assertEquals(
                "transactionStatus",
                field(fields, "transactionStatus").field()
        );
        assertEquals(
                "Status",
                field(fields, "transactionStatus").sourceHeader()
        );
    }
}
