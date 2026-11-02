package SSGA_Backend.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import SSGA_Backend.service.OrganizationMasterService;

/**
 * Organization Master API: Organization -> Parent Group -> Payroll Unit.
 *
 * Reads are available to any organizational-scope reader (organization.view,
 * wing.view or division.view, matching the navigation gate). All writes require
 * organization.manage, so ordinary Wing / Division administrators cannot change
 * organizational master data unless the permission is explicitly granted.
 */
@RestController
@RequestMapping("/api/organization")
public class OrganizationMasterController {

    private static final String READ =
            "hasAnyAuthority('organization.view','wing.view','division.view')";

    private final OrganizationMasterService organizationMasterService;

    public OrganizationMasterController(
            OrganizationMasterService organizationMasterService
    ) {
        this.organizationMasterService = organizationMasterService;
    }

    // --------------------------------------------------------- parent groups --

    @GetMapping("/parent-groups")
    @PreAuthorize(READ)
    public List<ParentGroupResponse> parentGroups() {
        return organizationMasterService.listParentGroups();
    }

    @PostMapping("/parent-groups")
    @PreAuthorize("hasAuthority('organization.manage')")
    public ParentGroupResponse createParentGroup(
            @RequestBody ParentGroupRequest request
    ) {
        return organizationMasterService.createParentGroup(request);
    }

    @PatchMapping("/parent-groups/{id}")
    @PreAuthorize("hasAuthority('organization.manage')")
    public ParentGroupResponse updateParentGroup(
            @PathVariable long id,
            @RequestBody ParentGroupRequest request
    ) {
        return organizationMasterService.updateParentGroup(id, request);
    }

    // -------------------------------------------------------- payroll units --

    @GetMapping("/payroll-units")
    @PreAuthorize(READ)
    public List<PayrollUnitResponse> payrollUnits(
            @RequestParam(required = false) Long parentGroupId,
            @RequestParam(required = false) String unitType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String verificationStatus,
            @RequestParam(name = "q", required = false) String search
    ) {
        return organizationMasterService.listPayrollUnits(
                parentGroupId,
                unitType,
                status,
                verificationStatus,
                search
        );
    }

    @GetMapping("/payroll-units/{id}")
    @PreAuthorize(READ)
    public PayrollUnitDetailResponse payrollUnit(@PathVariable long id) {
        return organizationMasterService.getUnit(id);
    }

    @PostMapping("/payroll-units")
    @PreAuthorize("hasAuthority('organization.manage')")
    public PayrollUnitDetailResponse createPayrollUnit(
            @RequestBody PayrollUnitRequest request
    ) {
        return organizationMasterService.createUnit(request);
    }

    @PatchMapping("/payroll-units/{id}")
    @PreAuthorize("hasAuthority('organization.manage')")
    public PayrollUnitDetailResponse updatePayrollUnit(
            @PathVariable long id,
            @RequestBody PayrollUnitRequest request
    ) {
        return organizationMasterService.updateUnit(id, request);
    }

    @PostMapping("/payroll-units/{id}/status")
    @PreAuthorize("hasAuthority('organization.manage')")
    public PayrollUnitDetailResponse changePayrollUnitStatus(
            @PathVariable long id,
            @RequestBody PayrollUnitStatusRequest request
    ) {
        return organizationMasterService.changeUnitStatus(id, request);
    }
}
