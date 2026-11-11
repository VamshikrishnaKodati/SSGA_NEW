-- ============================================================================
-- 15_hmwssb_schema.sql - HMWSSB Bulk Salary Processing Schema
-- PostgreSQL 17 | Additive migration for HMWSSB-specific tables
-- Run after 01_schema.sql and 08_import_resolution_workflow.sql
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. HMWSSB Divisions Lookup (All 48 Administrative Divisions)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.hmwssb_divisions (
    id              BIGSERIAL PRIMARY KEY,
    division_code   VARCHAR(50)  UNIQUE NOT NULL,
    division_name   VARCHAR(150) NOT NULL,
    category        VARCHAR(50)  NOT NULL,  -- 'O&M Division', 'Project Division', 'O&M Circle', 'PCC', 'Transmission Circle', 'STP Circle', 'Electrical Division', 'Quality Vigilance', 'Central Stores', 'Board Office'
    parent_group    VARCHAR(50),            -- 'OM', 'PROJECTS', 'TRANSMISSION', 'STP_ELECTRICAL', 'QUALITY_STORES', 'BOARD_ADMIN'
    display_order   SMALLINT,
    status          VARCHAR(20)  DEFAULT 'ACTIVE' NOT NULL,
    verification_status VARCHAR(30) DEFAULT 'CONFIRMED',
    verification_note TEXT,
    created_at      TIMESTAMPTZ  DEFAULT NOW() NOT NULL,
    updated_at      TIMESTAMPTZ  DEFAULT NOW() NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_hmwssb_divisions_category ON public.hmwssb_divisions(category);
CREATE INDEX IF NOT EXISTS ix_hmwssb_divisions_status ON public.hmwssb_divisions(status);

-- ---------------------------------------------------------------------------
-- 2. Employee Master (No mandatory Employee Code; relies on Unique Identifiers)
-- ---------------------------------------------------------------------------
-- We add new columns to existing employees table and make employee_code/hrms_id nullable
-- This is an ALTER approach to preserve existing data

-- Make employee_code nullable (it currently has NOT NULL)
ALTER TABLE public.employees ALTER COLUMN employee_code DROP NOT NULL;

-- Make hrms_id nullable (it currently has NOT NULL and CHECK constraint)
ALTER TABLE public.employees ALTER COLUMN hrms_id DROP NOT NULL;

-- Drop the hrms_id check constraint that enforces format
ALTER TABLE public.employees DROP CONSTRAINT IF EXISTS employees_hrms_id_check;

-- Add HMWSSB-specific columns
ALTER TABLE public.employees
    ADD COLUMN IF NOT EXISTS aadhaar_no     VARCHAR(12) UNIQUE,
    ADD COLUMN IF NOT EXISTS pan_no         VARCHAR(10)  UNIQUE,
    ADD COLUMN IF NOT EXISTS contact_no     VARCHAR(15)  UNIQUE,
    ADD COLUMN IF NOT EXISTS email          VARCHAR(150),
    ADD COLUMN IF NOT EXISTS pf_account_no  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS nps_id         VARCHAR(50),
    ADD COLUMN IF NOT EXISTS gpf_account_no VARCHAR(50),
    ADD COLUMN IF NOT EXISTS bank_account_no VARCHAR(30),
    ADD COLUMN IF NOT EXISTS ifsc_code      VARCHAR(15),
    ADD COLUMN IF NOT EXISTS bank_name      VARCHAR(150),
    ADD COLUMN IF NOT EXISTS branch_name    VARCHAR(150),
    ADD COLUMN IF NOT EXISTS hmwssb_division_id BIGINT REFERENCES public.hmwssb_divisions(id),
    ADD COLUMN IF NOT EXISTS is_temporary_id BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS pay_scale      VARCHAR(50),
    ADD COLUMN IF NOT EXISTS basic_pay      NUMERIC(12,2) DEFAULT 0.00,
    ADD COLUMN IF NOT EXISTS date_of_joining DATE,
    ADD COLUMN IF NOT EXISTS date_of_birth   DATE;

-- Indexes for identity resolution priority: HRMS_ID -> Aadhaar -> PAN -> Contact
CREATE INDEX IF NOT EXISTS ix_employees_hrms_id ON public.employees(hrms_id) WHERE hrms_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_employees_aadhaar ON public.employees(aadhaar_no) WHERE aadhaar_no IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_employees_pan ON public.employees(pan_no) WHERE pan_no IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_employees_contact ON public.employees(contact_no) WHERE contact_no IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_employees_hmwssb_div ON public.employees(hmwssb_division_id);

-- ---------------------------------------------------------------------------
-- 3. Salary Components (Ensure STATUTORY type exists)
-- ---------------------------------------------------------------------------
-- The existing salary_components table has category CHECK for EARNING/DEDUCTION only
-- We need to add STATUTORY type for things like PF, TDS, etc.

ALTER TABLE public.salary_components
    DROP CONSTRAINT IF EXISTS salary_components_category_check;

ALTER TABLE public.salary_components
    ADD CONSTRAINT salary_components_category_check
    CHECK (category IN ('EARNING', 'DEDUCTION', 'STATUTORY'));

-- Add is_active column if not exists
ALTER TABLE public.salary_components
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT TRUE;

-- ---------------------------------------------------------------------------
-- 4. Staging Payroll Header (Bulk Import Batch)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.staging_payroll_header (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_number        VARCHAR(50) UNIQUE NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    file_hash           VARCHAR(64)  NOT NULL,
    uploaded_by         BIGINT NOT NULL REFERENCES public.users(id),
    status              VARCHAR(30) DEFAULT 'DRAFT_MAPPED' NOT NULL,
    total_records       INT DEFAULT 0,
    valid_records       INT DEFAULT 0,
    error_records       INT DEFAULT 0,
    temp_id_count       INT DEFAULT 0,
    total_gross_payout  NUMERIC(15,2) DEFAULT 0.00,
    total_deductions    NUMERIC(15,2) DEFAULT 0.00,
    total_net_payout    NUMERIC(15,2) DEFAULT 0.00,
    payroll_month       INT CHECK (payroll_month BETWEEN 1 AND 12),
    payroll_year        INT CHECK (payroll_year >= 2020),
    source_type         VARCHAR(30),  -- 'EMPLOYEE_MASTER_AND_COMPONENTS', 'SALARY_ENTITLEMENTS', etc.
    import_job_id       BIGINT REFERENCES public.import_jobs(id),
    created_at          TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at          TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    submitted_by        BIGINT REFERENCES public.users(id),
    submitted_at        TIMESTAMPTZ,
    verified_by         BIGINT REFERENCES public.users(id),
    verified_at         TIMESTAMPTZ,
    approved_by         BIGINT REFERENCES public.users(id),
    approved_at         TIMESTAMPTZ,
    rejected_by         BIGINT REFERENCES public.users(id),
    rejected_at         TIMESTAMPTZ,
    rejection_reason    TEXT
);

-- Status enum-like check
ALTER TABLE public.staging_payroll_header
    ADD CONSTRAINT chk_staging_header_status
    CHECK (status IN ('DRAFT_MAPPED', 'VALIDATING', 'IN_RESOLUTION', 'VERIFIED_SO', 'PENDING_GM_APPROVAL', 'FINAL_APPROVED', 'REJECTED'));

CREATE INDEX IF NOT EXISTS ix_staging_header_status ON public.staging_payroll_header(status);
CREATE INDEX IF NOT EXISTS ix_staging_header_import_job ON public.staging_payroll_header(import_job_id);
CREATE INDEX IF NOT EXISTS ix_staging_header_period ON public.staging_payroll_header(payroll_year, payroll_month);

-- ---------------------------------------------------------------------------
-- 5. Staging Payroll Details (Unpivoted Long Format for In-Grid Workspace)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.staging_payroll_details (
    id                  BIGSERIAL PRIMARY KEY,
    header_id           UUID NOT NULL REFERENCES public.staging_payroll_header(id) ON DELETE CASCADE,
    row_index           INT NOT NULL,
    raw_hrms_id         VARCHAR(50),
    temp_hrms_id        VARCHAR(50),  -- Generated T-001, T-002 if raw_hrms_id is blank
    raw_aadhaar         VARCHAR(12),
    raw_pan             VARCHAR(10),
    raw_contact         VARCHAR(15),
    raw_employee_name   VARCHAR(150),
    raw_division        VARCHAR(150),
    raw_division_code   VARCHAR(50),
    emp_master_id       BIGINT REFERENCES public.employees(id),
    matched_division_id BIGINT REFERENCES public.hmwssb_divisions(id),
    component_id        BIGINT REFERENCES public.salary_components(id),
    component_code      VARCHAR(50),  -- Snapshot for display
    component_name      VARCHAR(150), -- Snapshot for display
    category            VARCHAR(20),  -- EARNING, DEDUCTION, STATUTORY
    amount              NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    effective_from      DATE,
    validation_status   VARCHAR(30) DEFAULT 'VALID',
    error_details       JSONB,
    is_excluded         BOOLEAN DEFAULT FALSE,
    created_at          TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at          TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

-- Row status enum-like check
ALTER TABLE public.staging_payroll_details
    ADD CONSTRAINT chk_staging_detail_status
    CHECK (validation_status IN ('VALID', 'TEMP_ID_ASSIGNED', 'DIVISION_MISMATCH', 'INVALID_COMPONENT', 'INVALID_AMOUNT', 'EMPLOYEE_NOT_FOUND', 'DUPLICATE_IN_FILE', 'OVERLAP_EXISTING'));

CREATE INDEX IF NOT EXISTS ix_staging_detail_header ON public.staging_payroll_details(header_id);
CREATE INDEX IF NOT EXISTS ix_staging_detail_temp_hrms ON public.staging_payroll_details(temp_hrms_id) WHERE temp_hrms_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_staging_detail_validation ON public.staging_payroll_details(validation_status);
CREATE INDEX IF NOT EXISTS ix_staging_detail_emp_master ON public.staging_payroll_details(emp_master_id) WHERE emp_master_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_staging_detail_division ON public.staging_payroll_details(matched_division_id) WHERE matched_division_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. Monthly Approved Salary Ledger (Final Approved Payroll)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.monthly_salary_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    emp_master_id       BIGINT NOT NULL REFERENCES public.employees(id),
    hrms_id             VARCHAR(50),
    emp_name            VARCHAR(150) NOT NULL,
    ward                VARCHAR(50),
    circle              VARCHAR(150),
    division_id         BIGINT REFERENCES public.hmwssb_divisions(id),
    payroll_month       INT NOT NULL CHECK (payroll_month BETWEEN 1 AND 12),
    payroll_year        INT NOT NULL CHECK (payroll_year >= 2020),
    basic_pay           NUMERIC(12,2) NOT NULL,
    gross_salary        NUMERIC(12,2) NOT NULL,
    total_deductions    NUMERIC(12,2) NOT NULL,
    net_salary          NUMERIC(12,2) NOT NULL,
    status              VARCHAR(30) DEFAULT 'FINAL_APPROVED' NOT NULL,
    generated_by        VARCHAR(100),
    verified_by         VARCHAR(100),
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ DEFAULT NOW(),
    staging_header_id   UUID REFERENCES public.staging_payroll_header(id),
    created_at          TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    
    UNIQUE(emp_master_id, payroll_month, payroll_year)
);

-- Add CHECK constraint for ledger status
ALTER TABLE public.monthly_salary_ledger
    ADD CONSTRAINT chk_monthly_ledger_status
    CHECK (status IN ('FINAL_APPROVED', 'DRAFT', 'CORRECTED'));

CREATE INDEX IF NOT EXISTS ix_monthly_ledger_period ON public.monthly_salary_ledger(payroll_year, payroll_month);
CREATE INDEX IF NOT EXISTS ix_monthly_ledger_division ON public.monthly_salary_ledger(division_id);
CREATE INDEX IF NOT EXISTS ix_monthly_ledger_status ON public.monthly_salary_ledger(status);

-- ---------------------------------------------------------------------------
-- 7. Salary Revision History (Basic Revision & Increment Log)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.salary_revision_history (
    id                  BIGSERIAL PRIMARY KEY,
    emp_master_id       BIGINT NOT NULL REFERENCES public.employees(id),
    previous_basic      NUMERIC(12,2) NOT NULL,
    revised_basic       NUMERIC(12,2) NOT NULL,
    increment_type      VARCHAR(50) NOT NULL,  -- 'ANNUAL', 'PROMOTION', 'DA_REVISION', 'ARREARS', 'OTHER'
    effective_date      DATE NOT NULL,
    sanction_order_no   VARCHAR(100),
    status              VARCHAR(30) DEFAULT 'PENDING_SO_VERIFICATION' NOT NULL,
    initiated_by        VARCHAR(100) NOT NULL,
    verified_by         VARCHAR(100),
    approved_by         VARCHAR(100),
    created_at          TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at          TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

ALTER TABLE public.salary_revision_history
    ADD CONSTRAINT chk_revision_status
    CHECK (status IN ('PENDING_SO_VERIFICATION', 'VERIFIED_SO', 'APPROVED_GM', 'REJECTED', 'APPLIED'));

CREATE INDEX IF NOT EXISTS ix_revision_emp ON public.salary_revision_history(emp_master_id);
CREATE INDEX IF NOT EXISTS ix_revision_status ON public.salary_revision_history(status);
CREATE INDEX IF NOT EXISTS ix_revision_effective ON public.salary_revision_history(effective_date);

-- ---------------------------------------------------------------------------
-- 8. RLS Policies for New Tables
-- ---------------------------------------------------------------------------
ALTER TABLE public.hmwssb_divisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hmwssb_divisions FORCE ROW LEVEL SECURITY;
ALTER TABLE public.staging_payroll_header ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.staging_payroll_header FORCE ROW LEVEL SECURITY;
ALTER TABLE public.staging_payroll_details ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.staging_payroll_details FORCE ROW LEVEL SECURITY;
ALTER TABLE public.monthly_salary_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.monthly_salary_ledger FORCE ROW LEVEL SECURITY;
ALTER TABLE public.salary_revision_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.salary_revision_history FORCE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON public.hmwssb_divisions TO ssga_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.staging_payroll_header TO ssga_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.staging_payroll_details TO ssga_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.monthly_salary_ledger TO ssga_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.salary_revision_history TO ssga_app;
GRANT USAGE, SELECT ON SEQUENCE public.hmwssb_divisions_id_seq TO ssga_app;
GRANT USAGE, SELECT ON SEQUENCE public.staging_payroll_details_id_seq TO ssga_app;
GRANT USAGE, SELECT ON SEQUENCE public.monthly_salary_ledger_id_seq TO ssga_app;
GRANT USAGE, SELECT ON SEQUENCE public.salary_revision_history_id_seq TO ssga_app;

-- hmwssb_divisions: readable by all authenticated users with org scope
DROP POLICY IF EXISTS hmwssb_divisions_select ON public.hmwssb_divisions;
CREATE POLICY hmwssb_divisions_select ON public.hmwssb_divisions FOR SELECT TO ssga_app
    USING (EXISTS (
        SELECT 1 FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        WHERE s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
    ));

-- staging_payroll_header: uploader + assigned collaborators
DROP POLICY IF EXISTS staging_header_select ON public.staging_payroll_header;
CREATE POLICY staging_header_select ON public.staging_payroll_header FOR SELECT TO ssga_app
    USING (
        uploaded_by = app.current_user_id()
        OR EXISTS (
            SELECT 1 FROM public.import_job_assignments a
            WHERE a.import_job_id = import_job_id
              AND a.user_id = app.current_user_id()
              AND a.is_active
              AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER','VIEWER')
        )
    );

DROP POLICY IF EXISTS staging_header_insert ON public.staging_payroll_header;
CREATE POLICY staging_header_insert ON public.staging_payroll_header FOR INSERT TO ssga_app
    WITH CHECK (uploaded_by = app.current_user_id());

DROP POLICY IF EXISTS staging_header_update ON public.staging_payroll_header;
CREATE POLICY staging_header_update ON public.staging_payroll_header FOR UPDATE TO ssga_app
    USING (
        uploaded_by = app.current_user_id()
        OR EXISTS (
            SELECT 1 FROM public.import_job_assignments a
            WHERE a.import_job_id = import_job_id
              AND a.user_id = app.current_user_id()
              AND a.is_active
              AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER')
        )
    )
    WITH CHECK (
        uploaded_by = app.current_user_id()
        OR EXISTS (
            SELECT 1 FROM public.import_job_assignments a
            WHERE a.import_job_id = import_job_id
              AND a.user_id = app.current_user_id()
              AND a.is_active
              AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER')
        )
    );

-- staging_payroll_details: same scope as header
DROP POLICY IF EXISTS staging_detail_select ON public.staging_payroll_details;
CREATE POLICY staging_detail_select ON public.staging_payroll_details FOR SELECT TO ssga_app
    USING (EXISTS (
        SELECT 1 FROM public.staging_payroll_header h
        WHERE h.id = header_id
          AND (h.uploaded_by = app.current_user_id()
              OR EXISTS (
                  SELECT 1 FROM public.import_job_assignments a
                  WHERE a.import_job_id = h.import_job_id
                    AND a.user_id = app.current_user_id()
                    AND a.is_active
                    AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER','VIEWER')
              ))
    ));

DROP POLICY IF EXISTS staging_detail_insert ON public.staging_payroll_details;
CREATE POLICY staging_detail_insert ON public.staging_payroll_details FOR INSERT TO ssga_app
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.staging_payroll_header h
        WHERE h.id = header_id AND h.uploaded_by = app.current_user_id()
    ));

