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

    // 48 HMWSSB Divisions
    private static final String[] DIVISIONS = {
        "ACCOUNTS (D01)", "ADMINISTRATION (D02)", "AUDIT (D03)", "BILLING (D04)",
        "BUILDING MAINTENANCE (D05)", "CENTRAL STORES (D06)", "CHEMICAL LAB (D07)",
        "COMMERCIAL (D08)", "COMPUTER CELL (D09)", "CONSTRUCTION DIV-1 (D10)",
        "CONSTRUCTION DIV-2 (D11)", "CONSTRUCTION DIV-3 (D12)", "CONSTRUCTION DIV-4 (D13)",
        "CONSTRUCTION DIV-5 (D14)", "CONSTRUCTION DIV-6 (D15)", "CONSTRUCTION DIV-7 (D16)",
        "CONSTRUCTION DIV-8 (D17)", "CONSTRUCTION DIV-9 (D18)", "CONSTRUCTION DIV-10 (D19)",
        "CONSTRUCTION DIV-11 (D20)", "CONSTRUCTION DIV-12 (D21)", "CONSTRUCTION DIV-13 (D22)",
        "CONSTRUCTION DIV-14 (D23)", "CONSTRUCTION DIV-15 (D24)", "DEWATERING (D25)",
        "ELECTRICAL MAINTENANCE (D26)", "FINANCE (D27)", "HORTICULTURE (D28)",
        "HR & LEGAL (D29)", "INTERNAL AUDIT (D30)", "LAND ACQUISITION (D31)",
        "MECHANICAL MAINTENANCE (D32)", "OPERATIONS (D33)", "PLANNING & DESIGN (D34)",
        "PROCUREMENT (D35)", "PROJECT MONITORING (D36)", "PUBLIC RELATIONS (D37)",
        "QUALITY CONTROL (D38)", "REVENUE (D39)", "SEWERAGE MAINTENANCE (D40)",
        "STORM WATER DRAINS (D41)", "SURVEY (D42)", "TRAINING (D43)",
        "VEHICLE MAINTENANCE (D44)", "VIGILANCE (D45)", "WATER SUPPLY MAINTENANCE (D46)",
        "WATER TREATMENT PLANT-1 (D47)", "WATER TREATMENT PLANT-2 (D48)"
    };

    // Earning component codes
    private static final String[] EARNING_COMPONENTS = {
        "BASIC", "DA", "HRA", "TA", "MA", "WA", "OA", "SPL_ALLOW",
        "CCA", "HCA", "HILL_ALLOW", "OVT", "ARREARS", "BONUS", "LEAVE_ENCASH",
        "MEDICAL", "EDU_ALLOW", "CHILD_ALLOW", "TRANSPORT", "WASHING",
        "UNIFORM", "RISK", "HARD_AREA", "SPL_PAY", "DEPUTATION", "HONORARIUM", "OTHER_EARN"
    };

    // Deduction component codes
    private static final String[] DEDUCTION_COMPONENTS = {
        "GPF_DED", "CPF_DED", "NPS_DED", "GIS_DED", "LIC_DED", "BANK_LOAN",
        "HBA_DED", "EDU_LOAN", "FEST_ADV", "COMP_ADV", "VEH_ADV", "HOUSE_RENT",
        "WATER_CHG", "ELEC_CHG", "INCOME_TAX", "PROF_TAX", "GIS_INS", "CGHS",
        "CGEGIS", "UNION_DUES", "COOP_SOC", "COURT_ATT", "OTHER_DED", "NPS_EMPR"
    };

    // Statutory component codes
    private static final String[] STATUTORY_COMPONENTS = {
        "PF_EMPR", "ESI_EMPR", "PT_EMPR", "LWF_EMPR"
    };

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

    public static void main(String[] args) throws IOException {
        String outputPath = "src/main/resources/templates/hmwssb_salary_import_template.xlsx";
        generateTemplate(outputPath);
        System.out.println("Template generated at: " + outputPath);
    }

    public static void generateTemplate(String outputPath) throws IOException {
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
            instrCell2.setCellValue("RULES: 1) At least ONE identity column required (HRMS_ID/AADHAAR/PAN/CONTACT/EMAIL/PF/NPS/GPF). 2) EMPLOYEE_NAME is MANDATORY. 3) DIVISION/DIVISION_ID optional (auto-matched to 48 HMWSSB divisions). 4) Component columns optional - enter amounts only for applicable components. 5) EFFECTIVE_FROM defaults to 1st of payroll month if blank. 6) Delete this instruction row before import.");
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
            addDivisionValidation(wideSheet, 8, DIVISIONS);

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
            addDivisionValidation(longSheet, 9, DIVISIONS);
            addDivisionValidation(longSheet, 10, DIVISIONS);

            // Add component code validation
            addComponentValidation(longSheet, 11, ALL_COMPONENTS);

            // Add category validation
            addCategoryValidation(longSheet, 13);

            // Sample rows (rows 4-12)
            Object[][] longSamples = {
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "ACCOUNTS (D01)", "D01", "BASIC", 25500, "EARNING", "01-04-2024", ""},
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "ACCOUNTS (D01)", "D01", "DA", 9690, "EARNING", "01-04-2024", ""},
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "ACCOUNTS (D01)", "D01", "HRA", 3000, "EARNING", "01-04-2024", ""},
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "ACCOUNTS (D01)", "D01", "GPF_DED", 2500, "DEDUCTION", "01-04-2024", ""},
                {"EMP001", "123456789012", "", "9876543210", "ravi@hmwssb.gov.in", "", "", "",
                 "RAVI KUMAR", "ACCOUNTS (D01)", "D01", "INCOME_TAX", 1200, "DEDUCTION", "01-04-2024", ""},
                {"", "234567890123", "", "9876543211", "priya@hmwssb.gov.in", "", "", "",
                 "PRIYA SHARMA", "OPERATIONS (D33)", "D33", "BASIC", 44900, "EARNING", "01-04-2024", ""},
                {"", "234567890123", "", "9876543211", "priya@hmwssb.gov.in", "", "", "",
                 "PRIYA SHARMA", "OPERATIONS (D33)", "D33", "DA", 17062, "EARNING", "01-04-2024", ""},
                {"", "234567890123", "", "9876543211", "priya@hmwssb.gov.in", "", "", "",
                 "PRIYA SHARMA", "OPERATIONS (D33)", "D33", "NPS_DED", 4490, "DEDUCTION", "01-04-2024", ""},
                {"", "", "ABCDE1234F", "9876543212", "suresh@hmwssb.gov.in", "", "", "",
                 "SURESH REDDY", "CONSTRUCTION DIV-1 (D10)", "D10", "BASIC", 35400, "EARNING", "01-04-2024", ""},
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

            String[][] divisionData = {
                {"D01", "ACCOUNTS", "FINANCE", "FINANCE", "1", "ACTIVE", "VERIFIED"},
                {"D02", "ADMINISTRATION", "ADMIN", "ADMIN", "2", "ACTIVE", "VERIFIED"},
                {"D03", "AUDIT", "FINANCE", "FINANCE", "3", "ACTIVE", "VERIFIED"},
                {"D04", "BILLING", "COMMERCIAL", "COMMERCIAL", "4", "ACTIVE", "VERIFIED"},
                {"D05", "BUILDING MAINTENANCE", "ENGINEERING", "MAINTENANCE", "5", "ACTIVE", "VERIFIED"},
                {"D06", "CENTRAL STORES", "STORES", "PROCUREMENT", "6", "ACTIVE", "VERIFIED"},
                {"D07", "CHEMICAL LAB", "QUALITY", "ENGINEERING", "7", "ACTIVE", "VERIFIED"},
                {"D08", "COMMERCIAL", "COMMERCIAL", "COMMERCIAL", "8", "ACTIVE", "VERIFIED"},
                {"D09", "COMPUTER CELL", "IT", "ADMIN", "9", "ACTIVE", "VERIFIED"},
                {"D10", "CONSTRUCTION DIV-1", "ENGINEERING", "CONSTRUCTION", "10", "ACTIVE", "VERIFIED"},
                {"D11", "CONSTRUCTION DIV-2", "ENGINEERING", "CONSTRUCTION", "11", "ACTIVE", "VERIFIED"},
                {"D12", "CONSTRUCTION DIV-3", "ENGINEERING", "CONSTRUCTION", "12", "ACTIVE", "VERIFIED"},
                {"D13", "CONSTRUCTION DIV-4", "ENGINEERING", "CONSTRUCTION", "13", "ACTIVE", "VERIFIED"},
                {"D14", "CONSTRUCTION DIV-5", "ENGINEERING", "CONSTRUCTION", "14", "ACTIVE", "VERIFIED"},
                {"D15", "CONSTRUCTION DIV-6", "ENGINEERING", "CONSTRUCTION", "15", "ACTIVE", "VERIFIED"},
                {"D16", "CONSTRUCTION DIV-7", "ENGINEERING", "CONSTRUCTION", "16", "ACTIVE", "VERIFIED"},
                {"D17", "CONSTRUCTION DIV-8", "ENGINEERING", "CONSTRUCTION", "17", "ACTIVE", "VERIFIED"},
                {"D18", "CONSTRUCTION DIV-9", "ENGINEERING", "CONSTRUCTION", "18", "ACTIVE", "VERIFIED"},
                {"D19", "CONSTRUCTION DIV-10", "ENGINEERING", "CONSTRUCTION", "19", "ACTIVE", "VERIFIED"},
                {"D20", "CONSTRUCTION DIV-11", "ENGINEERING", "CONSTRUCTION", "20", "ACTIVE", "VERIFIED"},
                {"D21", "CONSTRUCTION DIV-12", "ENGINEERING", "CONSTRUCTION", "21", "ACTIVE", "VERIFIED"},
                {"D22", "CONSTRUCTION DIV-13", "ENGINEERING", "CONSTRUCTION", "22", "ACTIVE", "VERIFIED"},
                {"D23", "CONSTRUCTION DIV-14", "ENGINEERING", "CONSTRUCTION", "23", "ACTIVE", "VERIFIED"},
                {"D24", "CONSTRUCTION DIV-15", "ENGINEERING", "CONSTRUCTION", "24", "ACTIVE", "VERIFIED"},
                {"D25", "DEWATERING", "ENGINEERING", "OPERATIONS", "25", "ACTIVE", "VERIFIED"},
                {"D26", "ELECTRICAL MAINTENANCE", "ENGINEERING", "MAINTENANCE", "26", "ACTIVE", "VERIFIED"},
                {"D27", "FINANCE", "FINANCE", "FINANCE", "27", "ACTIVE", "VERIFIED"},
                {"D28", "HORTICULTURE", "ENGINEERING", "MAINTENANCE", "28", "ACTIVE", "VERIFIED"},
                {"D29", "HR & LEGAL", "ADMIN", "ADMIN", "29", "ACTIVE", "VERIFIED"},
                {"D30", "INTERNAL AUDIT", "FINANCE", "FINANCE", "30", "ACTIVE", "VERIFIED"},
                {"D31", "LAND ACQUISITION", "ENGINEERING", "PLANNING", "31", "ACTIVE", "VERIFIED"},
                {"D32", "MECHANICAL MAINTENANCE", "ENGINEERING", "MAINTENANCE", "32", "ACTIVE", "VERIFIED"},
                {"D33", "OPERATIONS", "ENGINEERING", "OPERATIONS", "33", "ACTIVE", "VERIFIED"},
                {"D34", "PLANNING & DESIGN", "ENGINEERING", "PLANNING", "34", "ACTIVE", "VERIFIED"},
                {"D35", "PROCUREMENT", "STORES", "PROCUREMENT", "35", "ACTIVE", "VERIFIED"},
                {"D36", "PROJECT MONITORING", "ENGINEERING", "PLANNING", "36", "ACTIVE", "VERIFIED"},
                {"D37", "PUBLIC RELATIONS", "ADMIN", "ADMIN", "37", "ACTIVE", "VERIFIED"},
                {"D38", "QUALITY CONTROL", "QUALITY", "ENGINEERING", "38", "ACTIVE", "VERIFIED"},
                {"D39", "REVENUE", "COMMERCIAL", "COMMERCIAL", "39", "ACTIVE", "VERIFIED"},
                {"D40", "SEWERAGE MAINTENANCE", "ENGINEERING", "MAINTENANCE", "40", "ACTIVE", "VERIFIED"},
                {"D41", "STORM WATER DRAINS", "ENGINEERING", "MAINTENANCE", "41", "ACTIVE", "VERIFIED"},
                {"D42", "SURVEY", "ENGINEERING", "PLANNING", "42", "ACTIVE", "VERIFIED"},
                {"D43", "TRAINING", "ADMIN", "ADMIN", "43", "ACTIVE", "VERIFIED"},
                {"D44", "VEHICLE MAINTENANCE", "ENGINEERING", "MAINTENANCE", "44", "ACTIVE", "VERIFIED"},
                {"D45", "VIGILANCE", "ADMIN", "ADMIN", "45", "ACTIVE", "VERIFIED"},
                {"D46", "WATER SUPPLY MAINTENANCE", "ENGINEERING", "OPERATIONS", "46", "ACTIVE", "VERIFIED"},
                {"D47", "WATER TREATMENT PLANT-1", "ENGINEERING", "OPERATIONS", "47", "ACTIVE", "VERIFIED"},
                {"D48", "WATER TREATMENT PLANT-2", "ENGINEERING", "OPERATIONS", "48", "ACTIVE", "VERIFIED"},
            };

            for (int i = 0; i < divisionData.length; i++) {
                Row row = divSheet.createRow(i + 1);
                for (int j = 0; j < divisionData[i].length; j++) {
                    Cell cell = row.createCell(j);
                    cell.setCellValue(divisionData[i][j]);
                }
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

            String[][] componentData = {
                // Earnings
                {"BASIC", "Basic Pay", "EARNING", "Basic salary component", "true"},
                {"DA", "Dearness Allowance", "EARNING", "DA as per govt rules", "true"},
                {"HRA", "House Rent Allowance", "EARNING", "HRA based on city classification", "true"},
                {"TA", "Travel Allowance", "EARNING", "Transport allowance", "true"},
                {"MA", "Medical Allowance", "EARNING", "Fixed medical allowance", "true"},
                {"WA", "Washing Allowance", "EARNING", "Uniform washing allowance", "true"},
                {"OA", "Other Allowance", "EARNING", "Miscellaneous allowance", "true"},
                {"SPL_ALLOW", "Special Allowance", "EARNING", "Special duty allowance", "true"},
                {"CCA", "City Compensatory Allowance", "EARNING", "CCA for metro cities", "true"},
                {"HCA", "Hill Compensatory Allowance", "EARNING", "Hill area allowance", "true"},
                {"HILL_ALLOW", "Hill Allowance", "EARNING", "Allowance for hill postings", "true"},
                {"OVT", "Overtime", "EARNING", "Overtime payments", "true"},
                {"ARREARS", "Arrears", "EARNING", "Salary arrears", "true"},
                {"BONUS", "Bonus", "EARNING", "Annual bonus", "true"},
                {"LEAVE_ENCASH", "Leave Encashment", "EARNING", "Earned leave encashment", "true"},
                {"MEDICAL", "Medical Reimbursement", "EARNING", "Medical bill reimbursement", "true"},
                {"EDU_ALLOW", "Education Allowance", "EARNING", "Children education allowance", "true"},
                {"CHILD_ALLOW", "Child Allowance", "EARNING", "Child care allowance", "true"},
                {"TRANSPORT", "Transport Allowance", "EARNING", "Transport/commute allowance", "true"},
                {"WASHING", "Washing Allowance", "EARNING", "Uniform maintenance", "true"},
                {"UNIFORM", "Uniform Allowance", "EARNING", "Uniform stitching/maintenance", "true"},
                {"RISK", "Risk Allowance", "EARNING", "Hazardous duty allowance", "true"},
                {"HARD_AREA", "Hard Area Allowance", "EARNING", "Difficult area posting allowance", "true"},
                {"SPL_PAY", "Special Pay", "EARNING", "Special pay for additional duties", "true"},
                {"DEPUTATION", "Deputation Allowance", "EARNING", "Deputation duty allowance", "true"},
                {"HONORARIUM", "Honorarium", "EARNING", "Honorarium payments", "true"},
                {"OTHER_EARN", "Other Earnings", "EARNING", "Miscellaneous earnings", "true"},
                // Deductions
                {"GPF_DED", "GPF Subscription", "DEDUCTION", "General Provident Fund", "true"},
                {"CPF_DED", "CPF Subscription", "DEDUCTION", "Contributory Provident Fund", "true"},
                {"NPS_DED", "NPS Employee Share", "DEDUCTION", "National Pension System", "true"},
                {"GIS_DED", "Group Insurance Scheme", "DEDUCTION", "GIS premium", "true"},
                {"LIC_DED", "LIC Premium", "DEDUCTION", "LIC policy premium deduction", "true"},
                {"BANK_LOAN", "Bank Loan Recovery", "DEDUCTION", "Bank loan EMI recovery", "true"},
                {"HBA_DED", "House Building Advance", "DEDUCTION", "HBA recovery", "true"},
                {"EDU_LOAN", "Education Loan", "DEDUCTION", "Education loan recovery", "true"},
                {"FEST_ADV", "Festival Advance", "DEDUCTION", "Festival advance recovery", "true"},
                {"COMP_ADV", "Computer Advance", "DEDUCTION", "Computer advance recovery", "true"},
                {"VEH_ADV", "Vehicle Advance", "DEDUCTION", "Vehicle advance recovery", "true"},
                {"HOUSE_RENT", "House Rent Recovery", "DEDUCTION", "Govt quarter rent", "true"},
                {"WATER_CHG", "Water Charges", "DEDUCTION", "Water consumption charges", "true"},
                {"ELEC_CHG", "Electricity Charges", "DEDUCTION", "Electricity consumption charges", "true"},
                {"INCOME_TAX", "Income Tax (TDS)", "DEDUCTION", "Tax deducted at source", "true"},
                {"PROF_TAX", "Professional Tax", "DEDUCTION", "State professional tax", "true"},
                {"GIS_INS", "GIS Insurance", "DEDUCTION", "Group insurance premium", "true"},
                {"CGHS", "CGHS Contribution", "DEDUCTION", "Central Govt Health Scheme", "true"},
                {"CGEGIS", "CGEGIS", "DEDUCTION", "Central Govt Employees Group Insurance", "true"},
                {"UNION_DUES", "Union Dues", "DEDUCTION", "Employee union subscription", "true"},
                {"COOP_SOC", "Cooperative Society", "DEDUCTION", "Co-op society deduction", "true"},
                {"COURT_ATT", "Court Attachment", "DEDUCTION", "Court ordered deduction", "true"},
                {"OTHER_DED", "Other Deductions", "DEDUCTION", "Miscellaneous deductions", "true"},
                {"NPS_EMPR", "NPS Employer Share", "DEDUCTION", "Employer NPS contribution", "true"},
                // Statutory
                {"PF_EMPR", "PF Employer Share", "STATUTORY", "Employer PF contribution", "true"},
                {"ESI_EMPR", "ESI Employer Share", "STATUTORY", "Employer ESI contribution", "true"},
                {"PT_EMPR", "PT Employer Share", "STATUTORY", "Employer Professional Tax", "true"},
                {"LWF_EMPR", "LWF Employer Share", "STATUTORY", "Labour Welfare Fund", "true"},
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
        // Create a hidden sheet with division list for data validation (to avoid 255 char limit)
        Workbook wb = sheet.getWorkbook();
        String hiddenSheetName = "_Divisions_List_" + colIndex + "_" + System.currentTimeMillis();
        Sheet hiddenSheet = wb.createSheet(hiddenSheetName);
        for (int i = 0; i < divisions.length; i++) {
            Row r = hiddenSheet.createRow(i);
            r.createCell(0).setCellValue(divisions[i]);
        }
        wb.setSheetHidden(wb.getSheetIndex(hiddenSheet), true);

        String rangeName = "HMWSSB_DIVISIONS_" + colIndex;
        Name namedRange = wb.createName();
        namedRange.setNameName(rangeName);
        namedRange.setRefersToFormula(hiddenSheetName + "!$A$1:$A$" + divisions.length);

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
        String hiddenSheetName = "_Components_List_" + colIndex + "_" + System.currentTimeMillis();
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