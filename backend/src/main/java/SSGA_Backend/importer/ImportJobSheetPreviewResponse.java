package SSGA_Backend.importer;

import java.util.List;
import java.util.Map;

public record ImportJobSheetPreviewResponse(
        long jobId,
        int sheetIndex,
        String sheetName,
        List<PreviewColumn> columns,
        List<PreviewRow> rows,
        int validRows,
        int errorRows,
        int warningRows,
        boolean validated,
        List<Issue> issues,
        String message
) {
    public record PreviewColumn(
            String field,
            String label,
            String sourceHeader,
            boolean masked
    ) {
    }

    public record PreviewRow(
            int sourceRowNumber,
            String severity,
            Map<String, String> values
    ) {
    }

    public record Issue(
            int sourceRowNumber,
            String severity,
            String fieldName,
            String errorCode,
            String message
    ) {
    }
}
