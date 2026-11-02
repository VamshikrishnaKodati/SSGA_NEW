package SSGA_Backend.statement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Salary statement generation (Phase D).
 *
 * <p>Statements are issued only from FINALIZED payroll batches. Each statement
 * stores an immutable JSON snapshot built from the frozen salary transaction
 * and its lines (never from mutable employee/salary master data), the SHA-256
 * of that snapshot, and a deterministic PDF rendered from the snapshot together
 * with its own SHA-256 and size.
 *
 * <p>RLS plus explicit division-scope checks keep one division from reading or
 * writing another division's statements. Regeneration is idempotent: an
 * unchanged snapshot is skipped, a changed one supersedes the previous version.
 */
@Service
public class SalaryStatementService {

    private static final String TEMPLATE_VERSION = "SSGA-STMT-v1";
    private static final String ISSUED = "ISSUED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String FINALIZED = "FINALIZED";

    private static final String SELECT_STATEMENTS = """
            SELECT s.id, s.statement_no, s.transaction_id, s.employee_id,
                   t.employee_code_snapshot, t.name_snapshot,
                   s.period_id, p.period_year, p.period_month,
                   s.wing_id, s.division_id, s.version, s.status,
                   s.template_version, s.payload::text AS payload,
                   s.payload_sha256, s.file_key, s.file_sha256,
                   s.file_size_bytes, s.generated_by, s.generated_at
            FROM public.salary_statements s
            JOIN public.salary_transactions t ON t.id = s.transaction_id
            JOIN public.payroll_periods p ON p.id = s.period_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final Path storageRoot;

    public SalaryStatementService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper,
            @Value("${ssga.statement.storage-dir:}") String storageDirectory
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;
        this.storageRoot = resolveStorageRoot(storageDirectory);
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public StatementGenerationResponse generateForBatch(long batchId) {
        rlsContext.setCurrentUser();

        Batch batch = requireFinalizedBatch(batchId);
        List<Transaction> transactions = loadTransactions(batchId);

        if (transactions.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The batch has no salary transactions."
            );
        }

        int generated = 0;
        int skipped = 0;
        int superseded = 0;
        List<SalaryStatementResponse> issued = new ArrayList<>();

        for (Transaction transaction : transactions) {
            Map<String, Object> payload = buildPayload(batch, transaction);
            String payloadJson = jsonMapper.writeValueAsString(payload);
            String payloadSha = sha256(payloadJson.getBytes(StandardCharsets.UTF_8));

            Existing existing = findIssued(transaction.id());

            if (existing != null
                    && payloadSha.equalsIgnoreCase(existing.payloadSha256())) {
                skipped++;
                continue;
            }

            if (existing != null) {
                supersede(existing.id());
                superseded++;
            }

            int version = nextVersion(transaction.id());

            issued.add(issue(
                    batch, transaction, version, payload, payloadJson, payloadSha
            ));
            generated++;
        }

        return new StatementGenerationResponse(
                batchId,
                generated,
                skipped,
                superseded,
                issued,
                "Generated " + generated + " statement(s); "
                        + skipped + " unchanged; "
                        + superseded + " superseded."
        );
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            rollbackFor = Exception.class
    )
    public SalaryStatementResponse reissue(long statementId) {
        rlsContext.setCurrentUser();

        StatementRow current = requireStatement(statementId);

        if (!ISSUED.equals(current.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only an ISSUED statement can be reissued. Current status: "
                            + current.status() + "."
            );
        }

        Transaction transaction = requireTransaction(current.transactionId());
        Batch batch = requireFinalizedBatch(batchIdOf(current.transactionId()));

        supersede(current.id());

        int version = nextVersion(current.transactionId());

        Map<String, Object> payload = buildPayload(batch, transaction);
        String payloadJson = jsonMapper.writeValueAsString(payload);
        String payloadSha = sha256(payloadJson.getBytes(StandardCharsets.UTF_8));

        SalaryStatementResponse reissued = issue(
                batch, transaction, version, payload, payloadJson, payloadSha
        );

        audit(reissued.id(), "STATEMENT_REISSUE", reissued.statementNo(),
                "version " + current.version() + " -> " + version);

        return reissued;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            readOnly = true
    )
    public List<SalaryStatementResponse> list(Long periodId, Long divisionId) {
        rlsContext.setCurrentUser();

        StringBuilder sql = new StringBuilder(SELECT_STATEMENTS)
                .append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (periodId != null) {
            sql.append(" AND s.period_id = ?");
            args.add(periodId);
        }

        if (divisionId != null) {
            sql.append(" AND s.division_id = ?");
            args.add(divisionId);
        }

        sql.append(" ORDER BY s.employee_id, s.version DESC");

        List<StatementRow> rows = jdbcTemplate.query(
                sql.toString(), statementMapper(), args.toArray()
        );

        List<SalaryStatementResponse> result = new ArrayList<>();

        for (StatementRow row : rows) {
            result.add(mapStatement(row));
        }

        return result;
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public SalaryStatementDetail get(long statementId) {
        rlsContext.setCurrentUser();

        StatementRow row = requireStatement(statementId);

        audit(row.id(), "STATEMENT_VIEW", row.statementNo(), null);

        Object payload = jsonMapper.readValue(row.payload(), Object.class);

        return new SalaryStatementDetail(mapStatement(row), payload);
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public StatementFile download(long statementId) {
        rlsContext.setCurrentUser();

        StatementRow row = requireStatement(statementId);

        audit(row.id(), "STATEMENT_DOWNLOAD", row.statementNo(), null);

        Map<?, ?> payload = jsonMapper.readValue(row.payload(), Map.class);
        byte[] content = renderPdf(payload);

        return new StatementFile(
                content, row.statementNo() + ".pdf", sha256(content)
        );
    }

    private SalaryStatementResponse issue(
            Batch batch,
            Transaction transaction,
            int version,
            Map<String, Object> payload,
            String payloadJson,
            String payloadSha
    ) {
        byte[] pdf = renderPdf(payload);
        String fileSha = sha256(pdf);

        String statementNo = statementNo(batch, transaction, version);
        String fileKey = batch.periodLabel() + "/" + statementNo + ".pdf";

        storeFile(fileKey, pdf);

        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO public.salary_statements (
                    statement_no, transaction_id, employee_id, period_id,
                    wing_id, division_id, version, status, template_version,
                    payload, payload_sha256, file_key, file_sha256,
                    file_size_bytes, generated_by
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?,
                        ?::jsonb, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                statementNo,
                transaction.id(),
                transaction.employeeId(),
                batch.periodId(),
                batch.wingId(),
                batch.divisionId(),
                version,
                TEMPLATE_VERSION,
                payloadJson,
                payloadSha,
                fileKey,
                fileSha,
                (long) pdf.length,
                currentUserId()
        );

        if (id == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to issue a salary statement."
            );
        }

        SalaryStatementResponse response = mapStatement(requireStatement(id));
        audit(id, "STATEMENT_GENERATE", statementNo, null);

        return response;
    }

    private Map<String, Object> buildPayload(Batch batch, Transaction transaction) {
        List<Map<String, Object>> earnings = new ArrayList<>();
        List<Map<String, Object>> deductions = new ArrayList<>();

        for (Line line : transaction.lines()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", line.code());
            item.put("name", line.name());
            item.put("category", line.category());
            item.put("amount", line.amount());

            if ("EARNING".equals(line.category())) {
                earnings.add(item);
            } else {
                deductions.add(item);
            }
        }

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("id", batch.periodId());
        period.put("label", batch.periodLabel());
        period.put("year", batch.periodYear());
        period.put("month", batch.periodMonth());

        Map<String, Object> employee = new LinkedHashMap<>();
        employee.put("id", transaction.employeeId());
        employee.put("code", transaction.employeeCode());
        employee.put("hrms_id", transaction.hrmsId());
        employee.put("name", transaction.name());

        Map<String, Object> division = new LinkedHashMap<>();
        division.put("id", batch.divisionId());
        division.put("name", batch.divisionName());

        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("id", transaction.id());
        tx.put("gross_salary", transaction.gross());
        tx.put("total_deductions", transaction.deductions());
        tx.put("net_salary", transaction.net());

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("gross", transaction.gross());
        totals.put("deductions", transaction.deductions());
        totals.put("net", transaction.net());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("template_version", TEMPLATE_VERSION);
        payload.put("batch_id", batch.id());
        payload.put("period", period);
        payload.put("employee", employee);
        payload.put("division", division);
        payload.put("wing_id", batch.wingId());
        payload.put("transaction", new LinkedHashMap<>(Map.of(
                "id", transaction.id(),
                "gross_salary", transaction.gross(),
                "total_deductions", transaction.deductions(),
                "net_salary", transaction.net()
        )));
        payload.put("earnings", earnings);
        payload.put("deductions", deductions);
        payload.put("totals", new LinkedHashMap<>(Map.of(
                "gross", transaction.gross(),
                "deductions", transaction.deductions(),
                "net", transaction.net()
        )));

        return payload;
    }

    private byte[] renderPdf(Map<?, ?> payload) {
        return StatementPdfRenderer.render(payload);
    }

    private void storeFile(String fileKey, byte[] content) {
        Path target = storageRoot.resolve(fileKey).normalize();

        if (!target.startsWith(storageRoot)) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid statement storage path."
            );
        }

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Batch requireFinalizedBatch(long batchId) {
        List<Batch> rows = jdbcTemplate.query(
                """
                SELECT b.id, b.status, b.period_id, b.division_id, b.wing_id,
                       p.period_year, p.period_month,
                       COALESCE(b.division_name_snapshot, d.name)
                           AS division_name
                FROM public.payroll_batches b
                JOIN public.payroll_periods p ON p.id = b.period_id
                LEFT JOIN public.divisions d ON d.id = b.division_id
                WHERE b.id = ?
                """,
                (rs, rowNum) -> new Batch(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getLong("period_id"),
                        rs.getInt("period_year"),
                        rs.getInt("period_month"),
                        rs.getLong("division_id"),
                        rs.getObject("wing_id", Long.class),
                        rs.getString("division_name")
                ),
                batchId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payroll batch not found or not accessible."
            );
        }

        Batch batch = rows.get(0);

        if (!FINALIZED.equals(batch.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Salary statements can only be generated from a FINALIZED "
                            + "batch. Current status: " + batch.status() + "."
            );
        }

        if (!canAccessDivision(batch.divisionId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You do not have access to this batch's division."
            );
        }

        return batch;
    }

    private List<Transaction> loadTransactions(long batchId) {
        List<Transaction> transactions = jdbcTemplate.query(
                """
                SELECT id, employee_id, employee_code_snapshot,
                       hrms_id_snapshot, name_snapshot, gross_salary,
                       total_deductions, net_salary
                FROM public.salary_transactions
                WHERE payroll_batch_id = ?
                ORDER BY employee_code_snapshot
                """,
                (rs, rowNum) -> new Transaction(
                        rs.getLong("id"),
                        rs.getLong("employee_id"),
                        rs.getString("employee_code_snapshot"),
                        rs.getString("hrms_id_snapshot"),
                        rs.getString("name_snapshot"),
                        rs.getBigDecimal("gross_salary"),
                        rs.getBigDecimal("total_deductions"),
                        rs.getBigDecimal("net_salary"),
                        new ArrayList<>()
                ),
                batchId
        );

        for (Transaction transaction : transactions) {
            transaction.lines().addAll(loadLines(transaction.id()));
        }

        return transactions;
    }

    private Transaction requireTransaction(long transactionId) {
        List<Transaction> rows = jdbcTemplate.query(
                """
                SELECT id, employee_id, employee_code_snapshot,
                       hrms_id_snapshot, name_snapshot, gross_salary,
                       total_deductions, net_salary
                FROM public.salary_transactions
                WHERE id = ?
                """,
                (rs, rowNum) -> new Transaction(
                        rs.getLong("id"),
                        rs.getLong("employee_id"),
                        rs.getString("employee_code_snapshot"),
                        rs.getString("hrms_id_snapshot"),
                        rs.getString("name_snapshot"),
                        rs.getBigDecimal("gross_salary"),
                        rs.getBigDecimal("total_deductions"),
                        rs.getBigDecimal("net_salary"),
                        new ArrayList<>()
                ),
                transactionId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Salary transaction not found."
            );
        }

        Transaction transaction = rows.get(0);
        transaction.lines().addAll(loadLines(transactionId));

        return transaction;
    }

    private List<Line> loadLines(long transactionId) {
        return jdbcTemplate.query(
                """
                SELECT component_code_snapshot, component_name_snapshot,
                       category_snapshot, amount
                FROM public.salary_transaction_lines
                WHERE salary_transaction_id = ?
                ORDER BY category_snapshot DESC, component_code_snapshot
                """,
                (rs, rowNum) -> new Line(
                        rs.getString("component_code_snapshot"),
                        rs.getString("component_name_snapshot"),
                        rs.getString("category_snapshot"),
                        rs.getBigDecimal("amount")
                ),
                transactionId
        );
    }

    private StatementRow requireStatement(long statementId) {
        List<StatementRow> rows = jdbcTemplate.query(
                SELECT_STATEMENTS + " WHERE s.id = ?",
                statementMapper(),
                statementId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Salary statement not found or not accessible."
            );
        }

        return rows.get(0);
    }

    private Existing findIssued(long transactionId) {
        List<Existing> rows = jdbcTemplate.query(
                """
                SELECT id, payload_sha256
                FROM public.salary_statements
                WHERE transaction_id = ? AND status = 'ISSUED'
                """,
                (rs, rowNum) -> new Existing(
                        rs.getLong("id"),
                        rs.getString("payload_sha256")
                ),
                transactionId
        );

        return rows.isEmpty() ? null : rows.get(0);
    }

    private void supersede(long statementId) {
        jdbcTemplate.update(
                """
                UPDATE public.salary_statements
                SET status = 'SUPERSEDED'
                WHERE id = ? AND status = 'ISSUED'
                """,
                statementId
        );
    }

    private int nextVersion(long transactionId) {
        Integer max = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(version), 0) + 1
                FROM public.salary_statements
                WHERE transaction_id = ?
                """,
                Integer.class,
                transactionId
        );

        return max == null ? 1 : max;
    }

    private long batchIdOf(long transactionId) {
        List<Long> ids = jdbcTemplate.query(
                """
                SELECT payroll_batch_id FROM public.salary_transactions
                WHERE id = ?
                """,
                (rs, rowNum) -> rs.getLong("payroll_batch_id"),
                transactionId
        );

        if (ids.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Salary transaction not found."
            );
        }

        return ids.get(0);
    }

    private boolean canAccessDivision(Long divisionId) {
        if (divisionId == null) {
            return true;
        }

        Boolean allowed = jdbcTemplate.queryForObject(
                "SELECT app.can_access_division(?)",
                Boolean.class,
                divisionId
        );

        return Boolean.TRUE.equals(allowed);
    }

    private void audit(
            long statementId,
            String action,
            String statementNo,
            String details
    ) {
        jdbcTemplate.queryForObject(
                """
                SELECT app.write_audit_log(
                    ?, 'SALARY_STATEMENT', ?::varchar,
                    s.wing_id, s.division_id,
                    NULL::jsonb,
                    jsonb_build_object(
                        'statement_no', ?::text,
                        'details', ?::text
                    ),
                    NULL::text, NULL::uuid
                )
                FROM public.salary_statements s
                WHERE s.id = ?
                """,
                Long.class,
                action,
                String.valueOf(statementId),
                statementNo,
                details,
                statementId
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

    private RowMapper<StatementRow> statementMapper() {
        return (rs, rowNum) -> new StatementRow(
                rs.getLong("id"),
                rs.getString("statement_no"),
                rs.getLong("transaction_id"),
                rs.getLong("employee_id"),
                rs.getString("employee_code_snapshot"),
                rs.getString("name_snapshot"),
                rs.getLong("period_id"),
                periodLabel(rs.getInt("period_year"), rs.getInt("period_month")),
                rs.getLong("wing_id"),
                rs.getLong("division_id"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getString("template_version"),
                rs.getString("payload"),
                rs.getString("payload_sha256"),
                rs.getString("file_key"),
                rs.getString("file_sha256"),
                rs.getLong("file_size_bytes"),
                rs.getLong("generated_by"),
                rs.getTimestamp("generated_at").toInstant().toString()
        );
    }

    private static SalaryStatementResponse mapStatement(StatementRow row) {
        return new SalaryStatementResponse(
                row.id(),
                row.statementNo(),
                row.transactionId(),
                row.employeeId(),
                row.employeeCode(),
                row.employeeName(),
                row.periodId(),
                row.periodLabel(),
                row.wingId(),
                row.divisionId(),
                row.version(),
                row.status(),
                row.templateVersion(),
                row.payloadSha256(),
                row.fileSha256(),
                row.fileSizeBytes(),
                row.generatedBy(),
                row.generatedAt()
        );
    }

    private static String statementNo(
            Batch batch, Transaction transaction, int version
    ) {
        return "STMT-" + batch.periodLabel() + "-"
                + transaction.employeeCode() + "-v" + version;
    }

    private static String periodLabel(int year, int month) {
        return String.format("%04d-%02d", year, month);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static Path resolveStorageRoot(String configured) {
        if (configured == null || configured.isBlank()) {
            return Paths.get(
                    System.getProperty("user.home"),
                    "ssga-data",
                    "statements"
            ).toAbsolutePath().normalize();
        }

        return Paths.get(configured).toAbsolutePath().normalize();
    }

    public record StatementFile(byte[] content, String fileName, String sha256) {
    }

    private record Batch(
            long id,
            String status,
            long periodId,
            int periodYear,
            int periodMonth,
            long divisionId,
            Long wingId,
            String divisionName
    ) {
        String periodLabel() {
            return SalaryStatementService.periodLabel(periodYear, periodMonth);
        }
    }

    private record Transaction(
            long id,
            long employeeId,
            String employeeCode,
            String hrmsId,
            String name,
            java.math.BigDecimal gross,
            java.math.BigDecimal deductions,
            java.math.BigDecimal net,
            List<Line> lines
    ) {
    }

    private record Line(
            String code,
            String name,
            String category,
            java.math.BigDecimal amount
    ) {
    }

    private record Existing(long id, String payloadSha256) {
    }

    private record StatementRow(
            long id,
            String statementNo,
            long transactionId,
            long employeeId,
            String employeeCode,
            String employeeName,
            long periodId,
            String periodLabel,
            long wingId,
            long divisionId,
            int version,
            String status,
            String templateVersion,
            String payload,
            String payloadSha256,
            String fileKey,
            String fileSha256,
            long fileSizeBytes,
            long generatedBy,
            String generatedAt
    ) {
    }
}
