package SSGA_Backend.controller;

public record ParentGroupResponse(
        long id,
        long organizationId,
        String organizationName,
        String code,
        String name,
        String status,
        long unitCount
) {
}
