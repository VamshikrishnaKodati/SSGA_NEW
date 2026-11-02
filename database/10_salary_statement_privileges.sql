-- ============================================================
-- SSGA NEW - 10. SALARY STATEMENT PRIVILEGES (additive)
-- PostgreSQL 17
-- ============================================================
--
-- 02_security.sql grants the runtime role SELECT only on
-- salary_statements. Issuing (and reissuing) a statement needs INSERT and
-- a very narrow UPDATE for the lifecycle columns only. The snapshot columns
-- (payload, hashes, amounts, identifiers, file references) stay immutable to
-- the runtime role, and RLS is preserved by adding FOR INSERT/UPDATE policies
-- that reuse the existing employee-scope predicate.
-- ============================================================

-- Read/write the minimal columns required to issue and supersede statements.
GRANT INSERT ON public.salary_statements TO ssga_app;

-- Only lifecycle columns may ever change on an existing statement; the
-- immutable snapshot columns are deliberately excluded.
GRANT UPDATE (status, withdrawn_at, withdrawn_reason)
    ON public.salary_statements TO ssga_app;

-- Identity sequence (also covered by the schema-wide sequence grant, kept
-- explicit so the privilege is self-documenting for this table).
GRANT USAGE, SELECT ON SEQUENCE public.salary_statements_id_seq TO ssga_app;

-- RLS is enabled and forced on salary_statements. SELECT already has a policy;
-- INSERT and UPDATE need their own scope-checked policies.
DROP POLICY IF EXISTS salary_statement_insert_policy ON public.salary_statements;
CREATE POLICY salary_statement_insert_policy
    ON public.salary_statements
    FOR INSERT
    WITH CHECK (app.can_access_employee(employee_id));

DROP POLICY IF EXISTS salary_statement_update_policy ON public.salary_statements;
CREATE POLICY salary_statement_update_policy
    ON public.salary_statements
    FOR UPDATE
    USING (app.can_access_employee(employee_id))
    WITH CHECK (app.can_access_employee(employee_id));
