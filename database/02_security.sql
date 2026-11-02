-- ============================================================
-- SSGA NEW - SECURITY / RBAC / RLS
-- PostgreSQL 17
-- ============================================================

CREATE SCHEMA IF NOT EXISTS app;

-- ============================================================
-- 1. APPLICATION DATABASE ROLE
-- ============================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = 'ssga_app'
    ) THEN
        CREATE ROLE ssga_app
            LOGIN
            NOSUPERUSER
            NOBYPASSRLS
            NOCREATEDB
            NOCREATEROLE
            NOREPLICATION;
    ELSE
        ALTER ROLE ssga_app
            NOSUPERUSER
            NOBYPASSRLS
            NOCREATEDB
            NOCREATEROLE
            NOREPLICATION;
    END IF;
END
$$;

GRANT CONNECT ON DATABASE ssga_new_db TO ssga_app;
GRANT USAGE ON SCHEMA public, app TO ssga_app;

-- ============================================================
-- 2. ROLES
-- ============================================================

INSERT INTO roles (code, name, description, max_scope, is_system)
VALUES
('SYSTEM_ADMIN', 'System Administrator',
 'Full system administration', 'ORG', true),

('ORG_ADMIN', 'Organization Administrator',
 'Organization-wide administration', 'ORG', true),

('WING_ADMIN', 'Wing Administrator',
 'Administration within assigned Wing', 'WING', true),

('DIVISION_ADMIN', 'Division Administrator',
 'Administration within assigned Division', 'DIVISION', true),

('PAYROLL_OPERATOR', 'Salary / Payroll Operator',
 'Salary processing within assigned scope', 'DIVISION', true),

('APPROVING_AUTHORITY', 'Approving Authority',
 'Review and approval of payroll', 'DIVISION', true),

('REPORT_USER', 'Report User',
 'View and download reports', 'DIVISION', true),

('EMPLOYEE_SELF', 'Employee Self',
 'Own employee information and statement', 'SELF', true),

('AUDITOR', 'Auditor',
 'Read-only audit access', 'ORG', true)

ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- 3. PERMISSIONS
-- ============================================================

INSERT INTO permissions (code, module, description)
VALUES
('organization.view', 'ORGANIZATION', 'View organization'),
('organization.manage', 'ORGANIZATION', 'Manage organization'),

('wing.view', 'ORGANIZATION', 'View wings'),
('wing.manage', 'ORGANIZATION', 'Manage wings'),

('division.view', 'ORGANIZATION', 'View divisions'),
('division.manage', 'ORGANIZATION', 'Manage divisions'),

('employee.view', 'EMPLOYEE', 'View employees'),
('employee.create', 'EMPLOYEE', 'Create employees'),
('employee.update', 'EMPLOYEE', 'Update employees'),
('employee.hrms_replace', 'EMPLOYEE', 'Replace temporary HRMS'),

('salary.view', 'SALARY', 'View salary data'),
('salary.manage', 'SALARY', 'Manage salary master'),
('salary.prepare', 'SALARY', 'Prepare payroll'),

('payroll.view', 'PAYROLL', 'View payroll'),
('payroll.prepare', 'PAYROLL', 'Generate payroll'),
('payroll.verify', 'PAYROLL', 'Verify payroll'),
('payroll.submit', 'PAYROLL', 'Submit payroll'),
('payroll.approve', 'PAYROLL', 'Approve payroll'),
('payroll.finalize', 'PAYROLL', 'Finalize payroll'),
('payroll.reopen', 'PAYROLL', 'Reopen payroll'),

('statement.view', 'STATEMENT', 'View salary statements'),
('statement.generate', 'STATEMENT', 'Generate salary statements'),
('statement.download', 'STATEMENT', 'Download salary statements'),

('report.view', 'REPORT', 'View reports'),
('export.download', 'REPORT', 'Download exports'),

('user.manage', 'SECURITY', 'Manage users'),
('scope.manage', 'SECURITY', 'Manage organizational scopes'),

('audit.view', 'AUDIT', 'View audit logs')

ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- 4. ROLE → PERMISSION
-- ============================================================

-- System Administrator gets all permissions.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'SYSTEM_ADMIN'
ON CONFLICT DO NOTHING;

-- Organization Administrator
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'organization.view','organization.manage',
    'wing.view','wing.manage',
    'division.view','division.manage',
    'employee.view','employee.create','employee.update',
    'employee.hrms_replace',
    'salary.view','salary.manage',
    'payroll.view','payroll.prepare','payroll.verify',
    'payroll.submit',
    'statement.view','statement.generate','statement.download',
    'report.view','export.download'
)
WHERE r.code = 'ORG_ADMIN'
ON CONFLICT DO NOTHING;

-- Wing Administrator
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'wing.view',
    'division.view',
    'employee.view',
    'employee.create',
    'employee.update',
    'employee.hrms_replace',
    'salary.view',
    'salary.manage',
    'payroll.view',
    'report.view',
    'export.download'
)
WHERE r.code = 'WING_ADMIN'
ON CONFLICT DO NOTHING;

