-- 17_import_sheet_review_visibility.sql
--
-- Assigned approvers and finalizers could read the import job row
-- (import_jobs) but not its worksheets (import_job_sheets): the only
-- SELECT/UPDATE policies were for the uploader and for RESOLVER
-- assignments. As a result ImportJobWorkflowService.approve() always
-- failed with "The validated data changed since approval." for a real
-- (different) approver, and finalize() could not read or stamp the
-- worksheets it imports.
--
-- Additive policies only; existing policies stay in place and PostgreSQL
-- OR-combines permissive policies.

BEGIN;

DROP POLICY IF EXISTS import_job_sheets_approver_select_v14
    ON public.import_job_sheets;

CREATE POLICY import_job_sheets_approver_select_v14
    ON public.import_job_sheets
    FOR SELECT
    USING (
        app.has_import_job_assignment(
            import_job_id,
            ARRAY['APPROVER'::text, 'FINALIZER'::text],
            NULL::bigint
        )
    );

DROP POLICY IF EXISTS import_job_sheets_finalizer_update_v14
    ON public.import_job_sheets;

CREATE POLICY import_job_sheets_finalizer_update_v14
    ON public.import_job_sheets
    FOR UPDATE
    USING (
        app.has_import_job_assignment(
            import_job_id,
            ARRAY['FINALIZER'::text],
            NULL::bigint
        )
    );

INSERT INTO public.schema_migrations (version, name)
VALUES (17, '17_import_sheet_review_visibility')
ON CONFLICT (version) DO NOTHING;

COMMIT;
