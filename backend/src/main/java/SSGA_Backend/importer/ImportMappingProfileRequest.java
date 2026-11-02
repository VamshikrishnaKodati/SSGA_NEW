package SSGA_Backend.importer;

import java.util.Map;

public record ImportMappingProfileRequest(
        String name,
        String importType,
        String structureSignature,
        Integer schemaVersion,
        Map<String, Object> columnMapping,
        Map<String, Object> options,
        Boolean shared
) {
}
