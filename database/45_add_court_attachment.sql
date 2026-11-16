-- ============================================================================
-- 45_add_court_attachment.sql
--
-- Adds the Court Attachment deduction that the August 2026 Pay Roll Summary
-- Abstract prints as row 18 ("COURT ATCH", Rs 80,888 in Sheet 1).
--
-- Migration 43 built the 45-row register from the 27-line deduction list in the
-- specification, which omitted this row. Adding it makes the deduction block
-- 28 lines, so the register becomes 46 rows: rows 1-15 earnings, row 16 gross,
-- rows 17-44 deductions, row 45 total deductions, row 46 net salary.
--
-- Rows 45 and 46 are shifted by one so TOTAL_DEDUCTIONS and NET_SALARY stay
-- last. Any statement generated before this migration keeps its own
-- component_code_snapshot, so history is unaffected.
--
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Normalise the trailing summary rows to their final positions
--
--    Uses absolute targets rather than a relative shift so a re-run cannot
--    double-advance the rows.
-- ---------------------------------------------------------------------------
UPDATE public.salary_components
   SET display_order = 46
 WHERE component_code = 'NET_SALARY';

UPDATE public.salary_components
   SET display_order = 45
 WHERE component_code = 'TOTAL_DEDUCTIONS';

-- ---------------------------------------------------------------------------
-- 2. Insert the Court Attachment row at 44
-- ---------------------------------------------------------------------------
INSERT INTO public.salary_components
    (component_code, component_name, category, calculation_type, rate,
     recurrence_type, status, display_order)
VALUES
    ('COURT_ATCH', 'Court Attachment', 'DEDUCTION', 'FIXED', NULL,
     'INSTALLMENT_LOAN', 'ACTIVE', 44)
ON CONFLICT (component_code) DO UPDATE
    SET component_name = EXCLUDED.component_name,
        category       = EXCLUDED.category,
        recurrence_type = EXCLUDED.recurrence_type,
        status         = 'ACTIVE',
        display_order  = EXCLUDED.display_order;

-- ---------------------------------------------------------------------------
-- 3. Self-check
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_total int;
    v_ded   int;
    v_gross int;
    v_last  int;
BEGIN
    SELECT count(*) INTO v_total FROM public.salary_components;
    SELECT count(*) INTO v_ded   FROM public.salary_components WHERE category = 'DEDUCTION';

    IF v_total <> 46 THEN
        RAISE EXCEPTION '45_add_court_attachment: expected 46 components, found %', v_total;
    END IF;
    IF v_ded <> 28 THEN
        RAISE EXCEPTION '45_add_court_attachment: expected 28 deductions, found %', v_ded;
    END IF;

    -- GROSS_EARNINGS stays at 16; NET_SALARY must be the last row
    SELECT display_order INTO v_gross
      FROM public.salary_components WHERE component_code = 'GROSS_EARNINGS';
    IF v_gross <> 16 THEN
        RAISE EXCEPTION '45_add_court_attachment: GROSS_EARNINGS moved to %', v_gross;
    END IF;

    SELECT max(display_order) INTO v_last FROM public.salary_components;
    IF NOT EXISTS (SELECT 1 FROM public.salary_components
                    WHERE component_code = 'NET_SALARY' AND display_order = v_last) THEN
        RAISE EXCEPTION '45_add_court_attachment: NET_SALARY is not the final row';
    END IF;

    -- display_order must be contiguous from 1, or the register renders with gaps
    IF EXISTS (
        SELECT 1 FROM public.salary_components
        GROUP BY display_order HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION '45_add_court_attachment: duplicate display_order values';
    END IF;

    IF (SELECT count(DISTINCT display_order) FROM public.salary_components) <> v_total THEN
        RAISE EXCEPTION '45_add_court_attachment: display_order is not contiguous';
    END IF;

    RAISE NOTICE '45_add_court_attachment OK: % components, % deductions', v_total, v_ded;
END
$$;

COMMIT;