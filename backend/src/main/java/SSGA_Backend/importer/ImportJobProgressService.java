package SSGA_Backend.importer;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;

@Service
public class ImportJobProgressService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public ImportJobProgressService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            propagation = Propagation.REQUIRES_NEW
    )
    public void recordProgress(
            long jobId,
            int totalRecords,
            int processedRecords,
            int importedRows,
            int skippedRows,
            int failedRows,
            String level,
            String message
    ) {
        rlsContext.setCurrentUser();
        long actor = currentUserId();
        jdbcTemplate.update(
                """
                INSERT INTO public.import_job_progress_events (
                    import_job_id, actor_user_id, status, level, message,
                    total_records, processed_records, imported_rows,
                    skipped_rows, failed_rows
                )
                VALUES (?, ?, 'IMPORTING', ?, ?, ?, ?, ?, ?, ?)
                """,
                jobId,
                actor,
                level,
                message,
                totalRecords,
                processedRecords,
                importedRows,
                skippedRows,
                failedRows
        );
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            propagation = Propagation.MANDATORY
    )
    public void recordCompletion(
            long jobId,
            String status,
            int totalRecords,
            int processedRecords,
            int importedRows,
            int skippedRows,
            int failedRows,
            String message
    ) {
        rlsContext.setCurrentUser();
        long actor = currentUserId();
        jdbcTemplate.update(
                """
                INSERT INTO public.import_job_progress_events (
                    import_job_id, actor_user_id, status, level, message,
                    total_records, processed_records, imported_rows,
                    skipped_rows, failed_rows
                )
                VALUES (?, ?, ?, 'INFO', ?, ?, ?, ?, ?, ?)
                """,
                jobId,
                actor,
                status,
                message,
                totalRecords,
                processedRecords,
                importedRows,
                skippedRows,
                failedRows
        );
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager",
            propagation = Propagation.REQUIRES_NEW
    )
    public void markFailed(long jobId, String message) {
        rlsContext.setCurrentUser();
        long actor = currentUserId();
        int updated = jdbcTemplate.update(
                """
                UPDATE public.import_jobs
                SET status = 'FAILED',
                    failure_reason = ?,
                    imported_at = now()
                WHERE id = ?
                  AND status = 'APPROVED'
                  AND EXISTS (
                      SELECT 1
                      FROM public.import_job_progress_events e
                      WHERE e.import_job_id = public.import_jobs.id
                        AND e.status = 'IMPORTING'
                  )
                """,
                message,
                jobId
        );

        if (updated != 1) {
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO public.import_job_progress_events (
                    import_job_id, actor_user_id, status, level, message,
                    total_records, processed_records, imported_rows,
                    skipped_rows, failed_rows
                )
                VALUES (?, ?, 'FAILED', 'ERROR', ?, COALESCE((
                    SELECT SUM(s.data_rows_estimate)::integer
                    FROM public.import_job_sheets s
                    WHERE s.import_job_id = ?
                      AND s.confirmed_type IS NOT NULL
                ), 0), 0, 0, 0, 0)
                """,
                jobId,
                actor,
                message,
                jobId
        );
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobProgressResponse getProgress(long jobId) {
        rlsContext.setCurrentUser();

        List<Map<String, Object>> jobs = jdbcTemplate.queryForList(
                """
                SELECT id, status, file_name, source_type, uploaded_by,
                       date_range_start::text AS period_start,
                       date_range_end::text AS period_end,
                       COALESCE((
                           SELECT SUM(s.data_rows_estimate)
                           FROM public.import_job_sheets s
                           WHERE s.import_job_id = public.import_jobs.id
                             AND s.confirmed_type IS NOT NULL
                       ), 0)::integer AS total_records,
                       imported_at
                FROM public.import_jobs
                WHERE id = ?
                """,
                jobId
        );
        if (jobs.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Import job not found or not accessible."
            );
        }
        Map<String, Object> job = jobs.get(0);

        List<ImportJobProgressEventResponse> events = jdbcTemplate.query(
                """
                SELECT id, created_at::text AS event_time, status,
                       level, message, processed_records, imported_rows,
                       skipped_rows, failed_rows
                FROM public.import_job_progress_events
                WHERE import_job_id = ?
                ORDER BY id DESC
                LIMIT 100
                """,
                (rs, rowNum) -> new ImportJobProgressEventResponse(
                        rs.getLong("id"),
                        rs.getString("event_time"),
                        rs.getString("status"),
                        rs.getString("level"),
                        rs.getString("message"),
                        rs.getInt("processed_records"),
                        rs.getInt("imported_rows"),
                        rs.getInt("skipped_rows"),
                        rs.getInt("failed_rows")
                ),
                jobId
        );
        Collections.reverse(events);

        int totalRecords = ((Number) job.get("total_records")).intValue();
        String status = (String) job.get("status");
        ImportJobProgressEventResponse latest = events.isEmpty()
                ? null
                : events.get(events.size() - 1);
        if ("APPROVED".equals(status) && latest != null
                && "IMPORTING".equals(latest.status())) {
            status = "IMPORTING";
        }

        int processedRecords = latest == null ? 0 : latest.processedRecords();
        int importedRows = latest == null ? 0 : latest.importedRows();
        int skippedRows = latest == null ? 0 : latest.skippedRows();
        int failedRows = latest == null ? 0 : latest.failedRows();
        int pendingRows = Math.max(
                0,
                totalRecords - importedRows - skippedRows - failedRows
        );
        int percentage = totalRecords == 0
                ? 0
                : Math.min(100, (int) Math.floor(
                        (double) processedRecords * 100 / totalRecords));

        List<String> startTimes = jdbcTemplate.query(
                """
                SELECT created_at::text
                FROM public.import_job_progress_events
                WHERE import_job_id = ?
                ORDER BY id
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString(1),
                jobId
        );
        String startedAt = startTimes.isEmpty() ? null : startTimes.get(0);
        long elapsedSeconds = startedAt == null
                ? 0
                : jdbcTemplate.queryForObject(
                        """
                        SELECT GREATEST(
                            0,
                            FLOOR(EXTRACT(EPOCH FROM (
                                COALESCE(
                                    (SELECT imported_at
                                     FROM public.import_jobs
                                     WHERE id = ?),
                                    now()
                                ) - (
                                    SELECT created_at
                                    FROM public.import_job_progress_events
                                    WHERE import_job_id = ?
                                    ORDER BY id
                                    LIMIT 1
                                )
                            )))::bigint
                        )
                        """,
                        Long.class,
                        jobId,
                        jobId
                );

        Long remainingSeconds = null;
        if ("IMPORTING".equals(status) && processedRecords > 0
                && pendingRows > 0) {
            remainingSeconds = (long) Math.ceil(
                    (double) elapsedSeconds * pendingRows / processedRecords
            );
        }

        return new ImportJobProgressResponse(
                jobId,
                status,
                (String) job.get("file_name"),
                (String) job.get("source_type"),
                ((Number) job.get("uploaded_by")).longValue(),
                (String) job.get("period_start"),
                (String) job.get("period_end"),
                totalRecords,
                processedRecords,
                importedRows,
                pendingRows,
                failedRows,
                skippedRows,
                percentage,
                !events.isEmpty(),
                startedAt,
                elapsedSeconds,
                remainingSeconds,
                List.copyOf(events)
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
}
