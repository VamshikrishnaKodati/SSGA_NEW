package SSGA_Backend.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.support.FakeJdbcTemplate;

class PayrollBatchServiceTest {

    @Test
    void batchListReturnsSubmissionTimestampForApprovalAging() {
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-04-02T09:30:00Z");
        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("period_id", 4L);
        row.put("period_year", 2026);
        row.put("period_month", 4);
        row.put("division_id", 9L);
        row.put("division_name", "Scoped Division");
        row.put("wing_name", "Operations");
        row.put("run_type", "MONTHLY");
        row.put("run_no", 1);
        row.put("status", "SUBMITTED");
        row.put("maker_id", 2L);
        row.put("employee_count", 1);
        row.put("gross_total", new BigDecimal("1000"));
        row.put("deduction_total", new BigDecimal("100"));
        row.put("net_total", new BigDecimal("900"));
        row.put("created_at", submittedAt.minusDays(1));
        row.put("submitted_at", submittedAt);
        row.put("version", 1L);
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate()
                .row("FROM public.payroll_batches b", row);
        PayrollBatchService service = new PayrollBatchService(
                jdbc, mock(RlsContext.class)
        );

        var batches = service.listBatches(4L);

        assertEquals(1, batches.size());
        assertEquals(submittedAt, batches.get(0).submittedAt());
        assertTrue(jdbc.executedSqlContaining("b.created_at, b.submitted_at"));
    }

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

    // -----------------------------------------------------------------------
    // resolveAmounts: explicit values win, percentage rows derive, anything
    // underivable is refused rather than defaulted to zero.
    // -----------------------------------------------------------------------

    private static final BigDecimal BASIC = new BigDecimal("50000");

    // -----------------------------------------------------------------------
    // Supplementary runs: a held row is moved, never silently dropped
    // -----------------------------------------------------------------------

    private static PayrollBatchService supplementaryService(FakeJdbcTemplate jdbc) {
        jdbc.answer("app.current_user_id", 9L);
        jdbc.answer("app.write_audit_log", 1L);
        return new PayrollBatchService(jdbc, mock(RlsContext.class));
    }

    @Test
    void onlyAHeldRowCanBeMovedToASupplementaryRun() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate();
        Map<String, Object> row = new HashMap<>();
        row.put("id", 3L);
        row.put("status", "VERIFIED");
        row.put("payroll_batch_id", 6L);
        row.put("period_id", 4L);
        row.put("division_id_snapshot", 2L);
        jdbc.row("FROM public.salary_transactions st", row);