DROP POLICY IF EXISTS staging_detail_update ON public.staging_payroll_details;
CREATE POLICY staging_detail_update ON public.staging_payroll_details FOR UPDATE TO ssga_app
    USING (EXISTS (
        SELECT 1 FROM public.staging_payroll_header h
        WHERE h.id = header_id
          AND (h.uploaded_by = app.current_user_id()
              OR EXISTS (
                  SELECT 1 FROM public.import_job_assignments a
                  WHERE a.import_job_id = h.import_job_id
                    AND a.user_id = app.current_user_id()
                    AND a.is_active
                    AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER')
              ))
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.staging_payroll_header h
        WHERE h.id = header_id
          AND (h.uploaded_by = app.current_user_id()
              OR EXISTS (
                  SELECT 1 FROM public.import_job_assignments a
                  WHERE a.import_job_id = h.import_job_id
                    AND a.user_id = app.current_user_id()
                    AND a.is_active
                    AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER')
              ))
    ));

-- monthly_salary_ledger: readable by approved scope, writable by finalizer
DROP POLICY IF EXISTS monthly_ledger_select ON public.monthly_salary_ledger;
CREATE POLICY monthly_ledger_select ON public.monthly_salary_ledger FOR SELECT TO ssga_app
    USING (EXISTS (
        SELECT 1 FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        LEFT JOIN public.role_permissions rp ON rp.role_id = r.id
        LEFT JOIN public.permissions p ON p.id = rp.permission_id
        WHERE s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND (
              r.max_scope IN ('ORG', 'WING', 'DIVISION')
              OR p.code IN ('payroll.finalize', 'salary.manage', 'payroll.approve', 'payroll.prepare')
          )
    ));

