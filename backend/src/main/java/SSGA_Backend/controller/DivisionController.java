package SSGA_Backend.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import SSGA_Backend.service.DivisionService;

@RestController
@RequestMapping("/api/divisions")
public class DivisionController {

    private final DivisionService divisionService;

    public DivisionController(DivisionService divisionService) {
        this.divisionService = divisionService;
    }

    @GetMapping("/my-scope")
    @PreAuthorize(
            "hasAuthority('division.view') "
            + "or hasAuthority('payroll.view') "
            + "or hasAuthority('payroll.prepare') "
            + "or hasAuthority('payroll.verify') "
            + "or hasAuthority('payroll.submit') "
            + "or hasAuthority('payroll.approve') "
            + "or hasAuthority('payroll.finalize') "
            + "or hasAuthority('payroll.reopen')"
    )
    public List<DivisionResponse> myScope() {
        return divisionService.findMyScopeDivisions();
    }
}