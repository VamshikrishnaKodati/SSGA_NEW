-- ============================================================================
-- seed_demo_users.sql - idempotent demo/staging accounts, one per role.
--
-- Usage (after seed_reference_data.sql and seed_bootstrap_admin.sql):
--   psql -v demo_password='<password>' -f seed_demo_users.sql
--
-- One account per distinct spec role (section 3.1), so every role dashboard,
-- permission set and workflow can be exercised. All ten share the
-- operator-supplied password so the login page's demo buttons can fill the
-- form. Local / staging only.
--
-- Re-running resets the demo passwords and re-asserts exactly these ten scopes.
-- No other user, role or permission grant is touched.
-- ============================================================================

BEGIN;

-- Refuse to seed demo credentials anywhere that looks like production.
DO $$
BEGIN
    IF current_database() ~* '(^|_)(prod|production)($|_)' THEN
        RAISE EXCEPTION
            'seed_demo_users: database "%" looks like production; refusing to seed demo credentials',
            current_database();
    END IF;
END
$$;

CREATE TEMP TABLE demo_users (
    username    text PRIMARY KEY,
    display_name text NOT NULL,
    role_code   text NOT NULL,
    scope_kind  text NOT NULL CHECK (scope_kind IN ('ORG', 'WING', 'DIVISION', 'SELF')),
    scope_code  text
) ON COMMIT DROP;

-- One row per distinct role. Scope follows the role's max_scope:
--   ORG roles keep both wing and division NULL;
--   WING and DIVISION roles set exactly one, per
--   CHECK (num_nonnulls(wing_id, division_id) <= 1).
INSERT INTO demo_users (username, display_name, role_code, scope_kind, scope_code)
VALUES
    ('sysadmin',        'System Administrator',        'SYS_ADMIN',         'ORG',      NULL),
    ('orgadmin',        'Organization Administrator',  'ORG_ADMIN',         'ORG',      NULL),
    ('ce_om',           'Chief Engineer (O&M)',        'WING_ADMIN',        'WING',     'WING_OM'),
    ('ee_div1',         'EE O&M Division I',           'DIV_ADMIN',         'DIVISION', 'DIV_OM_01'),
    ('payroll_off1',    'Payroll Officer 1',           'PAYROLL_OPERATOR',  'DIVISION', 'DIV_OM_01'),
    ('gm_md',           'Managing Director (IAS)',     'APPROVING_AUTH',    'ORG',      NULL),
    ('fin_finalizer',   'Finance Finalizer',           'FINANCE_FINALIZER', 'ORG',      NULL),
    ('auditor',         'Internal Auditor',            'AUDITOR',           'ORG',      NULL),
    ('report_user',     'MIS / Report User',           'REPORT_USER',       'DIVISION', 'DIV_OM_01'),
    ('emp001',          'Ravi Kumar (Temp)',           'EMPLOYEE',          'SELF',     NULL);

-- ---------------------------------------------------------------------------
-- 1. Create the missing accounts and refresh the demo password on all ten.
--    Existing rows are updated in place, never deleted.
-- ---------------------------------------------------------------------------
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
SET display_name       = EXCLUDED.display_name,
    auth_provider      = EXCLUDED.auth_provider,
    password_hash      = EXCLUDED.password_hash,
    must_change_password = EXCLUDED.must_change_password,
    failed_attempts    = 0,
    locked_until       = NULL,
    is_active          = true,
    updated_at         = now();

-- ---------------------------------------------------------------------------
-- 2. Replace only the demo accounts' scopes so a re-run cannot leave a stale
--    grant behind. granted_by stays NULL: this script does not assume a
--    bootstrap admin exists.
-- ---------------------------------------------------------------------------
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
    NULL
FROM demo_users d
JOIN users u ON u.username = d.username
JOIN roles r ON r.code = d.role_code
LEFT JOIN wings w ON d.scope_kind = 'WING' AND w.code = d.scope_code
LEFT JOIN divisions dv ON d.scope_kind = 'DIVISION' AND dv.code = d.scope_code;

-- ---------------------------------------------------------------------------
-- 3. Self-check: every demo account must have resolved to exactly one scope of
--    the right shape, or the JOIN above silently dropped it.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_expected int := 10;
    v_actual   int;
BEGIN
    SELECT count(*) INTO v_actual
      FROM user_role_scopes urs
      JOIN users u ON u.id = urs.user_id
     WHERE urs.is_active
       AND u.username IN (SELECT username FROM demo_users);

    IF v_actual <> v_expected THEN
        RAISE EXCEPTION
            'seed_demo_users: expected % active demo scopes, found % (check the wing/division codes)',
            v_expected, v_actual;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM user_role_scopes urs
          JOIN users u ON u.id = urs.user_id
          JOIN roles r ON r.id = urs.role_id
         WHERE urs.is_active
           AND u.username IN (SELECT username FROM demo_users)
           AND (
                (r.max_scope = 'ORG'      AND (urs.wing_id IS NOT NULL OR urs.division_id IS NOT NULL))
             OR (r.max_scope = 'WING'     AND (urs.wing_id IS NULL     OR urs.division_id IS NOT NULL))
             OR (r.max_scope = 'DIVISION' AND (urs.wing_id IS NOT NULL OR urs.division_id IS NULL))
           )
    ) THEN
        RAISE EXCEPTION 'seed_demo_users: a demo scope does not match its role''s max_scope';
    END IF;
END
$$;

SELECT u.username,
       u.display_name,
       r.code AS role,
       w.code AS wing,
       d.code AS division
  FROM users u
  JOIN user_role_scopes urs ON urs.user_id = u.id
  JOIN roles r ON r.id = urs.role_id
  LEFT JOIN wings w ON w.id = urs.wing_id
  LEFT JOIN divisions d ON d.id = urs.division_id
 WHERE urs.is_active
   AND u.username IN (SELECT username FROM demo_users)
 ORDER BY r.code, u.username;

COMMIT;