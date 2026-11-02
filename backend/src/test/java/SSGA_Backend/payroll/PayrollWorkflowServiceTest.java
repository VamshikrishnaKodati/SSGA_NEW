package SSGA_Backend.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.support.FakeJdbcTemplate;

class PayrollWorkflowServiceTest {

    private static final long MAKER_ID = 7L;
    private static final long ACTOR_ID = 42L;

    private FakeJdbcTemplate jdbc;
    private PayrollWorkflowService service;

    @BeforeEach
    void setUp() {
        jdbc = new FakeJdbcTemplate();
        jdbc.answer("app.current_user_id", ACTOR_ID);
        jdbc.answer("app.write_audit_log", 1L);
        jdbc.answer("status = 'VERIFIED'", 1);
        jdbc.answer("SELECT count(*) FROM public.salary_transactions", 1);
        jdbc.row("FROM public.payroll_batches", batchRow("SUBMITTED"));

        service = new PayrollWorkflowService(jdbc, mock(RlsContext.class));
    }

    @Test
    void approveUsesTheAuthoritativeReviewRoutine() {
        jdbc.answer("app.review_payroll_batch", 5L);

        PayrollBatchStatusResponse response = service.approve(11L);

        assertEquals("APPROVED", response.status());
        assertTrue(jdbc.executedSqlContaining("app.review_payroll_batch"),
                "approval must go through app.review_payroll_batch so the "
                        + "approval history and the scoped audit entry are "
                        + "written");
    }

    @Test
    void underReviewBatchCanUseTheAuthoritativeReviewRoutine() {
        jdbc.row("FROM public.payroll_batches", batchRow("UNDER_REVIEW"));
        jdbc.answer("app.review_payroll_batch", 5L);

        PayrollBatchStatusResponse response = service.approve(11L);

        assertEquals("APPROVED", response.status());
        assertTrue(jdbc.executedSqlContaining("app.review_payroll_batch"));
    }

