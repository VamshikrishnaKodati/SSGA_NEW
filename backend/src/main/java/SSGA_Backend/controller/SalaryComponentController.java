package SSGA_Backend.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import SSGA_Backend.service.SalaryComponentService;

@RestController
@RequestMapping("/api/salary-components")
public class SalaryComponentController {

    private final SalaryComponentService salaryComponentService;

    public SalaryComponentController(
            SalaryComponentService salaryComponentService
    ) {
        this.salaryComponentService = salaryComponentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('salary.view')")
    public List<SalaryComponentResponse> list() {
        return salaryComponentService.findAll();
    }
}
