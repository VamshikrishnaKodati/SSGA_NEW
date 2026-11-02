-- ============================================================================
-- seed_bootstrap_admin.sql - first organization + first administrator.
--
-- Idempotent. No credential is stored in source control: the admin password
-- is supplied at setup time through a psql variable.
--
-- Usage (after seed_reference_data.sql, which creates the organization and
-- the SYSTEM_ADMIN role):
--   psql -v admin_password='<choose-a-strong-password>' -f seed_bootstrap_admin.sql
--
-- The user is created with must_change_password = true, so the operator
-- password only ever lives for the first login. Password hashing uses
-- pgcrypto (bcrypt cost 10) and carries the {bcrypt} encoder prefix expected
-- by the Spring Security delegating encoder.
-- ============================================================================

BEGIN;

INSERT INTO users (
    username,
    display_name,
    auth_provider,
    password_hash,
    must_change_password,
    failed_attempts,
    is_active
)
VALUES (
    'admin',
    'System Administrator',
    'LOCAL',
    '{bcrypt}' || crypt(:'admin_password', gen_salt('bf', 10)),
    true,
    0,
    true
)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_role_scopes (
    user_id,
    role_id,
    wing_id,
    division_id,
    valid_from,
    is_active,
    granted_by
)
SELECT
    u.id,
    r.id,
    NULL,
    NULL,
    CURRENT_DATE,
    true,
    u.id
FROM users u
JOIN roles r ON r.code = 'SYSTEM_ADMIN'
WHERE u.username = 'admin'
  AND NOT EXISTS (
      SELECT 1
      FROM user_role_scopes s
      WHERE s.user_id = u.id
        AND s.role_id = r.id
        AND s.wing_id IS NULL
        AND s.division_id IS NULL
  );

COMMIT;
