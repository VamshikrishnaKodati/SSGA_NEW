-- ============================================================
-- SSGA NEW - 24. SALARY VALUE AMOUNT CHECK (additive)
-- PostgreSQL 17
-- ============================================================

BEGIN;

-- NOT VALID: enforced for new writes without failing on legacy rows.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_employee_salary_values_amount_nonnegative'
          AND conrelid = 'public.employee_salary_values'::regclass
    ) THEN
        ALTER TABLE public.employee_salary_values
            ADD CONSTRAINT chk_employee_salary_values_amount_nonnegative
            CHECK (amount >= 0) NOT VALID;
    END IF;
END
$$;

COMMIT;
