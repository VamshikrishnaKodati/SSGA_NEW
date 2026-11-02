package SSGA_Backend.importer;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/import/mapping-profiles")
public class ImportMappingProfileController {

    private final ImportMappingProfileService service;

    public ImportMappingProfileController(ImportMappingProfileService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<List<ImportMappingProfileResponse>> list(
            @RequestParam(required = false) String importType
    ) {
        return ResponseEntity.ok(service.list(importType));
    }

    @PostMapping
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<ImportMappingProfileResponse> create(
            @RequestBody ImportMappingProfileRequest request
    ) {
        return ResponseEntity.ok(service.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<ImportMappingProfileResponse> update(
            @PathVariable long id,
            @RequestBody ImportMappingProfileRequest request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(
            "hasRole('SYSTEM_ADMIN') "
            + "or hasAuthority('salary.manage') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('employee.create')"
    )
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
