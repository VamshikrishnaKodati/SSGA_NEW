package SSGA_Backend.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import SSGA_Backend.service.EmployeeService;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('employee.view')")
    public List<EmployeeResponse> list() {
        return employeeService.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('employee.view')")
    public ResponseEntity<EmployeeResponse> getById(
            @PathVariable long id
    ) {
        return employeeService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/salary-values")
    @PreAuthorize("hasAuthority('salary.view')")
    public List<EmployeeSalaryValuesResponse> salaryValues() {
        return employeeService.allSalaryValues();
    }

    @GetMapping("/{id}/salary")
    @PreAuthorize("hasAuthority('salary.view')")
    public EmployeeSalaryResponse salary(@PathVariable long id) {
        return employeeService.salaryStructure(id);
    }

    @PutMapping("/{id}/salary/{componentId}")
    @PreAuthorize("hasAnyAuthority('salary.manage', 'salary.prepare')")
    public EmployeeSalaryResponse setSalaryComponent(
            @PathVariable long id,
            @PathVariable long componentId,
            @RequestBody UpdateEmployeeSalaryRequest request
    ) {
        return employeeService.setSalaryComponent(
                id,
                componentId,
                request.amount(),
                request.effectiveFrom()
        );
    }

    @PostMapping
    @PreAuthorize("hasAuthority('employee.create')")
    public ResponseEntity<EmployeeResponse> create(
            @RequestBody CreateEmployeeRequest request
    ) {
        EmployeeResponse employee = employeeService.create(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(employee);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('employee.update')")
    public EmployeeResponse update(
            @PathVariable long id,
            @RequestBody UpdateEmployeeRequest request
    ) {
        return employeeService.update(id, request);
    }
}
