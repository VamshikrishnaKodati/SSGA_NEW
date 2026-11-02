-- ============================================================================
-- 18_payroll_finalization_audit.sql
--
-- Records who finalized a payroll batch and when, and makes the finalization
-- separation explicit. Approval and finalization are separate controls: the
-- approver recorded in checker_id cannot also finalize the batch (enforced by
-- the application workflow), and the finalized-by pair is immutable while the
-- batch stays FINALIZED.
--
-- Additive and idempotent. Existing finalized batches keep NULL audit columns;
-- no historical result is rewritten.
-- ============================================================================

BEGIN;

ALTER TABLE public.payroll_batches
    ADD COLUMN IF NOT EXISTS finalized_by BIGINT
        REFERENCES public.users(id),
    ADD COLUMN IF NOT EXISTS finalized_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'payroll_batches_finalizer_pair_check'
    ) THEN
        ALTER TABLE public.payroll_batches
            ADD CONSTRAINT payroll_batches_finalizer_pair_check
            CHECK ((finalized_by IS NULL) = (finalized_at IS NULL));
    END IF;
END;
$$;

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
         OR NEW.division_name_snapshot IS DISTINCT FROM OLD.division_name_snapshot
         OR NEW.finalized_by IS DISTINCT FROM OLD.finalized_by
         OR NEW.finalized_at IS DISTINCT FROM OLD.finalized_at) THEN
        RAISE EXCEPTION
            'Payroll batch % is finalized; only its status may change while reopening.',
            OLD.id USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$function$;

INSERT INTO public.schema_migrations (version, name)
VALUES (18, '18_payroll_finalization_audit')
ON CONFLICT (version) DO NOTHING;

COMMIT;
