
package SSGA_Backend.importer;

import java.util.Map;

public record ImportJobSheetMappingResponse(
        long jobId,
        int sheetIndex,
        String sheetName,
        String confirmedType,
        String status,
        Map<String, Object> columnMapping,
        Map<String, Object> options,
        String message
) {
}

