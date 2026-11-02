package SSGA_Backend.importer;

import java.util.Map;

public record ImportMappingProfileResponse(
        long id,
        String name,
        String importType,
        String structureSignature,
        int schemaVersion,
        Map<String, Object> columnMapping,
        Map<String, Object> options,
        boolean shared,
        boolean active,
        String createdAt,
        String message
) {
}
