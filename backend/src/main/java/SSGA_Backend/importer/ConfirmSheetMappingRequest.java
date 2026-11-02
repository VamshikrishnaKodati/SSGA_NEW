
package SSGA_Backend.importer;

import java.util.Map;

public record ConfirmSheetMappingRequest(
        String confirmedType,
        Map<String, Object> columnMapping,
        Map<String, Object> options,
        String notes
) {
}
