package SSGA_Backend.report;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final MediaType CSV =
            MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/payroll-summary")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('report.view')"
    )
    public ResponseEntity<PayrollSummaryResponse> payrollSummary(
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(reportService.summary(periodId, status));
    }

    @GetMapping(value = "/payroll-summary.csv", produces = "text/csv")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('export.download')"
    )
    public ResponseEntity<String> payrollSummaryCsv(
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) String status
    ) {
        PayrollSummaryResponse summary = reportService.summary(periodId, status);

        return ResponseEntity.ok()
                .contentType(CSV)
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"payroll-summary-"
                                + summary.periodLabel() + ".csv\""
                )
                .body(reportService.toCsv(summary));
    }
}
