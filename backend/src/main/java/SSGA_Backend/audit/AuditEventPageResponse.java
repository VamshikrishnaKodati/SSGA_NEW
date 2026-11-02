package SSGA_Backend.audit;

import java.util.List;

public record AuditEventPageResponse(
        List<AuditEventResponse> events,
        int limit,
        int offset,
        boolean hasMore
) {}
