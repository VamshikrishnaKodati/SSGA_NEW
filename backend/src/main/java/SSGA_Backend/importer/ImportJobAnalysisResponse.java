
package SSGA_Backend.importer;

import java.util.List;

public record ImportJobAnalysisResponse(
        long jobId,
        Long uploadedBy,
        String status,
        String detectedFormat,
        List<SheetAnalysis> sheets,
        String message
) {
    public record SheetAnalysis(
            int sheetIndex,
            String sheetName,
            int nonEmptyRows,
            int dataRowsEstimate,
            Integer headerRowNumber,
            int columnCount,
            String suggestedType,
            List<String> headers,
            String note
    ) {
    }
}
