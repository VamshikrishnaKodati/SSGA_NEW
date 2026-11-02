BEGIN;

-- 1. Create the organization
INSERT INTO organizations (name, code, status)
VALUES (
    'SSGA Organization',
    'SSGA',
    'ACTIVE'
)
ON CONFLICT (code) DO NOTHING;

-- 2. Create the first administrator
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
    '{bcrypt}$2a$10$HitEv/WxzI/tnlM4ZSBsyu5KpB17ec3x60Wt1Y7T98g.4vjOWsLh.',
    true,
    0,
    true
)
ON CONFLICT (username) DO NOTHING;

-- 3. Give the administrator the system role with organization-wide scope
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

SELECT
    u.id,
    u.username,
    u.display_name,
    u.is_active,
    r.code AS role,
    s.wing_id,
    s.division_id
FROM users u
JOIN user_role_scopes s ON s.user_id = u.id
JOIN roles r ON r.id = s.role_id
WHERE u.username = 'admin';