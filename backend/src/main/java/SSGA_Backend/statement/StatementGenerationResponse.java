package SSGA_Backend.statement;

import java.util.List;

public record StatementGenerationResponse(
        long batchId,
        int generated,
        int skipped,
        int superseded,
        List<SalaryStatementResponse> statements,
        String message
) {
}
