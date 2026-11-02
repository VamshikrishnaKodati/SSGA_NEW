package SSGA_Backend.payroll;

import java.util.List;

/**
 * Server-computed readiness for a payroll batch. The frontend uses this to
 * block invalid actions before calling the workflow, while the workflow
 * endpoints re-check the same conditions authoritatively.
 */
public record PayrollReadinessResponse(
        long batchId,
        String status,
        boolean editable,
        int totalTransactions,
        int verifiedTransactions,
        boolean readyToVerify,
        boolean readyToSubmit,
        boolean readyToFinalize,
        List<String> issues
) {
}
