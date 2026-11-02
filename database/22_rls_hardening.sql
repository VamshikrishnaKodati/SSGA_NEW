-- ============================================================
-- SSGA NEW - 22. RLS POLICY HARDENING (additive)
-- PostgreSQL 17
-- ============================================================

BEGIN;

-- Ensure RLS is enabled on critical tables
ALTER TABLE public.payroll_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.salary_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.salary_transaction_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.salary_statements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.import_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.import_job_sheets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.import_job_sheet_issues ENABLE ROW LEVEL SECURITY;

-- Statement RLS policies already exist in the v14 baseline
-- (salary_statement_scope_policy, salary_statement_insert_policy,
-- salary_statement_update_policy) and are keyed on app.can_access_employee.

COMMIT;
