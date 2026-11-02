package SSGA_Backend.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        long id,
        Instant occurredAt,
        Long userId,
        String username,
        String action,
        String entityType,
        String entityId,
        Long wingId,
        Long divisionId,
        String reason,
        UUID requestId
) {}
