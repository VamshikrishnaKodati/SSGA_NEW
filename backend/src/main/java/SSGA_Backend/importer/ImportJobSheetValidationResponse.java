
package SSGA_Backend.importer;

import java.util.List;

public record ImportJobSheetValidationResponse(
        long jobId,
        int sheetIndex,
        String sheetName,
        String status,
        int totalRows,
        int validRows,
        int errorRows,
        int warningRows,
        int tempIdRows,
        List<RowIssue> issues,
        String message
) {
    public record RowIssue(
            int sourceRowNumber,
            String severity,
            String fieldName,
            String errorCode,
            String message
    ) {
    }
}
