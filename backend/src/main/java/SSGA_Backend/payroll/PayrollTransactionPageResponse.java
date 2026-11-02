package SSGA_Backend.payroll;

import java.util.List;

/**
 * One server-side page of salary transactions for a payroll batch.
 */
public record PayrollTransactionPageResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<PayrollBatchDetailResponse.TransactionDetail> items
) {
}
