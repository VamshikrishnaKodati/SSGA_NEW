
package SSGA_Backend.importer;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/import/jobs")
public class ImportJobController {

    private final ImportJobService importJobService;
    private final ImportJobAnalysisService analysisService;
    private final ImportJobSheetMappingService mappingService;
    private final ImportJobSheetValidationService validationService;
    private final ImportJobAssignmentService assignmentService;
    private final ImportJobWorkflowService workflowService;
    private final ImportJobAutoMapService autoMapService;
    private final ImportJobSheetPreviewService previewService;
    private final ImportJobProgressService progressService;
    private final ImportJobCorrectionService correctionService;
    private final ImportJobReviewService reviewService;

    public ImportJobController(
            ImportJobService importJobService,
            ImportJobAnalysisService analysisService,
            ImportJobSheetMappingService mappingService,
            ImportJobSheetValidationService validationService,
            ImportJobAssignmentService assignmentService,
            ImportJobWorkflowService workflowService,
            ImportJobAutoMapService autoMapService,
            ImportJobSheetPreviewService previewService,
            ImportJobProgressService progressService,
            ImportJobCorrectionService correctionService,
            ImportJobReviewService reviewService
    ) {
        this.importJobService = importJobService;
        this.analysisService = analysisService;
        this.mappingService = mappingService;
        this.validationService = validationService;
        this.assignmentService = assignmentService;
        this.workflowService = workflowService;
        this.autoMapService = autoMapService;
        this.previewService = previewService;
        this.progressService = progressService;
        this.correctionService = correctionService;
        this.reviewService = reviewService;
    }

    @GetMapping
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<java.util.List<ImportJobSummaryResponse>> list() {
        return ResponseEntity.ok(importJobService.list());
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<ImportJobUploadResponse> upload(
            @RequestParam("file") MultipartFile file
    ) throws Exception {
        return ResponseEntity.ok(importJobService.upload(file));
    }

    @PostMapping("/{jobId}/analyze")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<ImportJobAnalysisResponse> analyze(
            @PathVariable long jobId
    ) throws Exception {
        return ResponseEntity.ok(analysisService.analyze(jobId));
    }

    @PutMapping("/{jobId}/sheets/{sheetIndex}/mapping")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<ImportJobSheetMappingResponse> confirmMapping(
            @PathVariable long jobId,
            @PathVariable int sheetIndex,
            @RequestBody ConfirmSheetMappingRequest request
    ) throws Exception {
        return ResponseEntity.ok(
                mappingService.confirmMapping(
                        jobId,
                        sheetIndex,
                        request
                )
        );
    }

    @PostMapping("/{jobId}/sheets/{sheetIndex}/validate")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<ImportJobSheetValidationResponse> validate(
            @PathVariable long jobId,
            @PathVariable int sheetIndex
    ) throws Exception {
        return ResponseEntity.ok(
                validationService.validate(jobId, sheetIndex)
        );
    }

    @GetMapping("/{jobId}/sheets/{sheetIndex}/automap")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<ImportJobSheetAutoMapResponse> autoMap(
            @PathVariable long jobId,
            @PathVariable int sheetIndex
    ) throws Exception {
        return ResponseEntity.ok(autoMapService.autoMap(jobId, sheetIndex));
    }

    @GetMapping("/{jobId}/sheets/{sheetIndex}/preview")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<ImportJobSheetPreviewResponse> preview(
            @PathVariable long jobId,
            @PathVariable int sheetIndex
    ) throws Exception {
        return ResponseEntity.ok(previewService.preview(jobId, sheetIndex));
    }

    @GetMapping(
            value = "/{jobId}/sheets/{sheetIndex}/issues.csv",
            produces = "text/csv"
    )
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<byte[]> issuesCsv(
            @PathVariable long jobId,
            @PathVariable int sheetIndex
    ) {
        String csv = previewService.errorReportCsv(jobId, sheetIndex);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=import-job-"
                                + jobId + "-sheet-"
                                + sheetIndex + "-errors.csv")
                .contentType(new org.springframework.http.MediaType(
                        "text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @PostMapping("/{jobId}/assignments")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('user.manage') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.approve')"
    )
    public ResponseEntity<ImportJobAssignmentResponse> assign(
            @PathVariable long jobId,
            @RequestBody ImportJobAssignmentRequest request
    ) {
        return ResponseEntity.ok(
                assignmentService.assign(jobId, request)
        );
    }

    @GetMapping("/{jobId}/sheets/{sheetIndex}/error-rows")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<ImportJobSheetPreviewResponse> errorRows(
            @PathVariable long jobId,
            @PathVariable int sheetIndex
    ) throws Exception {
        return ResponseEntity.ok(
                previewService.errorRows(jobId, sheetIndex)
        );
    }

    @PostMapping("/{jobId}/sheets/{sheetIndex}/corrections")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<ImportJobSheetValidationResponse> correct(
            @PathVariable long jobId,
            @PathVariable int sheetIndex,
            @RequestBody ImportJobCorrectionRequest request
    ) throws Exception {
        return ResponseEntity.ok(
                correctionService.apply(jobId, sheetIndex, request)
        );
    }

    @GetMapping("/{jobId}/review-summary")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create') "
            + "or hasAuthority('payroll.approve')"
    )
    public ResponseEntity<ImportJobReviewSummaryResponse> reviewSummary(
            @PathVariable long jobId
    ) {
        return ResponseEntity.ok(reviewService.summary(jobId));
    }

    @GetMapping("/{jobId}/events")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<java.util.List<ImportJobWorkflowEventResponse>> events(
            @PathVariable long jobId
    ) {
        return ResponseEntity.ok(workflowService.events(jobId));
    }

    @PostMapping("/{jobId}/import-draft")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('salary.manage')"
    )
    public ResponseEntity<ImportJobWorkflowResponse> importDraft(
            @PathVariable long jobId
    ) {
        return ResponseEntity.ok(workflowService.importDraft(jobId));
    }