-- Division Administrator
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'division.view',
    'employee.view',
    'employee.create',
    'employee.update',
    'employee.hrms_replace',
    'salary.view',
    'payroll.view',
    'report.view'
)
WHERE r.code = 'DIVISION_ADMIN'
ON CONFLICT DO NOTHING;

-- Payroll Operator
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.view',
    'salary.view',
    'salary.prepare',
    'payroll.view',
    'payroll.prepare',
    'payroll.verify',
    'payroll.submit'
)
WHERE r.code = 'PAYROLL_OPERATOR'
ON CONFLICT DO NOTHING;

-- Approving Authority
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.view',
    'salary.view',
    'payroll.view',
    'payroll.approve',
    'payroll.finalize'
)
WHERE r.code = 'APPROVING_AUTHORITY'
ON CONFLICT DO NOTHING;

-- Report User
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.view',
    'salary.view',
    'payroll.view',
    'statement.view',
    'statement.download',
    'report.view',
    'export.download'
)
WHERE r.code = 'REPORT_USER'
ON CONFLICT DO NOTHING;

-- Employee Self
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.view',
    'statement.view',
    'statement.download'
)
WHERE r.code = 'EMPLOYEE_SELF'
ON CONFLICT DO NOTHING;

-- Auditor
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'audit.view',
    'report.view'
)
WHERE r.code = 'AUDITOR'
ON CONFLICT DO NOTHING;

-- ============================================================
-- 5. APPLICATION USER CONTEXT
-- ============================================================

CREATE OR REPLACE FUNCTION app.current_user_id()
RETURNS BIGINT
LANGUAGE SQL
STABLE
AS $$
    SELECT NULLIF(
        current_setting('app.user_id', true),
        ''
    )::BIGINT;
$$;

-- ============================================================
-- 6. WING ACCESS
-- ============================================================

CREATE OR REPLACE FUNCTION app.can_access_wing(
    p_wing_id BIGINT
)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM user_role_scopes s
        JOIN roles r ON r.id = s.role_id
        JOIN users u ON u.id = s.user_id
        WHERE s.user_id = app.current_user_id()
          AND s.is_active = true
          AND r.is_active = true
          AND u.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
          AND (
              (
                  r.max_scope = 'ORG'
                  AND s.wing_id IS NULL
                  AND s.division_id IS NULL
              )
              OR (
                  r.max_scope = 'WING'
                  AND s.wing_id = p_wing_id
              )
          )
    );
$$;

-- ============================================================
-- 7. DIVISION ACCESS
-- ============================================================

CREATE OR REPLACE FUNCTION app.can_access_division(
    p_division_id BIGINT
)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM divisions d
        JOIN wings w ON w.id = d.wing_id
        JOIN user_role_scopes s
          ON s.user_id = app.current_user_id()
        JOIN roles r
          ON r.id = s.role_id
        JOIN users u
          ON u.id = s.user_id
        WHERE d.id = p_division_id
          AND s.is_active = true
          AND r.is_active = true
          AND u.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
          AND (
              (
                  r.max_scope = 'ORG'
                  AND s.wing_id IS NULL
                  AND s.division_id IS NULL
              )
              OR (
                  r.max_scope = 'WING'
                  AND s.wing_id = w.id
              )
              OR (
                  r.max_scope = 'DIVISION'
                  AND s.division_id = d.id
              )
          )
    );
$$;

-- ============================================================
-- 8. EMPLOYEE ACCESS
-- ============================================================

CREATE OR REPLACE FUNCTION app.can_access_employee(
    p_employee_id BIGINT
)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM employees e
        JOIN users u
          ON u.id = app.current_user_id()
        JOIN user_role_scopes s
          ON s.user_id = u.id
        JOIN roles r
          ON r.id = s.role_id
        WHERE e.id = p_employee_id
          AND s.is_active = true
          AND r.is_active = true
          AND u.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
          AND (
              (
                  r.max_scope = 'ORG'
                  AND s.wing_id IS NULL
                  AND s.division_id IS NULL
              )
              OR (
                  r.max_scope = 'WING'
                  AND EXISTS (
                      SELECT 1
                      FROM divisions d
                      WHERE d.id = e.division_id
                        AND d.wing_id = s.wing_id
                  )
              )
              OR (
                  r.max_scope = 'DIVISION'
                  AND e.division_id = s.division_id
              )
              OR (
                  r.max_scope = 'SELF'
                  AND u.employee_id = e.id
              )
          )
    );
$$;

-- ============================================================
-- 9. RLS
-- ============================================================

ALTER TABLE divisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE divisions FORCE ROW LEVEL SECURITY;

ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE employees FORCE ROW LEVEL SECURITY;

ALTER TABLE employee_salary_values ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_salary_values FORCE ROW LEVEL SECURITY;

ALTER TABLE salary_adjustments ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_adjustments FORCE ROW LEVEL SECURITY;

ALTER TABLE payroll_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_batches FORCE ROW LEVEL SECURITY;

ALTER TABLE salary_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_transactions FORCE ROW LEVEL SECURITY;

ALTER TABLE salary_transaction_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_transaction_lines FORCE ROW LEVEL SECURITY;

