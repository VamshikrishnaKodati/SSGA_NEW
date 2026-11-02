package SSGA_Backend.payroll;

import java.time.OffsetDateTime;

public record PayrollApprovalResponse(
        long id,
        String entityType,
        long entityId,
        String fromStatus,
        String toStatus,
        String action,
        long actedBy,
        String remarks,
        OffsetDateTime actedAt,
        String actedByName
) {
}
