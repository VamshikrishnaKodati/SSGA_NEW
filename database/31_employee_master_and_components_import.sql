-- ============================================================================
-- 31_employee_master_and_components_import.sql
--
-- Completes the EMPLOYEE_MASTER_AND_COMPONENTS import type that the mapping
-- service, auto-mapper and UI already advertise:
--   1. The confirmed-type check constraint rejected the type, causing a 500
--      when saving a mapping.
--   2. The client workbook uses 30 component codes that are not in the
--      seeded 42-code catalogue. They are added as ACTIVE components so the
--      wide component columns can be mapped and imported.
--
-- Idempotent: safe to run more than once.
-- ============================================================================

BEGIN;

ALTER TABLE public.import_job_sheets
    DROP CONSTRAINT IF EXISTS import_job_sheets_confirmed_type_check;

ALTER TABLE public.import_job_sheets
    ADD CONSTRAINT import_job_sheets_confirmed_type_check
    CHECK (
        confirmed_type IS NULL
        OR confirmed_type IN (
            'EMPLOYEE_MASTER',
            'EMPLOYEE_MASTER_AND_COMPONENTS',
            'SALARY_ENTITLEMENTS',
            'PAYROLL_HISTORY',
            'SALARY_REVISIONS',
            'REFERENCE_ONLY',
            'SKIP'
        )
    );

INSERT INTO public.salary_components
    (component_code, component_name, category, calculation_type,
     status, display_order)
VALUES
    ('MEDICAL_ALLOWANCE',    'Medical Allowance',    'EARNING',   'FIXED', 'ACTIVE', 43),
    ('CONVEYANCE_ALLOWANCE', 'Conveyance Allowance', 'EARNING',   'FIXED', 'ACTIVE', 44),
    ('WASHING_ALLOWANCE',    'Washing Allowance',    'EARNING',   'FIXED', 'ACTIVE', 45),
    ('SPECIAL_ALLOWANCE',    'Special Allowance',    'EARNING',   'FIXED', 'ACTIVE', 46),
    ('INTERIM_RELIEF',       'Interim Relief',       'EARNING',   'FIXED', 'ACTIVE', 47),
    ('OTHER_EARNINGS',       'Other Earnings',       'EARNING',   'FIXED', 'ACTIVE', 48),
    ('NPS',                  'NPS',                  'DEDUCTION', 'FIXED', 'ACTIVE', 49),
    ('GPF',                  'GPF',                  'DEDUCTION', 'FIXED', 'ACTIVE', 50),
    ('PROFESSIONAL_TAX',     'Professional Tax',     'DEDUCTION', 'FIXED', 'ACTIVE', 51),
    ('CGEGIS',               'CGEGIS',               'DEDUCTION', 'FIXED', 'ACTIVE', 52),
    ('EHF',                  'EHF',                  'DEDUCTION', 'FIXED', 'ACTIVE', 53),
    ('HOUSE_RENT_RECOVERY',  'House Rent Recovery',  'DEDUCTION', 'FIXED', 'ACTIVE', 54),
    ('FESTIVAL_ADVANCE',     'Festival Advance',     'DEDUCTION', 'FIXED', 'ACTIVE', 55),
    ('VEHICLE_ADVANCE',      'Vehicle Advance',      'DEDUCTION', 'FIXED', 'ACTIVE', 56),
    ('COMPUTER_ADVANCE',     'Computer Advance',     'DEDUCTION', 'FIXED', 'ACTIVE', 57),
    ('HBA_RECOVERY',         'HBA Recovery',         'DEDUCTION', 'FIXED', 'ACTIVE', 58),
    ('MOTOR_CAR_RECOVERY',   'Motor Car Recovery',   'DEDUCTION', 'FIXED', 'ACTIVE', 59),
    ('CYCLE_RECOVERY',       'Cycle Recovery',       'DEDUCTION', 'FIXED', 'ACTIVE', 60),
    ('COURT_ATTACHMENT',     'Court Attachment',     'DEDUCTION', 'FIXED', 'ACTIVE', 61),
    ('UNION_SUBSCRIPTION',   'Union Subscription',   'DEDUCTION', 'FIXED', 'ACTIVE', 62),
    ('WELFARE_FUND',         'Welfare Fund',         'DEDUCTION', 'FIXED', 'ACTIVE', 63),
    ('MEDICAL_RECOVERY',     'Medical Recovery',     'DEDUCTION', 'FIXED', 'ACTIVE', 64),
    ('LOAN_RECOVERY_1',      'Loan Recovery 1',      'DEDUCTION', 'FIXED', 'ACTIVE', 65),
    ('LOAN_RECOVERY_2',      'Loan Recovery 2',      'DEDUCTION', 'FIXED', 'ACTIVE', 66),
    ('OTHER_RECOVERY_1',     'Other Recovery 1',     'DEDUCTION', 'FIXED', 'ACTIVE', 67),
    ('OTHER_RECOVERY_2',     'Other Recovery 2',     'DEDUCTION', 'FIXED', 'ACTIVE', 68),
    ('OTHER_RECOVERY_3',     'Other Recovery 3',     'DEDUCTION', 'FIXED', 'ACTIVE', 69),
    ('OTHER_DEDUCTION_1',    'Other Deduction 1',    'DEDUCTION', 'FIXED', 'ACTIVE', 70),
    ('OTHER_DEDUCTION_2',    'Other Deduction 2',    'DEDUCTION', 'FIXED', 'ACTIVE', 71),
    ('OTHER_DEDUCTION_3',    'Other Deduction 3',    'DEDUCTION', 'FIXED', 'ACTIVE', 72)
ON CONFLICT (component_code) DO NOTHING;

COMMIT;
