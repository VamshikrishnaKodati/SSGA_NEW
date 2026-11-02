package SSGA_Backend.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;
import SSGA_Backend.support.FakeJdbcTemplate;

class PayrollPeriodLockTest {

    private FakeJdbcTemplate jdbc;
    private PayrollPeriodService service;

    @BeforeEach
    void setUp() {
        jdbc = new FakeJdbcTemplate();
        jdbc.answer("app.write_audit_log", 1L);
        jdbc.row(
                "SELECT app.can_manage_payroll_period(organization_id)",
                Map.of("1", true));
        service = new PayrollPeriodService(jdbc, mock(RlsContext.class));
    }

    @Test
    void locksAnOpenPeriod() {
        jdbc.row("FROM public.payroll_periods", periodRow("OPEN"));

        service.lock(4L);

        assertTrue(jdbc.executedSqlContaining("SET status = ?"));
        assertTrue(jdbc.calledWith("LOCKED"),
                "the period must be moved to LOCKED");
    }

    @Test
    void refusesToLockAPeriodThatIsAlreadyLocked() {
        jdbc.row("FROM public.payroll_periods", periodRow("LOCKED"));

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.lock(4L)
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
    }

    @Test
    void refusesToCloseAPeriodWithUnfinalizedBatches() {
        jdbc.answer("SELECT count(*) FROM public.payroll_batches", 2);

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.close(4L)
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getMessage().contains("not finalized"));
    }

    @Test
    void setsRlsContextBeforeCheckingBatchesWhenClosing() {
        jdbc.answer("set_config('app.user_id'", "42");
        jdbc.answer("SELECT count(*) FROM public.payroll_batches", 1);
        service = new PayrollPeriodService(jdbc, new RlsContext(jdbc));

        var principal = new SsgaPrincipal(
                42L,
                "org_admin",
                "unused",
                true,
                true,
                List.of(
                        new SimpleGrantedAuthority("ROLE_ORG_ADMIN"),
                        new SimpleGrantedAuthority("payroll.period.manage")
                ),
                7L
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, "unused", principal.getAuthorities()
                )
        );

        try {
            ResponseStatusException thrown = assertThrows(
                    ResponseStatusException.class,
                    () -> service.close(4L)
            );

            assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
            assertTrue(indexOfSql("set_config('app.user_id'") <
                    indexOfSql("SELECT count(*) FROM public.payroll_batches"));
            assertTrue(!jdbc.executedSqlContaining("SET status = ?"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private int indexOfSql(String fragment) {
        for (int i = 0; i < jdbc.executed().size(); i++) {
            if (jdbc.executed().get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void reopensAClosedPeriodThroughTheLockedState() {
        jdbc.row("FROM public.payroll_periods", periodRow("CLOSED"));

        service.reopen(4L);

        assertTrue(jdbc.executedSqlContaining("SET status = ?"));
        assertTrue(jdbc.calledWith("PAYROLL_PERIOD_REOPEN"),
                "the reopen must be audited");
        assertTrue(jdbc.calledWith("LOCKED"),
                "a closed period first goes back to LOCKED");
    }

    private static Map<String, Object> periodRow(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 4L);
        row.put("organization_id", 1L);
        row.put("organization_name", "SSGA");
        row.put("period_year", 2026);
        row.put("period_month", 12);
        row.put("period_start", java.sql.Date.valueOf("2026-12-01"));
        row.put("period_end", java.sql.Date.valueOf("2026-12-31"));
        row.put("status", status);
        return row;
    }
}