        PayrollBatchService service = supplementaryService(jdbc);

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.transferToSupplementary(6L, 3L)
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("on hold"),
                "a verified row must not be moved out of a batch, otherwise a "
                        + "finalized total would change under the checker");
        assertTrue(jdbc.executedSqlContaining("INSERT INTO public.salary_transactions") == false,
                "no row may be copied before the hold check passes");
    }

    @Test
    void movingAHeldRowNeedsAnOpenSupplementaryRun() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate();
        Map<String, Object> row = new HashMap<>();
        row.put("id", 3L);
        row.put("status", "ON_HOLD");
        row.put("payroll_batch_id", 6L);
        row.put("period_id", 4L);
        row.put("division_id_snapshot", 2L);
        jdbc.row("FROM public.salary_transactions st", row);

        PayrollBatchService service = supplementaryService(jdbc);

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.transferToSupplementary(6L, 3L)
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("supplementary"));
    }

    @Test
    void supplementaryRunTypesAreAcceptedByTheGenerator() {
        assertEquals("SUPPLEMENTARY",
                PayrollBatchService.normalizeRunType("supplementary"));
        assertEquals("MONTHLY", PayrollBatchService.normalizeRunType(null));
    }
    private static PayrollBatchService.Component component(
            long id, String code, String category,
            String calculationType, String rate, String baseCode
    ) {
        return new PayrollBatchService.Component(
                id, code, code, category, calculationType,
                rate == null ? null : new BigDecimal(rate), baseCode
        );
    }

    @Test
    void percentageComponentsAreDerivedFromBasicPayAndDa() {
        var components = java.util.List.of(
                component(1, "BASIC_PAY", "EARNING", "FIXED", null, null),
                component(2, "DA", "EARNING", "PERCENTAGE", "0.3367", "BASIC_PAY"),
                component(3, "HRA", "EARNING", "PERCENTAGE", "0.2400", "BASIC_PAY"),
                component(4, "EPF_12_PCT", "DEDUCTION", "PERCENTAGE", "0.1200", "BASIC_PLUS_DA")
        );

        Map<Long, BigDecimal> amounts = PayrollBatchService.resolveAmounts(
                components, BASIC, Map.of(), "HRMS-1"
        );

        // 50000 * 0.3367 = 16835 exactly
        assertEquals(new BigDecimal("16835"), amounts.get(2L));
        // 50000 * 0.24 = 12000 exactly
        assertEquals(new BigDecimal("12000"), amounts.get(3L));
        // (50000 + 16835) * 0.12 = 8020.2 -> half-up to 8020
        assertEquals(new BigDecimal("8020"), amounts.get(4L));
        // basic pay is a FIXED earning whose value lives on the employee; it must
        // reach the line items, or gross excludes it while DA/HRA include it
        assertEquals(BASIC, amounts.get(1L));
    }

    @Test
    void basicPayIsNotUsedWhenAnExplicitAmountExists() {
        var components = java.util.List.of(
                component(1, "BASIC_PAY", "EARNING", "FIXED", null, null),
                component(2, "DA", "EARNING", "PERCENTAGE", "0.3367", "BASIC_PAY")
        );

        Map<Long, BigDecimal> amounts = PayrollBatchService.resolveAmounts(
                components, BASIC, Map.of(1L, new BigDecimal("48000")), "HRMS-1"
        );

        assertEquals(new BigDecimal("48000"), amounts.get(1L));
        // DA still derives from the employee's basic pay, not the override
        assertEquals(new BigDecimal("16835"), amounts.get(2L));
    }

    @Test
    void explicitAmountOverridesTheFormula() {
        var components = java.util.List.of(
                component(1, "BASIC_PAY", "EARNING", "FIXED", null, null),
                component(2, "DA", "EARNING", "PERCENTAGE", "0.3367", "BASIC_PAY")
        );

        Map<Long, BigDecimal> amounts = PayrollBatchService.resolveAmounts(
                components, BASIC, Map.of(2L, new BigDecimal("17500")), "HRMS-1"
        );

        assertEquals(new BigDecimal("17500"), amounts.get(2L));
    }

    @Test
    void slabComponentWithoutAnImportedAmountIsRefused() {
        var components = java.util.List.of(
                component(1, "BASIC_PAY", "EARNING", "FIXED", null, null),
                component(2, "CCA", "EARNING", "SLAB", null, null)
        );

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> PayrollBatchService.resolveAmounts(
                        components, BASIC, Map.of(), "HRMS-1"
                )
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("CCA"));
    }

    @Test
    void percentageComponentWithoutARateIsRefused() {
        var components = java.util.List.of(
                component(1, "BASIC_PAY", "EARNING", "FIXED", null, null),
                component(2, "INCOME_TAX", "DEDUCTION", "PERCENTAGE", null, "BASIC_PAY")
        );

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> PayrollBatchService.resolveAmounts(
                        components, BASIC, Map.of(), "HRMS-1"
                )
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("INCOME_TAX"));
    }

    @Test
    void percentageComponentWithNoBasicPayIsRefused() {
        var components = java.util.List.of(
                component(2, "DA", "EARNING", "PERCENTAGE", "0.3367", "BASIC_PAY")
        );

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> PayrollBatchService.resolveAmounts(
                        components, null, Map.of(), "HRMS-1"
                )
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("basic pay"));
    }
}
