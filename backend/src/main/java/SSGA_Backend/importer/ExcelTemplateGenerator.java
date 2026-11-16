package SSGA_Backend.importer;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ExcelTemplateGenerator {

    /**
     * The active HMWSSB divisions and circles, read from public.divisions.
     *
     * <p>This used to be a hand-maintained copy of the register. It silently fell
     * behind twice, and the reference sheet disagreed with the dropdown because
     * the two lists were separate. There is now one source: the database, so the
     * template cannot describe an organization unit that does not exist.
     */
    public record DivisionMaster(List<Division> divisions) {

        public record Division(String code, String name, String unitType, String wingCode) {
        }

        public String[] names() {
            return divisions.stream().map(Division::name).toArray(String[]::new);
        }

        /**
         * Reads the division master the web application uses. The generator is a
         * CLI, so it has no RLS session: reading public.divisions directly as
         * ssga_app returns nothing because divisions_scope_policy needs
         * app.user_id. app.template_divisions() is the app's own reader for
         * exactly this purpose.
         */
        public static DivisionMaster fromJdbc(java.sql.Connection connection)
                throws java.sql.SQLException {
            List<Division> rows = new java.util.ArrayList<>();

            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT * FROM app.template_divisions()")) {
                while (result.next()) {
                    rows.add(new Division(
                            result.getString("code"),
                            result.getString("name"),
                            result.getString("unit_type"),
                            result.getString("wing_code")
                    ));
                }
            }

            if (rows.isEmpty()) {
                throw new java.sql.SQLException(
                        "public.divisions returned no active units; refusing to write a template"
                );
            }

            return new DivisionMaster(rows);
        }
    }


    // Earning component codes
    // Source: database/43_salary_components_master_reset.sql.
    static final String[] EARNING_COMPONENTS = {
        "BASIC_PAY", "T_SPL_INC", "FPP", "SPL_PAY_GI", "DA",
        "HRA", "ADDL_HRA", "CCA", "PETROL_AUTO_ALLOW", "WASHING_ALLOW",
        "BOARD_FORM_ALLOW", "IR_2023", "DA_ARR_26_39", "DA_ARR_30_03",
        "DA_ARR_33_67"
    };

    // Deduction component codes
    // Source: database/43_salary_components_master_reset.sql and
    // database/45_add_court_attachment.sql.
    static final String[] DEDUCTION_COMPONENTS = {
        "GPF_EPF", "GPF_EPF_LOAN", "PENSION_2009", "TSGLI", "GIS", "PRC_2020_NPS",
        "ELEC_CHARGES", "FESTIVAL_ADV", "EDN_ADV", "VEHICLE_ADV", "MOPED_ADV",
        "EXCESS_SAL_REC", "EPF_12_PCT", "SBI_LIFE_INS", "ONE_DAY_SAL",
        "MARRIAGE_LOAN", "MGB_AN", "PROF_TAX", "INCOME_TAX", "EPF_PRC_LIC_MISC",
        "PRC_EXCESS_REC", "MGB_PC", "TG_HARITHA_NIDHI", "DARUSSALAM_BANK_REC",
        "SBI_HIMAYATNAGAR", "FLAG_DAY_FUND", "LIC_PREMIUM", "COURT_ATCH"
    };

    // The register has no STATUTORY rows; employer contributions are not part
    // of the HMWSSB component master.
    private static final String[] STATUTORY_COMPONENTS = {};

    private static final String[] ALL_COMPONENTS;

    static {
        List<String> all = new java.util.ArrayList<>();
        all.addAll(List.of(EARNING_COMPONENTS));
        all.addAll(List.of(DEDUCTION_COMPONENTS));
        all.addAll(List.of(STATUTORY_COMPONENTS));
        ALL_COMPONENTS = all.toArray(new String[0]);
    }

    private static final String[] IDENTITY_COLUMNS = {
        "HRMS_ID", "AADHAAR", "PAN", "CONTACT", "EMAIL", "PF", "NPS", "GPF"
    };

    private static final String[] EMPLOYEE_INFO_COLUMNS = {
        "EMPLOYEE_NAME", "DIVISION", "DIVISION_ID", "DESIGNATION", "WING",
        "DATE_OF_JOINING", "DATE_OF_BIRTH", "EMPLOYMENT_TYPE", "PAY_SCALE",
        "BASIC_PAY", "BANK_ACCOUNT", "IFSC_CODE", "BANK_NAME", "BRANCH", "EMPLOYEE_STATUS"
    };

    public static void main(String[] args) throws Exception {
        String outputPath = "src/main/resources/templates/hmwssb_salary_import_template.xlsx";

        String url = env("SSGA_DB_URL", "jdbc:postgresql://localhost:5432/ssga_new_db");
        String user = env("SSGA_DB_USER", "ssga_app");
        String password = env("SSGA_DB_PASSWORD", "2706");

        try (java.sql.Connection connection =
                     java.sql.DriverManager.getConnection(url, user, password)) {
            DivisionMaster master = DivisionMaster.fromJdbc(connection);
            generateTemplate(outputPath, master);
        }

        System.out.println("Template generated at: " + outputPath);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);

        return value == null || value.isBlank() ? fallback : value;
    }

    public static void generateTemplate(String outputPath, DivisionMaster master) throws IOException {
        String[] divisions = master.names();

        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());

        try (XSSFWorkbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(path.toFile())) {
            // Styles
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle requiredStyle = createRequiredStyle(wb);
            CellStyle optionalStyle = createOptionalStyle(wb);
            CellStyle sampleStyle = createSampleStyle(wb);
            CellStyle instructionStyle = createInstructionStyle(wb);

            // ============================================================
            // SHEET 1: WIDE LAYOUT (Main Template)
            // ============================================================
            Sheet wideSheet = wb.createSheet("WIDE_Layout_Import");
            wideSheet.setDefaultColumnWidth(18);

            // Row 0: Instructions
            Row instrRow = wideSheet.createRow(0);
            Cell instrCell = instrRow.createCell(0);
            instrCell.setCellValue("HMWSSB HRMS - BULK SALARY IMPORT TEMPLATE (WIDE LAYOUT)");
            instrCell.setCellStyle(instructionStyle);
            wideSheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 80));

            Row instrRow2 = wideSheet.createRow(1);
            Cell instrCell2 = instrRow2.createCell(0);
            instrCell2.setCellValue("RULES: 1) At least ONE identity column required (HRMS_ID/AADHAAR/PAN/CONTACT/EMAIL/PF/NPS/GPF). 2) EMPLOYEE_NAME is MANDATORY. 3) DIVISION/DIVISION_ID optional (auto-matched to 54 HMWSSB divisions/circles). 4) Component columns optional - enter amounts only for applicable components. 5) EFFECTIVE_FROM defaults to 1st of payroll month if blank. 6) Delete this instruction row before import.");
            instrCell2.setCellStyle(instructionStyle);
            wideSheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 80));

            // Row 3: Headers
            Row headerRow = wideSheet.createRow(3);
            int colIdx = 0;

            // Identity columns (mark required group)
            for (String col : IDENTITY_COLUMNS) {
                Cell cell = headerRow.createCell(colIdx++);
                cell.setCellValue(col);
                cell.setCellStyle(optionalStyle); // At least one required, but individually optional
            }

            // Employee info
            for (String col : EMPLOYEE_INFO_COLUMNS) {
                Cell cell = headerRow.createCell(colIdx++);
                cell.setCellValue(col);
                cell.setCellStyle(col.equals("EMPLOYEE_NAME") ? requiredStyle : optionalStyle);
            }

            // Earning components
            for (String comp : EARNING_COMPONENTS) {
                Cell cell = headerRow.createCell(colIdx++);
                cell.setCellValue(comp);
                cell.setCellStyle(optionalStyle);
            }

            // Deduction components
            for (String comp : DEDUCTION_COMPONENTS) {
                Cell cell = headerRow.createCell(colIdx++);
                cell.setCellValue(comp);
                cell.setCellStyle(optionalStyle);
            }

            // Statutory components
            for (String comp : STATUTORY_COMPONENTS) {
                Cell cell = headerRow.createCell(colIdx++);
                cell.setCellValue(comp);
                cell.setCellStyle(optionalStyle);
            }

            // Effective date
            Cell effCell = headerRow.createCell(colIdx++);
            effCell.setCellValue("EFFECTIVE_FROM");
            effCell.setCellStyle(optionalStyle);

            // Add data validation for DIVISION column (column index = 8)
            addDivisionValidation(wideSheet, 8, divisions);

            // Add data validation for EMPLOYMENT_TYPE (column index = 14)
            addEmploymentTypeValidation(wideSheet, 14);

            // Add data validation for EMPLOYEE_STATUS (column index = 23)
            addEmployeeStatusValidation(wideSheet, 23);

            // Sample rows (rows 4-8)
            Object[][] wideSamples = {
                // Row 4: Complete row with HRMS_ID
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "ACCOUNTS (D01)", "D01", "CLERK", "ADMINISTRATION",
                 "15-06-2010", "01-01-1985", "PERMANENT", "LEVEL-4", "25500",
                 "1234567890123456", "SBIN0001234", "STATE BANK OF INDIA", "HYDERABAD MAIN", "ACTIVE",
                 25500, 9690, 3000, 1800, 500, 0, 0, 1500, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 2500, 0, 2000, 0, 0, 0, 0, 0, 0, 0, 0, 1200, 200, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 0, 0, 0, 0,
                 "01-04-2024"},
                // Row 5: Aadhaar only (no HRMS_ID) - will get temp ID
                {"", "234567890123", "", "9876543211", "priya@hmwssb.gov.in", "", "", "",
                 "PRIYA SHARMA", "OPERATIONS (D33)", "D33", "ASSISTANT ENGINEER", "OPERATIONS",
                 "01-07-2018", "15-03-1992", "PERMANENT", "LEVEL-7", "44900",
                 "2345678901234567", "HDFC0001234", "HDFC BANK", "SECUNDERABAD", "ACTIVE",
                 44900, 17062, 5400, 3600, 1000, 0, 0, 2000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 4490, 0, 3000, 0, 0, 0, 0, 0, 0, 0, 0, 2500, 200, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 0, 0, 0, 0,
                 "01-04-2024"},
                // Row 6: PAN only, CONTRACT employee
                {"", "", "ABCDE1234F", "9876543212", "suresh@hmwssb.gov.in", "", "", "",
                 "SURESH REDDY", "CONSTRUCTION DIV-1 (D10)", "D10", "JUNIOR ENGINEER", "CONSTRUCTION",
                 "01-01-2022", "10-07-1995", "CONTRACT", "LEVEL-6", "35400",
                 "3456789012345678", "ICIC0001234", "ICICI BANK", "KUKATPALLY", "ACTIVE",
                 35400, 13452, 4200, 2700, 800, 0, 0, 1200, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 3540, 0, 2500, 0, 0, 0, 0, 0, 0, 0, 0, 1800, 200, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 0, 0, 0, 0,
                 "01-04-2024"},
                // Row 7: Contact only, DEPUTATION
                {"", "", "", "9876543213", "amit@hmwssb.gov.in", "", "", "",
                 "AMIT SINGH", "WATER TREATMENT PLANT-1 (D47)", "D47", "OPERATOR", "WATER SUPPLY",
                 "10-03-2020", "22-11-1988", "DEPUTATION", "LEVEL-3", "21700",
                 "4567890123456789", "PNB0001234", "PUNJAB NATIONAL BANK", "MEHDIPATNAM", "ACTIVE",
                 21700, 8246, 2600, 1500, 400, 0, 0, 800, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 2170, 0, 1500, 0, 0, 0, 0, 0, 0, 0, 0, 1000, 200, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 0, 0, 0, 0,
                 "01-04-2024"},
                // Row 8: Minimal - only Aadhaar + Name + one component
                {"", "345678901234", "", "", "", "", "", "",
                 "LAKSHMI DEVI", "", "", "HELPER", "",
                 "", "", "PERMANENT", "", "",
                 "", "", "", "", "ACTIVE",
                 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 0, 0, 0, 0,
                 ""},
            };

            for (int i = 0; i < wideSamples.length; i++) {
                Row row = wideSheet.createRow(4 + i);
                Object[] rowData = wideSamples[i];
                for (int j = 0; j < rowData.length; j++) {
                    Cell cell = row.createCell(j);
                    Object val = rowData[j];
                    if (val instanceof Number) {
                        cell.setCellValue(((Number) val).doubleValue());
                    } else {
                        cell.setCellValue(val == null ? "" : val.toString());
                    }
                    cell.setCellStyle(sampleStyle);
                }
            }

            // Freeze panes
            wideSheet.createFreezePane(0, 4);

            // ============================================================
            // SHEET 2: LONG LAYOUT (Normalized)
            // ============================================================
            Sheet longSheet = wb.createSheet("LONG_Layout_Import");
            longSheet.setDefaultColumnWidth(20);

            Row longInstr = longSheet.createRow(0);
            Cell longInstrCell = longInstr.createCell(0);
            longInstrCell.setCellValue("HMWSSB HRMS - BULK SALARY IMPORT TEMPLATE (LONG LAYOUT - One row per component)");
            longInstrCell.setCellStyle(instructionStyle);
            longSheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 10));

            Row longInstr2 = longSheet.createRow(1);
            Cell longInstrCell2 = longInstr2.createCell(0);
            longInstrCell2.setCellValue("RULES: 1) At least ONE identity column required per row. 2) EMPLOYEE_NAME mandatory. 3) COMPONENT_CODE must match master list (see WIDE sheet for codes). 4) CATEGORY auto-resolved if blank. 5) Each row = one employee + one component. 6) Delete instruction rows before import.");
            longInstrCell2.setCellStyle(instructionStyle);
            longSheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 10));

            // Headers row 3
            Row longHeader = longSheet.createRow(3);
            String[] longHeaders = {"HRMS_ID", "AADHAAR", "PAN", "CONTACT", "EMAIL", "PF", "NPS", "GPF",
                                    "EMPLOYEE_NAME", "DIVISION", "DIVISION_ID", "COMPONENT_CODE", "AMOUNT", "CATEGORY", "EFFECTIVE_FROM", "EFFECTIVE_TO"};
            for (int i = 0; i < longHeaders.length; i++) {
                Cell cell = longHeader.createCell(i);
                cell.setCellValue(longHeaders[i]);
                boolean isRequired = List.of("EMPLOYEE_NAME", "COMPONENT_CODE", "AMOUNT").contains(longHeaders[i]);
                cell.setCellStyle(isRequired ? requiredStyle : optionalStyle);
            }

            // Add division validation for DIVISION (col 9) and DIVISION_ID (col 10)
            addDivisionValidation(longSheet, 9, divisions);
            addDivisionValidation(longSheet, 10, divisions);

            // Add component code validation
            addComponentValidation(longSheet, 11, ALL_COMPONENTS);

            // Add category validation
            addCategoryValidation(longSheet, 13);

            // Sample rows (rows 4-12)
            Object[][] longSamples = {
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "Board Office", "DIV_BRD_OFF", "BASIC_PAY", 25500, "EARNING", "01-04-2024", ""},
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "Board Office", "DIV_BRD_OFF", "DA", 8584, "EARNING", "01-04-2024", ""},
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "Board Office", "DIV_BRD_OFF", "HRA", 6120, "EARNING", "01-04-2024", ""},
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "Board Office", "DIV_BRD_OFF", "GPF_EPF", 2500, "DEDUCTION", "01-04-2024", ""},
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "Board Office", "DIV_BRD_OFF", "INCOME_TAX", 1200, "DEDUCTION", "01-04-2024", ""},
                {"", "234567890123", "", "9876543211", "priya@hmwssb.gov.in", "", "", "",
                 "PRIYA SHARMA", "O&M Division No.I", "DIV_OM_01", "BASIC_PAY", 44900, "EARNING", "01-04-2024", ""},
                {"", "234567890123", "", "9876543211", "priya@hmwssb.gov.in", "", "", "",
                 "PRIYA SHARMA", "O&M Division No.I", "DIV_OM_01", "DA", 15118, "EARNING", "01-04-2024", ""},
                {"", "234567890123", "", "9876543211", "priya@hmwssb.gov.in", "", "", "",
                 "PRIYA SHARMA", "O&M Division No.I", "DIV_OM_01", "PRC_2020_NPS", 6002, "DEDUCTION", "01-04-2024", ""},
                {"", "", "ABCDE1234F", "9876543212", "suresh@hmwssb.gov.in", "", "", "",
                 "SURESH REDDY", "Central Stores Division", "DIV_CEN_STR", "BASIC_PAY", 35400, "EARNING", "01-04-2024", ""},
            };

            for (int i = 0; i < longSamples.length; i++) {
                Row row = longSheet.createRow(4 + i);
                Object[] rowData = longSamples[i];
                for (int j = 0; j < rowData.length; j++) {
                    Cell cell = row.createCell(j);
                    Object val = rowData[j];
                    if (val instanceof Number) {
                        cell.setCellValue(((Number) val).doubleValue());
                    } else {
                        cell.setCellValue(val == null ? "" : val.toString());
                    }
                    cell.setCellStyle(sampleStyle);
                }
            }

            longSheet.createFreezePane(0, 4);

            // ============================================================
            // SHEET 3: REFERENCE - Division Master
            // ============================================================
            Sheet divSheet = wb.createSheet("Division_Master_Reference");
            divSheet.setDefaultColumnWidth(25);

            Row divHeader = divSheet.createRow(0);
            String[] divHeaders = {"Division Code", "Division Name", "Category", "Parent Group", "Display Order", "Status", "Verification Status"};
            for (int i = 0; i < divHeaders.length; i++) {
                Cell cell = divHeader.createCell(i);
                cell.setCellValue(divHeaders[i]);
                cell.setCellStyle(headerStyle);
            }

            // Same master as the dropdown, so the sheet and the validation list
            // can never describe different organizations.
            for (int i = 0; i < master.divisions().size(); i++) {
                Row row = divSheet.createRow(i + 1);
                DivisionMaster.Division unit = master.divisions().get(i);

                row.createCell(0).setCellValue(unit.code());
                row.createCell(1).setCellValue(unit.name());
                row.createCell(2).setCellValue(unit.unitType());
                row.createCell(3).setCellValue(unit.wingCode());
                row.createCell(4).setCellValue(String.valueOf((i + 1) * 10));
                row.createCell(5).setCellValue("ACTIVE");
                row.createCell(6).setCellValue("CONFIRMED");
            }

            // ============================================================
            // SHEET 4: REFERENCE - Component Master
            // ============================================================
            Sheet compSheet = wb.createSheet("Component_Master_Reference");
            compSheet.setDefaultColumnWidth(25);

            Row compHeader = compSheet.createRow(0);
            String[] compHeaders = {"Component Code", "Component Name", "Category", "Description", "Is Active"};
            for (int i = 0; i < compHeaders.length; i++) {
                Cell cell = compHeader.createCell(i);
                cell.setCellValue(compHeaders[i]);
                cell.setCellStyle(headerStyle);
            }

            // Mirrors salary_components. The summary rows (GROSS_EARNINGS,
            // TOTAL_DEDUCTIONS, NET_SALARY) are derived by the engine and must
            // never be imported, so they are deliberately absent.
            String[][] componentData = {
                // Earnings
                {"BASIC_PAY", "Basic Pay", "EARNING", "Basic pay", "true"},
                {"T_SPL_INC", "Telangana Special Increment", "EARNING", "Telangana special increment", "true"},
                {"FPP", "Family Planning Pay", "EARNING", "Family planning pay", "true"},
                {"SPL_PAY_GI", "Special Pay / Grade Increment", "EARNING", "Special pay / grade increment", "true"},
                {"DA", "Dearness Allowance", "EARNING", "Dearness allowance", "true"},
                {"HRA", "House Rent Allowance", "EARNING", "House rent allowance", "true"},
                {"ADDL_HRA", "Additional House Rent Allowance", "EARNING", "Additional house rent allowance", "true"},
                {"CCA", "City Compensatory Allowance", "EARNING", "City compensatory allowance", "true"},
                {"PETROL_AUTO_ALLOW", "Petrol / Auto Conveyance Allowance", "EARNING", "Petrol / auto conveyance allowance", "true"},
                {"WASHING_ALLOW", "Washing Allowance", "EARNING", "Washing allowance", "true"},
                {"BOARD_FORM_ALLOW", "Board Formation Day Allowance", "EARNING", "Board formation day allowance", "true"},
                {"IR_2023", "Interim Relief (5% - 2023)", "EARNING", "Interim relief", "true"},
                {"DA_ARR_26_39", "DA Arrears (26.39% Slab)", "EARNING", "DA arrears, 26.39% slab", "true"},
                {"DA_ARR_30_03", "DA Arrears (30.03% Slab)", "EARNING", "DA arrears, 30.03% slab", "true"},
                {"DA_ARR_33_67", "DA Arrears (33.67% Slab)", "EARNING", "DA arrears, 33.67% slab", "true"},
                // Deductions
                {"GPF_EPF", "General / Employees Provident Fund", "DEDUCTION", "GPF / EPF subscription", "true"},
                {"GPF_EPF_LOAN", "GPF / EPF Loan Recovery", "DEDUCTION", "GPF / EPF loan recovery", "true"},
                {"PENSION_2009", "Pension Scheme (w.e.f. March 2009)", "DEDUCTION", "Pension scheme contribution", "true"},
                {"TSGLI", "Telangana State Group Life Insurance", "DEDUCTION", "TSGLI premium", "true"},
                {"GIS", "Group Insurance Scheme", "DEDUCTION", "Group insurance scheme premium", "true"},
                {"PRC_2020_NPS", "PRC-2020 NPS Deduction", "DEDUCTION", "NPS employee contribution", "true"},
                {"ELEC_CHARGES", "Electricity Charges Recovery", "DEDUCTION", "Electricity charges recovery", "true"},
                {"FESTIVAL_ADV", "Festival Advance Recovery", "DEDUCTION", "Festival advance recovery", "true"},
                {"EDN_ADV", "Education Advance Recovery", "DEDUCTION", "Education advance recovery", "true"},
                {"VEHICLE_ADV", "Vehicle Advance Recovery", "DEDUCTION", "Vehicle advance recovery", "true"},
                {"MOPED_ADV", "Moped Advance Recovery", "DEDUCTION", "Moped advance recovery", "true"},
                {"EXCESS_SAL_REC", "Excess Salary Recovery", "DEDUCTION", "Excess salary recovery", "true"},
                {"EPF_12_PCT", "EPF Contribution (12% of Pay + DA)", "DEDUCTION", "EPF employee contribution", "true"},
                {"SBI_LIFE_INS", "SBI Life Insurance Premium", "DEDUCTION", "SBI Life insurance premium", "true"},
                {"ONE_DAY_SAL", "One Day Salary Contribution", "DEDUCTION", "One day salary contribution", "true"},
                {"MARRIAGE_LOAN", "Marriage Loan Recovery", "DEDUCTION", "Marriage loan recovery", "true"},
                {"MGB_AN", "MGB Recovery (A.N)", "DEDUCTION", "MGB recovery, A.N", "true"},
                {"PROF_TAX", "Professional Tax (PT)", "DEDUCTION", "Professional tax", "true"},
                {"INCOME_TAX", "Income Tax (TDS)", "DEDUCTION", "Tax deducted at source", "true"},
                {"EPF_PRC_LIC_MISC", "EPF / PRC Arrears / LIC / BHEL Recovery", "DEDUCTION", "EPF / PRC arrears / LIC / BHEL recovery", "true"},
                {"PRC_EXCESS_REC", "PRC Excess Amount Recovery", "DEDUCTION", "PRC excess amount recovery", "true"},
                {"MGB_PC", "MGB Recovery (P.C)", "DEDUCTION", "MGB recovery, P.C", "true"},
                {"TG_HARITHA_NIDHI", "Telangana Haritha Nidhi (Green Fund)", "DEDUCTION", "Telangana Haritha Nidhi", "true"},
                {"DARUSSALAM_BANK_REC", "Darussalam Co-op Bank Recovery", "DEDUCTION", "Darussalam Co-op Bank recovery", "true"},
                {"SBI_HIMAYATNAGAR", "SBI Himayatnagar Recovery", "DEDUCTION", "SBI Himayatnagar recovery", "true"},
                {"FLAG_DAY_FUND", "Armed Forces Flag Day Fund", "DEDUCTION", "Armed Forces Flag Day Fund", "true"},
                {"LIC_PREMIUM", "LIC Salary Savings Scheme Premium", "DEDUCTION", "LIC salary savings scheme premium", "true"},
                {"COURT_ATCH", "Court Attachment", "DEDUCTION", "Court ordered deduction", "true"},
            };

            for (int i = 0; i < componentData.length; i++) {
                Row row = compSheet.createRow(i + 1);
                for (int j = 0; j < componentData[i].length; j++) {
                    Cell cell = row.createCell(j);
                    cell.setCellValue(componentData[i][j]);
                }
            }

            wb.write(fos);
        }
    }

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createRequiredStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.RED.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createOptionalStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.DARK_GREEN.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createSampleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createInstructionStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.DARK_RED.getIndex());
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        return style;
    }

    private static void addDivisionValidation(Sheet sheet, int colIndex, String[] divisions) {
        Workbook wb = sheet.getWorkbook();
        // POI silently trims a sheet name longer than 31 characters, which would
        // leave the validation formula pointing at a sheet that does not exist
        // and make Excel offer to repair the file.
        String hiddenSheetName = org.apache.poi.ss.util.WorkbookUtil
                .createSafeSheetName("_Divisions_List_" + colIndex);
        Sheet hiddenSheet = wb.createSheet(hiddenSheetName);
        for (int i = 0; i < divisions.length; i++) {
            Row r = hiddenSheet.createRow(i);
            r.createCell(0).setCellValue(divisions[i]);
        }
        wb.setSheetHidden(wb.getSheetIndex(hiddenSheet), true);

        String rangeName = "HMWSSB_DIVISIONS_" + colIndex;
        Name namedRange = wb.createName();
        namedRange.setNameName(rangeName);
        namedRange.setRefersToFormula(
                org.apache.poi.ss.util.WorkbookUtil
                        .createSafeSheetName(hiddenSheetName) + "!$A$1:$A$" + divisions.length
        );

        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidationConstraint dvConstraint = dvHelper.createFormulaListConstraint(rangeName);
        CellRangeAddressList addressList = new CellRangeAddressList(4, 1000, colIndex, colIndex);
        DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
        validation.setShowErrorBox(true);
        validation.createErrorBox("Invalid Division", "Select a valid HMWSSB division from the list");
        sheet.addValidationData(validation);
    }

    private static void addEmploymentTypeValidation(Sheet sheet, int colIndex) {
        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidationConstraint dvConstraint = dvHelper.createExplicitListConstraint(
            new String[]{"PERMANENT", "CONTRACT", "DEPUTATION", "TEMPORARY", "RE_EMPLOYED"}
        );
        CellRangeAddressList addressList = new CellRangeAddressList(4, 1000, colIndex, colIndex);
        DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private static void addEmployeeStatusValidation(Sheet sheet, int colIndex) {
        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidationConstraint dvConstraint = dvHelper.createExplicitListConstraint(
            new String[]{"ACTIVE", "RETIRED", "SUSPENDED", "TERMINATED", "RESIGNED", "EXPIRED", "ON_LEAVE"}
        );
        CellRangeAddressList addressList = new CellRangeAddressList(4, 1000, colIndex, colIndex);
        DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private static void addComponentValidation(Sheet sheet, int colIndex, String[] components) {
        Workbook wb = sheet.getWorkbook();
        String hiddenSheetName = org.apache.poi.ss.util.WorkbookUtil
                .createSafeSheetName("_Components_List_" + colIndex);
        Sheet hiddenSheet = wb.createSheet(hiddenSheetName);
        for (int i = 0; i < components.length; i++) {
            Row r = hiddenSheet.createRow(i);
            r.createCell(0).setCellValue(components[i]);
        }
        wb.setSheetHidden(wb.getSheetIndex(hiddenSheet), true);

        String rangeName = "HMWSSB_COMPONENTS_" + colIndex;
        Name namedRange = wb.createName();
        namedRange.setNameName(rangeName);
        namedRange.setRefersToFormula(hiddenSheetName + "!$A$1:$A$" + components.length);

        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidationConstraint dvConstraint = dvHelper.createFormulaListConstraint(rangeName);
        CellRangeAddressList addressList = new CellRangeAddressList(4, 1000, colIndex, colIndex);
        DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
        validation.setShowErrorBox(true);
        validation.createErrorBox("Invalid Component", "Select a valid salary component code");
        sheet.addValidationData(validation);
    }

    private static void addCategoryValidation(Sheet sheet, int colIndex) {
        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidationConstraint dvConstraint = dvHelper.createExplicitListConstraint(
            new String[]{"EARNING", "DEDUCTION", "STATUTORY"}
        );
        CellRangeAddressList addressList = new CellRangeAddressList(4, 1000, colIndex, colIndex);
        DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }
}

