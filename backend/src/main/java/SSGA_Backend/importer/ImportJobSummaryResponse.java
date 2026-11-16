package SSGA_Backend.importer;

public record ImportJobSummaryResponse(
        long jobId,
        String status,
        String sourceType,
        String fileName,
        int totalRows,
        int validRows,
        int errorRows,
        int warningRows,
        String uploadedAt,
        Long uploadedBy,
        Long submittedBy,
        String submittedAt,
        String approvers
) {
}
