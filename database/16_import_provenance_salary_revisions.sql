-- ============================================================================
-- 16_import_provenance_salary_revisions.sql
--
-- Adds import provenance to salary_revisions so revised-salary imports are
-- traceable to the source job/sheet/row, matching employee_salary_values.
--
-- Additive and idempotent. Does not change any existing payroll result.
-- ============================================================================

BEGIN;

ALTER TABLE public.salary_revisions
    ADD COLUMN IF NOT EXISTS import_job_id BIGINT
        REFERENCES public.import_jobs(id),
    ADD COLUMN IF NOT EXISTS import_job_sheet_id BIGINT
        REFERENCES public.import_job_sheets(id),
    ADD COLUMN IF NOT EXISTS import_row_number INTEGER;

CREATE INDEX IF NOT EXISTS ix_salary_revisions_import
    ON public.salary_revisions(import_job_id, import_job_sheet_id)
    WHERE import_job_id IS NOT NULL;

INSERT INTO public.schema_migrations (version, name)
VALUES (16, '16_import_provenance_salary_revisions')
ON CONFLICT (version) DO NOTHING;

COMMIT;
