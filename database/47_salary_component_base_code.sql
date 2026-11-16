-- ============================================================================
-- 47_salary_component_base_code.sql
--
-- Adds salary_components.base_code and seeds the five percentage components so
-- the Java engine can compute them without hard-coding the base in source.
--
-- base_code values:
--   BASIC_PAY     - the employee's basic pay alone
--   BASIC_PLUS_DA - basic pay plus the DA amount already resolved for the row
--
-- Only the five rate-bearing PERCENTAGE components get a base. CCA and
-- PROF_TAX are SLAB and INCOME_TAX has no rate yet, so they stay NULL; the
-- engine refuses to compute them and requires an explicit imported amount.
--
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

ALTER TABLE public.salary_components
    ADD COLUMN IF NOT EXISTS base_code character varying(30);

COMMENT ON COLUMN public.salary_components.base_code IS
    'What a PERCENTAGE component''s rate is applied to: BASIC_PAY or BASIC_PLUS_DA. NULL for FIXED, SLAB and SUMMARY rows.';

UPDATE public.salary_components
   SET base_code = 'BASIC_PAY'
 WHERE component_code IN ('DA', 'HRA', 'IR_2023');

UPDATE public.salary_components
   SET base_code = 'BASIC_PLUS_DA'
 WHERE component_code IN ('PRC_2020_NPS', 'EPF_12_PCT');

-- ---------------------------------------------------------------------------
-- Self-check
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_missing int;
BEGIN
    SELECT count(*) INTO v_missing
      FROM public.salary_components
     WHERE component_code IN ('DA', 'HRA', 'IR_2023', 'PRC_2020_NPS', 'EPF_12_PCT')
       AND base_code IS NULL;

    IF v_missing > 0 THEN
        RAISE EXCEPTION '47_salary_component_base_code: % percentage components still have no base_code', v_missing;
    END IF;

    -- Every row that declares a base must also declare a rate, or the engine
    -- would divide by an unknown percentage.
    IF EXISTS (
        SELECT 1 FROM public.salary_components
        WHERE base_code IS NOT NULL
          AND (rate IS NULL OR calculation_type <> 'PERCENTAGE')
    ) THEN
        RAISE EXCEPTION '47_salary_component_base_code: a base_code row is not a rated PERCENTAGE component';
    END IF;

    -- And the two bases used above must be the only ones present.
    IF EXISTS (
        SELECT 1 FROM public.salary_components
        WHERE base_code IS NOT NULL
          AND base_code NOT IN ('BASIC_PAY', 'BASIC_PLUS_DA')
    ) THEN
        RAISE EXCEPTION '47_salary_component_base_code: unknown base_code value';
    END IF;
END
$$;

SELECT component_code, calculation_type, rate, base_code
  FROM public.salary_components
 WHERE base_code IS NOT NULL
 ORDER BY component_code;

COMMIT;