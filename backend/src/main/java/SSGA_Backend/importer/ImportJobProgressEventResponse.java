package SSGA_Backend.importer;

public record ImportJobProgressEventResponse(
        long id,
        String timestamp,
        String status,
        String level,
        String message,
        int processedRecords,
        int importedRows,
        int skippedRows,
        int failedRows
) {
}
