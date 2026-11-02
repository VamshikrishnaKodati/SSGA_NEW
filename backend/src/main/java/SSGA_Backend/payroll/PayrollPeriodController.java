package SSGA_Backend.payroll;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payroll/periods")
public class PayrollPeriodController {

    private final PayrollPeriodService payrollPeriodService;

    public PayrollPeriodController(PayrollPeriodService payrollPeriodService) {
        this.payrollPeriodService = payrollPeriodService;
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
    public ResponseEntity<List<PayrollPeriodResponse>> list() {
        return ResponseEntity.ok(payrollPeriodService.list());
    }

    @GetMapping("/{periodId}/scope-summary")
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
    public ResponseEntity<PayrollScopeSummaryResponse> scopeSummary(
            @PathVariable long periodId,
            @RequestParam long divisionId
    ) {
        return ResponseEntity.ok(
                payrollPeriodService.scopeSummary(periodId, divisionId)
        );
    }

    @PostMapping
    @PreAuthorize("hasAuthority('payroll.period.manage')")
    public ResponseEntity<PayrollPeriodResponse> create(
            @RequestBody PayrollPeriodCreateRequest request
    ) {
        return ResponseEntity.ok(payrollPeriodService.create(request));
    }

    @PostMapping("/{periodId}/lock")
    @PreAuthorize("hasAuthority('payroll.period.manage')")
    public ResponseEntity<PayrollPeriodResponse> lock(
            @PathVariable long periodId
    ) {
        return ResponseEntity.ok(payrollPeriodService.lock(periodId));
    }

    @PostMapping("/{periodId}/close")
    @PreAuthorize("hasAuthority('payroll.period.manage')")
    public ResponseEntity<PayrollPeriodResponse> close(
            @PathVariable long periodId
    ) {
        return ResponseEntity.ok(payrollPeriodService.close(periodId));
    }

    @PostMapping("/{periodId}/reopen")
    @PreAuthorize("hasAuthority('payroll.period.manage')")
    public ResponseEntity<PayrollPeriodResponse> reopen(
            @PathVariable long periodId
    ) {
        return ResponseEntity.ok(payrollPeriodService.reopen(periodId));
    }
}
