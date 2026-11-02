package SSGA_Backend.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.sql.Date;
import java.time.YearMonth;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.support.FakeJdbcTemplate;

class PayrollPeriodServiceTest {

    @Test
    void resolvesMonthWindowIncludingLeapFebruary() {
        YearMonth feb = PayrollPeriodService.periodMonth(2028, 2);

        assertEquals(1, feb.atDay(1).getDayOfMonth());
        assertEquals(29, feb.atEndOfMonth().getDayOfMonth(),
                "2028 is a leap year");
        assertEquals(28, PayrollPeriodService.periodMonth(2027, 2)
                .atEndOfMonth().getDayOfMonth());
    }

    @Test
    void rejectsOutOfRangeYearAndMonth() {
        assertThrows(IllegalArgumentException.class,
                () -> PayrollPeriodService.periodMonth(1999, 1));
        assertThrows(IllegalArgumentException.class,
                () -> PayrollPeriodService.periodMonth(2101, 1));
        assertThrows(IllegalArgumentException.class,
                () -> PayrollPeriodService.periodMonth(2026, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PayrollPeriodService.periodMonth(2026, 13));
    }

    @Test
    void scopeSummaryUsesAccessibleDivisionAndReturnsActualCountsAndLatestBatch() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate()
                .row("FROM public.payroll_periods p", Map.of(
                        "period_start", Date.valueOf("2026-04-01")
                ))
                .row("FROM public.employees e", Map.of(
                        "total_employees", 12L,
                        "active_eligible_employees", 8L
                ))
                .row("FROM public.payroll_batches", Map.of(
                        "id", 37L,
                        "status", "SUBMITTED",
                        "run_type", "MONTHLY"
                ));
        PayrollPeriodService service = new PayrollPeriodService(
                jdbc, mock(RlsContext.class)
        );

        PayrollScopeSummaryResponse summary = service.scopeSummary(4L, 9L);

        assertEquals(12L, summary.totalEmployees());
        assertEquals(8L, summary.activeEligibleEmployees());
        assertEquals(4L, summary.inactiveOrExcludedEmployees());
        assertEquals(37L, summary.lastBatchId());
        assertEquals("SUBMITTED", summary.lastBatchStatus());
        assertTrue(jdbc.executedSqlContaining("app.can_access_division"));
        assertTrue(jdbc.executedSqlContaining(
                "p.organization_id = w.organization_id"
        ));
    }

    @Test
    void scopeSummaryDoesNotReturnDataForAnInaccessibleScope() {
        PayrollPeriodService service = new PayrollPeriodService(
                new FakeJdbcTemplate(), mock(RlsContext.class)
        );

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.scopeSummary(4L, 9L)
        );

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }
}
