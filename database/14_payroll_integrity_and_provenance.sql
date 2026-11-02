-- ============================================================
-- SSGA NEW - 14. PAYROLL INTEGRITY AND IMPORT PROVENANCE (additive)
-- PostgreSQL 17
-- ============================================================
--
-- Hardening for payroll correctness and data protection. Nothing here
-- removes or rewrites existing records; every change is additive.
--
-- 1. Division name snapshot on the payroll batch. A finalized statement must
--    not change because the division was renamed after the batch was
--    generated, so the name is captured when the batch is created.
--
-- 2. Import provenance on imported salary values: which job, sheet and row
--    produced each value.
--
-- 3. Import reconciliation totals on the import job (what was actually
--    imported, split by earnings and deductions).
--
-- 4. Finalized payroll is immutable at the database level, so a bug or a
--    direct SQL session cannot rewrite an issued payroll.
-- ============================================================

BEGIN;

-- ------------------------------------------------------------------
-- 1. Division name snapshot
-- ------------------------------------------------------------------
ALTER TABLE public.payroll_batches
    ADD COLUMN IF NOT EXISTS division_name_snapshot VARCHAR(150);

UPDATE public.payroll_batches b
   SET division_name_snapshot = d.name
  FROM public.divisions d
 WHERE d.id = b.division_id
   AND b.division_name_snapshot IS NULL;

ALTER TABLE public.payroll_batches
    ALTER COLUMN division_name_snapshot SET NOT NULL;

-- ------------------------------------------------------------------
-- 2. Import provenance
-- ------------------------------------------------------------------
ALTER TABLE public.employee_salary_values
    ADD COLUMN IF NOT EXISTS import_job_id BIGINT
        REFERENCES public.import_jobs(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS import_job_sheet_id BIGINT
        REFERENCES public.import_job_sheets(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS import_row_number INTEGER;

CREATE INDEX IF NOT EXISTS ix_employee_salary_values_import_job_v14
    ON public.employee_salary_values(import_job_id);

-- ------------------------------------------------------------------
-- 3. Import reconciliation totals
-- ------------------------------------------------------------------
ALTER TABLE public.import_jobs
    ADD COLUMN IF NOT EXISTS imported_amount_total NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS imported_earnings_total NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS imported_deductions_total NUMERIC(18,2);

-- ------------------------------------------------------------------
-- 4. Finalized payroll is immutable
-- ------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.guard_finalized_payroll_batch()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'FINALIZED' THEN
            RAISE EXCEPTION
                'Payroll batch % is finalized and locked; it cannot be deleted.',
                OLD.id USING ERRCODE = 'check_violation';
        END IF;

        RETURN OLD;
    END IF;

    IF OLD.status = 'FINALIZED' AND NEW.status <> 'REOPENED' THEN
        RAISE EXCEPTION
            'Payroll batch % is finalized and locked; reopen it before changing it.',
            OLD.id USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'FINALIZED'
       AND (NEW.period_id IS DISTINCT FROM OLD.period_id
         OR NEW.division_id IS DISTINCT FROM OLD.division_id
         OR NEW.wing_id IS DISTINCT FROM OLD.wing_id
         OR NEW.run_type IS DISTINCT FROM OLD.run_type
         OR NEW.run_no IS DISTINCT FROM OLD.run_no
         OR NEW.employee_count IS DISTINCT FROM OLD.employee_count
         OR NEW.gross_total IS DISTINCT FROM OLD.gross_total
         OR NEW.deduction_total IS DISTINCT FROM OLD.deduction_total
         OR NEW.net_total IS DISTINCT FROM OLD.net_total
         OR NEW.division_name_snapshot IS DISTINCT FROM OLD.division_name_snapshot) THEN
        RAISE EXCEPTION
            'Payroll batch % is finalized; only its status may change while reopening.',
            OLD.id USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_guard_finalized_payroll_batch
    ON public.payroll_batches;

CREATE TRIGGER trg_guard_finalized_payroll_batch
    BEFORE UPDATE OR DELETE ON public.payroll_batches
    FOR EACH ROW EXECUTE FUNCTION app.guard_finalized_payroll_batch();

CREATE OR REPLACE FUNCTION app.guard_finalized_salary_transaction()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
DECLARE
    v_status VARCHAR(20);
    v_id BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_id := OLD.id;
    ELSE
        v_id := NEW.id;
    END IF;

    SELECT b.status INTO v_status
      FROM public.salary_transactions st
      JOIN public.payroll_batches b ON b.id = st.payroll_batch_id
     WHERE st.id = v_id;

    IF v_status = 'FINALIZED' THEN
        RAISE EXCEPTION
            'Salary transaction % belongs to a finalized payroll batch and cannot be changed.',
            v_id USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_guard_finalized_salary_transactions
    ON public.salary_transactions;

CREATE TRIGGER trg_guard_finalized_salary_transactions
    BEFORE UPDATE OR DELETE ON public.salary_transactions
    FOR EACH ROW EXECUTE FUNCTION app.guard_finalized_salary_transaction();

CREATE OR REPLACE FUNCTION app.guard_finalized_salary_transaction_line()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
DECLARE
    v_status VARCHAR(20);
    v_id BIGINT;
    v_transaction_id BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_id := OLD.id;
        v_transaction_id := OLD.salary_transaction_id;
    ELSE
        v_id := NEW.id;
        v_transaction_id := NEW.salary_transaction_id;
    END IF;

    SELECT b.status INTO v_status
      FROM public.salary_transactions st
      JOIN public.payroll_batches b ON b.id = st.payroll_batch_id
     WHERE st.id = v_transaction_id;

    IF v_status = 'FINALIZED' THEN
        RAISE EXCEPTION
            'Salary transaction line % belongs to a finalized payroll batch and cannot be changed.',
            v_id USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_guard_finalized_salary_transaction_lines
    ON public.salary_transaction_lines;

CREATE TRIGGER trg_guard_finalized_salary_transaction_lines
    BEFORE UPDATE OR DELETE ON public.salary_transaction_lines
    FOR EACH ROW EXECUTE FUNCTION app.guard_finalized_salary_transaction_line();

COMMIT;
