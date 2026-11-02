-- ============================================================================
-- seed_demo_users.sql - idempotent demo/staging accounts, one per role.
--
-- Usage (after seed_reference_data.sql and seed_bootstrap_admin.sql):
--   psql -v demo_password='<password>' -f seed_demo_users.sql
--
-- All accounts share the operator-supplied password so the login page's demo
-- buttons can fill the form. Never run this against a production database.
-- Re-running resets the demo passwords and re-asserts the role scopes.
-- ============================================================================

BEGIN;

CREATE TEMP TABLE demo_users (
    username text PRIMARY KEY,
    display_name text NOT NULL,
    role_code text NOT NULL,
    scope_kind text NOT NULL CHECK (scope_kind IN ('ORG', 'WING', 'DIVISION')),
    scope_code text
) ON COMMIT DROP;

INSERT INTO demo_users (username, display_name, role_code, scope_kind, scope_code)
VALUES
    ('org_admin',       'Organization Administrator', 'ORG_ADMIN',         'ORG',      NULL),
    ('wing_admin_eng',  'Engineering Wing Administrator', 'WING_ADMIN',   'WING',     'ENG'),
    ('division_admin_a1', 'Engineering Division A1 Administrator', 'DIVISION_ADMIN', 'DIVISION', 'ENG-A1'),
    ('payroll_maker',   'Payroll Maker (operator)',   'PAYROLL_OPERATOR',  'DIVISION', 'ENG-A1'),
    ('payroll_checker', 'Payroll Checker (approver)', 'APPROVING_AUTHORITY','DIVISION', 'ENG-A1'),
    ('payroll_finalizer','Payroll Finalizer', 'PAYROLL_FINALIZER','DIVISION', 'ENG-A1'),
    ('report_user',     'Report User',                'REPORT_USER',       'DIVISION', 'ENG-A1'),
    ('auditor',         'Auditor',                    'AUDITOR',           'ORG',      NULL),
    ('employee_ravi',   'Ravi Kumar',                 'EMPLOYEE_SELF',     'ORG',      NULL);

INSERT INTO users (
    username,
    display_name,
    auth_provider,
    password_hash,
    must_change_password,
    failed_attempts,
    is_active
)
SELECT
    d.username,
    d.display_name,
    'LOCAL',
    '{bcrypt}' || crypt(:'demo_password', gen_salt('bf', 10)),
    false,
    0,
    true
FROM demo_users d
ON CONFLICT (username) DO UPDATE
SET display_name = EXCLUDED.display_name,
    auth_provider = EXCLUDED.auth_provider,
    password_hash = EXCLUDED.password_hash,
    must_change_password = EXCLUDED.must_change_password,
    failed_attempts = 0,
    is_active = true,
    updated_at = now();

-- Replace the demo accounts' scopes so a re-run cannot leave stale grants.
DELETE FROM user_role_scopes
WHERE user_id IN (SELECT id FROM users WHERE username IN (SELECT username FROM demo_users));

INSERT INTO user_role_scopes (
    user_id, role_id, wing_id, division_id, valid_from, is_active, granted_by
)
SELECT
    u.id,
    r.id,
    CASE d.scope_kind WHEN 'WING' THEN w.id END,
    CASE d.scope_kind WHEN 'DIVISION' THEN dv.id END,
    CURRENT_DATE,
    true,
    (SELECT id FROM users WHERE username = 'admin')
FROM demo_users d
JOIN users u ON u.username = d.username
JOIN roles r ON r.code = d.role_code
LEFT JOIN wings w ON d.scope_kind = 'WING' AND w.code = d.scope_code
LEFT JOIN divisions dv ON d.scope_kind = 'DIVISION' AND dv.code = d.scope_code;

COMMIT;
