-- ============================================================
-- SSGA NEW - 20. IMPORT INTEGRITY HARDENING (additive)
-- PostgreSQL 17
-- ============================================================
-- Strengthen import_jobs separation/triggers coverage and add
-- checks to prevent self-approval/finalization via DB where possible.
-- ============================================================

BEGIN;

-- Ensure maker-checker cannot be same user at DB level for workflow transitions
CREATE OR REPLACE FUNCTION app.guard_import_job_workflow()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status = 'AWAITING_APPROVAL' AND OLD.status IN ('VALIDATED', 'HAS_ERRORS') THEN
        IF NEW.submitted_by IS NOT NULL AND NEW.submitted_by = OLD.uploaded_by THEN
            RAISE EXCEPTION 'Maker-checker violation: submitter cannot be the uploader.' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.status = 'APPROVED' AND OLD.status = 'AWAITING_APPROVAL' THEN
        IF NEW.approved_by IS NOT NULL AND OLD.submitted_by IS NOT NULL AND NEW.approved_by = OLD.submitted_by THEN
            RAISE EXCEPTION 'Maker-checker violation: approver cannot be the submitter.' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.approved_by IS NOT NULL AND OLD.uploaded_by IS NOT NULL AND NEW.approved_by = OLD.uploaded_by THEN
            RAISE EXCEPTION 'Maker-checker violation: approver cannot be the uploader.' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.status = 'IMPORTING' AND OLD.status = 'APPROVED' THEN
        IF NEW.finalized_by IS NOT NULL AND OLD.approved_by IS NOT NULL AND NEW.finalized_by = OLD.approved_by THEN
            RAISE EXCEPTION 'Maker-checker violation: finalizer cannot be the approver.' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.finalized_by IS NOT NULL AND OLD.submitted_by IS NOT NULL AND NEW.finalized_by = OLD.submitted_by THEN
            RAISE EXCEPTION 'Maker-checker violation: finalizer cannot be the submitter.' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.finalized_by IS NOT NULL AND OLD.uploaded_by IS NOT NULL AND NEW.finalized_by = OLD.uploaded_by THEN
            RAISE EXCEPTION 'Maker-checker violation: finalizer cannot be the uploader.' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_guard_import_job_workflow ON public.import_jobs;
CREATE TRIGGER trg_guard_import_job_workflow
    BEFORE UPDATE ON public.import_jobs
    FOR EACH ROW EXECUTE FUNCTION app.guard_import_job_workflow();

COMMIT;
