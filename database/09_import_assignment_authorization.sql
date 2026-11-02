-- SSGA Migration 09: authorize shared import-job assignment safely
-- PostgreSQL 17 | Additive/security migration only; no salary data is changed.
-- Run after 08_import_resolution_workflow.sql.
--
-- Purpose:
--   * Allow a properly authorized organization-wide import coordinator to
--     assign collaborators to an existing job, even if they did not upload it.
--   * Keep uploader-created assignments inside the uploader's own scope.
--   * Require each assignee to hold the capability and organizational scope
--     appropriate to the assigned role.
--   * Preserve assignment immutability and revocation semantics from Migration 08.

BEGIN;

-- Checks the current actor's authority to administer assignments for a job.
-- This is deliberately organization-wide for a non-uploader coordinator.
CREATE OR REPLACE FUNCTION app.can_manage_import_assignments(
    p_import_job_id BIGINT,
    p_division_id BIGINT DEFAULT NULL
)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.import_jobs j
        WHERE j.id = p_import_job_id
          AND (
              (
                  j.uploaded_by = app.current_user_id()
                  AND (
                      (p_division_id IS NOT NULL
                       AND app.can_access_division(p_division_id))
                      OR
                      (p_division_id IS NULL AND EXISTS (
                          SELECT 1
                          FROM public.user_role_scopes s
                          JOIN public.roles r ON r.id = s.role_id
                          JOIN public.users u ON u.id = s.user_id
                          WHERE s.user_id = app.current_user_id()
                            AND s.is_active AND r.is_active AND u.is_active
                            AND CURRENT_DATE >= s.valid_from
                            AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
                            AND r.max_scope = 'ORG'
                            AND s.wing_id IS NULL AND s.division_id IS NULL
                      ))
                  )
              )
              OR EXISTS (
                  SELECT 1
                  FROM public.user_role_scopes s
                  JOIN public.roles r ON r.id = s.role_id
                  JOIN public.role_permissions rp ON rp.role_id = r.id
                  JOIN public.permissions p ON p.id = rp.permission_id
                  JOIN public.users u ON u.id = s.user_id
                  WHERE s.user_id = app.current_user_id()
                    AND s.is_active AND r.is_active AND u.is_active
                    AND CURRENT_DATE >= s.valid_from
                    AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
                    AND r.max_scope = 'ORG'
                    AND s.wing_id IS NULL AND s.division_id IS NULL
                    AND p.code IN ('import.approve', 'import.create', 'salary.manage',
                                  'salary.approve', 'salary.prepare', 'payroll.prepare',
                                  'user.manage')
              )
          )
    );
$$;

-- Verifies that the intended recipient has both the required capability and
-- the scope requested by this assignment.
CREATE OR REPLACE FUNCTION app.can_assign_import_role(
    p_user_id BIGINT,
    p_assignment_role TEXT,
    p_division_id BIGINT DEFAULT NULL
)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        JOIN public.users u ON u.id = s.user_id
        LEFT JOIN public.divisions d ON d.id = p_division_id
        WHERE s.user_id = p_user_id
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND (
              (p_assignment_role = 'RESOLVER'
               AND p.code IN ('import.validate','salary.manage','payroll.prepare','salary.prepare'))
              OR
              (p_assignment_role = 'APPROVER'
               AND p.code IN ('import.approve','salary.approve','payroll.approve'))
              OR
              (p_assignment_role = 'FINALIZER'
               AND p.code IN ('salary.finalize','payroll.finalize','import.finalize'))
              OR
              (p_assignment_role = 'VIEWER'
               AND p.code IN ('salary.view','payroll.view','import.validate','report.view'))
          )
          AND (
              (p_division_id IS NULL
               AND r.max_scope = 'ORG'
               AND s.wing_id IS NULL AND s.division_id IS NULL)
              OR
              (p_division_id IS NOT NULL AND d.id IS NOT NULL AND (
                   (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
                   OR (r.max_scope IN ('WING','ORG') AND s.wing_id = d.wing_id)
                   OR (r.max_scope IN ('DIVISION','WING','ORG') AND s.division_id = d.id)
              ))
          )
    );
$$;

