package SSGA_Backend.importer;

import java.util.List;

public record ImportJobSheetAutoMapResponse(
        long jobId,
        int sheetIndex,
        String sheetName,
        String importType,
        Integer headerRowNumber,
        String structureSignature,
        List<FieldSuggestion> fields,
        List<ComponentSuggestion> components,
        String message
) {
    public record FieldSuggestion(
            String field,
            String label,
            String sourceHeader,
            String confidence,
            String reason,
            boolean identifier
    ) {
    }

    public record ComponentSuggestion(
            String componentCode,
            String sourceHeader,
            String confidence,
            String reason
    ) {
    }
}
