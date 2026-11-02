package SSGA_Backend.payroll;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payroll/batches")
public class PayrollController {

    private final PayrollBatchService payrollBatchService;
    private final PayrollWorkflowService payrollWorkflowService;

    public PayrollController(
            PayrollBatchService payrollBatchService,
            PayrollWorkflowService payrollWorkflowService
    ) {
        this.payrollBatchService = payrollBatchService;
        this.payrollWorkflowService = payrollWorkflowService;
    }

    @PostMapping("/generate")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.prepare')"
    )
    public ResponseEntity<PayrollBatchResponse> generate(
            @RequestBody PayrollGenerateRequest request
    ) {
        return ResponseEntity.ok(payrollBatchService.generate(request));
    }

    @GetMapping
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.view') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.verify') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize') "
            + "or hasAuthority('payroll.reopen')"
    )
    public ResponseEntity<List<PayrollBatchResponse>> list(
            @RequestParam(required = false) Long periodId
    ) {
        return ResponseEntity.ok(payrollBatchService.listBatches(periodId));
    }

    @GetMapping("/{batchId}")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.view') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.verify') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize') "
            + "or hasAuthority('payroll.reopen')"
    )
    public ResponseEntity<PayrollBatchDetailResponse> detail(
            @PathVariable long batchId
    ) {
        return ResponseEntity.ok(payrollBatchService.getBatch(batchId));
    }

    @GetMapping("/{batchId}/summary")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.view') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.verify') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize') "
            + "or hasAuthority('payroll.reopen')"
    )
    public ResponseEntity<PayrollBatchDetailResponse> summary(
            @PathVariable long batchId
    ) {
        return ResponseEntity.ok(payrollBatchService.getBatchSummary(batchId));
    }

    @GetMapping("/{batchId}/transactions")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.view') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.verify') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize') "
            + "or hasAuthority('payroll.reopen')"
    )
    public ResponseEntity<PayrollTransactionPageResponse> transactions(
            @PathVariable long batchId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String hrmsId
    ) {
        return ResponseEntity.ok(payrollBatchService.getTransactionPage(
                batchId, page, size, search, hrmsId
        ));
    }

    @GetMapping("/{batchId}/transactions/{transactionId}")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.view') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.verify') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize') "
            + "or hasAuthority('payroll.reopen')"
    )
    public ResponseEntity<PayrollBatchDetailResponse.TransactionDetail> transaction(
            @PathVariable long batchId,
            @PathVariable long transactionId
    ) {
        return ResponseEntity.ok(
                payrollBatchService.getTransactionDetail(batchId, transactionId)
        );
    }

    @GetMapping("/{batchId}/readiness")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.view') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.verify') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize') "
            + "or hasAuthority('payroll.reopen')"
    )
    public ResponseEntity<PayrollReadinessResponse> readiness(
            @PathVariable long batchId
    ) {
        return ResponseEntity.ok(payrollWorkflowService.readiness(batchId));
    }

    @PostMapping("/{batchId}/verify")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.verify')"
    )
    public ResponseEntity<PayrollBatchStatusResponse> verify(
            @PathVariable long batchId
    ) {
        return ResponseEntity.ok(payrollWorkflowService.verify(batchId));
    }

    @PostMapping("/{batchId}/submit")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.prepare')"
    )
    public ResponseEntity<PayrollBatchStatusResponse> submit(
            @PathVariable long batchId
    ) {
        return ResponseEntity.ok(payrollWorkflowService.submit(batchId));
    }

    @PostMapping("/{batchId}/approve")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.approve')"
    )
    public ResponseEntity<PayrollBatchStatusResponse> approve(
            @PathVariable long batchId
    ) {
        return ResponseEntity.ok(payrollWorkflowService.approve(batchId));
    }

    @PostMapping("/{batchId}/reject")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.approve')"
    )
    public ResponseEntity<PayrollBatchStatusResponse> reject(
            @PathVariable long batchId,
            @RequestBody(required = false) PayrollDecisionRequest request
    ) {
        return ResponseEntity.ok(payrollWorkflowService.reject(
                batchId, request == null ? null : request.reason()
        ));
    }

    @PostMapping("/{batchId}/finalize")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<PayrollBatchStatusResponse> finalizeBatch(
            @PathVariable long batchId,
            @RequestBody(required = false) PayrollFinalizeRequest request
    ) {
        if (request == null || request.version() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The reviewed batch version is required for finalization."
            );
        }
        return ResponseEntity.ok(
                payrollWorkflowService.finalizeBatch(
                        batchId,
                        Boolean.TRUE.equals(request.confirmed()),
                        request.version()
                )
        );
    }

    @GetMapping("/{batchId}/finalization-eligibility")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<PayrollFinalizationEligibilityResponse> finalizationEligibility(
            @PathVariable long batchId,
            @RequestParam long version
    ) {
        return ResponseEntity.ok(
                payrollWorkflowService.finalizationEligibility(batchId, version)
        );
    }

    @PostMapping("/{batchId}/reopen")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.reopen')"
    )
    public ResponseEntity<PayrollBatchStatusResponse> reopen(
            @PathVariable long batchId,
            @RequestBody(required = false) PayrollDecisionRequest request
    ) {
        return ResponseEntity.ok(payrollWorkflowService.reopen(
                batchId, request == null ? null : request.reason()
        ));
    }

    @PostMapping("/{batchId}/transactions/{transactionId}/correction")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.prepare')"
    )
    public ResponseEntity<PayrollBatchStatusResponse> correctTransaction(
            @PathVariable long batchId,
            @PathVariable long transactionId,
            @RequestBody PayrollCorrectionRequest request
    ) {
        return ResponseEntity.ok(payrollWorkflowService.correctTransaction(
                batchId, transactionId, request
        ));
    }

    @GetMapping("/{batchId}/approvals")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('payroll.view') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.verify') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize') "
            + "or hasAuthority('payroll.reopen')"
    )
    public ResponseEntity<List<PayrollApprovalResponse>> approvals(
            @PathVariable long batchId
    ) {
        return ResponseEntity.ok(payrollWorkflowService.approvals(batchId));
    }
}
