package SSGA_Backend.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.sql.Date;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.support.FakeJdbcTemplate;

class PayrollBatchServiceTest {

    @Test
    void generationRejectsDivisionOutsidePeriodOrganization() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate()
                .row("FROM public.payroll_periods", Map.of(
                        "id", 4L,
                        "organization_id", 11L,
                        "period_year", 2026,
                        "period_month", 4,
                        "period_start", Date.valueOf("2026-04-01"),
                        "status", "OPEN"
                ))
                .row("FROM public.divisions d", Map.of(
                        "id", 9L,
                        "wing_id", 3L,
                        "name", "Scoped Division",
                        "organization_id", 12L
                ));
        PayrollBatchService service = new PayrollBatchService(
                jdbc, mock(RlsContext.class)
        );

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.generate(new PayrollGenerateRequest(
                        4L, 9L, "MONTHLY"
                ))
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains(
                "does not belong to the payroll period's organization"
        ));
    }

    @Test
    void transactionPageUsesBoundedServerSideSearchAndPagination() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate()
                .answer("SELECT count(*) FROM public.salary_transactions", 1L)
                .row("FROM public.salary_transactions st", Map.of(
                        "id", 12L,
                        "employee_code_snapshot", "EMP-12",
                        "hrms_id_snapshot", "HRMS-12",
                        "name_snapshot", "Vamshi Employee",
                        "gross_salary", new java.math.BigDecimal("1000"),
                        "total_deductions", new java.math.BigDecimal("100"),
                        "net_salary", new java.math.BigDecimal("900"),
                        "status", "VERIFIED"
                ));
        PayrollBatchService service = new PayrollBatchService(
                jdbc, mock(RlsContext.class)
        );

        PayrollTransactionPageResponse response = service.getTransactionPage(
                7L, 2, 25, "Vamshi", "HRMS-12"
        );

        assertEquals(2, response.page());
        assertEquals(1, response.totalElements());
        assertEquals(1, response.items().size());
        assertTrue(jdbc.calledWith("%Vamshi%"));
        assertTrue(jdbc.calledWith("%HRMS-12%"));
        assertTrue(jdbc.executedSqlContaining("LIMIT ? OFFSET ?"));
    }

    @Test
    void transactionPageRejectsInvalidPageSize() {
        PayrollBatchService service = new PayrollBatchService(
                new FakeJdbcTemplate(), mock(RlsContext.class)
        );

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.getTransactionPage(7L, 0, 101, null, null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }
}
