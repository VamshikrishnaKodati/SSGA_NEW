package SSGA_Backend.importer;

import java.io.IOException;
import java.util.*;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/staging-payroll")
public class StagingPayrollController {

    private final StagingPayrollService stagingPayrollService;
    private final ImportJobService importJobService;
    private final ImportJobAnalysisService analysisService;
    private final ImportJobSheetMappingService mappingService;
    private final ImportJobSheetValidationService validationService;
    private final ImportJobAutoMapService autoMapService;

    public StagingPayrollController(
            StagingPayrollService stagingPayrollService,
            ImportJobService importJobService,
            ImportJobAnalysisService analysisService,
            ImportJobSheetMappingService mappingService,
            ImportJobSheetValidationService validationService,
            ImportJobAutoMapService autoMapService) {
        this.stagingPayrollService = stagingPayrollService;
        this.importJobService = importJobService;
        this.analysisService = analysisService;
        this.mappingService = mappingService;
        this.validationService = validationService;
        this.autoMapService = autoMapService;
    }

    /**
     * Create staging payroll from an already validated import job sheet.
     * This is the main entry point after upload -> analyze -> map -> validate flow.
     */
    @PostMapping("/from-import-job/{jobId}/sheets/{sheetIndex}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare')")
    public ResponseEntity<StagingPayrollHeaderResponse> createFromImportJob(
            @PathVariable long jobId,
            @PathVariable int sheetIndex,
            @RequestBody StagingPayrollCreateRequest request
    ) throws Exception {
        StagingPayrollHeaderResponse response = stagingPayrollService.createFromImportJob(jobId, sheetIndex, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get staging payroll header details.
     */
    @GetMapping("/{headerId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare') or hasAuthority('payroll.approve') or hasAuthority('payroll.finalize')")
    public ResponseEntity<StagingPayrollHeaderResponse> getHeader(@PathVariable UUID headerId) {
        StagingDetailFilterRequest filter = new StagingDetailFilterRequest();
        StagingPayrollDetailPageResponse page = stagingPayrollService.getDetails(headerId, filter, 0, 1);
        
        // Build header response from service
        Map<String, Object> header = stagingPayrollService.getJdbcTemplate().queryForMap(
            "SELECT id, batch_number, file_name, status, total_records, valid_records, error_records, " +
            "temp_id_count, total_gross_payout, total_deductions, total_net_payout, " +
            "payroll_month, payroll_year, created_at " +
            "FROM public.staging_payroll_header WHERE id = ?", headerId
        );

        return ResponseEntity.ok(new StagingPayrollHeaderResponse(
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
            "Staging payroll header retrieved"
        ));
    }

    /**
     * Get detail rows for in-grid editing with filters.
     * Filters: validationStatus, hasTempId, divisionMismatch, componentCode
     */
    @GetMapping("/{headerId}/details")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare') or hasAuthority('payroll.approve') or hasAuthority('payroll.finalize')")
    public ResponseEntity<StagingPayrollDetailPageResponse> getDetails(
            @PathVariable UUID headerId,
            @RequestParam(value = "validationStatus", required = false) java.util.List<String> validationStatus,
            @RequestParam(value = "hasTempId", required = false) Boolean hasTempId,
            @RequestParam(value = "divisionMismatch", required = false) Boolean divisionMismatch,
            @RequestParam(value = "componentCode", required = false) String componentCode,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size
    ) {
        StagingDetailFilterRequest filter = new StagingDetailFilterRequest();
        filter.setValidationStatus(validationStatus);
        filter.setHasTempId(hasTempId);
        filter.setDivisionMismatch(divisionMismatch);
        filter.setComponentCode(componentCode);

        StagingPayrollDetailPageResponse response = stagingPayrollService.getDetails(headerId, filter, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * Update a single detail row (in-grid edit).
     */
    @PutMapping("/{headerId}/details/{detailId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare')")
    public ResponseEntity<StagingDetailRow> updateDetail(
            @PathVariable UUID headerId,
            @PathVariable long detailId,
            @RequestBody StagingDetailUpdateRequest request
    ) {
        StagingDetailRow updated = stagingPayrollService.updateDetail(headerId, detailId, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Bulk update detail rows (e.g., apply division to multiple rows).
     */
    @PutMapping("/{headerId}/details/bulk")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare')")
    public ResponseEntity<Integer> bulkUpdateDetails(
            @PathVariable UUID headerId,
            @RequestBody StagingBulkUpdateRequest request
    ) {
        int updated = stagingPayrollService.bulkUpdateDetails(headerId, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Assign temporary HRMS IDs for rows missing HRMS ID.
     */
    @PostMapping("/{headerId}/assign-temp-ids")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare')")
    public ResponseEntity<String> assignTempIds(@PathVariable UUID headerId) {
        stagingPayrollService.assignTemporaryHrmsIds(headerId);
        return ResponseEntity.ok("Temporary HRMS IDs assigned");
    }

    /**
     * Recalculate header totals from detail rows.
     */
    @PostMapping("/{headerId}/recalculate")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare')")
    public ResponseEntity<String> recalculate(@PathVariable UUID headerId) {
        stagingPayrollService.recalculateHeaderTotals(headerId);
        return ResponseEntity.ok("Totals recalculated");
    }

    // ============================================================
    // 3-Tier Workflow Endpoints
    // ============================================================

    /**
     * Officer (Preparation) submits for Senior Officer verification.
     */
    @PostMapping("/{headerId}/submit")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('payroll.prepare') or hasAuthority('salary.prepare')")
    public ResponseEntity<StagingPayrollWorkflowResponse> submitForVerification(
            @PathVariable UUID headerId,
            @RequestBody StagingWorkflowRequest request
    ) {
        StagingPayrollWorkflowResponse response = stagingPayrollService.submitForApproval(headerId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Senior Officer F&A verifies the payroll.
     */
    @PostMapping("/{headerId}/verify")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('payroll.approve') or hasAuthority('salary.approve')")
    public ResponseEntity<StagingPayrollWorkflowResponse> verifyBySO(
            @PathVariable UUID headerId,
            @RequestBody StagingWorkflowRequest request
    ) {
        StagingPayrollWorkflowResponse response = stagingPayrollService.verifyBySO(headerId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * General Manager approves and locks the monthly ledger.
     */
    @PostMapping("/{headerId}/approve")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('payroll.finalize')")
    public ResponseEntity<StagingPayrollWorkflowResponse> approveByGM(
            @PathVariable UUID headerId,
            @RequestBody StagingWorkflowRequest request
    ) {
        StagingPayrollWorkflowResponse response = stagingPayrollService.approveByGM(headerId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Reject at any stage.
     */
    @PostMapping("/{headerId}/reject")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('payroll.approve') or hasAuthority('payroll.finalize')")
    public ResponseEntity<StagingPayrollWorkflowResponse> reject(
            @PathVariable UUID headerId,
            @RequestBody StagingWorkflowRequest request
    ) {
        StagingPayrollWorkflowResponse response = stagingPayrollService.reject(headerId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get current workflow status and history.
     */
    @GetMapping("/{headerId}/workflow")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare') or hasAuthority('payroll.approve') or hasAuthority('payroll.finalize')")
    public ResponseEntity<StagingPayrollWorkflowResponse> getWorkflowStatus(@PathVariable UUID headerId) {
        StagingPayrollWorkflowResponse response = stagingPayrollService.getWorkflowStatus(headerId);
        return ResponseEntity.ok(response);
    }

    // ============================================================
    // Division Hierarchy Endpoints (Wing → Circle → Division)
    // ============================================================

    /**
     * Get all 6 Wings (Level 1).
     */
    @GetMapping("/divisions/wings")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare') or hasAuthority('employee.create')")
    public ResponseEntity<java.util.List<HmwssbDivisionDto>> getWings() {
        String sql = """
            SELECT id, division_code, division_name, category, wing_code, wing_name, 
                   hierarchy_level, parent_group, display_order, status, verification_status,
                   area_coverage, head_designation
            FROM public.hmwssb_divisions 
            WHERE hierarchy_level = 1 AND status = 'ACTIVE'
            ORDER BY display_order
            """;

        java.util.List<HmwssbDivisionDto> wings = stagingPayrollService.getJdbcTemplate().query(
            sql, mapDivisionDto()
        );

        return ResponseEntity.ok(wings);
    }

    /**
     * Get Circles under a specific Wing (Level 2).
     */
    @GetMapping("/divisions/wings/{wingCode}/circles")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare') or hasAuthority('employee.create')")
    public ResponseEntity<java.util.List<HmwssbDivisionDto>> getCirclesByWing(@PathVariable String wingCode) {
        String sql = """
            SELECT id, division_code, division_name, category, wing_code, wing_name, 
                   circle_code, circle_name, hierarchy_level, parent_group, parent_division_id,
                   display_order, status, verification_status, area_coverage, head_designation
            FROM public.hmwssb_divisions 
            WHERE hierarchy_level = 2 AND wing_code = ? AND status = 'ACTIVE'
            ORDER BY display_order
            """;

        java.util.List<HmwssbDivisionDto> circles = stagingPayrollService.getJdbcTemplate().query(
            sql, mapDivisionDto(), wingCode
        );

        return ResponseEntity.ok(circles);
    }

    /**
     * Get Divisions under a specific Circle (Level 3) - the actual 48 administrative divisions.
     */
    @GetMapping("/divisions/circles/{circleCode}/divisions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare') or hasAuthority('employee.create')")
    public ResponseEntity<java.util.List<HmwssbDivisionDto>> getDivisionsByCircle(@PathVariable String circleCode) {
        String sql = """
            SELECT id, division_code, division_name, category, wing_code, wing_name, 
                   circle_code, circle_name, hierarchy_level, parent_group, parent_division_id,
                   display_order, status, verification_status, area_coverage, head_designation
            FROM public.hmwssb_divisions 
            WHERE hierarchy_level = 3 AND circle_code = ? AND status = 'ACTIVE'
            ORDER BY display_order
            """;

        java.util.List<HmwssbDivisionDto> divisions = stagingPayrollService.getJdbcTemplate().query(
            sql, mapDivisionDto(), circleCode
        );

        return ResponseEntity.ok(divisions);
    }

    /**
     * Get full hierarchy tree: Wings → Circles → Divisions.
     * Useful for cascading dropdowns in UI.
     */
    @GetMapping("/divisions/hierarchy")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare') or hasAuthority('employee.create')")
    public ResponseEntity<java.util.List<HmwssbDivisionDto>> getFullHierarchy() {
        // Get all active nodes
        String sql = """
            SELECT id, division_code, division_name, category, wing_code, wing_name, 
                   circle_code, circle_name, hierarchy_level, parent_group, parent_division_id,
                   display_order, status, verification_status, area_coverage, head_designation
            FROM public.hmwssb_divisions 
            WHERE status = 'ACTIVE'
            ORDER BY hierarchy_level, display_order
            """;

        java.util.List<HmwssbDivisionDto> allNodes = stagingPayrollService.getJdbcTemplate().query(
            sql, mapDivisionDto()
        );

        // Build tree structure
        Map<Long, HmwssbDivisionDto> nodeMap = new LinkedHashMap<>();
        for (HmwssbDivisionDto node : allNodes) {
            nodeMap.put(node.getId(), node);
        }

        List<HmwssbDivisionDto> roots = new ArrayList<>();
        for (HmwssbDivisionDto node : allNodes) {
            if (node.getHierarchyLevel() == 1) {
                roots.add(node);
            } else if (node.getParentDivisionId() != null) {
                HmwssbDivisionDto parent = nodeMap.get(node.getParentDivisionId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(node);
                }
            }
        }

        return ResponseEntity.ok(roots);
    }

    /**
     * Get all active divisions (Level 3) flat list for simple dropdown.
     */
    @GetMapping("/divisions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare') or hasAuthority('employee.create')")
    public ResponseEntity<java.util.List<HmwssbDivisionDto>> getDivisions(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", defaultValue = "ACTIVE") String status,
            @RequestParam(value = "wingCode", required = false) String wingCode,
            @RequestParam(value = "circleCode", required = false) String circleCode
    ) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, division_code, division_name, category, wing_code, wing_name, " +
            "circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, " +
            "display_order, status, verification_status, area_coverage, head_designation " +
            "FROM public.hmwssb_divisions WHERE status = ? AND hierarchy_level = 3"
        );
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(status);

        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (wingCode != null && !wingCode.isBlank()) {
            sql.append(" AND wing_code = ?");
            params.add(wingCode);
        }
        if (circleCode != null && !circleCode.isBlank()) {
            sql.append(" AND circle_code = ?");
            params.add(circleCode);
        }
        sql.append(" ORDER BY display_order");

        java.util.List<HmwssbDivisionDto> divisions = stagingPayrollService.getJdbcTemplate().query(
            sql.toString(), params.toArray(), mapDivisionDto()
        );

        return ResponseEntity.ok(divisions);
    }

    /**
     * Match division from raw name/code (for auto-suggestion in grid).
     * Now includes hierarchy info.
     */
    @GetMapping("/divisions/match")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAuthority('salary.manage') or hasAuthority('payroll.prepare')")
    public ResponseEntity<HmwssbDivisionDto> matchDivision(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "code", required = false) String code
    ) {
        Long divisionId = stagingPayrollService.getJdbcTemplate().queryForObject(
            "SELECT public.match_hmwssb_division(?, ?)", Long.class, name, code
        );

        if (divisionId == null) {
            return ResponseEntity.ok(null);
        }

        HmwssbDivisionDto dto = stagingPayrollService.getJdbcTemplate().queryForObject(
            "SELECT id, division_code, division_name, category, wing_code, wing_name, " +
            "circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, " +
            "display_order, status, verification_status, area_coverage, head_designation " +
            "FROM public.hmwssb_divisions WHERE id = ?", mapDivisionDto(), divisionId
        );

        return ResponseEntity.ok(dto);
    }

    private RowMapper<HmwssbDivisionDto> mapDivisionDto() {
        return (rs, rowNum) -> {
            HmwssbDivisionDto dto = new HmwssbDivisionDto();
            dto.setId(rs.getLong("id"));
            dto.setDivisionCode(rs.getString("division_code"));
            dto.setDivisionName(rs.getString("division_name"));
            dto.setCategory(rs.getString("category"));
            dto.setWingCode(rs.getString("wing_code"));
            dto.setWingName(rs.getString("wing_name"));
            dto.setCircleCode(rs.getString("circle_code"));
            dto.setCircleName(rs.getString("circle_name"));
            dto.setHierarchyLevel(rs.getObject("hierarchy_level", Integer.class));
            dto.setParentGroup(rs.getString("parent_group"));
            dto.setParentDivisionId(rs.getObject("parent_division_id", Long.class));
            dto.setDisplayOrder(rs.getInt("display_order"));
            dto.setStatus(rs.getString("status"));
            dto.setVerificationStatus(rs.getString("verification_status"));
            dto.setAreaCoverage(rs.getString("area_coverage"));
            dto.setHeadDesignation(rs.getString("head_designation"));
            return dto;
        };
    }
}