REVOKE ALL ON FUNCTION app.can_manage_import_assignments(BIGINT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.can_assign_import_role(BIGINT, TEXT, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.can_manage_import_assignments(BIGINT, BIGINT) TO ssga_app;
GRANT EXECUTE ON FUNCTION app.can_assign_import_role(BIGINT, TEXT, BIGINT) TO ssga_app;

-- Enforce policies even for table owners; app calls must still run through RLS.
ALTER TABLE public.import_job_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.import_job_assignments FORCE ROW LEVEL SECURITY;
ALTER TABLE public.import_job_corrections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.import_job_corrections FORCE ROW LEVEL SECURITY;
ALTER TABLE public.import_job_workflow_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.import_job_workflow_events FORCE ROW LEVEL SECURITY;

-- Org-wide coordinators may discover a job in order to administer assignments.
DROP POLICY IF EXISTS import_jobs_assignment_manager_select_v09 ON public.import_jobs;
CREATE POLICY import_jobs_assignment_manager_select_v09
    ON public.import_jobs FOR SELECT TO ssga_app
    USING (app.can_manage_import_assignments(id, NULL));

-- Permit the uploader to assign only within their own scope; allow a separate
-- organization-wide import coordinator with import.approve/import.create to
-- assign roles on another user's job. The target's capability+scope is checked
-- here as a defense in depth in addition to Java service validation.
DROP POLICY IF EXISTS import_job_assignments_insert_v08 ON public.import_job_assignments;
CREATE POLICY import_job_assignments_insert_v08
    ON public.import_job_assignments FOR INSERT TO ssga_app
    WITH CHECK (
        assigned_by = app.current_user_id()
        AND app.can_assign_import_role(user_id, assignment_role, division_id)
        AND app.can_manage_import_assignments(import_job_id, division_id)
        AND EXISTS (
            SELECT 1 FROM public.import_jobs j
            WHERE j.id = import_job_id
              AND j.status NOT IN ('IMPORTED','CANCELLED')
        )
    );

-- The original uploader can inspect the assignments for the job they created,
-- even if an organization-wide coordinator made those assignments.
DROP POLICY IF EXISTS import_job_assignments_uploader_select_v09 ON public.import_job_assignments;
CREATE POLICY import_job_assignments_uploader_select_v09
    ON public.import_job_assignments FOR SELECT TO ssga_app
    USING (EXISTS (
        SELECT 1 FROM public.import_jobs j
        WHERE j.id = import_job_id AND j.uploaded_by = app.current_user_id()
    ));

-- An org-wide coordinator can see assignment rows and revoke them. An assignee
-- still cannot change identity/scope, due to the immutable-identity trigger.
DROP POLICY IF EXISTS import_job_assignments_manager_select_v09 ON public.import_job_assignments;
CREATE POLICY import_job_assignments_manager_select_v09
    ON public.import_job_assignments FOR SELECT TO ssga_app
    USING (app.can_manage_import_assignments(import_job_id, NULL));

DROP POLICY IF EXISTS import_job_assignments_update_v08 ON public.import_job_assignments;
CREATE POLICY import_job_assignments_update_v08
    ON public.import_job_assignments FOR UPDATE TO ssga_app
    USING (
        assigned_by = app.current_user_id()
        OR app.can_manage_import_assignments(import_job_id, division_id)
    )
    WITH CHECK (
        assigned_by = app.current_user_id()
        OR app.can_manage_import_assignments(import_job_id, division_id)
    );

-- Assignment coordinators may append a workflow event for assignment/revocation.
DROP POLICY IF EXISTS import_job_workflow_events_insert_manager_v09 ON public.import_job_workflow_events;
CREATE POLICY import_job_workflow_events_insert_manager_v09
    ON public.import_job_workflow_events FOR INSERT TO ssga_app
    WITH CHECK (
        actor_user_id = app.current_user_id()
        AND action IN ('ASSIGN','REASSIGN','REVOKE_ASSIGNMENT')
        AND app.can_manage_import_assignments(import_job_id, division_id)
    );

COMMIT;

-- Verification query (run separately after successful commit):
-- SELECT to_regprocedure('app.can_manage_import_assignments(bigint,bigint)'),
--        to_regprocedure('app.can_assign_import_role(bigint,text,bigint)');
