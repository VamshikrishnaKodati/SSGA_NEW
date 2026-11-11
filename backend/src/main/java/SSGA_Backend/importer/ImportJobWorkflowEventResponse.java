package SSGA_Backend.importer;

import java.time.OffsetDateTime;

public record ImportJobWorkflowEventResponse(
        long id,
        String action,
        String previousStatus,
        String newStatus,
        Long actorUserId,
        String actorName,
        String details,
        OffsetDateTime createdAt
) {
}
