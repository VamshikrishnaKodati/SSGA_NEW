-- ============================================================================
-- 40_import_approval_queue.sql
-- Give the responsible approver a working approval queue: a user holding
-- payroll.approve on their active assignment can see and decide a submitted
-- import job even when no explicit APPROVER assignment exists. The service
-- still enforces maker-checker and status transitions; these policies only
-- remove the row-level dead end that produced a 409 on approve.
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

CREATE OR REPLACE FUNCTION app.has_active_permission(p_code TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        JOIN public.users u ON u.id = s.user_id
        WHERE s.id = app.current_role_scope_id()
          AND s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND p.code = p_code
    );
$function$;

REVOKE ALL ON FUNCTION app.has_active_permission(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.has_active_permission(TEXT) TO ssga_app;

-- An approver can read the submitted job without being assigned to it.
DROP POLICY IF EXISTS import_jobs_approver_select_v40 ON public.import_jobs;
CREATE POLICY import_jobs_approver_select_v40
    ON public.import_jobs FOR SELECT TO ssga_app
    USING (
        app.has_active_permission('payroll.approve')
        AND status IN ('AWAITING_APPROVAL', 'APPROVED', 'IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED')
    );

-- The decide/auto-import path updates the job through these statuses.
DROP POLICY IF EXISTS import_jobs_approver_update_v40 ON public.import_jobs;
CREATE POLICY import_jobs_approver_update_v40
    ON public.import_jobs FOR UPDATE TO ssga_app
    USING (
        app.has_active_permission('payroll.approve')
        AND status IN ('AWAITING_APPROVAL', 'APPROVED', 'IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED')
    )
    WITH CHECK (app.has_active_permission('payroll.approve'));

-- Approval guards read the sheet totals, and auto-import stamps sheets.
DROP POLICY IF EXISTS import_job_sheets_approver_select_v40
    ON public.import_job_sheets;
CREATE POLICY import_job_sheets_approver_select_v40
    ON public.import_job_sheets FOR SELECT TO ssga_app
    USING (
        app.has_active_permission('payroll.approve')
        AND EXISTS (
            SELECT 1 FROM public.import_jobs j
            WHERE j.id = import_job_id
              AND j.status IN ('AWAITING_APPROVAL', 'APPROVED', 'IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED')
        )
    );

DROP POLICY IF EXISTS import_job_sheets_approver_update_v40
    ON public.import_job_sheets;
CREATE POLICY import_job_sheets_approver_update_v40
    ON public.import_job_sheets FOR UPDATE TO ssga_app
    USING (
        app.has_active_permission('payroll.approve')
        AND EXISTS (
            SELECT 1 FROM public.import_jobs j
            WHERE j.id = import_job_id
              AND j.status IN ('AWAITING_APPROVAL', 'APPROVED', 'IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED')
        )
    )
    WITH CHECK (app.has_active_permission('payroll.approve'));

-- "Who has to approve" is read from the active APPROVER assignments.
DROP POLICY IF EXISTS import_job_assignments_approver_select_v40
    ON public.import_job_assignments;
CREATE POLICY import_job_assignments_approver_select_v40
    ON public.import_job_assignments FOR SELECT TO ssga_app
    USING (
        app.has_active_permission('payroll.approve')
        AND EXISTS (
            SELECT 1 FROM public.import_jobs j
            WHERE j.id = import_job_id
              AND j.status IN ('AWAITING_APPROVAL', 'APPROVED', 'IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED')
        )
    );

-- Timeline: the approver appends SUBMIT/APPROVE/REJECT decisions and reads them.
DROP POLICY IF EXISTS import_job_workflow_events_approver_select_v40
    ON public.import_job_workflow_events;
CREATE POLICY import_job_workflow_events_approver_select_v40
    ON public.import_job_workflow_events FOR SELECT TO ssga_app
    USING (
        app.has_active_permission('payroll.approve')
        AND EXISTS (
            SELECT 1 FROM public.import_jobs j
            WHERE j.id = import_job_id
              AND j.status IN ('AWAITING_APPROVAL', 'APPROVED', 'IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED')
        )
    );

DROP POLICY IF EXISTS import_job_workflow_events_approver_insert_v40
    ON public.import_job_workflow_events;
CREATE POLICY import_job_workflow_events_approver_insert_v40
    ON public.import_job_workflow_events FOR INSERT TO ssga_app
    WITH CHECK (
        actor_user_id = app.current_user_id()
        AND app.has_active_permission('payroll.approve')
    );

-- Auto-import runs under the approver's own database context and streams
-- progress rows; without these it aborts halfway through the import.
DROP POLICY IF EXISTS import_job_progress_events_approver_select_v40
    ON public.import_job_progress_events;
CREATE POLICY import_job_progress_events_approver_select_v40
    ON public.import_job_progress_events FOR SELECT TO ssga_app
    USING (
        app.has_active_permission('payroll.approve')
        AND EXISTS (
            SELECT 1 FROM public.import_jobs j
            WHERE j.id = import_job_id
              AND j.status IN ('AWAITING_APPROVAL', 'APPROVED', 'IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED')
        )
    );

DROP POLICY IF EXISTS import_job_progress_events_approver_insert_v40
    ON public.import_job_progress_events;
CREATE POLICY import_job_progress_events_approver_insert_v40
    ON public.import_job_progress_events FOR INSERT TO ssga_app
    WITH CHECK (
        actor_user_id = app.current_user_id()
        AND app.has_active_permission('payroll.approve')
    );

INSERT INTO public.schema_migrations (version, name)
VALUES (40, '40_import_approval_queue')
ON CONFLICT (version) DO NOTHING;

COMMIT;
