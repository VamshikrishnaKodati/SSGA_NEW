package SSGA_Backend.controller;

public record DivisionResponse(
        long id,
        long wingId,
        String wingName,
        long organizationId,
        String name,
        String code,
        String status
) {
}