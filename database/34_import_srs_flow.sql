-- ============================================================================
-- 34_import_srs_flow.sql - SRS import flow support
-- Adds: corrected workbook pointer, draft-import audit columns, DRAFT_IMPORTED
-- ============================================================================

BEGIN;

ALTER TABLE public.import_jobs
    ADD COLUMN IF NOT EXISTS corrected_file_key text,
    ADD COLUMN IF NOT EXISTS corrected_file_sha256 character(64),
    ADD COLUMN IF NOT EXISTS draft_imported_by bigint REFERENCES public.users(id),
    ADD COLUMN IF NOT EXISTS draft_imported_at timestamp with time zone;

ALTER TABLE public.import_jobs
    DROP CONSTRAINT IF EXISTS import_jobs_corrected_file_sha256_check;

ALTER TABLE public.import_jobs
    ADD CONSTRAINT import_jobs_corrected_file_sha256_check
    CHECK (corrected_file_sha256 IS NULL
           OR corrected_file_sha256 ~ '^[0-9a-fA-F]{64}$');

ALTER TABLE public.import_jobs
    DROP CONSTRAINT IF EXISTS import_jobs_status_check;

ALTER TABLE public.import_jobs
    ADD CONSTRAINT import_jobs_status_check
    CHECK (status::text = ANY (ARRAY[
        'UPLOADED', 'VALIDATING', 'VALIDATED', 'HAS_ERRORS',
        'DRAFT_IMPORTED', 'AWAITING_APPROVAL', 'APPROVED', 'IMPORTING',
        'PARTIALLY_IMPORTED', 'IMPORTED', 'FAILED', 'CANCELLED'
    ]::text[]));

COMMIT;
