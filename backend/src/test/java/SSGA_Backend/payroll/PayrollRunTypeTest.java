package SSGA_Backend.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PayrollRunTypeTest {

    @Test
    void blankRunTypeDefaultsToMonthly() {
        assertEquals("MONTHLY", PayrollBatchService.normalizeRunType(null));
        assertEquals("MONTHLY", PayrollBatchService.normalizeRunType("   "));
    }

    @Test
    void monthlyIsAcceptedCaseInsensitively() {
        assertEquals("MONTHLY", PayrollBatchService.normalizeRunType("monthly"));
        assertEquals("MONTHLY", PayrollBatchService.normalizeRunType(" Monthly "));
    }

    @Test
    void unsupportedRunTypeIsRejectedWithClientMessage() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PayrollBatchService.normalizeRunType("ARREARS")
        );

        assertTrue(ex.getMessage().contains("Unsupported run type 'ARREARS'"));
        assertTrue(ex.getMessage().contains("MONTHLY"));
    }
}
