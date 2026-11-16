-- ============================================================================
-- 39_allow_uploader_submit.sql
-- The preparer (uploader) may submit their own import job; independence is
-- required for approval and finalization. Migration 20's
-- submitter <> uploader clause contradicted the workflow service, the UI and
-- the documented flow, and rejected a normal submit with a raw 500.
-- The approval and finalization separation checks are unchanged.
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

CREATE OR REPLACE FUNCTION app.guard_import_job_workflow()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
BEGIN
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

INSERT INTO public.schema_migrations (version, name)
VALUES (39, '39_allow_uploader_submit')
ON CONFLICT (version) DO NOTHING;

COMMIT;
