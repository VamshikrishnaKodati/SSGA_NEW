BEGIN;

DROP POLICY IF EXISTS import_jobs_select_policy
    ON public.import_jobs;

CREATE POLICY import_jobs_select_policy
ON public.import_jobs
FOR SELECT
TO ssga_app
USING (
    uploaded_by = app.current_user_id()
);

COMMIT;