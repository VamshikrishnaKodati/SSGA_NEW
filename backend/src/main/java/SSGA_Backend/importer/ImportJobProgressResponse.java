package SSGA_Backend.importer;

import java.util.List;

public record ImportJobProgressResponse(
        long jobId,
        String status,
        String fileName,
        String sourceType,
        Long uploadedBy,
        String periodStart,
        String periodEnd,
        int totalRecords,
        int processedRecords,
        int importedRows,
        int pendingRows,
        int failedRows,
        int skippedRows,
        int percentage,
        boolean progressAvailable,
        String startedAt,
        long elapsedSeconds,
        Long estimatedSecondsRemaining,
        List<ImportJobProgressEventResponse> events
) {
}
