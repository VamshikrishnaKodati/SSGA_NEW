package SSGA_Backend.payroll;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;

/**
 * Payroll batch lifecycle (verify -&gt; submit -&gt; approve/reject -&gt; finalize).
 *
 * <p>Enforces maker-checker separation (a batch cannot be approved or finalized
 * by its maker) and treats FINALIZED as a terminal, locked state. Transitions
 * are enforced here because the database intentionally leaves them to the
 * application layer, mirroring migration 08 for import jobs.
 *
 * <p>Approval and rejection are delegated to {@code app.review_payroll_batch},
 * which is the authoritative review routine: it re-checks scope, RBAC,
 * maker-checker and the verification gate, writes the approval history row and
 * writes a division-scoped audit entry.
 */
@Service
public class PayrollWorkflowService {

    private static final String FINALIZED = "FINALIZED";
    private static final java.util.Set<String> AWAITING_REVIEW_STATUSES =
            java.util.Set.of("SUBMITTED", "UNDER_REVIEW");
    private static final java.util.Set<String> EDITABLE_STATUSES =
            java.util.Set.of("DRAFT", "REJECTED", "REOPENED");

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public PayrollWorkflowService(JdbcTemplate jdbcTemplate, RlsContext rlsContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollBatchStatusResponse verify(long batchId) {
        rlsContext.setCurrentUser();
        Batch batch = requireBatch(batchId);
        requireVerifiable(batch);

        PayrollReadinessResponse readiness = readiness(batch);

        if (!readiness.issues().isEmpty()) {
            throw conflict("The batch is not ready for verification: "
                    + summarize(readiness.issues()));
        }

        int verified = jdbcTemplate.update(
                """
                UPDATE public.salary_transactions
                SET status = 'VERIFIED'
                WHERE payroll_batch_id = ? AND status <> 'VERIFIED'
                """,
                batchId
        );

        audit(batch, "PAYROLL_VERIFY", batch.status(), batch.status(),
                "verified=" + verified);

        return statusResponse(batchId, batch.status(),
                "Verified " + verified + " transaction(s).");
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public PayrollReadinessResponse readiness(long batchId) {
        rlsContext.setCurrentUser();
        return readiness(requireBatch(batchId));
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public PayrollBatchStatusResponse submit(long batchId) {
        rlsContext.setCurrentUser();
        Batch batch = requireBatch(batchId);
        requireEditable(batch, "submit");

        if (!List.of("DRAFT", "REJECTED", "REOPENED")
                .contains(batch.status())) {
            throw conflict("Only a DRAFT, REJECTED or REOPENED batch can be "
                    + "submitted. Current status: " + batch.status() + ".");
        }

        int total = totalCount(batchId);
        int verified = verifiedCount(batchId);

        if (total == 0) {
            throw conflict("The batch has no salary transactions to submit.");
        }

        if (verified < total) {
            throw conflict("All transactions must be verified before "
                    + "submission. " + (total - verified) + " remain "
                    + "unverified.");
        }

        PayrollReadinessResponse readiness = readiness(batch);

        if (!readiness.issues().isEmpty()) {
            throw conflict("The batch has data issues and cannot be "
                    + "submitted: " + summarize(readiness.issues()));
        }

        updateStatus(batch, "SUBMITTED");
        audit(batch, "PAYROLL_SUBMIT", batch.status(), "SUBMITTED", null);

        return statusResponse(batchId, "SUBMITTED",
                "Batch submitted for approval.");
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollBatchStatusResponse approve(long batchId) {
        rlsContext.setCurrentUser();
        Batch batch = requireBatch(batchId);
        requireEditable(batch, "approve");
        requireMakerChecker(batch, "approve");

        if (!AWAITING_REVIEW_STATUSES.contains(batch.status())) {
            throw conflict("Only a submitted or under-review batch can be approved. Current "
                    + "status: " + batch.status() + ".");
        }

        // app.review_payroll_batch writes the approval history row and the
        // division-scoped audit entry for this transition.
        review(batchId, "APPROVE", null);

        return statusResponse(batchId, "APPROVED", "Batch approved.");
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollBatchStatusResponse reject(long batchId, String reason) {
        rlsContext.setCurrentUser();
        Batch batch = requireBatch(batchId);
        requireEditable(batch, "reject");
        requireMakerChecker(batch, "reject");

        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A rejection reason is required."
            );
        }

        if (!AWAITING_REVIEW_STATUSES.contains(batch.status())) {
            throw conflict("Only a submitted or under-review batch can be rejected. Current "
                    + "status: " + batch.status() + ".");
        }

        review(batchId, "REJECT", reason);

        return statusResponse(batchId, "REJECTED", "Batch rejected.");
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollBatchStatusResponse finalizeBatch(
            long batchId,
            boolean confirmed,
            Long expectedVersion
    ) {
        rlsContext.setCurrentUser();

        if (expectedVersion == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The reviewed batch version is required for finalization."
            );
        }

        Batch batch = requireBatch(batchId);
        requireEditable(batch, "finalize");
        requireMakerChecker(batch, "finalize");

        if (batch.version() != expectedVersion) {
            throw conflict("The batch changed after it was reviewed. Refresh "
                    + "the batch before finalizing.");
        }

        if (!confirmed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Finalization must be explicitly confirmed."
            );
        }

        if (!"APPROVED".equals(batch.status())) {
            throw conflict("Only an approved batch can be finalized. Current "
                    + "status: " + batch.status() + ".");
        }

        if (batch.checkerId() != null
                && currentUserId() == batch.checkerId()) {
            throw conflict("Maker-checker violation: the approver cannot "
                    + "finalize the same batch.");
        }
        if (batch.makerId() != null && currentUserId() == batch.makerId()) {
            throw conflict("Maker-checker violation: the maker cannot "
                    + "finalize their own batch.");
        }

        PayrollReadinessResponse readiness = readiness(batch);

        if (!readiness.issues().isEmpty()
                || readiness.verifiedTransactions()
                        != readiness.totalTransactions()) {
            throw conflict("The batch is not ready for finalization: "
                    + summarize(readiness.issues()));
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE public.payroll_batches
                SET status = 'FINALIZED',
                    finalized_by = ?,
                    finalized_at = now()
                WHERE id = ? AND status = 'APPROVED' AND version = ?
                """,
                currentUserId(),
                batchId,
                expectedVersion
        );

        if (updated != 1) {
            throw conflict("The batch could not be finalized because its "
                    + "status changed. Refresh and retry.");
        }

        audit(batch, "PAYROLL_FINALIZE", batch.status(), "FINALIZED", null);

        return statusResponse(batchId, "FINALIZED",
                "Batch finalized and locked.");
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public PayrollFinalizationEligibilityResponse finalizationEligibility(
            long batchId,
            long expectedVersion
    ) {
        rlsContext.setCurrentUser();
        Batch batch = requireBatch(batchId);

        if (!"APPROVED".equals(batch.status())) {
            return new PayrollFinalizationEligibilityResponse(
                    false, batch.status(), batch.version(),
                    "The batch is not approved."
            );
        }
        if (batch.version() != expectedVersion) {
            return new PayrollFinalizationEligibilityResponse(
                    false, batch.status(), batch.version(),
                    "The batch changed after it was loaded. Refresh it before finalizing."
            );
        }
        long actorId = currentUserId();
        if ((batch.makerId() != null && actorId == batch.makerId())
                || (batch.checkerId() != null
                        && actorId == batch.checkerId())) {
            return new PayrollFinalizationEligibilityResponse(
                    false, batch.status(), batch.version(),
                    "A different authorized user must finalize this batch."
            );
        }

        PayrollReadinessResponse readiness = readiness(batch);
        if (!readiness.readyToFinalize()) {
            return new PayrollFinalizationEligibilityResponse(
                    false, batch.status(), batch.version(),
                    "The batch is not ready for finalization."
            );
        }

        return new PayrollFinalizationEligibilityResponse(
                true, batch.status(), batch.version(), null
        );
    }

    /**
     * Reopens a FINALIZED batch for correction. The transition is audited with
     * a mandatory reason, and any statements already issued for the batch are
     * superseded so no valid statement remains attached to a reopened batch.
     * A reopened batch is editable again (submit -&gt; approve -&gt; finalize).
     *
     * <p>The transactions are returned to CALCULATED so the correction has to
     * be verified again before the batch can be submitted.
     */
    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollBatchStatusResponse reopen(long batchId, String reason) {
        rlsContext.setCurrentUser();
        Batch batch = requireBatch(batchId);

        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A reopening reason is required."
            );
        }

        if (!FINALIZED.equals(batch.status())) {
            throw conflict("Only a finalized batch can be reopened. Current "
                    + "status: " + batch.status() + ".");
        }

        int superseded = jdbcTemplate.update(
                """
                UPDATE public.salary_statements
                SET status = 'SUPERSEDED', withdrawn_at = now(),
                    withdrawn_reason = ?
                WHERE status = 'ISSUED'
                  AND transaction_id IN (
                      SELECT id FROM public.salary_transactions
                      WHERE payroll_batch_id = ?
                  )
                """,
                "Payroll batch " + batchId + " reopened for correction.",
                batchId
        );

        int reopened = jdbcTemplate.update(
                """
                UPDATE public.payroll_batches
                SET status = 'REOPENED', checker_id = NULL,
                    finalized_by = NULL, finalized_at = NULL
                WHERE id = ? AND status = 'FINALIZED'
                """,
                batchId
        );

        if (reopened != 1) {
            throw conflict("The batch could not be reopened because its "
                    + "status changed. Refresh and retry.");
        }

        int reset = jdbcTemplate.update(
                """
                UPDATE public.salary_transactions
                SET status = 'CALCULATED'
                WHERE payroll_batch_id = ? AND status = 'VERIFIED'
                """,
                batchId
        );

        audit(batch, "PAYROLL_REOPEN", batch.status(), "REOPENED", reason);

        return statusResponse(batchId, "REOPENED",
                "Batch reopened for correction"
                        + (superseded > 0
                                ? "; " + superseded + " statement(s) "
                                        + "superseded."
                                : ".")
                        + (reset > 0
                                ? "; " + reset + " transaction(s) must be "
                                        + "verified again."
                                : "."));
    }

    /**
     * Corrects one salary component amount inside an editable batch. The
     * transaction totals and the batch totals are recomputed from the lines and
     * the transaction returns to CALCULATED, so the correction has to be
     * verified again before the batch can be submitted.
     */
    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public PayrollBatchStatusResponse correctTransaction(
            long batchId,
            long transactionId,
            PayrollCorrectionRequest request
    ) {
        rlsContext.setCurrentUser();
        Batch batch = requireBatch(batchId);

        if (request == null || request.componentId() == null
                || request.amount() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "componentId and amount are required."
            );
        }

        if (request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A correction reason is required."
            );
        }

        if (request.amount().signum() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A correction amount cannot be negative."
            );
        }

        if (!List.of("DRAFT", "REJECTED", "REOPENED")
                .contains(batch.status())) {
            throw conflict("Only a DRAFT, REJECTED or REOPENED batch can be "
                    + "corrected. Current status: " + batch.status() + ".");
        }

        BigDecimal previous = currentAmount(transactionId, request.componentId());

        if (previous == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "The component is not part of this salary transaction."
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE public.salary_transaction_lines l
                SET amount = ?
                FROM public.salary_transactions st
                WHERE st.id = l.salary_transaction_id
                  AND st.payroll_batch_id = ?
                  AND st.id = ?
                  AND l.salary_component_id = ?
                """,
                request.amount(), batchId, transactionId, request.componentId()
        );

        if (updated != 1) {
            throw conflict("The correction could not be applied. Refresh and "
                    + "retry.");
        }

        recomputeTransaction(batchId, transactionId);
        recomputeBatchTotals(batchId);

        audit(batch, "PAYROLL_CORRECTION", batch.status(), batch.status(),
                "component=" + request.componentId()
                        + "; previous=" + previous.toPlainString()
                        + "; amount=" + request.amount().toPlainString()
                        + "; reason=" + request.reason());

        return statusResponse(batchId, batch.status(),
                "Correction applied; the transaction must be verified again.");
    }

    /**
     * Approval history recorded by {@code app.review_payroll_batch}.
     */
    @Transactional(readOnly = true)
    public List<PayrollApprovalResponse> approvals(long batchId) {
        rlsContext.setCurrentUser();
        requireBatch(batchId);

        return jdbcTemplate.query(
                """
                SELECT a.id, a.entity_type, a.entity_id, a.from_status,
                       a.to_status, a.action, a.acted_by, a.remarks,
                       a.acted_at, u.display_name AS acted_by_name
                FROM public.salary_approvals a
                LEFT JOIN public.users u ON u.id = a.acted_by
                WHERE a.entity_type = 'PAYROLL_BATCH' AND a.entity_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new PayrollApprovalResponse(
                        rs.getLong("id"),
                        rs.getString("entity_type"),
                        rs.getLong("entity_id"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("action"),
                        rs.getLong("acted_by"),
                        rs.getString("remarks"),
                        rs.getObject("acted_at", java.time.OffsetDateTime.class),
                        rs.getString("acted_by_name")
                ),
                batchId
        );
    }

    private PayrollReadinessResponse readiness(Batch batch) {
        List<TransactionCheck> checks = jdbcTemplate.query(
                """
                SELECT st.id, st.hrms_id_snapshot, st.name_snapshot,
                       st.status, st.gross_salary, st.total_deductions,
                       st.net_salary,
                       COUNT(l.id) AS line_count,
                       COALESCE(SUM(l.amount) FILTER (
                           WHERE l.category_snapshot = 'EARNING'
                       ), 0) AS line_gross,
                       COALESCE(SUM(l.amount) FILTER (
                           WHERE l.category_snapshot = 'DEDUCTION'
                       ), 0) AS line_deductions,
                       COALESCE(MIN(l.amount), 0) AS min_amount
                FROM public.salary_transactions st
                LEFT JOIN public.salary_transaction_lines l
                  ON l.salary_transaction_id = st.id
                WHERE st.payroll_batch_id = ?
                GROUP BY st.id, st.hrms_id_snapshot, st.name_snapshot,
                         st.status, st.gross_salary, st.total_deductions,
                         st.net_salary
                ORDER BY st.hrms_id_snapshot
                """,
                (rs, rowNum) -> new TransactionCheck(
                        rs.getLong("id"),
                        rs.getString("hrms_id_snapshot"),
                        rs.getString("name_snapshot"),
                        rs.getString("status"),
                        rs.getBigDecimal("gross_salary"),
                        rs.getBigDecimal("total_deductions"),
                        rs.getBigDecimal("net_salary"),
                        rs.getInt("line_count"),
                        rs.getBigDecimal("line_gross"),
                        rs.getBigDecimal("line_deductions"),
                        rs.getBigDecimal("min_amount")
                ),
                batch.id()
        );

        List<String> issues = new java.util.ArrayList<>();

        if (checks.isEmpty()) {
            issues.add("The batch has no salary transactions.");
        }

        for (TransactionCheck check : checks) {
            String who = check.hrmsId() + " (" + check.name() + ")";

            if (check.lineCount() == 0) {
                issues.add(who + " has no salary component lines.");
                continue;
            }

            if (check.minAmount() != null
                    && check.minAmount().signum() < 0) {
                issues.add(who + " has a negative component amount.");
            }

            if (check.gross().compareTo(check.lineGross()) != 0) {
                issues.add(who + " gross total does not match its lines.");
            }

            if (check.deductions().compareTo(check.lineDeductions()) != 0) {
                issues.add(who + " deduction total does not match its lines.");
            }

            if (check.net().compareTo(
                    check.gross().subtract(check.deductions())) != 0) {
                issues.add(who + " net salary is inconsistent.");
            }
        }

        int total = checks.size();
        int verified = (int) checks.stream()
                .filter(check -> "VERIFIED".equals(check.status()))
                .count();
        boolean editable = EDITABLE_STATUSES.contains(batch.status());

        return new PayrollReadinessResponse(
                batch.id(),
                batch.status(),
                editable,
                total,
                verified,
                editable && total > 0 && issues.isEmpty(),
                editable && total > 0 && verified == total && issues.isEmpty(),
                "APPROVED".equals(batch.status())
                        && total > 0 && verified == total && issues.isEmpty(),
                List.copyOf(issues)
        );
    }

    private void requireVerifiable(Batch batch) {
        if (!EDITABLE_STATUSES.contains(batch.status())) {
            throw conflict("Only a DRAFT, REJECTED or REOPENED batch can be "
                    + "verified. Current status: " + batch.status() + ".");
        }
    }

    private static String summarize(List<String> issues) {
        if (issues.isEmpty()) {
            return "no issues reported.";
        }

        return String.join(" ", issues.stream().limit(5).toList())
                + (issues.size() > 5
                        ? " (+" + (issues.size() - 5) + " more)"
                        : "");
    }

    private void requireEditable(Batch batch, String action) {
        if (FINALIZED.equals(batch.status())) {
            throw conflict("This payroll batch is finalized and locked; it "
                    + "cannot be " + action + "ed.");
        }
    }

    private void requireMakerChecker(Batch batch, String action) {
        if (batch.makerId() != null && currentUserId() == batch.makerId()) {
            throw conflict("Maker-checker violation: the maker cannot "
                    + action + " their own batch.");
        }
    }

    /**
     * Runs the authoritative review routine. It raises a plain exception with
     * a human readable reason, which is mapped back to a conflict or a
     * forbidden response here.
     */
    private void review(long batchId, String action, String reason) {
        try {
            Long approvalId = jdbcTemplate.queryForObject(
                    "SELECT app.review_payroll_batch(?, ?, ?)",
                    Long.class,
                    batchId,
                    action,
                    reason
            );

            if (approvalId == null) {
                throw conflict("The payroll batch could not be reviewed.");
            }
        } catch (DataAccessException ex) {
            String message = ex.getMostSpecificCause().getMessage();

            if (message != null
                    && (message.contains("access")
                    || message.contains("permission"))) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        message
                );
            }

            throw conflict(message == null
                    ? "The payroll batch could not be reviewed."
                    : message);
        }
    }

    private BigDecimal currentAmount(long transactionId, long componentId) {
        List<BigDecimal> amounts = jdbcTemplate.query(
                """
                SELECT amount
                FROM public.salary_transaction_lines
                WHERE salary_transaction_id = ? AND salary_component_id = ?
                """,
                (rs, rowNum) -> rs.getBigDecimal("amount"),
                transactionId,
                componentId
        );

        return amounts.isEmpty() ? null : amounts.get(0);
    }

    private void recomputeTransaction(long batchId, long transactionId) {
        jdbcTemplate.update(
                """
                UPDATE public.salary_transactions st
                SET gross_salary = agg.gross,
                    total_deductions = agg.deductions,
                    net_salary = agg.gross - agg.deductions,
                    status = 'CALCULATED'
                FROM (
                    SELECT salary_transaction_id,
                           COALESCE(SUM(amount) FILTER (
                               WHERE category_snapshot = 'EARNING'
                           ), 0) AS gross,
                           COALESCE(SUM(amount) FILTER (
                               WHERE category_snapshot = 'DEDUCTION'
                           ), 0) AS deductions
                    FROM public.salary_transaction_lines
                    WHERE salary_transaction_id = ?
                    GROUP BY salary_transaction_id
                ) agg
                WHERE st.id = agg.salary_transaction_id
                  AND st.payroll_batch_id = ?
                """,
                transactionId,
                batchId
        );
    }

    private void recomputeBatchTotals(long batchId) {
        jdbcTemplate.update(
                """
                UPDATE public.payroll_batches b
                SET gross_total = agg.gross,
                    deduction_total = agg.deductions,
                    net_total = agg.gross - agg.deductions
                FROM (
                    SELECT payroll_batch_id,
                           COALESCE(SUM(gross_salary), 0) AS gross,
                           COALESCE(SUM(total_deductions), 0) AS deductions
                    FROM public.salary_transactions
                    WHERE payroll_batch_id = ?
                    GROUP BY payroll_batch_id
                ) agg
                WHERE b.id = agg.payroll_batch_id
                """,
                batchId
        );
    }

    private void updateStatus(Batch batch, String status) {
        jdbcTemplate.update(
                "UPDATE public.payroll_batches SET status = ? WHERE id = ?",
                status, batch.id()
        );
    }

    private int verifiedCount(long batchId) {
        Integer value = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM public.salary_transactions
                WHERE payroll_batch_id = ? AND status = 'VERIFIED'
                """,
                Integer.class,
                batchId
        );

        return value == null ? 0 : value;
    }

    private int totalCount(long batchId) {
        Integer value = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM public.salary_transactions
                WHERE payroll_batch_id = ?
                """,
                Integer.class,
                batchId
        );

        return value == null ? 0 : value;
    }

    private Batch requireBatch(long batchId) {
        List<Batch> batches = jdbcTemplate.query(
                """
                SELECT id, status, maker_id, checker_id, wing_id, division_id,
                       version
                FROM public.payroll_batches
                WHERE id = ?
                """,
                (rs, rowNum) -> new Batch(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getObject("maker_id", Long.class),
                        rs.getObject("checker_id", Long.class),
                        rs.getObject("wing_id", Long.class),
                        rs.getObject("division_id", Long.class),
                        rs.getLong("version")
                ),
                batchId
        );

        if (batches.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payroll batch not found or not accessible."
            );
        }

        return batches.get(0);
    }

    private PayrollBatchStatusResponse statusResponse(
            long batchId,
            String status,
            String message
    ) {
        Batch batch = requireBatch(batchId);

        return new PayrollBatchStatusResponse(
                batchId,
                status,
                batch.makerId(),
                batch.checkerId(),
                verifiedCount(batchId),
                totalCount(batchId),
                message
        );
    }

    private void audit(
            Batch batch,
            String action,
            String previousStatus,
            String newStatus,
            String reason
    ) {
        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    ?, 'PAYROLL_BATCH', ?::varchar,
                    ?::bigint, ?::bigint,
                    jsonb_build_object('status', ?::text),
                    jsonb_build_object('status', ?::text),
                    ?, NULL::uuid
                )
                """,
                Long.class,
                action,
                String.valueOf(batch.id()),
                batch.wingId(),
                batch.divisionId(),
                previousStatus,
                newStatus,
                reason
        );
    }

    private long currentUserId() {
        Long actor = jdbcTemplate.queryForObject(
                "SELECT app.current_user_id()", Long.class
        );

        if (actor == null) {
            throw new IllegalStateException(
                    "Authenticated database user context is missing."
            );
        }

        return actor;
    }

    private record TransactionCheck(
            long id,
            String hrmsId,
            String name,
            String status,
            BigDecimal gross,
            BigDecimal deductions,
            BigDecimal net,
            int lineCount,
            BigDecimal lineGross,
            BigDecimal lineDeductions,
            BigDecimal minAmount
    ) {
    }

    private record Batch(
            long id,
            String status,
            Long makerId,
            Long checkerId,
            Long wingId,
            Long divisionId,
            long version
    ) {
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
