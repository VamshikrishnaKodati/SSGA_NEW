package SSGA_Backend.reports;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class PayrollReportController {

    private final PayrollReportService reportService;

    public PayrollReportController(PayrollReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/division-summary")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('report.view')"
    )
    public ResponseEntity<List<DivisionSummaryRow>> divisionSummary(
            @RequestParam(required = false) Long periodId
    ) {
        return ResponseEntity.ok(reportService.divisionSummary(periodId));
    }

    @GetMapping(value = "/division-summary.csv", produces = "text/csv")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('export.download')"
    )
    public ResponseEntity<byte[]> divisionSummaryCsv(
            @RequestParam(required = false) Long periodId
    ) {
        List<DivisionSummaryRow> rows = reportService.divisionSummary(periodId);

        byte[] body = PayrollReportService.toCsv(rows)
                .getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"division-summary.csv\"")
                .contentType(new MediaType("text", "csv",
                        StandardCharsets.UTF_8))
                .body(body);
    }

    @GetMapping("/employees/{employeeId}/history")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('report.view')"
    )
    public ResponseEntity<EmployeeSalaryHistoryResponse> employeeHistory(
            @PathVariable long employeeId
    ) {
        return ResponseEntity.ok(reportService.employeeHistory(employeeId));
    }
}
