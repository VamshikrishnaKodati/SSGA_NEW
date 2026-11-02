package SSGA_Backend.importer;

import java.math.BigDecimal;

public record ImportJobWorkflowResponse(
        long jobId,
        Long uploadedBy,
        String status,
        Long submittedBy,
        Long approvedBy,
        Long finalizedBy,
        String rejectionReason,
        Integer importedRows,
        Integer skippedRows,
        Integer errorRows,
        BigDecimal importedAmountTotal,
        BigDecimal importedEarningsTotal,
        BigDecimal importedDeductionsTotal,
        String message
) {
}