    @PostMapping("/{jobId}/submit")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('salary.manage')"
    )
    public ResponseEntity<ImportJobWorkflowResponse> submit(
            @PathVariable long jobId
    ) {
        return ResponseEntity.ok(workflowService.submit(jobId));
    }

    @PostMapping("/{jobId}/approve")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.approve')"
    )
    public ResponseEntity<ImportJobWorkflowResponse> approve(
            @PathVariable long jobId,
            @RequestBody(required = false) ImportJobDecisionRequest request
    ) throws IOException {
        try {
            return ResponseEntity.ok(workflowService.approve(jobId, request));
        } catch (IOException | RuntimeException ex) {
            String message = ex instanceof ResponseStatusException statusException
                    && statusException.getReason() != null
                    ? statusException.getReason()
                    : "The import failed and its database transaction was rolled back.";
            progressService.markFailed(jobId, message);
            throw ex;
        }
    }

    @PostMapping("/{jobId}/reject")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.approve')"
    )
    public ResponseEntity<ImportJobWorkflowResponse> reject(
            @PathVariable long jobId,
            @RequestBody ImportJobDecisionRequest request
    ) {
        return ResponseEntity.ok(workflowService.reject(jobId, request));
    }

    @PostMapping("/{jobId}/finalize")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<ImportJobWorkflowResponse> finalizeImport(
            @PathVariable long jobId
    ) throws IOException {
        try {
            return ResponseEntity.ok(workflowService.finalizeImport(jobId));
        } catch (IOException | RuntimeException ex) {
            String message = ex instanceof ResponseStatusException statusException
                    && statusException.getReason() != null
                    ? statusException.getReason()
                    : "The import failed and its database transaction was rolled back.";
            progressService.markFailed(jobId, message);
            throw ex;
        }
    }

    @GetMapping("/{jobId}/progress")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<ImportJobProgressResponse> progress(
            @PathVariable long jobId
    ) {
        return ResponseEntity.ok(progressService.getProgress(jobId));
    }
}