DROP POLICY IF EXISTS monthly_ledger_insert ON public.monthly_salary_ledger;
CREATE POLICY monthly_ledger_insert ON public.monthly_salary_ledger FOR INSERT TO ssga_app
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.import_job_assignments a
        JOIN public.staging_payroll_header h ON h.import_job_id = a.import_job_id
        WHERE h.id = staging_header_id
          AND a.user_id = app.current_user_id()
          AND a.is_active
          AND a.assignment_role = 'FINALIZER'
    ));

-- salary_revision_history: readable by salary.manage scope
DROP POLICY IF EXISTS revision_history_select ON public.salary_revision_history;
CREATE POLICY revision_history_select ON public.salary_revision_history FOR SELECT TO ssga_app
    USING (EXISTS (
        SELECT 1 FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND p.code IN ('salary.manage', 'payroll.prepare', 'salary.prepare')
    ));

DROP POLICY IF EXISTS revision_history_insert ON public.salary_revision_history;
CREATE POLICY revision_history_insert ON public.salary_revision_history FOR INSERT TO ssga_app
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND p.code IN ('salary.manage', 'payroll.prepare')
    ));

DROP POLICY IF EXISTS revision_history_update ON public.salary_revision_history;
CREATE POLICY revision_history_update ON public.salary_revision_history FOR UPDATE TO ssga_app
    USING (EXISTS (
        SELECT 1 FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND p.code IN ('salary.manage', 'payroll.prepare', 'payroll.approve', 'payroll.finalize')
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND p.code IN ('salary.manage', 'payroll.prepare', 'payroll.approve', 'payroll.finalize')
    ));

