-- ============================================================
-- SSGA NEW - 23. ADDITIONAL IMPORT VALIDATIONS (additive)
-- PostgreSQL 17
-- ============================================================

BEGIN;

-- Prevent duplicate active import jobs for same file hash by same user
CREATE UNIQUE INDEX IF NOT EXISTS ux_import_jobs_active_hash_user
    ON public.import_jobs (file_sha256, uploaded_by)
    WHERE status NOT IN ('FAILED', 'REJECTED', 'IMPORTED');

COMMIT;
