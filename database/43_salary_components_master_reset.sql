-- ============================================================================
-- 43_salary_components_master_reset.sql
--
-- Replaces the salary component master with the 45 rows from the HMWSSB Pay
-- Roll Summary Abstract (August 2026, Division No. I Charminar):
--   rows  1-15  earnings components
--   row     16  GROSS_EARNINGS        (SUMMARY_TOTAL)
--   rows 17-43  deduction components
--   row     44  TOTAL_DEDUCTIONS      (SUMMARY_TOTAL)
--   row     45  NET_SALARY            (SUMMARY_TOTAL)
--
-- Existing rows are deleted, not merged, per instruction. Every table that
-- references salary_components is empty (employee_salary_values,
-- salary_transaction_lines, salary_component_rules, salary_rule_slabs), so the
-- delete cannot orphan financial data.
--
-- Adds recurrence_type so ad-hoc arrears / one-off recoveries can be switched
-- off per month without a schema change (section "Database Modeling Tip").
--
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Schema: recurrence_type column and SUMMARY_TOTAL category
--
--    The existing category CHECK allows only EARNING / DEDUCTION / STATUTORY,
--    so the three summary rows (16, 44, 45) need it widened.
-- ---------------------------------------------------------------------------
ALTER TABLE public.salary_components
    ADD COLUMN IF NOT EXISTS recurrence_type VARCHAR(32) NOT NULL DEFAULT 'RECURRING_FIXED';

ALTER TABLE public.salary_components
    DROP CONSTRAINT IF EXISTS salary_components_recurrence_type_check;

ALTER TABLE public.salary_components
    ADD CONSTRAINT salary_components_recurrence_type_check
    CHECK (recurrence_type IN (
        'RECURRING_FIXED',
        'RECURRING_FORMULA',
        'INSTALLMENT_LOAN',
        'ONE_TIME_ARREAR_OR_RECOVERY',
        'SUMMARY_TOTAL'
    ));

ALTER TABLE public.salary_components
    DROP CONSTRAINT IF EXISTS salary_components_category_check;

ALTER TABLE public.salary_components
    ADD CONSTRAINT salary_components_category_check
    CHECK (category IN ('EARNING', 'DEDUCTION', 'STATUTORY', 'SUMMARY_TOTAL'));

-- ---------------------------------------------------------------------------
-- 2. Delete the existing master
-- ---------------------------------------------------------------------------
DELETE FROM public.salary_components;

ALTER SEQUENCE public.salary_components_id_seq RESTART WITH 1;

-- ---------------------------------------------------------------------------
-- 3. Seed the 45 components
--
--    display_order follows the sheet row number so the statement renders in
--    register order. calculation_type stays 'FIXED' except where the abstract
--    gives an explicit percentage or slab rule.
-- ---------------------------------------------------------------------------
INSERT INTO public.salary_components
    (component_code, component_name, category, calculation_type, rate,
     recurrence_type, status, display_order)
