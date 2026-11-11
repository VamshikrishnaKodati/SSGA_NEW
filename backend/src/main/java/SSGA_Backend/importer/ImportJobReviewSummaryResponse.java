package SSGA_Backend.importer;

import java.math.BigDecimal;
import java.util.List;

public record ImportJobReviewSummaryResponse(
        long jobId,
        String status,
        String importType,
        String periodStart,
        String periodEnd,
        Totals current,
        Totals previous,
        String previousPeriodLabel,
        String previousSource,
        List<Warning> warnings,
        boolean canImportDraft,
        boolean canSubmit
) {

    public record Totals(
            BigDecimal earnings,
            BigDecimal deductions,
            BigDecimal net,
            BigDecimal basicPay
    ) {
    }

    public record Warning(String code, String message, String severity) {
    }
}
