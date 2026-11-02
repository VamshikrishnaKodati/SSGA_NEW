package SSGA_Backend.payroll;

import java.math.BigDecimal;

public record PayrollCorrectionRequest(
        Long componentId,
        BigDecimal amount,
        String reason
) {
}
