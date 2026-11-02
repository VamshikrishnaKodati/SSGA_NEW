CREATE OR REPLACE FUNCTION app.find_auth_user(p_username varchar)
RETURNS TABLE (
    user_id bigint,
    username varchar,
    password_hash text,
    is_active boolean,
    employee_id bigint,
    must_change_password boolean,
    failed_attempts integer,
    locked_until timestamptz
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path TO pg_catalog, public, app
AS $$
    SELECT
        u.id,
        u.username,
        u.password_hash,
        u.is_active,
        u.employee_id,
        u.must_change_password,
        u.failed_attempts,
        u.locked_until
    FROM public.users u
    WHERE u.username = p_username;
$$;


CREATE OR REPLACE FUNCTION app.find_user_authorities(p_user_id bigint)
RETURNS TABLE (authority text)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path TO pg_catalog, public, app
AS $$
    SELECT DISTINCT authority
    FROM (
        SELECT 'ROLE_' || r.code AS authority
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        WHERE s.user_id = p_user_id
          AND s.is_active = true
          AND r.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )

        UNION

        SELECT p.code AS authority
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE s.user_id = p_user_id
          AND s.is_active = true
          AND r.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
    ) x
    ORDER BY authority;
$$;


REVOKE ALL ON FUNCTION app.find_auth_user(varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.find_user_authorities(bigint) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION app.find_auth_user(varchar) TO ssga_app;
GRANT EXECUTE ON FUNCTION app.find_user_authorities(bigint) TO ssga_app;