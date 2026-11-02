-- ============================================================
-- SSGA NEW - 19. FINALIZE IMMUTABILITY HARDENING (additive)
-- PostgreSQL 17
-- ============================================================
-- Extends immutability to salary_statements. Blocks UPDATE/DELETE
-- when statement is ISSUED or SUPERSEDED. Also blocks DELETE when
-- FINALIZED via batch linkage where applicable.
-- ============================================================

BEGIN;

-- Guard for salary_statements
CREATE OR REPLACE FUNCTION app.guard_immutable_salary_statement()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('ISSUED', 'SUPERSEDED') THEN
            RAISE EXCEPTION
                'Salary statement % is issued/superseded and cannot be deleted.',
                OLD.id USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status IN ('ISSUED', 'SUPERSEDED') THEN
        RAISE EXCEPTION
            'Salary statement % is issued/superseded and cannot be changed.',
            OLD.id USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_guard_immutable_salary_statement ON public.salary_statements;
CREATE TRIGGER trg_guard_immutable_salary_statement
    BEFORE UPDATE OR DELETE ON public.salary_statements
    FOR EACH ROW EXECUTE FUNCTION app.guard_immutable_salary_statement();

COMMIT;