VALUES
    -- ---- Earnings: sheet rows 1-15 ----------------------------------------
    ('BASIC_PAY',          'Basic Pay',                          'EARNING', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE',  1),
    ('T_SPL_INC',          'Telangana Special Increment',        'EARNING', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE',  2),
    ('FPP',                'Family Planning Pay',                'EARNING', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE',  3),
    ('SPL_PAY_GI',         'Special Pay / Grade Increment',      'EARNING', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE',  4),
    ('DA',                 'Dearness Allowance',                 'EARNING', 'PERCENTAGE', 0.3367, 'RECURRING_FORMULA',            'ACTIVE',  5),
    ('HRA',                'House Rent Allowance',               'EARNING', 'PERCENTAGE', 0.2400, 'RECURRING_FORMULA',            'ACTIVE',  6),
    ('ADDL_HRA',           'Additional House Rent Allowance',    'EARNING', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE',  7),
    ('CCA',                'City Compensatory Allowance',        'EARNING', 'SLAB',       NULL,   'RECURRING_FORMULA',            'ACTIVE',  8),
    ('PETROL_AUTO_ALLOW',  'Petrol / Auto Conveyance Allowance', 'EARNING', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE',  9),
    ('WASHING_ALLOW',      'Washing Allowance',                  'EARNING', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 10),
    ('BOARD_FORM_ALLOW',   'Board Formation Day Allowance',      'EARNING', 'FIXED',      NULL,   'ONE_TIME_ARREAR_OR_RECOVERY',  'ACTIVE', 11),
    ('IR_2023',            'Interim Relief (5% - 2023)',         'EARNING', 'PERCENTAGE', 0.0500, 'RECURRING_FORMULA',            'ACTIVE', 12),
    ('DA_ARR_26_39',       'DA Arrears (26.39% Slab)',           'EARNING', 'FIXED',      NULL,   'ONE_TIME_ARREAR_OR_RECOVERY',  'ACTIVE', 13),
    ('DA_ARR_30_03',       'DA Arrears (30.03% Slab)',           'EARNING', 'FIXED',      NULL,   'ONE_TIME_ARREAR_OR_RECOVERY',  'ACTIVE', 14),
    ('DA_ARR_33_67',       'DA Arrears (33.67% Slab)',           'EARNING', 'FIXED',      NULL,   'ONE_TIME_ARREAR_OR_RECOVERY',  'ACTIVE', 15),

    -- ---- Summary: row 16 --------------------------------------------------
    ('GROSS_EARNINGS',     'Total Gross Earnings',               'SUMMARY_TOTAL', 'SUMMARY', NULL, 'SUMMARY_TOTAL',             'ACTIVE', 16),

    -- ---- Deductions: sheet rows 17-43 -------------------------------------
    ('GPF_EPF',            'General / Employees Provident Fund', 'DEDUCTION', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 17),
    ('GPF_EPF_LOAN',       'GPF / EPF Loan Recovery',            'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 18),
    ('PENSION_2009',       'Pension Scheme (w.e.f. March 2009)', 'DEDUCTION', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 19),
    ('TSGLI',              'Telangana State Group Life Insurance','DEDUCTION','FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 20),
    ('GIS',                'Group Insurance Scheme',             'DEDUCTION', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 21),
    ('PRC_2020_NPS',       'PRC-2020 NPS Deduction',             'DEDUCTION', 'PERCENTAGE', 0.1000, 'RECURRING_FORMULA',           'ACTIVE', 22),
    ('ELEC_CHARGES',       'Electricity Charges Recovery',       'DEDUCTION', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 23),
    ('FESTIVAL_ADV',       'Festival Advance Recovery',          'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 24),
    ('EDN_ADV',            'Education Advance Recovery',         'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 25),
    ('VEHICLE_ADV',        'Vehicle Advance Recovery',           'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 26),
    ('MOPED_ADV',          'Moped Advance Recovery',             'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 27),
    ('EXCESS_SAL_REC',     'Excess Salary Recovery',             'DEDUCTION', 'FIXED',      NULL,   'ONE_TIME_ARREAR_OR_RECOVERY',  'ACTIVE', 28),
    ('EPF_12_PCT',         'EPF Contribution (12% of Pay + DA)', 'DEDUCTION', 'PERCENTAGE', 0.1200, 'RECURRING_FORMULA',           'ACTIVE', 29),
    ('SBI_LIFE_INS',       'SBI Life Insurance Premium',         'DEDUCTION', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 30),
    ('ONE_DAY_SAL',        'One Day Salary Contribution',        'DEDUCTION', 'FIXED',      NULL,   'ONE_TIME_ARREAR_OR_RECOVERY',  'ACTIVE', 31),
    ('MARRIAGE_LOAN',      'Marriage Loan Recovery',             'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 32),
    ('MGB_AN',             'MGB Recovery (A.N)',                 'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 33),
    ('PROF_TAX',           'Professional Tax (PT)',              'DEDUCTION', 'SLAB',       NULL,   'RECURRING_FORMULA',            'ACTIVE', 34),
    ('INCOME_TAX',         'Income Tax (TDS)',                   'DEDUCTION', 'PERCENTAGE', NULL,   'RECURRING_FORMULA',            'ACTIVE', 35),
    ('EPF_PRC_LIC_MISC',   'EPF / PRC Arrears / LIC / BHEL Recovery', 'DEDUCTION', 'FIXED', NULL, 'INSTALLMENT_LOAN',             'ACTIVE', 36),
    ('PRC_EXCESS_REC',     'PRC Excess Amount Recovery',         'DEDUCTION', 'FIXED',      NULL,   'ONE_TIME_ARREAR_OR_RECOVERY',  'ACTIVE', 37),
    ('MGB_PC',             'MGB Recovery (P.C)',                 'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 38),
    ('TG_HARITHA_NIDHI',   'Telangana Haritha Nidhi (Green Fund)','DEDUCTION','FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 39),
    ('DARUSSALAM_BANK_REC','Darussalam Co-op Bank Recovery',     'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 40),
    ('SBI_HIMAYATNAGAR',   'SBI Himayatnagar Recovery',          'DEDUCTION', 'FIXED',      NULL,   'INSTALLMENT_LOAN',             'ACTIVE', 41),
    ('FLAG_DAY_FUND',      'Armed Forces Flag Day Fund',         'DEDUCTION', 'FIXED',      NULL,   'ONE_TIME_ARREAR_OR_RECOVERY',  'ACTIVE', 42),
    ('LIC_PREMIUM',        'LIC Salary Savings Scheme Premium',  'DEDUCTION', 'FIXED',      NULL,   'RECURRING_FIXED',              'ACTIVE', 43),

    -- ---- Summary: row 44 --------------------------------------------------
    ('TOTAL_DEDUCTIONS',   'Total Deductions',                   'SUMMARY_TOTAL', 'SUMMARY', NULL, 'SUMMARY_TOTAL',               'ACTIVE', 44),

    -- ---- Summary: row 45 --------------------------------------------------
    ('NET_SALARY',         'Net Payable Salary',                 'SUMMARY_TOTAL', 'SUMMARY', NULL, 'SUMMARY_TOTAL',               'ACTIVE', 45);

-- ---------------------------------------------------------------------------
-- 4. Self-check
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_total int;
    v_earn  int;
    v_ded   int;
    v_sum   int;
BEGIN
    SELECT count(*) INTO v_total FROM public.salary_components;
    SELECT count(*) INTO v_earn  FROM public.salary_components WHERE category = 'EARNING';
    SELECT count(*) INTO v_ded   FROM public.salary_components WHERE category = 'DEDUCTION';
    SELECT count(*) INTO v_sum   FROM public.salary_components WHERE category = 'SUMMARY_TOTAL';

    IF v_total <> 45 THEN
        RAISE EXCEPTION '43_salary_components_master_reset: expected 45 components, found %', v_total;
    END IF;
    IF v_earn <> 15 THEN
        RAISE EXCEPTION '43_salary_components_master_reset: expected 15 earnings, found %', v_earn;
    END IF;
    IF v_ded <> 27 THEN
        RAISE EXCEPTION '43_salary_components_master_reset: expected 27 deductions, found %', v_ded;
    END IF;
    IF v_sum <> 3 THEN
        RAISE EXCEPTION '43_salary_components_master_reset: expected 3 summary rows, found %', v_sum;
    END IF;

    -- the three summary rows must be the last three in display order
    IF (SELECT min(display_order) FROM public.salary_components
         WHERE category = 'SUMMARY_TOTAL') <> 16 THEN
        RAISE EXCEPTION '43_salary_components_master_reset: GROSS_EARNINGS must be display_order 16';
    END IF;

    RAISE NOTICE '43_salary_components_master_reset OK: % components (15 earning, 27 deduction, 3 summary)', v_total;
END
$$;

COMMIT;