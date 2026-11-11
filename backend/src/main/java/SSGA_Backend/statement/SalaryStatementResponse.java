package SSGA_Backend.statement;

public record SalaryStatementResponse(
        long id,
        String statementNo,
        long transactionId,
        long employeeId,
        String hrmsId,
        String employeeName,
        long periodId,
        String periodLabel,
        long wingId,
        long divisionId,
        int version,
        String status,
        String templateVersion,
        String payloadSha256,
        String fileSha256,
        long fileSizeBytes,
        long generatedBy,
        String generatedAt
) {
}
