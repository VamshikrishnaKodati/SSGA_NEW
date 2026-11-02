
package SSGA_Backend.importer;

import SSGA_Backend.security.RlsContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ImportJobAssignmentService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public ImportJobAssignmentService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    @Transactional(
            transactionManager = "dataSourceTransactionManager"
    )
    public ImportJobAssignmentResponse assign(
            long jobId,
            ImportJobAssignmentRequest request
    ) {
        rlsContext.setCurrentUser();

        if (jobId <= 0 || request == null
                || request.userId() == null
                || request.userId() <= 0
                || request.assignmentRole() == null) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "A valid job, user and assignment role are required."
            );
        }

        String role = request.assignmentRole()
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!List.of(
                "RESOLVER",
                "APPROVER",
                "FINALIZER",
                "VIEWER"
        ).contains(role)) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Unsupported assignment role."
            );
        }

        Long divisionId = request.divisionId();

        if (divisionId != null && divisionId <= 0) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Division ID must be positive or null."
            );
        }

        JobInfo job = findJob(jobId);

        if (List.of(
                "APPROVED",
                "IMPORTING",
                "PARTIALLY_IMPORTED",
                "IMPORTED",
                "CANCELLED"
        ).contains(job.status())) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Assignments cannot be changed at this workflow stage."
            );
        }

        boolean managerAllowed = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        """
                        SELECT app.can_manage_import_assignments(
                            ?::bigint,
                            ?::bigint
                        )
                        """,
                        Boolean.class,
                        jobId,
                        divisionId
                )
        );

        if (!managerAllowed) {
            throw new AccessDeniedException(
                    "You cannot assign collaborators for this job and scope."
            );
        }

        boolean recipientAllowed = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        """
                        SELECT app.can_assign_import_role(
                            ?::bigint,
                            ?::text,
                            ?::bigint
                        )
                        """,
                        Boolean.class,
                        request.userId(),
                        role,
                        divisionId
                )
        );

        if (!recipientAllowed) {
            throw new ResponseStatusException(
                    FORBIDDEN,
                    "The selected user does not have the required "
                            + "permission and organizational scope."
            );
        }

        enforceSeparation(job, request.userId(), role);

        Boolean duplicate = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM public.import_job_assignments
                    WHERE import_job_id = ?
                      AND user_id = ?
                      AND assignment_role = ?
                      AND division_id IS NOT DISTINCT FROM ?::bigint
                      AND is_active = TRUE
                )
                """,
                Boolean.class,
                jobId,
                request.userId(),
                role,
                divisionId
        );

        if (Boolean.TRUE.equals(duplicate)) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "This active assignment already exists."
            );
        }

        Long actorId = jdbcTemplate.queryForObject(
                "SELECT app.current_user_id()",
                Long.class
        );

        if (actorId == null) {
            throw new IllegalStateException(
                    "Authenticated database user context is missing."
            );
        }

        String notes = request.notes() == null
                ? null
                : request.notes().trim();

        List<Long> insertedIds = jdbcTemplate.query(
                """
                INSERT INTO public.import_job_assignments (
                    import_job_id,
                    user_id,
                    assignment_role,
                    division_id,
                    assigned_by,
                    notes
                )
                VALUES (?, ?, ?, ?::bigint, ?, ?)
                RETURNING id
                """,
                (rs, rowNum) -> rs.getLong("id"),
                jobId,
                request.userId(),
                role,
                divisionId,
                actorId,
                notes
        );

        if (insertedIds.isEmpty()) {
            throw new IllegalStateException(
                    "The assignment could not be created."
            );
        }

        long assignmentId = insertedIds.get(0);

        jdbcTemplate.update(
                """
                INSERT INTO public.import_job_workflow_events (
                    import_job_id,
                    division_id,
                    action,
                    previous_status,
                    new_status,
                    actor_user_id,
                    details
                )
                VALUES (
                    ?, ?::bigint, 'ASSIGN', ?, ?,
                    ?, jsonb_build_object(
                        'assignmentId', ?::bigint,
                        'assigneeUserId', ?::bigint,
                        'assignmentRole', ?::text,
                        'divisionId', ?::bigint
                    )
                )
                """,
                jobId,
                divisionId,
                job.status(),
                job.status(),
                actorId,
                assignmentId,
                request.userId(),
                role,
                divisionId
        );

        return findAssignment(assignmentId);
    }

    private JobInfo findJob(long jobId) {
        List<JobInfo> jobs = jdbcTemplate.query(
                """
                SELECT
                    id,
                    status,
                    uploaded_by,
                    submitted_by,
                    approved_by,
                    finalized_by
                FROM public.import_jobs
                WHERE id = ?
                """,
                (rs, rowNum) -> new JobInfo(
                        rs.getLong("id"),
                        rs.getString("status"),
                        nullableLong(rs, "uploaded_by"),
                        nullableLong(rs, "submitted_by"),
                        nullableLong(rs, "approved_by"),
                        nullableLong(rs, "finalized_by")
                ),
                jobId
        );

        if (jobs.isEmpty()) {
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "Import job not found or not accessible."
            );
        }

        return jobs.get(0);
    }

    private void enforceSeparation(
            JobInfo job,
            long assigneeId,
            String role
    ) {
        if ("APPROVER".equals(role)) {
            if (assigneeId == job.uploadedBy()
                    || sameUser(assigneeId, job.submittedBy())
                    || hasActiveRole(
                            job.id(), assigneeId, "FINALIZER")) {
                throw new ResponseStatusException(
                        CONFLICT,
                        "The approver must differ from the uploader, "
                                + "submitter and finalizer."
                );
            }
        }

        if ("FINALIZER".equals(role)) {
            if (assigneeId == job.uploadedBy()
                    || sameUser(assigneeId, job.submittedBy())
                    || sameUser(assigneeId, job.approvedBy())
                    || hasActiveRole(
                            job.id(), assigneeId, "APPROVER")) {
                throw new ResponseStatusException(
                        CONFLICT,
                        "The finalizer must differ from the uploader, "
                                + "submitter and approver."
                );
            }
        }
    }

    private boolean hasActiveRole(
            long jobId,
            long userId,
            String role
    ) {
        Boolean result = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM public.import_job_assignments
                    WHERE import_job_id = ?
                      AND user_id = ?
                      AND assignment_role = ?
                      AND is_active = TRUE
                )
                """,
                Boolean.class,
                jobId,
                userId,
                role
        );

        return Boolean.TRUE.equals(result);
    }

    private ImportJobAssignmentResponse findAssignment(
            long assignmentId
    ) {
        List<ImportJobAssignmentResponse> rows =
                jdbcTemplate.query(
                        """
                        SELECT
                            id,
                            import_job_id,
                            user_id,
                            assignment_role,
                            division_id,
                            assigned_by,
                            is_active,
                            assigned_at::text AS assigned_at,
                            notes
                        FROM public.import_job_assignments
                        WHERE id = ?
                        """,
                        (rs, rowNum) ->
                                new ImportJobAssignmentResponse(
                                        rs.getLong("id"),
                                        rs.getLong("import_job_id"),
                                        rs.getLong("user_id"),
                                        rs.getString("assignment_role"),
                                        nullableLong(rs, "division_id"),
                                        rs.getLong("assigned_by"),
                                        rs.getBoolean("is_active"),
                                        rs.getString("assigned_at"),
                                        rs.getString("notes"),
                                        "Assignment created successfully."
                                ),
                        assignmentId
                );

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Assignment created but not visible under current scope."
            );
        }

        return rows.get(0);
    }

    private static boolean sameUser(Long userId, Long otherUserId) {
        return userId != null && userId.equals(otherUserId);
    }

    private static Long nullableLong(
            ResultSet rs,
            String column
    ) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record JobInfo(
            long id,
            String status,
            Long uploadedBy,
            Long submittedBy,
            Long approvedBy,
            Long finalizedBy
    ) {
    }
}