ALTER TABLE salary_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_revisions FORCE ROW LEVEL SECURITY;

ALTER TABLE salary_statements ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_statements FORCE ROW LEVEL SECURITY;

ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;

-- ============================================================
-- 10. DIVISION POLICY
-- ============================================================

CREATE POLICY divisions_scope_policy
ON divisions
FOR ALL
TO ssga_app
USING (app.can_access_division(id))
WITH CHECK (app.can_access_division(id));

-- ============================================================
-- 11. EMPLOYEE POLICY
-- ============================================================

CREATE POLICY employees_scope_policy
ON employees
FOR ALL
TO ssga_app
USING (app.can_access_employee(id))
WITH CHECK (app.can_access_division(division_id));

-- ============================================================
-- 12. SALARY ENTITLEMENT POLICY
-- ============================================================

CREATE POLICY employee_salary_scope_policy
ON employee_salary_values
FOR ALL
TO ssga_app
USING (app.can_access_employee(employee_id))
WITH CHECK (app.can_access_employee(employee_id));

-- ============================================================
-- 13. PAYROLL BATCH POLICY
-- ============================================================

CREATE POLICY payroll_batch_scope_policy
ON payroll_batches
FOR ALL
TO ssga_app
USING (app.can_access_division(division_id))
WITH CHECK (app.can_access_division(division_id));

-- ============================================================
-- 14. TRANSACTION POLICY
-- ============================================================

CREATE POLICY salary_transaction_scope_policy
ON salary_transactions
FOR ALL
TO ssga_app
USING (app.can_access_employee(employee_id))
WITH CHECK (app.can_access_employee(employee_id));

-- ============================================================
-- 15. TRANSACTION LINE POLICY
-- ============================================================

CREATE POLICY salary_line_scope_policy
ON salary_transaction_lines
FOR ALL
TO ssga_app
USING (
    EXISTS (
        SELECT 1
        FROM salary_transactions t
        WHERE t.id = salary_transaction_id
          AND app.can_access_employee(t.employee_id)
    )
);

-- ============================================================
-- 16. ADJUSTMENT POLICY
-- ============================================================

CREATE POLICY salary_adjustment_scope_policy
ON salary_adjustments
FOR ALL
TO ssga_app
USING (app.can_access_employee(employee_id))
WITH CHECK (app.can_access_employee(employee_id));

-- ============================================================
-- 17. REVISION POLICY
-- ============================================================

CREATE POLICY salary_revision_scope_policy
ON salary_revisions
FOR ALL
TO ssga_app
USING (app.can_access_employee(employee_id))
WITH CHECK (app.can_access_employee(employee_id));

-- ============================================================
-- 18. STATEMENT POLICY
-- ============================================================

CREATE POLICY salary_statement_scope_policy
ON salary_statements
FOR SELECT
TO ssga_app
USING (app.can_access_employee(employee_id));

-- ============================================================
-- 19. AUDIT POLICY
-- ============================================================

CREATE POLICY audit_scope_policy
ON audit_logs
FOR SELECT
TO ssga_app
USING (
    (
        division_id IS NOT NULL
        AND app.can_access_division(division_id)
    )
    OR
    (
        division_id IS NULL
        AND EXISTS (
            SELECT 1
            FROM user_role_scopes s
            JOIN roles r ON r.id = s.role_id
            WHERE s.user_id = app.current_user_id()
              AND s.is_active = true
              AND r.is_active = true
              AND r.max_scope = 'ORG'
              AND s.wing_id IS NULL
              AND s.division_id IS NULL
        )
    )
);

-- ============================================================
-- 20. APPLICATION TABLE PRIVILEGES
-- ============================================================

GRANT SELECT ON organizations, wings, divisions TO ssga_app;

GRANT SELECT, INSERT, UPDATE, DELETE
ON designations, grades, employees
TO ssga_app;

GRANT SELECT, INSERT, UPDATE
ON salary_components, salary_component_rules,
   salary_rule_slabs, employee_salary_values,
   system_parameters
TO ssga_app;

GRANT SELECT, INSERT, UPDATE
ON payroll_periods, payroll_batches,
   salary_transactions, salary_transaction_lines,
   salary_adjustments
TO ssga_app;

GRANT SELECT, INSERT, UPDATE
ON salary_revision_batches, salary_revisions
TO ssga_app;

GRANT SELECT ON roles, permissions, role_permissions
TO ssga_app;

GRANT SELECT ON salary_approvals
TO ssga_app;

GRANT SELECT ON salary_statements
TO ssga_app;

GRANT SELECT ON audit_logs
TO ssga_app;

-- Never give direct application DML access
-- to users / user_role_scopes.
REVOKE ALL ON users FROM ssga_app;
REVOKE ALL ON user_role_scopes FROM ssga_app;

-- Audit must be written through controlled functions later.
REVOKE INSERT, UPDATE, DELETE
ON audit_logs
FROM ssga_app;

-- Identity columns use sequences.
GRANT USAGE, SELECT
ON ALL SEQUENCES IN SCHEMA public
TO ssga_app;