-- ============================================================================
-- 17_update_salary_components.sql - Update component categories for HMWSSB
-- Some deductions are STATUTORY (PF, TDS, Professional Tax, etc.)
-- Run after 15_hmwssb_schema.sql and 16_seed_hmwssb_divisions.sql
-- ============================================================================

BEGIN;

-- Update statutory deductions
UPDATE public.salary_components
SET category = 'STATUTORY',
    updated_at = NOW()
WHERE component_code IN (
    'GPF_EPF',           -- GPF/EPF - Statutory PF
    'PENSION_SCHEME',    -- Pension Scheme
    'TSGLIF',            -- TSGLIF - Group Insurance
    'GIS',               -- GIS - Group Insurance
    'PRC_2020_NPS',      -- NPS - Statutory pension
    'PROF_TAX',          -- Professional Tax
    'INCOME_TAX',        -- Income Tax (TDS)
    'EPF_12_PERCENT',    -- EPF 12%
    'EPF_PRC_ARREARS',   -- EPF Arrears
    'TELANGANA_HARITHA_NIDHI',  -- Telangana Haritha Nidhi
    'FLAG_DAY_2025'      -- Flag Day contribution
)
AND status = 'ACTIVE';

-- Verify
-- SELECT component_code, component_name, category FROM public.salary_components ORDER BY display_order;

COMMIT;