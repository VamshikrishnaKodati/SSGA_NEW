package SSGA_Backend.importer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/import")
public class SalaryImportController {

    private final SalaryExcelParser salaryExcelParser;

    public SalaryImportController(
            SalaryExcelParser salaryExcelParser
    ) {
        this.salaryExcelParser = salaryExcelParser;
    }

    @PostMapping(
            value = "/salary-values/preview",
            consumes = "multipart/form-data"
    )
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<SalaryImportPreview> previewSalaryExcel(
            @RequestParam("file") MultipartFile file
    ) throws Exception {

        SalaryImportPreview preview =
                salaryExcelParser.parse(file);

        return ResponseEntity.ok(preview);
    }
}