-- ---------------------------------------------------------------------------
-- 9. Function: Assign Temporary HRMS IDs (T-001, T-002, ...)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.assign_temporary_hrms_ids(p_header_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_catalog
AS $$
DECLARE
    r RECORD;
    seq_num INT := 1;
    new_temp_id VARCHAR(50);
BEGIN
    FOR r IN 
        SELECT id FROM public.staging_payroll_details 
        WHERE header_id = p_header_id 
          AND (raw_hrms_id IS NULL OR TRIM(raw_hrms_id) = '')
        ORDER BY row_index
    LOOP
        new_temp_id := 'T-' || LPAD(seq_num::TEXT, 3, '0');
        
        UPDATE public.staging_payroll_details
        SET temp_hrms_id = new_temp_id,
            validation_status = 'TEMP_ID_ASSIGNED',
            error_details = jsonb_build_object(
                'info', 'Temporary ID assigned automatically',
                'assigned_at', NOW()
            ),
            updated_at = NOW()
        WHERE id = r.id;
        
        seq_num := seq_num + 1;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION public.assign_temporary_hrms_ids(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.assign_temporary_hrms_ids(UUID) TO ssga_app;

-- ---------------------------------------------------------------------------
-- 10. Function: Auto-match Divisions from Raw Division Name/Code
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.match_hmwssb_division(
    p_raw_division_name VARCHAR,
    p_raw_division_code VARCHAR
)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_catalog
AS $$
DECLARE
    v_division_id BIGINT;
BEGIN
    -- Try exact code match first
    IF p_raw_division_code IS NOT NULL THEN
        SELECT id INTO v_division_id
        FROM public.hmwssb_divisions
        WHERE division_code ILIKE TRIM(p_raw_division_code)
          AND status = 'ACTIVE'
        LIMIT 1;
        
        IF v_division_id IS NOT NULL THEN
            RETURN v_division_id;
        END IF;
    END IF;
    
    -- Try exact name match
    IF p_raw_division_name IS NOT NULL THEN
        SELECT id INTO v_division_id
        FROM public.hmwssb_divisions
        WHERE division_name ILIKE TRIM(p_raw_division_name)
          AND status = 'ACTIVE'
        LIMIT 1;
        
        IF v_division_id IS NOT NULL THEN
            RETURN v_division_id;
        END IF;
    END IF;
    
    -- Try fuzzy match on name (contains)
    IF p_raw_division_name IS NOT NULL THEN
        SELECT id INTO v_division_id
        FROM public.hmwssb_divisions
        WHERE division_name ILIKE '%' || TRIM(p_raw_division_name) || '%'
          AND status = 'ACTIVE'
        ORDER BY LENGTH(division_name)
        LIMIT 1;
        
        IF v_division_id IS NOT NULL THEN
            RETURN v_division_id;
        END IF;
    END IF;
    
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.match_hmwssb_division(VARCHAR, VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.match_hmwssb_division(VARCHAR, VARCHAR) TO ssga_app;

-- ---------------------------------------------------------------------------
-- 11. Function: Validate and Calculate Staging Header Totals
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.recalculate_staging_header_totals(p_header_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_catalog
AS $$
DECLARE
    v_total_records INT;
    v_valid_records INT;
    v_error_records INT;
    v_temp_id_count INT;
    v_gross NUMERIC(15,2);
    v_deductions NUMERIC(15,2);
    v_net NUMERIC(15,2);
BEGIN
    SELECT 
        COUNT(*),
        COUNT(*) FILTER (WHERE validation_status = 'VALID'),
        COUNT(*) FILTER (WHERE validation_status != 'VALID' AND NOT is_excluded),
        COUNT(*) FILTER (WHERE temp_hrms_id IS NOT NULL),
        COALESCE(SUM(amount) FILTER (WHERE category = 'EARNING' AND validation_status = 'VALID' AND NOT is_excluded), 0),
        COALESCE(SUM(amount) FILTER (WHERE category = 'DEDUCTION' AND validation_status = 'VALID' AND NOT is_excluded), 0),
        COALESCE(SUM(amount) FILTER (WHERE category = 'EARNING' AND validation_status = 'VALID' AND NOT is_excluded), 0) -
        COALESCE(SUM(amount) FILTER (WHERE category = 'DEDUCTION' AND validation_status = 'VALID' AND NOT is_excluded), 0)
    INTO 
        v_total_records, v_valid_records, v_error_records, v_temp_id_count,
        v_gross, v_deductions, v_net
    FROM public.staging_payroll_details
    WHERE header_id = p_header_id;
    
    UPDATE public.staging_payroll_header
    SET total_records = v_total_records,
        valid_records = v_valid_records,
        error_records = v_error_records,
        temp_id_count = v_temp_id_count,
        total_gross_payout = v_gross,
        total_deductions = v_deductions,
        total_net_payout = v_net,
        updated_at = NOW()
    WHERE id = p_header_id;
END;
$$;

REVOKE ALL ON FUNCTION public.recalculate_staging_header_totals(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.recalculate_staging_header_totals(UUID) TO ssga_app;

-- ---------------------------------------------------------------------------
-- 12. Trigger: Auto-assign temp HRMS IDs on detail insert
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.trg_staging_detail_temp_hrms()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_catalog
AS $$
BEGIN
    IF NEW.raw_hrms_id IS NULL OR TRIM(NEW.raw_hrms_id) = '' THEN
        -- Get next sequence number for this header
        SELECT COALESCE(MAX(
            CASE 
                WHEN temp_hrms_id ~ '^T-[0-9]{3}$' 
                THEN SUBSTRING(temp_hrms_id FROM 3)::INT 
                ELSE 0 
            END
        ), 0) + 1 INTO NEW.temp_hrms_id
        FROM public.staging_payroll_details
        WHERE header_id = NEW.header_id;
        
        NEW.temp_hrms_id := 'T-' || LPAD(NEW.temp_hrms_id::TEXT, 3, '0');
        NEW.validation_status := 'TEMP_ID_ASSIGNED';
        NEW.error_details := jsonb_build_object('info', 'Temporary ID assigned automatically');
    END IF;
    
    -- Auto-match division if provided
    IF NEW.matched_division_id IS NULL 
       AND (NEW.raw_division IS NOT NULL OR NEW.raw_division_code IS NOT NULL) THEN
        NEW.matched_division_id := public.match_hmwssb_division(
            NEW.raw_division, NEW.raw_division_code
        );
        
        IF NEW.matched_division_id IS NULL 
           AND (NEW.raw_division IS NOT NULL OR NEW.raw_division_code IS NOT NULL) THEN
            NEW.validation_status := 'DIVISION_MISMATCH';
            NEW.error_details := jsonb_build_object(
                'error', 'Division not found in HMWSSB master',
                'raw_division', NEW.raw_division,
                'raw_division_code', NEW.raw_division_code
            );
        END IF;
    END IF;
    
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_staging_detail_temp_hrms ON public.staging_payroll_details;
CREATE TRIGGER trg_staging_detail_temp_hrms
    BEFORE INSERT ON public.staging_payroll_details
    FOR EACH ROW EXECUTE FUNCTION public.trg_staging_detail_temp_hrms();

-- ---------------------------------------------------------------------------
-- 13. Trigger: Recalculate header totals on detail changes
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.trg_staging_detail_totals()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_catalog
AS $$
BEGIN
    PERFORM public.recalculate_staging_header_totals(NEW.header_id);
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_staging_detail_totals ON public.staging_payroll_details;
CREATE TRIGGER trg_staging_detail_totals
    AFTER INSERT OR UPDATE OR DELETE ON public.staging_payroll_details
    FOR EACH ROW EXECUTE FUNCTION public.trg_staging_detail_totals();

-- ---------------------------------------------------------------------------
-- 14. Staging Workflow Events (Audit trail for 3-tier approval)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.staging_workflow_events (
    id              BIGSERIAL PRIMARY KEY,
    header_id       UUID NOT NULL REFERENCES public.staging_payroll_header(id) ON DELETE CASCADE,
    action          VARCHAR(50) NOT NULL,  -- 'SUBMIT_FOR_VERIFICATION', 'VERIFY_BY_SO', 'APPROVE_BY_GM', 'REJECT', 'REOPEN'
    previous_status VARCHAR(30),
    new_status      VARCHAR(30),
    actor_user_id   BIGINT NOT NULL REFERENCES public.users(id),
    details         JSONB DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_staging_workflow_events_header ON public.staging_workflow_events(header_id, created_at);

ALTER TABLE public.staging_workflow_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.staging_workflow_events FORCE ROW LEVEL SECURITY;

GRANT SELECT, INSERT ON public.staging_workflow_events TO ssga_app;
GRANT USAGE, SELECT ON SEQUENCE public.staging_workflow_events_id_seq TO ssga_app;

DROP POLICY IF EXISTS staging_workflow_events_select ON public.staging_workflow_events;
CREATE POLICY staging_workflow_events_select ON public.staging_workflow_events FOR SELECT TO ssga_app
    USING (EXISTS (
        SELECT 1 FROM public.staging_payroll_header h
        WHERE h.id = header_id
          AND (h.uploaded_by = app.current_user_id()
              OR EXISTS (
                  SELECT 1 FROM public.import_job_assignments a
                  WHERE a.import_job_id = h.import_job_id
                    AND a.user_id = app.current_user_id()
                    AND a.is_active
                    AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER','VIEWER')
              ))
    ));

DROP POLICY IF EXISTS staging_workflow_events_insert ON public.staging_workflow_events;
CREATE POLICY staging_workflow_events_insert ON public.staging_workflow_events FOR INSERT TO ssga_app
    WITH CHECK (
        actor_user_id = app.current_user_id()
        AND EXISTS (
            SELECT 1 FROM public.staging_payroll_header h
            WHERE h.id = header_id
              AND (h.uploaded_by = app.current_user_id()
                  OR EXISTS (
                      SELECT 1 FROM public.import_job_assignments a
                      WHERE a.import_job_id = h.import_job_id
                        AND a.user_id = app.current_user_id()
                        AND a.is_active
                        AND a.assignment_role IN ('RESOLVER','APPROVER','FINALIZER')
                  ))
        )
    );

COMMIT;

-- ============================================================================
-- Verification Queries (run separately):
-- ============================================================================
-- SELECT * FROM public.hmwssb_divisions ORDER BY display_order;
-- SELECT * FROM public.staging_payroll_header;
-- SELECT * FROM public.staging_payroll_details LIMIT 10;
-- SELECT * FROM public.monthly_salary_ledger LIMIT 10;
-- SELECT public.assign_temporary_hrms_ids('uuid-here');
-- SELECT public.match_hmwssb_division('O&M Division IV', 'OM-DIV-04');