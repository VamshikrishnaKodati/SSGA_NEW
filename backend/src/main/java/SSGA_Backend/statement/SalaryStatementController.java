package SSGA_Backend.statement;

import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SalaryStatementController {

    private final SalaryStatementService salaryStatementService;

    public SalaryStatementController(SalaryStatementService salaryStatementService) {
        this.salaryStatementService = salaryStatementService;
    }

    @PostMapping("/api/payroll/batches/{batchId}/statements")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('statement.generate') "
            + "or hasAuthority('payroll.finalize')"
    )
    public ResponseEntity<StatementGenerationResponse> generate(
            @PathVariable long batchId
    ) {
        return ResponseEntity.ok(
                salaryStatementService.generateForBatch(batchId)
        );
    }

    @PostMapping("/api/statements/{statementId}/reissue")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('statement.reissue')"
    )
    public ResponseEntity<SalaryStatementResponse> reissue(
            @PathVariable long statementId
    ) {
        return ResponseEntity.ok(salaryStatementService.reissue(statementId));
    }

    @GetMapping("/api/statements")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('statement.view') "
            + "or hasAuthority('statement.download')"
    )
    public ResponseEntity<List<SalaryStatementResponse>> list(
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) Long divisionId
    ) {
        return ResponseEntity.ok(
                salaryStatementService.list(periodId, divisionId)
        );
    }

    @GetMapping("/api/statements/{statementId}")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('statement.view') "
            + "or hasAuthority('statement.download')"
    )
    public ResponseEntity<SalaryStatementDetail> detail(
            @PathVariable long statementId
    ) {
        return ResponseEntity.ok(salaryStatementService.get(statementId));
    }

    @GetMapping("/api/statements/{statementId}/download")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('statement.download')"
    )
    public ResponseEntity<byte[]> download(@PathVariable long statementId) {
        SalaryStatementService.StatementFile file =
                salaryStatementService.download(statementId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(file.fileName()).build()
        );
        headers.setContentLength(file.content().length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(file.content());
    }
}