    @Test
    void approveReportsAPermissionFailureAsForbidden() {
        jdbc.failOn("app.review_payroll_batch", new DataAccessResourceFailureException(
                "User 42 does not have permission payroll.approve"));

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.approve(11L)
        );

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
    }

    @Test
    void verifyIsRejectedAfterTheBatchHasBeenSubmitted() {
        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.verify(11L)
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("DRAFT, REJECTED or REOPENED"));
    }

    @Test
    void finalizeRequiresExplicitConfirmation() {
        jdbc.row("FROM public.payroll_batches", batchRow("APPROVED"));

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.finalizeBatch(11L, false, 1L)
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    @Test
    void finalizeFailsWhenTheBatchIsNoLongerApproved() {
        jdbc.updateRows(0);

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.finalizeBatch(11L, true, 1L)
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
    }

    @Test
    void finalizeRejectsAStaleBatchVersionBeforeUpdating() {
        jdbc.row("FROM public.payroll_batches", batchRow("APPROVED"));

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.finalizeBatch(11L, true, 0L)
        );

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("changed after it was reviewed"));
        assertTrue(!jdbc.executedSqlContaining("SET status = 'FINALIZED'"));
    }

    @Test
    void finalizationEligibilityRequiresApprovedCurrentVersionAndDifferentActor() {
        Map<String, Object> row = batchRow("APPROVED");
        row.put("checker_id", ACTOR_ID);
        jdbc.row("FROM public.payroll_batches", row);

        PayrollFinalizationEligibilityResponse eligibility =
                service.finalizationEligibility(11L, 1L);

        assertEquals(false, eligibility.eligible());
        assertTrue(eligibility.reason().contains("different authorized user"));
    }

    @Test
    void finalizationEligibilityRejectsNonApprovedAndStaleBatches() {
        jdbc.row("FROM public.payroll_batches", batchRow("UNDER_REVIEW"));

        PayrollFinalizationEligibilityResponse notApproved =
                service.finalizationEligibility(11L, 1L);
        assertEquals(false, notApproved.eligible());
        assertEquals("UNDER_REVIEW", notApproved.status());

        jdbc.row("FROM public.payroll_batches", batchRow("APPROVED"));
        PayrollFinalizationEligibilityResponse stale =
                service.finalizationEligibility(11L, 0L);
        assertEquals(false, stale.eligible());
        assertTrue(stale.reason().contains("changed after it was loaded"));
    }

    @Test
    void finalizeRejectsMakerAndApprover() {
        Map<String, Object> makerRow = batchRow("APPROVED");
        makerRow.put("maker_id", ACTOR_ID);
        jdbc.row("FROM public.payroll_batches", makerRow);
        ResponseStatusException maker = assertThrows(
                ResponseStatusException.class,
                () -> service.finalizeBatch(11L, true, 1L)
        );
        assertTrue(maker.getReason().contains("maker cannot finalize"));

        Map<String, Object> checkerRow = batchRow("APPROVED");
        checkerRow.put("checker_id", ACTOR_ID);
        jdbc.row("FROM public.payroll_batches", checkerRow);
        ResponseStatusException checker = assertThrows(
                ResponseStatusException.class,
                () -> service.finalizeBatch(11L, true, 1L)
        );
        assertTrue(checker.getReason().contains("approver cannot finalize"));
    }

    @Test
    void finalizeUpdateIsConditionalOnTheReviewedVersion() {
        jdbc.row("FROM public.payroll_batches", batchRow("APPROVED"));
        jdbc.row("FROM public.salary_transactions st", Map.ofEntries(
                Map.entry("id", 1L),
                Map.entry("employee_code_snapshot", "E-1"),
                Map.entry("name_snapshot", "Employee"),
                Map.entry("status", "VERIFIED"),
                Map.entry("gross_salary", BigDecimal.ZERO),
                Map.entry("total_deductions", BigDecimal.ZERO),
                Map.entry("net_salary", BigDecimal.ZERO),
                Map.entry("line_count", 1),
                Map.entry("line_gross", BigDecimal.ZERO),
                Map.entry("line_deductions", BigDecimal.ZERO),
                Map.entry("min_amount", BigDecimal.ZERO)
        ));

        service.finalizeBatch(11L, true, 1L);

        assertTrue(jdbc.executedSqlContaining(
                "AND version = ?"
        ));
        assertTrue(jdbc.calledWith("[42, 11, 1]"));
    }

    @Test
    void reopenSendsTheTransactionsBackForVerification() {
        jdbc.row("FROM public.payroll_batches", batchRow("FINALIZED"));

        PayrollBatchStatusResponse response = service.reopen(11L, "wrong amount");

        assertEquals("REOPENED", response.status());
        assertTrue(jdbc.executedSqlContaining("checker_id = NULL"),
                "the checker must be cleared when the batch is reopened");
        assertTrue(jdbc.executedSqlContaining("SET status = 'CALCULATED'"),
                "the transactions must be verified again after a correction");
    }

    @Test
    void correctionRequiresAReasonAndANonNegativeAmount() {
        jdbc.row("FROM public.payroll_batches", batchRow("DRAFT"));

        ResponseStatusException missingReason = assertThrows(
                ResponseStatusException.class,
                () -> service.correctTransaction(11L, 3L, new PayrollCorrectionRequest(
                        2L, new BigDecimal("100"), " "))
        );
        assertEquals(HttpStatus.BAD_REQUEST, missingReason.getStatusCode());

        ResponseStatusException negative = assertThrows(
                ResponseStatusException.class,
                () -> service.correctTransaction(11L, 3L, new PayrollCorrectionRequest(
                        2L, new BigDecimal("-1"), "typo"))
        );
        assertEquals(HttpStatus.BAD_REQUEST, negative.getStatusCode());
    }

    @Test
    void correctionRecomputesTheTransactionAndBatchTotals() {
        jdbc.row("FROM public.payroll_batches", batchRow("REOPENED"));
        jdbc.row("FROM public.salary_transaction_lines",
                Map.of("amount", new BigDecimal("500")));

        service.correctTransaction(11L, 3L, new PayrollCorrectionRequest(
                2L, new BigDecimal("750"), "basic pay correction"));

        assertTrue(jdbc.executedSqlContaining("SET gross_salary = agg.gross"),
                "the transaction totals must be recomputed from the lines");
        assertTrue(jdbc.executedSqlContaining("SET gross_total = agg.gross"),
                "the batch totals must be recomputed from the transactions");
    }

    @Test
    void approvalHistoryIsReadFromTheApprovalTable() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("entity_type", "PAYROLL_BATCH");
        row.put("entity_id", 11L);
        row.put("from_status", "SUBMITTED");
        row.put("to_status", "APPROVED");
        row.put("action", "APPROVE");
        row.put("acted_by", ACTOR_ID);
        row.put("remarks", "checked");
        row.put("acted_at", null);
        jdbc.row("FROM public.salary_approvals", row);

        var approvals = service.approvals(11L);

        assertEquals(1, approvals.size());
        assertEquals("APPROVE", approvals.get(0).action());
    }

    private static Map<String, Object> batchRow(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 11L);
        row.put("status", status);
        row.put("maker_id", MAKER_ID);
        row.put("checker_id", null);
        row.put("wing_id", 3L);
        row.put("division_id", 4L);
        row.put("version", 1L);
        return row;
    }
}
