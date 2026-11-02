package SSGA_Backend.report;

import java.util.List;

public record PayrollSummaryResponse(
        String periodLabel,
        String statusFilter,
        List<DivisionSummary> divisions,
        DivisionSummary totals
) {
}
