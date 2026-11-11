package SSGA_Backend.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pure unit checks for worksheet classification in
 * {@link ImportJobAnalysisService}. No database and no Spring context.
 */
class ImportJobAnalysisClassificationTest {

    private final ImportJobAnalysisService service = newImportJobAnalysisService();

    private static ImportJobAnalysisService newImportJobAnalysisService() {
        return new ImportJobAnalysisService(
                null, null, null, ""
        );
    }

    @Test
    void wideSheetClassifiesAsEmployeeMasterAndComponents() {
        String type = service.suggestType(
                "SALARY_VALUES_WIDE",
                List.of("EMP_CODE", "EMP_NAME", "DIVISION", "BASIC_PAY", "DA_PAY", "HRA_PAY")
        );

        assertEquals("EMPLOYEE_MASTER_AND_COMPONENTS", type);
    }

    @Test
    void wideSheetWithThreeComponentColumnsClassifiesCorrectly() {
        String type = service.suggestType(
                "Q4_PAYROLL",
                List.of("EMPLOYEE_CODE", "EMPLOYEE_NAME", "DIVISION", "BASIC_PAY", "DA_PAY", "HRA_PAY")
        );

        assertEquals("EMPLOYEE_MASTER_AND_COMPONENTS", type);
    }

    @Test
    void longSheetClassifiesAsSalaryEntitlements() {
        String type = service.suggestType(
                "SALARY_VALUES_IMPORT",
                List.of("EMP_CODE", "EMP_NAME", "COMPONENT_CODE", "AMOUNT")
        );

        assertEquals("SALARY_ENTITLEMENTS", type);
    }

    @Test
    void longSheetByHeadersClassifiesAsSalaryEntitlements() {
        String type = service.suggestType(
                "JANUARY_PAYROLL",
                List.of("EMPLOYEE_ID", "EMPLOYEE_NAME", "SALARY_COMPONENT", "SALARY_AMOUNT")
        );

        assertEquals("SALARY_ENTITLEMENTS", type);
    }

    @Test
    void employeeMasterWithoutComponentsClassifiesCorrectly() {
        String type = service.suggestType(
                "EMPLOYEE_MASTER",
                List.of("HRMS_ID", "EMPLOYEE_NAME", "DIVISION")
        );

        assertEquals("EMPLOYEE_MASTER", type);
    }

    @Test
    void payrollHistoryClassifiesCorrectly() {
        String type = service.suggestType(
                "PAYROLL_HISTORY_2024",
                List.of("EMP_CODE", "PAY_PERIOD", "GROSS_SALARY", "NET_SALARY")
        );

        assertEquals("PAYROLL_HISTORY", type);
    }

    @Test
    void notesSheetByNameIsReferenceOnly() {
        String type = service.suggestType(
                "IMPORT_NOTES",
                List.of("EMP_CODE", "AMOUNT")
        );

        assertEquals("REFERENCE_ONLY", type);
    }

    @Test
    void instructionSheetByNameIsReferenceOnly() {
        String type = service.suggestType(
                "PAYROLL_INSTRUCTIONS",
                List.of("EMP_CODE", "AMOUNT")
        );

        assertEquals("REFERENCE_ONLY", type);
    }

    @Test
    void guidanceSheetByNameIsReferenceOnly() {
        String type = service.suggestType(
                "SALARY_GUIDANCE",
                List.of("EMP_CODE", "AMOUNT")
        );

        assertEquals("REFERENCE_ONLY", type);
    }

    @Test
    void guidelineSheetByNameIsReferenceOnly() {
        String type = service.suggestType(
                "IMPORT_GUIDELINE",
                List.of("EMP_CODE", "AMOUNT")
        );

        assertEquals("REFERENCE_ONLY", type);
    }

    @Test
    void sheetWithInstructionalHeadersAndNoPayrollIsReferenceOnly() {
        String type = service.suggestType(
                "Sheet1",
                List.of("INSTRUCTION_STEP", "HOW_TO_FILL", "POLICY_REFERENCE")
        );

        assertEquals("REFERENCE_ONLY", type);
    }

    @Test
    void sheetWithInstructionalHeadersButPayrollColumnsIsNotReferenceOnly() {
        String type = service.suggestType(
                "Sheet1",
                List.of("EMP_CODE", "INSTRUCTION_STEP", "AMOUNT")
        );

        assertNotReferenceOnly(type);
    }

    @Test
    void unknownSheetWithNoRecognizedHeaders() {
        String type = service.suggestType(
                "Sheet1",
                List.of("RANDOM_COLUMN", "ANOTHER_FIELD")
        );

        assertEquals("UNKNOWN", type);
    }

    private static void assertNotReferenceOnly(String type) {
        assertEquals("UNKNOWN", type);
    }
}
