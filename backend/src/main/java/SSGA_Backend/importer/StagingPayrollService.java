package SSGA_Backend.importer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;

@Service
public class StagingPayrollService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;

    public JdbcTemplate getJdbcTemplate() { return jdbcTemplate; }

    public StagingPayrollService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            ObjectMapper objectMapper,
            @Value("${ssga.import.storage-dir:}") String storageDirectory
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.objectMapper = objectMapper;
        
        Path configuredPath;
        if (storageDirectory == null || storageDirectory.isBlank()) {
            configuredPath = Paths.get(System.getProperty("user.home"), "ssga-data", "imports");
        } else {
            configuredPath = Paths.get(storageDirectory);
        }
        this.storageRoot = configuredPath.toAbsolutePath().normalize();
    }

    /**
     * Create a staging payroll header from an analyzed import job sheet.
     * This is the entry point for Phase 3: In-Grid Workspace.
     */
    @Transactional(transactionManager = "dataSourceTransactionManager")
    public StagingPayrollHeaderResponse createFromImportJob(
            long importJobId, int sheetIndex, StagingPayrollCreateRequest request
    ) throws Exception {
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SsgaPrincipal user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found");
        }

        rlsContext.setCurrentUser();

        // Verify access to import job
        Map<String, Object> job = jdbcTemplate.queryForMap(
            "SELECT id, file_key, file_name, uploaded_by, source_mapping FROM public.import_jobs WHERE id = ? AND uploaded_by = ?",
            importJobId, user.getUserId()
        );

        // Get sheet mapping and validation
        Map<String, Object> sheet = jdbcTemplate.queryForMap(
            "SELECT id, sheet_name, confirmed_type, column_mapping, header_row_number, headers " +
            "FROM public.import_job_sheets WHERE import_job_id = ? AND sheet_index = ?",
            importJobId, sheetIndex
        );

        String confirmedType = (String) sheet.get("confirmed_type");
        if (!Set.of("EMPLOYEE_MASTER_AND_COMPONENTS", "SALARY_ENTITLEMENTS").contains(confirmedType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                "Only EMPLOYEE_MASTER_AND_COMPONENTS or SALARY_ENTITLEMENTS sheets can create staging payroll");
        }

        // Generate batch number
        String batchNumber = "HMW-" + YearMonth.now().toString() + "-" + 
            String.format("%05d", getNextBatchSequence());

        // Read the Excel file and unpivot to staging details
        String fileKey = (String) job.get("file_key");
        Path filePath = storageRoot.resolve(fileKey).normalize();
        
        if (!filePath.startsWith(storageRoot) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source workbook not found");
        }

        // Parse column mapping
        JsonNode mappingJson = objectMapper.readTree(sheet.get("column_mapping").toString());
        JsonNode columns = mappingJson.get("columns");
        JsonNode options = mappingJson.get("options");
        String layout = options.has("layout") ? options.get("layout").asText() : "LONG";

        // Create staging header
        String sha256 = calculateFileSha256(filePath);
        UUID headerId = jdbcTemplate.queryForObject(
            "INSERT INTO public.staging_payroll_header " +
            "(batch_number, file_name, file_hash, uploaded_by, status, source_type, " +
            " payroll_month, payroll_year, import_job_id, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, 'DRAFT_MAPPED', ?, ?, ?, ?, NOW(), NOW()) RETURNING id",
            UUID.class,
            batchNumber,
            (String) job.get("file_name"),
            sha256,
            user.getUserId(),
            confirmedType,
            request.getPayrollMonth(),
            request.getPayrollYear(),
            importJobId
        );

        // Process sheet data into staging details
        int processed = processSheetToStaging(
            headerId, filePath, sheet, columns, layout, request.getPayrollMonth(), request.getPayrollYear()
        );

        // Assign temporary HRMS IDs for missing ones
        assignTemporaryHrmsIds(headerId);

        // Recalculate totals
        recalculateHeaderTotals(headerId);

        // Return response with header info
        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT id, batch_number, file_name, status, total_records, valid_records, " +
            "error_records, temp_id_count, total_gross_payout, total_deductions, total_net_payout, " +
            "payroll_month, payroll_year, created_at " +
            "FROM public.staging_payroll_header WHERE id = ?", headerId
        );

        return new StagingPayrollHeaderResponse(
            (UUID) header.get("id"),
            (String) header.get("batch_number"),
            (String) header.get("file_name"),
            (String) header.get("status"),
            ((Number) header.get("total_records")).intValue(),
            ((Number) header.get("valid_records")).intValue(),
            ((Number) header.get("error_records")).intValue(),
            ((Number) header.get("temp_id_count")).intValue(),
            ((Number) header.get("total_gross_payout")).doubleValue(),
            ((Number) header.get("total_deductions")).doubleValue(),
            ((Number) header.get("total_net_payout")).doubleValue(),
            ((Number) header.get("payroll_month")).intValue(),
            ((Number) header.get("payroll_year")).intValue(),
            header.get("created_at").toString(),
            "Staging payroll created with " + processed + " detail rows. " +
            ((Number) header.get("temp_id_count")).intValue() + " temporary IDs assigned."
        );
    }

    /**
     * Process Excel sheet and unpivot to staging_payroll_details (long format).
     * Handles both LONG layout (componentCode + amount columns) and WIDE layout (componentColumns).
     */
    private int processSheetToStaging(
            UUID headerId, Path filePath, Map<String, Object> sheet,
            JsonNode columns, String layout, int payrollMonth, int payrollYear
    ) throws Exception {
        
        int headerRowNumber = ((Number) sheet.get("header_row_number")).intValue();
        String[] headerArray = objectMapper.readValue(
            sheet.get("headers").toString(), String[].class
        );
        List<String> headers = Arrays.asList(headerArray);
        Map<String, Integer> columnIndexes = createColumnIndexes(headers);

        // Build component column mapping for WIDE layout
        Map<String, String> componentColumns = new HashMap<>();
        JsonNode options = columns.has("options") ? columns.get("options") : objectMapper.createObjectNode();
        if (columns.has("componentColumns")) {
            JsonNode compCols = columns.get("componentColumns");
            if (compCols.isObject()) {
                // Convert to Map using ObjectMapper
                @SuppressWarnings("unchecked")
                Map<String, String> compMap = objectMapper.convertValue(compCols, Map.class);
                componentColumns.putAll(compMap);
            }
        }

        DataFormatter formatter = new DataFormatter();
        int rowCount = 0;

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            Sheet sheetObj = workbook.getSheetAt((Integer) sheet.get("sheet_index"));
            int headerIndex = headerRowNumber - 1;

            for (int rowIndex = headerIndex + 1; rowIndex <= sheetObj.getLastRowNum(); rowIndex++) {
                Row row = sheetObj.getRow(rowIndex);
                if (row == null || isBlankRow(row)) continue;

                // Extract employee identifiers
                String hrmsId = readMappedText(row, columnIndexes, columns, "hrmsId", formatter);
                String aadhaar = readMappedText(row, columnIndexes, columns, "aadhaar", formatter);
                String pan = readMappedText(row, columnIndexes, columns, "pan", formatter);
                String contact = readMappedText(row, columnIndexes, columns, "contact", formatter);
                String email = readMappedText(row, columnIndexes, columns, "email", formatter);
                String employeeName = readMappedText(row, columnIndexes, columns, "employeeName", formatter);
                String division = readMappedText(row, columnIndexes, columns, "division", formatter);
                String divisionCode = readMappedText(row, columnIndexes, columns, "divisionId", formatter);

                // Read effective date
                LocalDate effectiveFrom = readMappedDate(row, columnIndexes, columns, "effectiveFrom", formatter);
                if (effectiveFrom == null && options.has("effectiveFromDefault")) {
                    effectiveFrom = LocalDate.parse(options.get("effectiveFromDefault").asText());
                }
                if (effectiveFrom == null) {
                    effectiveFrom = LocalDate.of(payrollYear, payrollMonth, 1);
                }

                if ("LONG".equals(layout)) {
                    // Long layout: one row per component
                    String componentCode = readMappedText(row, columnIndexes, columns, "componentCode", formatter);
                    BigDecimal amount = readMappedAmount(row, columnIndexes, columns, "amount");
                    String category = readMappedText(row, columnIndexes, columns, "category", formatter);
                    
                    if (componentCode != null && amount != null) {
                        insertStagingDetail(headerId, rowIndex + 1, hrmsId, aadhaar, pan, contact, email,
                            employeeName, division, divisionCode, componentCode, category, amount, effectiveFrom);
                        rowCount++;
                    }
                } else if ("WIDE".equals(layout) || columns.has("componentColumns")) {
                    // Wide layout: unpivot component columns
                    for (Map.Entry<String, String> entry : componentColumns.entrySet()) {
                        String componentCode = entry.getKey();
                        String sourceHeader = entry.getValue();
                        Integer colIdx = columnIndexes.get(sourceHeader);
                        
                        if (colIdx != null) {
                            BigDecimal amount = readAmountFromCell(row, colIdx);
                            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                                // Get category from component master
                                String category = getComponentCategory(componentCode);
                                insertStagingDetail(headerId, rowIndex + 1, hrmsId, aadhaar, pan, contact, email,
                                    employeeName, division, divisionCode, componentCode, category, amount, effectiveFrom);
                                rowCount++;
                            }
                        }
                    }
                }
            }
        }

        return rowCount;
    }

    private void insertStagingDetail(UUID headerId, int rowIndex, String hrmsId, String aadhaar, 
            String pan, String contact, String email, String employeeName, 
            String division, String divisionCode, String componentCode, String category,
            BigDecimal amount, LocalDate effectiveFrom) {
        
        // Try to match employee master
        Long empMasterId = null;
        if (hrmsId != null && !hrmsId.isBlank()) {
            empMasterId = jdbcTemplate.queryForObject(
                "SELECT id FROM public.employees WHERE hrms_id = ?", Long.class, hrmsId.trim());
        }
        if (empMasterId == null && aadhaar != null && !aadhaar.isBlank()) {
            empMasterId = jdbcTemplate.queryForObject(
                "SELECT id FROM public.employees WHERE aadhaar_no = ?", Long.class, aadhaar.replaceAll("[^0-9]", ""));
        }
        if (empMasterId == null && pan != null && !pan.isBlank()) {
            empMasterId = jdbcTemplate.queryForObject(
                "SELECT id FROM public.employees WHERE pan_no = ?", Long.class, pan.trim().toUpperCase());
        }
        if (empMasterId == null && contact != null && !contact.isBlank()) {
            empMasterId = jdbcTemplate.queryForObject(
                "SELECT id FROM public.employees WHERE contact_no = ?", Long.class, contact.replaceAll("[^0-9]", ""));
        }

        // If no existing employee found, CREATE new employee master record
        if (empMasterId == null) {
            empMasterId = createEmployeeMaster(hrmsId, aadhaar, pan, contact, email, employeeName, 
                    division, divisionCode);
        }

        // Try to match division
        Long matchedDivisionId = null;
        if (division != null || divisionCode != null) {
            matchedDivisionId = jdbcTemplate.queryForObject(
                "SELECT public.match_hmwssb_division(?, ?)", Long.class, division, divisionCode);
        }

        // Get component ID
        Long componentId = jdbcTemplate.queryForObject(
            "SELECT id FROM public.salary_components WHERE component_code = ? AND status = 'ACTIVE'",
            Long.class, componentCode
        );

        String compName = jdbcTemplate.queryForObject(
            "SELECT component_name FROM public.salary_components WHERE component_code = ?",
            String.class, componentCode
        );

        String finalCategory = category != null ? category : 
            (componentId != null ? getComponentCategory(componentCode) : "EARNING");

        jdbcTemplate.update(
            "INSERT INTO public.staging_payroll_details " +
            "(header_id, row_index, raw_hrms_id, raw_aadhaar, raw_pan, raw_contact, raw_employee_name, " +
            " raw_division, raw_division_code, emp_master_id, matched_division_id, component_id, " +
            " component_code, component_name, category, amount, effective_from, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
            headerId, rowIndex, hrmsId, aadhaar, pan, contact, employeeName, division, divisionCode,
            empMasterId, matchedDivisionId, componentId, componentCode, compName, finalCategory, amount, effectiveFrom
        );
    }

    /**
     * Create new employee master record when no match found during import.
     * Returns the newly created employee ID.
     */
    private Long createEmployeeMaster(String hrmsId, String aadhaar, String pan, String contact,
            String email, String employeeName, String division, String divisionCode) {
        
        String cleanAadhaar = aadhaar != null ? aadhaar.replaceAll("[^0-9]", "") : null;
        String cleanPan = pan != null ? pan.trim().toUpperCase() : null;
        String cleanContact = contact != null ? contact.replaceAll("[^0-9]", "") : null;
        String cleanHrmsId = hrmsId != null ? hrmsId.trim() : null;
        String cleanEmail = email != null ? email.trim().toLowerCase() : null;

        // Get division ID if provided
        Long divisionId = null;
        if (division != null || divisionCode != null) {
            divisionId = jdbcTemplate.queryForObject(
                "SELECT public.match_hmwssb_division(?, ?)", Long.class, division, divisionCode);
        }

        // Insert new employee - use temp HRMS ID if no real HRMS ID provided
        String finalHrmsId = cleanHrmsId;
        boolean isTemp = false;
        if (finalHrmsId == null || finalHrmsId.isBlank()) {
            // Generate a temporary HRMS ID for the master record too
            Integer nextTempSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(CASE WHEN hrms_id ~ '^T-[0-9]{3}$' THEN SUBSTRING(hrms_id FROM 3)::INT ELSE 0 END), 0) + 1 " +
                "FROM public.employees", Integer.class);
            finalHrmsId = "T-" + String.format("%03d", nextTempSeq);
            isTemp = true;
        }

        final String finalHrmsIdForInsert = finalHrmsId;
        final String finalEmployeeName = employeeName != null ? employeeName : "UNKNOWN";
        final String finalAadhaar = cleanAadhaar;
        final String finalPan = cleanPan;
        final String finalContact = cleanContact;
        final String finalEmail = cleanEmail;
        final Long finalDivisionId = divisionId;
        final boolean finalIsTemp = isTemp;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO public.employees " +
                "(hrms_id, employee_code, employee_name, aadhaar_no, pan_no, contact_no, email, " +
                " hmwssb_division_id, is_temporary_id, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())",
                new String[]{"id"}
            );
            ps.setString(1, finalHrmsIdForInsert);
            ps.setString(2, finalHrmsIdForInsert);
            ps.setString(3, finalEmployeeName);
            ps.setString(4, finalAadhaar);
            ps.setString(5, finalPan);
            ps.setString(6, finalContact);
            ps.setString(7, finalEmail);
            if (finalDivisionId != null) {
                ps.setLong(8, finalDivisionId);
            } else {
                ps.setNull(8, java.sql.Types.BIGINT);
            }
            ps.setBoolean(9, finalIsTemp);
            return ps;
        }, keyHolder);

        return keyHolder.getKey().longValue();
    }

    /**
     * Assign temporary HRMS IDs (T-001, T-002...) for rows missing HRMS ID.
     */
    public void assignTemporaryHrmsIds(UUID headerId) {
        jdbcTemplate.update("SELECT public.assign_temporary_hrms_ids(?)", headerId);
    }

    /**
     * Recalculate header totals from detail rows.
     */
    public void recalculateHeaderTotals(UUID headerId) {
        jdbcTemplate.update("SELECT public.recalculate_staging_header_totals(?)", headerId);
    }

    /**
     * Get staging detail rows for in-grid editing with filters.
     */
    @Transactional(readOnly = true)
    public StagingPayrollDetailPageResponse getDetails(
            UUID headerId, StagingDetailFilterRequest filter, int page, int size
    ) {
        rlsContext.setCurrentUser();

        // Verify access
        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT id, uploaded_by, import_job_id FROM public.staging_payroll_header WHERE id = ?", headerId
        );

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT d.id, d.row_index, d.raw_hrms_id, d.temp_hrms_id, d.raw_aadhaar, d.raw_pan, d.raw_contact, ");
        sql.append("d.raw_employee_name, d.raw_division, d.raw_division_code, d.emp_master_id, d.matched_division_id, ");
        sql.append("d.component_id, d.component_code, d.component_name, d.category, d.amount, d.effective_from, ");
        sql.append("d.validation_status, d.error_details, d.is_excluded, d.created_at, d.updated_at, ");
        sql.append("div.division_name, div.division_code, emp.name as master_name, emp.hrms_id as master_hrms_id ");
        sql.append("FROM public.staging_payroll_details d ");
        sql.append("LEFT JOIN public.hmwssb_divisions div ON div.id = d.matched_division_id ");
        sql.append("LEFT JOIN public.employees emp ON emp.id = d.emp_master_id ");
        sql.append("WHERE d.header_id = ?");

        List<Object> params = new ArrayList<>();
        params.add(headerId);

        // Apply filters
        if (filter.getValidationStatus() != null && !filter.getValidationStatus().isEmpty()) {
            sql.append(" AND d.validation_status IN (").append(
                filter.getValidationStatus().stream().map(s -> "?").collect(Collectors.joining(","))
            ).append(")");
            params.addAll(filter.getValidationStatus());
        }
        if (filter.getHasTempId() != null && filter.getHasTempId()) {
            sql.append(" AND d.temp_hrms_id IS NOT NULL");
        }
        if (filter.getDivisionMismatch() != null && filter.getDivisionMismatch()) {
            sql.append(" AND d.validation_status = 'DIVISION_MISMATCH'");
        }
        if (filter.getComponentCode() != null && !filter.getComponentCode().isBlank()) {
            sql.append(" AND d.component_code = ?");
            params.add(filter.getComponentCode());
        }

        // Order by row_index
        sql.append(" ORDER BY d.row_index");

        // Pagination
        sql.append(" LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

        List<StagingDetailRow> rows = jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> {
            StagingDetailRow row = new StagingDetailRow();
            row.setId(rs.getLong("id"));
            row.setRowIndex(rs.getInt("row_index"));
            row.setRawHrmsId(rs.getString("raw_hrms_id"));
            row.setTempHrmsId(rs.getString("temp_hrms_id"));
            row.setRawAadhaar(rs.getString("raw_aadhaar"));
            row.setRawPan(rs.getString("raw_pan"));
            row.setRawContact(rs.getString("raw_contact"));
            row.setRawEmployeeName(rs.getString("raw_employee_name"));
            row.setRawDivision(rs.getString("raw_division"));
            row.setRawDivisionCode(rs.getString("raw_division_code"));
            row.setEmpMasterId(rs.getObject("emp_master_id", Long.class));
            row.setMatchedDivisionId(rs.getObject("matched_division_id", Long.class));
            row.setComponentId(rs.getObject("component_id", Long.class));
            row.setComponentCode(rs.getString("component_code"));
            row.setComponentName(rs.getString("component_name"));
            row.setCategory(rs.getString("category"));
            row.setAmount(rs.getBigDecimal("amount"));
            row.setEffectiveFrom(rs.getObject("effective_from", LocalDate.class));
            row.setValidationStatus(rs.getString("validation_status"));
            row.setErrorDetails(rs.getObject("error_details", Map.class));
            row.setExcluded(rs.getBoolean("is_excluded"));
            row.setDivisionName(rs.getString("division_name"));
            row.setDivisionCode(rs.getString("division_code"));
            row.setMasterName(rs.getString("master_name"));
            row.setMasterHrmsId(rs.getString("master_hrms_id"));
            return row;
        });

        // Get total count
        String countSql = "SELECT COUNT(*) FROM public.staging_payroll_details WHERE header_id = ?";
        int total = jdbcTemplate.queryForObject(countSql, Integer.class, headerId);

        return new StagingPayrollDetailPageResponse(
            rows, total, page, size, (int) Math.ceil((double) total / size)
        );
    }

    /**
     * Update a single staging detail row (in-grid edit).
     */
    @Transactional
    public StagingDetailRow updateDetail(UUID headerId, long detailId, StagingDetailUpdateRequest request) {
        rlsContext.setCurrentUser();

        // Verify access
        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT h.id FROM public.staging_payroll_header h " +
            "WHERE h.id = ? AND (h.uploaded_by = ? OR EXISTS (" +
            "  SELECT 1 FROM public.import_job_assignments a " +
            "  WHERE a.import_job_id = h.import_job_id AND a.user_id = ? AND a.is_active " +
            "  AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER')" +
            "))",
            headerId, getCurrentUserId(), getCurrentUserId()
        );

        StringBuilder sql = new StringBuilder("UPDATE public.staging_payroll_details SET ");
        List<Object> params = new ArrayList<>();

        if (request.getComponentCode() != null) {
            sql.append("component_code = ?, ");
            params.add(request.getComponentCode());
            // Update component_id and name
            Long compId = jdbcTemplate.queryForObject(
                "SELECT id FROM public.salary_components WHERE component_code = ?", Long.class, request.getComponentCode());
            String compName = jdbcTemplate.queryForObject(
                "SELECT component_name FROM public.salary_components WHERE component_code = ?", String.class, request.getComponentCode());
            sql.append("component_id = ?, component_name = ?, ");
            params.add(compId);
            params.add(compName);
        }
        if (request.getAmount() != null) {
            sql.append("amount = ?, ");
            params.add(request.getAmount());
        }
        if (request.getCategory() != null) {
            sql.append("category = ?, ");
            params.add(request.getCategory());
        }
        if (request.getEffectiveFrom() != null) {
            sql.append("effective_from = ?, ");
            params.add(request.getEffectiveFrom());
        }
        if (request.getMatchedDivisionId() != null) {
            sql.append("matched_division_id = ?, ");
            params.add(request.getMatchedDivisionId());
            // Re-validate division match
            String divName = jdbcTemplate.queryForObject(
                "SELECT division_name FROM public.hmwssb_divisions WHERE id = ?", String.class, request.getMatchedDivisionId());
            sql.append("validation_status = 'VALID', error_details = '{}', ");
        }
        if (request.getIsExcluded() != null) {
            sql.append("is_excluded = ?, ");
            params.add(request.getIsExcluded());
        }

        sql.append("updated_at = NOW() WHERE id = ? AND header_id = ?");
        params.add(detailId);
        params.add(headerId);

        int updated = jdbcTemplate.update(sql.toString(), params.toArray());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Detail row not found");
        }

        // Recalculate totals
        recalculateHeaderTotals(headerId);

        // Return updated row
        return jdbcTemplate.queryForObject(
            "SELECT d.*, div.division_name, div.division_code, emp.name as master_name, emp.hrms_id as master_hrms_id " +
            "FROM public.staging_payroll_details d " +
            "LEFT JOIN public.hmwssb_divisions div ON div.id = d.matched_division_id " +
            "LEFT JOIN public.employees emp ON emp.id = d.emp_master_id " +
            "WHERE d.id = ?", (rs, rowNum) -> mapDetailRow(rs), detailId
        );
    }

    /**
     * Bulk update for grid operations (e.g., apply division to multiple rows).
     */
    @Transactional
    public int bulkUpdateDetails(UUID headerId, StagingBulkUpdateRequest request) {
        rlsContext.setCurrentUser();

        // Verify access (same as updateDetail)
        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT h.id FROM public.staging_payroll_header h " +
            "WHERE h.id = ? AND (h.uploaded_by = ? OR EXISTS (" +
            "  SELECT 1 FROM public.import_job_assignments a " +
            "  WHERE a.import_job_id = h.import_job_id AND a.user_id = ? AND a.is_active " +
            "  AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER')" +
            "))",
            headerId, getCurrentUserId(), getCurrentUserId()
        );

        if (request.getDetailIds() == null || request.getDetailIds().isEmpty()) {
            return 0;
        }

        String placeholders = request.getDetailIds().stream().map(i -> "?").collect(Collectors.joining(","));
        
        StringBuilder sql = new StringBuilder("UPDATE public.staging_payroll_details SET ");
        List<Object> params = new ArrayList<>();

        if (request.getMatchedDivisionId() != null) {
            sql.append("matched_division_id = ?, validation_status = 'VALID', error_details = '{}', ");
            params.add(request.getMatchedDivisionId());
        }
        if (request.getComponentCode() != null) {
            sql.append("component_code = ?, ");
            params.add(request.getComponentCode());
            Long compId = jdbcTemplate.queryForObject(
                "SELECT id FROM public.salary_components WHERE component_code = ?", Long.class, request.getComponentCode());
            String compName = jdbcTemplate.queryForObject(
                "SELECT component_name FROM public.salary_components WHERE component_code = ?", String.class, request.getComponentCode());
            sql.append("component_id = ?, component_name = ?, ");
            params.add(compId);
            params.add(compName);
        }
        if (request.getIsExcluded() != null) {
            sql.append("is_excluded = ?, ");
            params.add(request.getIsExcluded());
        }

        sql.append("updated_at = NOW() WHERE id IN (").append(placeholders).append(") AND header_id = ?");
        params.addAll(request.getDetailIds());
        params.add(headerId);

        int updated = jdbcTemplate.update(sql.toString(), params.toArray());
        if (updated > 0) {
            recalculateHeaderTotals(headerId);
        }
        return updated;
    }

    /**
     * Submit staging payroll for 3-tier approval workflow.
     * Officer (Preparation) -> Senior Officer F&A (Verification) -> General Manager (Approval)
     */
    @Transactional
    public StagingPayrollWorkflowResponse submitForApproval(UUID headerId, StagingWorkflowRequest request) {
        rlsContext.setCurrentUser();
        long userId = getCurrentUserId();

        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT id, status, uploaded_by, import_job_id, total_records, valid_records, error_records, temp_id_count " +
            "FROM public.staging_payroll_header WHERE id = ?", headerId
        );

        String currentStatus = (String) header.get("status");
        if (!"DRAFT_MAPPED".equals(currentStatus) && !"IN_RESOLUTION".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, 
                "Can only submit from DRAFT_MAPPED or IN_RESOLUTION status. Current: " + currentStatus);
        }

        // Validate: must have valid records, no blocking errors
        int errorCount = ((Number) header.get("error_records")).intValue();
        if (errorCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                "Cannot submit with " + errorCount + " error rows. Resolve or exclude them first.");
        }

        // Officer submits -> moves to VERIFIED_SO (awaiting Senior Officer)
        jdbcTemplate.update(
            "UPDATE public.staging_payroll_header SET status = 'VERIFIED_SO', " +
            "submitted_by = ?, submitted_at = NOW(), updated_at = NOW() WHERE id = ?",
            userId, headerId
        );

        // Log workflow event
        logWorkflowEvent(headerId, "SUBMIT_FOR_VERIFICATION", currentStatus, "VERIFIED_SO", userId, 
            Map.of("remarks", request.getRemarks()));

        return getWorkflowStatus(headerId);
    }

    /**
     * Senior Officer F&A verifies the payroll.
     */
    @Transactional
    public StagingPayrollWorkflowResponse verifyBySO(UUID headerId, StagingWorkflowRequest request) {
        rlsContext.setCurrentUser();
        long userId = getCurrentUserId();

        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT id, status, submitted_by FROM public.staging_payroll_header WHERE id = ?", headerId
        );

        String currentStatus = (String) header.get("status");
        if (!"VERIFIED_SO".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, 
                "Can only verify from VERIFIED_SO status. Current: " + currentStatus);
        }

        // Maker-checker: SO cannot be the same as Officer who submitted
        Long submittedBy = (Long) header.get("submitted_by");
        if (submittedBy != null && submittedBy == userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, 
                "Senior Officer cannot verify their own submission");
        }

        // Verify budget, sanction orders, salary variations
        // (Business logic validation would go here)

        // SO verifies -> moves to PENDING_GM_APPROVAL (awaiting General Manager)
        jdbcTemplate.update(
            "UPDATE public.staging_payroll_header SET status = 'PENDING_GM_APPROVAL', " +
            "verified_by = ?, verified_at = NOW(), updated_at = NOW() WHERE id = ?",
            userId, headerId
        );

        logWorkflowEvent(headerId, "VERIFY_BY_SO", currentStatus, "PENDING_GM_APPROVAL", userId, 
            Map.of("remarks", request.getRemarks()));

        return getWorkflowStatus(headerId);
    }

    /**
     * General Manager approves and locks the monthly ledger.
     */
    @Transactional
    public StagingPayrollWorkflowResponse approveByGM(UUID headerId, StagingWorkflowRequest request) {
        rlsContext.setCurrentUser();
        long userId = getCurrentUserId();

        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT id, status, verified_by, payroll_month, payroll_year, total_gross_payout, " +
            "total_deductions, total_net_payout FROM public.staging_payroll_header WHERE id = ?", headerId
        );

        String currentStatus = (String) header.get("status");
        if (!"PENDING_GM_APPROVAL".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, 
                "Can only approve from PENDING_GM_APPROVAL status. Current: " + currentStatus);
        }

        // Maker-checker: GM cannot be the same as SO who verified
        Long verifiedBy = (Long) header.get("verified_by");
        if (verifiedBy != null && verifiedBy == userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, 
                "General Manager cannot approve their own verification");
        }

        // Create monthly salary ledger entries from valid staging details
        int payrollMonth = ((Number) header.get("payroll_month")).intValue();
        int payrollYear = ((Number) header.get("payroll_year")).intValue();
        int ledgerCount = createMonthlyLedgerFromStaging(headerId, payrollMonth, payrollYear, userId);

        // Update header status to FINAL_APPROVED (ledger locked)
        jdbcTemplate.update(
            "UPDATE public.staging_payroll_header SET status = 'FINAL_APPROVED', " +
            "approved_by = ?, approved_at = NOW(), updated_at = NOW() WHERE id = ?",
            userId, headerId
        );

        logWorkflowEvent(headerId, "APPROVE_BY_GM", currentStatus, "FINAL_APPROVED", userId, 
            Map.of("remarks", request.getRemarks(), "ledger_entries_created", ledgerCount));

        return getWorkflowStatus(headerId);
    }

    /**
     * Reject at any stage (Officer, SO, or GM can reject with reason).
     */
    @Transactional
    public StagingPayrollWorkflowResponse reject(UUID headerId, StagingWorkflowRequest request) {
        rlsContext.setCurrentUser();
        long userId = getCurrentUserId();

        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT id, status FROM public.staging_payroll_header WHERE id = ?", headerId
        );

        String currentStatus = (String) header.get("status");
        if ("REJECTED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already rejected");
        }

        jdbcTemplate.update(
            "UPDATE public.staging_payroll_header SET status = 'REJECTED', " +
            "rejected_by = ?, rejected_at = NOW(), rejection_reason = ?, updated_at = NOW() WHERE id = ?",
            userId, request.getRemarks(), headerId
        );

        logWorkflowEvent(headerId, "REJECT", currentStatus, "REJECTED", userId, 
            Map.of("rejection_reason", request.getRemarks()));

        return getWorkflowStatus(headerId);
    }

    /**
     * Get current workflow status.
     */
    @Transactional(readOnly = true)
    public StagingPayrollWorkflowResponse getWorkflowStatus(UUID headerId) {
        rlsContext.setCurrentUser();

        Map<String, Object> header = jdbcTemplate.queryForMap(
            "SELECT id, batch_number, status, submitted_by, submitted_at, verified_by, verified_at, " +
            "approved_by, approved_at, rejected_by, rejected_at, rejection_reason " +
            "FROM public.staging_payroll_header WHERE id = ?", headerId
        );

        // Get workflow events
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
            "SELECT action, previous_status, new_status, actor_user_id, details, created_at " +
            "FROM public.staging_workflow_events WHERE header_id = ? ORDER BY created_at",
            headerId
        );

        return new StagingPayrollWorkflowResponse(
            (UUID) header.get("id"),
            (String) header.get("batch_number"),
            (String) header.get("status"),
            header.get("submitted_at") != null ? header.get("submitted_at").toString() : null,
            header.get("verified_at") != null ? header.get("verified_at").toString() : null,
            header.get("approved_at") != null ? header.get("approved_at").toString() : null,
            header.get("rejected_at") != null ? header.get("rejected_at").toString() : null,
            (String) header.get("rejection_reason"),
            events
        );
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private int getNextBatchSequence() {
        Integer seq = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(CAST(SUBSTRING(batch_number FROM 'HMW-[0-9]{6}-([0-9]{5})') AS INT)), 0) + 1 " +
            "FROM public.staging_payroll_header WHERE batch_number LIKE 'HMW-" + YearMonth.now() + "-%'",
            Integer.class
        );
        return seq != null ? seq : 1;
    }

    private String calculateFileSha256(Path filePath) throws Exception {
        try (java.io.InputStream is = Files.newInputStream(filePath)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return bytesToHex(digest.digest());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Map<String, Integer> createColumnIndexes(List<String> headers) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).trim();
            if (!h.isEmpty()) indexes.put(h, i);
        }
        return indexes;
    }

    private String readMappedText(Row row, Map<String, Integer> colIndexes, JsonNode columns, 
            String mappingKey, DataFormatter formatter) {
        if (!columns.has(mappingKey)) return null;
        String header = columns.get(mappingKey).asText();
        Integer idx = colIndexes.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? null : formatter.formatCellValue(cell).trim();
    }

    private LocalDate readMappedDate(Row row, Map<String, Integer> colIndexes, JsonNode columns, 
            String mappingKey, DataFormatter formatter) {
        String text = readMappedText(row, colIndexes, columns, mappingKey, formatter);
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            // Try other formats
            try {
                return LocalDate.parse(text, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private BigDecimal readMappedAmount(Row row, Map<String, Integer> colIndexes, 
            JsonNode columns, String mappingKey) {
        if (!columns.has(mappingKey)) return null;
        String header = columns.get(mappingKey).asText();
        Integer idx = colIndexes.get(header);
        if (idx == null) return null;
        return readAmountFromCell(row, idx);
    }

    private BigDecimal readAmountFromCell(Row row, int colIdx) {
        Cell cell = row.getCell(colIdx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null || cell.getCellType() == CellType.BLANK) return BigDecimal.ZERO;
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        if (cell.getCellType() == CellType.STRING) {
            String text = cell.getStringCellValue().trim();
            if (text.isEmpty()) return BigDecimal.ZERO;
            try {
                return new BigDecimal(text.replace(",", "").replace("₹", "").replace(" ", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private boolean isBlankRow(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String getComponentCategory(String componentCode) {
        String cat = jdbcTemplate.queryForObject(
            "SELECT category FROM public.salary_components WHERE component_code = ?", 
            String.class, componentCode);
        return cat != null ? cat : "EARNING";
    }

    private long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SsgaPrincipal user) {
            return user.getUserId();
        }
        return -1;
    }

    private void logWorkflowEvent(UUID headerId, String action, String prevStatus, String newStatus, 
            long userId, Map<String, Object> details) {
        jdbcTemplate.update(
            "INSERT INTO public.staging_workflow_events " +
            "(header_id, action, previous_status, new_status, actor_user_id, details) " +
            "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
            headerId, action, prevStatus, newStatus, userId, objectMapper.writeValueAsString(details)
        );
    }

    private int createMonthlyLedgerFromStaging(UUID headerId, int month, int year, long userId) {
        // Aggregate by employee
        String sql = """
            INSERT INTO public.monthly_salary_ledger
            (emp_master_id, hrms_id, emp_name, ward, circle, division_id,
             payroll_month, payroll_year, basic_pay, gross_salary, total_deductions, net_salary,
             status, generated_by, verified_by, approved_by, approved_at, staging_header_id, created_at)
            SELECT 
                d.emp_master_id,
                COALESCE(d.temp_hrms_id, d.raw_hrms_id) as hrms_id,
                d.raw_employee_name as emp_name,
                NULL as ward,
                NULL as circle,
                d.matched_division_id as division_id,
                ? as payroll_month, ? as payroll_year,
                COALESCE(SUM(CASE WHEN d.component_code = 'BASIC_PAY' THEN d.amount END), 0) as basic_pay,
                COALESCE(SUM(CASE WHEN d.category = 'EARNING' THEN d.amount END), 0) as gross_salary,
                COALESCE(SUM(CASE WHEN d.category IN ('DEDUCTION','STATUTORY') THEN d.amount END), 0) as total_deductions,
                COALESCE(SUM(CASE WHEN d.category = 'EARNING' THEN d.amount END), 0) -
                COALESCE(SUM(CASE WHEN d.category IN ('DEDUCTION','STATUTORY') THEN d.amount END), 0) as net_salary,
                'FINAL_APPROVED' as status,
                ? as generated_by,
                ? as verified_by,
                ? as approved_by,
                NOW() as approved_at,
                ? as staging_header_id,
                NOW() as created_at
            FROM public.staging_payroll_details d
            WHERE d.header_id = ? AND d.validation_status = 'VALID' AND NOT d.is_excluded
            GROUP BY d.emp_master_id, d.temp_hrms_id, d.raw_hrms_id, d.raw_employee_name, d.matched_division_id
            """;
        return jdbcTemplate.update(sql, month, year, userId, userId, userId, headerId, headerId);
    }

    private StagingDetailRow mapDetailRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        StagingDetailRow row = new StagingDetailRow();
        row.setId(rs.getLong("id"));
        row.setRowIndex(rs.getInt("row_index"));
        row.setRawHrmsId(rs.getString("raw_hrms_id"));
        row.setTempHrmsId(rs.getString("temp_hrms_id"));
        row.setRawAadhaar(rs.getString("raw_aadhaar"));
        row.setRawPan(rs.getString("raw_pan"));
        row.setRawContact(rs.getString("raw_contact"));
        row.setRawEmployeeName(rs.getString("raw_employee_name"));
        row.setRawDivision(rs.getString("raw_division"));
        row.setRawDivisionCode(rs.getString("raw_division_code"));
        row.setEmpMasterId(rs.getObject("emp_master_id", Long.class));
        row.setMatchedDivisionId(rs.getObject("matched_division_id", Long.class));
        row.setComponentId(rs.getObject("component_id", Long.class));
        row.setComponentCode(rs.getString("component_code"));
        row.setComponentName(rs.getString("component_name"));
        row.setCategory(rs.getString("category"));
        row.setAmount(rs.getBigDecimal("amount"));
        row.setEffectiveFrom(rs.getObject("effective_from", LocalDate.class));
        row.setValidationStatus(rs.getString("validation_status"));
        row.setErrorDetails(rs.getObject("error_details", Map.class));
        row.setExcluded(rs.getBoolean("is_excluded"));
        row.setDivisionName(rs.getString("division_name"));
        row.setDivisionCode(rs.getString("division_code"));
        row.setMasterName(rs.getString("master_name"));
        row.setMasterHrmsId(rs.getString("master_hrms_id"));
        return row;
    }
}