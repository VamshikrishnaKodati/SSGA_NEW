-- ============================================================
-- SSGA NEW - 21. PAYROLL INTEGRITY HARDENING (additive)
-- PostgreSQL 17
-- ============================================================

BEGIN;

CREATE OR REPLACE FUNCTION app.guard_payroll_workflow()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status = 'APPROVED' AND OLD.status = 'SUBMITTED' THEN
        IF NEW.checker_id IS NOT NULL AND OLD.maker_id IS NOT NULL AND NEW.checker_id = OLD.maker_id THEN
            RAISE EXCEPTION 'Maker-checker violation: checker cannot be the maker.' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.status = 'FINALIZED' AND OLD.status = 'APPROVED' THEN
        IF NEW.finalized_by IS NOT NULL AND OLD.maker_id IS NOT NULL AND NEW.finalized_by = OLD.maker_id THEN
            RAISE EXCEPTION 'Maker-checker violation: finalizer cannot be the maker.' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.finalized_by IS NOT NULL AND OLD.checker_id IS NOT NULL AND NEW.finalized_by = OLD.checker_id THEN
            RAISE EXCEPTION 'Maker-checker violation: finalizer cannot be the checker.' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_guard_payroll_workflow ON public.payroll_batches;
CREATE TRIGGER trg_guard_payroll_workflow
    BEFORE UPDATE ON public.payroll_batches
    FOR EACH ROW EXECUTE FUNCTION app.guard_payroll_workflow();

COMMIT;
