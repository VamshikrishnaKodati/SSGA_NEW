package SSGA_Backend.importer;

import java.io.IOException;
import java.util.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;

@Service
public class ImportJobAutoMapService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final ObjectMapper objectMapper;

    public ImportJobAutoMapService(JdbcTemplate jdbcTemplate, RlsContext rlsContext, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.objectMapper = objectMapper;
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobSheetAutoMapResponse autoMap(long jobId, int sheetIndex) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SsgaPrincipal user)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Authenticated user not found");
        }

        rlsContext.setCurrentUser();

        // Get sheet info
        Map<String, Object> sheet = jdbcTemplate.queryForMap(
            "SELECT id, sheet_name, suggested_type, headers, header_row_number " +
            "FROM public.import_job_sheets WHERE import_job_id = ? AND sheet_index = ?",
            jobId, sheetIndex
        );

        String suggestedType = (String) sheet.get("suggested_type");
        String[] headerArray = objectMapper.readValue(sheet.get("headers").toString(), String[].class);
        List<String> headers = Arrays.asList(headerArray);

        // Build suggested column mapping based on sheet type
        Map<String, Object> suggestedColumns = new LinkedHashMap<>();
        Map<String, Object> suggestedOptions = new LinkedHashMap<>();

        switch (suggestedType) {
            case "EMPLOYEE_MASTER_AND_COMPONENTS" -> {
                suggestedColumns.putAll(suggestEmployeeMasterColumns(headers));
                suggestedColumns.putAll(suggestComponentColumns(headers));
                suggestedOptions.put("layout", detectLayout(headers));
                suggestedOptions.put("dateFormat", "yyyy-MM-dd");
            }
            case "SALARY_ENTITLEMENTS" -> {
                suggestedColumns.putAll(suggestSalaryEntitlementColumns(headers));
                suggestedOptions.put("layout", detectLayout(headers));
                suggestedOptions.put("dateFormat", "yyyy-MM-dd");
            }
            case "PAYROLL_HISTORY" -> {
                suggestedColumns.putAll(suggestPayrollHistoryColumns(headers));
                suggestedOptions.put("layout", detectLayout(headers));
                suggestedOptions.put("dateFormat", "yyyy-MM-dd");
            }
            case "SALARY_REVISIONS" -> {
                suggestedColumns.putAll(suggestSalaryRevisionColumns(headers));
                suggestedOptions.put("dateFormat", "yyyy-MM-dd");
            }
            case "EMPLOYEE_MASTER" -> {
                suggestedColumns.putAll(suggestEmployeeMasterColumns(headers));
                suggestedOptions.put("dateFormat", "yyyy-MM-dd");
            }
        }

        return new ImportJobSheetAutoMapResponse(
            jobId, sheetIndex, (String) sheet.get("sheet_name"),
            suggestedType, suggestedColumns, suggestedOptions,
            "Auto-mapping suggestions generated. Review and adjust before confirming."
        );
    }

    private String detectLayout(List<String> headers) {
        // Check if component columns are spread across many columns (WIDE)
        // vs single componentCode + amount columns (LONG)
        long componentLikeCount = headers.stream()
            .filter(h -> h.toUpperCase().contains("PAY") || h.toUpperCase().contains("ALLOW") 
                      || h.toUpperCase().contains("DEDUCT") || h.toUpperCase().contains("TAX")
                      || h.toUpperCase().contains("PF") || h.toUpperCase().contains("HRA")
                      || h.toUpperCase().contains("DA") || h.toUpperCase().contains("ADV"))
            .count();
        return componentLikeCount >= 5 ? "WIDE" : "LONG";
    }

    private Map<String, Object> suggestEmployeeMasterColumns(List<String> headers) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        String[] identityFields = {"hrmsId", "aadhaar", "pan", "contact", "email", "pf", "nps", "gpf"};
        String[] nameFields = {"employeeName", "name", "empName", "fullName"};
        String[] divisionFields = {"division", "divisionId", "divCode", "divisionCode"};
        String[] otherFields = {"designation", "wing", "dateOfJoining", "dateOfBirth", 
                               "employmentType", "payScale", "basicPay", "bankAccount", 
                               "ifscCode", "bankName", "branch", "employeeStatus"};

        for (String field : identityFields) {
            String match = findBestMatch(headers, field);
            if (match != null) mapping.put(field, match);
        }
        for (String field : nameFields) {
            String match = findBestMatch(headers, field);
            if (match != null) { mapping.put("employeeName", match); break; }
        }
        for (String field : divisionFields) {
            String match = findBestMatch(headers, field);
            if (match != null) { 
                if (field.contains("Id") || field.contains("Code")) mapping.put("divisionId", match);
                else mapping.put("division", match);
                break; 
            }
        }
        for (String field : otherFields) {
            String match = findBestMatch(headers, field);
            if (match != null) mapping.put(field, match);
        }
        return mapping;
    }

    private Map<String, Object> suggestSalaryEntitlementColumns(List<String> headers) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        String[] identityFields = {"hrmsId", "aadhaar", "pan", "contact", "email", "pf", "nps", "gpf"};
        
        for (String field : identityFields) {
            String match = findBestMatch(headers, field);
            if (match != null) mapping.put(field, match);
        }
        
        // For LONG layout
        String compCodeMatch = findBestMatch(headers, "componentCode", "salaryComponent", "component");
        String amountMatch = findBestMatch(headers, "amount", "value", "salaryAmount");
        String categoryMatch = findBestMatch(headers, "category", "type", "earningDeduction");
        String effFromMatch = findBestMatch(headers, "effectiveFrom", "effectiveDate", "fromDate");
        String effToMatch = findBestMatch(headers, "effectiveTo", "toDate", "endDate");
        
        if (compCodeMatch != null) mapping.put("componentCode", compCodeMatch);
        if (amountMatch != null) mapping.put("amount", amountMatch);
        if (categoryMatch != null) mapping.put("category", categoryMatch);
        if (effFromMatch != null) mapping.put("effectiveFrom", effFromMatch);
        if (effToMatch != null) mapping.put("effectiveTo", effToMatch);
        
        return mapping;
    }

    private Map<String, Object> suggestComponentColumns(List<String> headers) {
        // For WIDE layout - find all component-like columns
        Map<String, String> componentColumns = new LinkedHashMap<>();
        Set<String> knownComponents = Set.of(
            "BASIC_PAY", "T_SPL_INCREMENT", "FP_PP", "SPL_PAY_G_MITRA", "DA_33_67",
            "HRA_24", "ADDL_HRA", "CCA", "PETROL_AUTO_ALLOW", "WASHING_ALLOW",
            "BOARD_FORMATION_DAY", "IR_5_2023", "DA_ARREARS_26_39", "DA_ARREARS_30_03", "DA_ARREARS_33_67",
            "GPF_EPF", "COURT_ATCH", "PENSION_SCHEME", "TSGLIF", "GIS", "PRC_2020_NPS",
            "ELECT_CHAR", "FESTIVAL_ADV", "EDCN_ADV", "VEHICLE_ADV", "MOPED_ADV",
            "EXCESS_SALARY_RECOVERY", "EPF_12_PERCENT", "SBI_LIFE_INSURANCE", "ONE_DAY_SALARY",
            "MARRIAGE_LOAN", "MGB_AN", "PROF_TAX", "INCOME_TAX", "EPF_PRC_ARREARS",
            "PRC_EXCESS_RECOVERY", "MGB_PC", "TELANGANA_HARITHA_NIDHI",
            "CCA_RECOVERY_DARUSSALAM", "SBH_ANAND_NAGAR", "FLAG_DAY_2025", "LIC"
        );

        for (String header : headers) {
            String normalized = header.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_");
            if (knownComponents.contains(normalized)) {
                componentColumns.put(normalized, header);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (!componentColumns.isEmpty()) {
            result.put("componentColumns", componentColumns);
        }
        return result;
    }

    private Map<String, Object> suggestPayrollHistoryColumns(List<String> headers) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        String[] identityFields = {"hrmsId", "aadhaar", "pan", "contact", "email", "pf", "nps", "gpf"};
        for (String field : identityFields) {
            String match = findBestMatch(headers, field);
            if (match != null) mapping.put(field, match);
        }
        
        String periodMatch = findBestMatch(headers, "period", "payPeriod", "monthYear");
        String monthMatch = findBestMatch(headers, "payrollMonth", "month");
        String yearMatch = findBestMatch(headers, "payrollYear", "year");
        String compCodeMatch = findBestMatch(headers, "componentCode", "component");
        String amountMatch = findBestMatch(headers, "amount", "value");
        String grossMatch = findBestMatch(headers, "gross", "grossSalary");
        String netMatch = findBestMatch(headers, "net", "netSalary");
        String dedMatch = findBestMatch(headers, "totalDeductions", "deductions");
        
        if (periodMatch != null) mapping.put("period", periodMatch);
        if (monthMatch != null) mapping.put("payrollMonth", monthMatch);
        if (yearMatch != null) mapping.put("payrollYear", yearMatch);
        if (compCodeMatch != null) mapping.put("componentCode", compCodeMatch);
        if (amountMatch != null) mapping.put("amount", amountMatch);
        if (grossMatch != null) mapping.put("gross", grossMatch);
        if (netMatch != null) mapping.put("net", netMatch);
        if (dedMatch != null) mapping.put("totalDeductions", dedMatch);
        
        return mapping;
    }

    private Map<String, Object> suggestSalaryRevisionColumns(List<String> headers) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        String[] identityFields = {"hrmsId", "aadhaar", "pan", "contact", "email", "pf", "nps", "gpf"};
        for (String field : identityFields) {
            String match = findBestMatch(headers, field);
            if (match != null) mapping.put(field, match);
        }
        
        String effFromMatch = findBestMatch(headers, "effectiveFrom", "effectiveDate", "fromDate");
        String compCodeMatch = findBestMatch(headers, "componentCode", "component");
        String oldAmtMatch = findBestMatch(headers, "oldAmount", "previousAmount", "oldBasic");
        String newAmtMatch = findBestMatch(headers, "newAmount", "revisedAmount", "newBasic");
        String amountMatch = findBestMatch(headers, "amount", "incrementAmount");
        String reasonMatch = findBestMatch(headers, "reason", "remarks", "sanctionOrder");
        
        if (effFromMatch != null) mapping.put("effectiveFrom", effFromMatch);
        if (compCodeMatch != null) mapping.put("componentCode", compCodeMatch);
        if (oldAmtMatch != null) mapping.put("oldAmount", oldAmtMatch);
        if (newAmtMatch != null) mapping.put("newAmount", newAmtMatch);
        if (amountMatch != null) mapping.put("amount", amountMatch);
        if (reasonMatch != null) mapping.put("reason", reasonMatch);
        
        return mapping;
    }

    private String findBestMatch(List<String> headers, String... candidates) {
        for (String candidate : candidates) {
            String normalizedCandidate = candidate.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
            for (String header : headers) {
                String normalizedHeader = header.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_");
                if (normalizedHeader.equals(normalizedCandidate) || 
                    normalizedHeader.contains(normalizedCandidate) ||
                    normalizedCandidate.contains(normalizedHeader)) {
                    return header;
                }
            }
        }
        return null;
    }
}