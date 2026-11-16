-- ============================================================================
-- 49_admin_rbac_and_sensitive_data.sql
--
-- Backing changes for the System Administrator workspace:
--   1. sensitive-data permissions for bank details, PAN and salary detail
--   2. per-user permission overrides (grant or deny), used only when a role
--      assignment cannot express the need
--   3. table privileges so the admin services can manage users and scopes
--
-- Reuses the existing permissions / role_permissions / user_role_scopes /
-- roles tables. No existing permission, role or grant is removed.
--
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Sensitive-data permissions
-- ---------------------------------------------------------------------------
INSERT INTO public.permissions (code, module, description)
VALUES
    ('employee.bank_view',    'EMPLOYEE', 'View unmasked employee bank account details'),
    ('employee.pan_view',     'EMPLOYEE', 'View unmasked employee PAN details'),
    ('salary.view_sensitive', 'SALARY',   'View detailed salary and deduction information')
ON CONFLICT (code) DO NOTHING;

-- Who may see what:
--   bank details - anyone who already maintains employee records or pays staff;
--   PAN          - administrators only, it identifies a person and is not
--                  needed to run payroll;
--   salary detail- everyone who already approves, finalizes or prepares pay.
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM public.roles r
  JOIN public.permissions p ON p.code = ANY (CASE r.code
    WHEN 'SYS_ADMIN' THEN ARRAY['employee.bank_view','employee.pan_view','salary.view_sensitive']
    WHEN 'ORG_ADMIN' THEN ARRAY['employee.bank_view','employee.pan_view','salary.view_sensitive']
    WHEN 'WING_ADMIN' THEN ARRAY['employee.bank_view']
    WHEN 'DIV_ADMIN' THEN ARRAY['employee.bank_view']
    WHEN 'PAYROLL_OPERATOR' THEN ARRAY['employee.bank_view','salary.view_sensitive']
    WHEN 'APPROVING_AUTH' THEN ARRAY['salary.view_sensitive']
    WHEN 'FINANCE_FINALIZER' THEN ARRAY['salary.view_sensitive']
    ELSE ARRAY[]::text[]
  END)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Per-user permission overrides
--
--    An override is the exception, not the rule: a user whose duties do not fit
--    any role gets one row here. granted = false removes a permission the
--    user's roles would otherwise confer.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.user_permissions (
    id            bigserial PRIMARY KEY,
    user_id       bigint      NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    permission_id bigint      NOT NULL REFERENCES public.permissions (id) ON DELETE CASCADE,
    granted       boolean     NOT NULL DEFAULT true,
    reason        text,
    granted_by    bigint      REFERENCES public.users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_permissions_user_permission_key UNIQUE (user_id, permission_id),
    CONSTRAINT user_permissions_grant_or_deny CHECK (granted OR reason IS NOT NULL)
);

COMMENT ON TABLE public.user_permissions IS
    'Per-user permission overrides. granted=true adds a permission no role grants; granted=false removes one a role grants. A deny must carry a reason.';

CREATE INDEX IF NOT EXISTS ix_user_permissions_user
    ON public.user_permissions (user_id);

-- ---------------------------------------------------------------------------
-- 3. Effective authorities: role grants plus overrides, minus denies
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.find_user_authorities(p_user_id bigint)
RETURNS TABLE(authority text)
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
    SELECT DISTINCT granted.authority
    FROM (
        SELECT 'ROLE_' || r.code AS authority
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        WHERE s.user_id = p_user_id
          AND s.is_active = true
          AND r.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)

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
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)

        UNION

        SELECT p.code AS authority
        FROM public.user_permissions up
        JOIN public.permissions p ON p.id = up.permission_id
        WHERE up.user_id = p_user_id
          AND up.granted = true
    ) granted

    EXCEPT

    SELECT p.code
    FROM public.user_permissions up
    JOIN public.permissions p ON p.id = up.permission_id
    WHERE up.user_id = p_user_id
      AND up.granted = false

    ORDER BY authority;
$function$;

-- ---------------------------------------------------------------------------
-- 4. Table privileges for the admin services
--
--    users and user_role_scopes carry no row level security, so the service
--    layer is the authorization boundary. roles, permissions and
--    role_permissions already grant SELECT to ssga_app.
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON public.users TO ssga_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.user_role_scopes TO ssga_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.user_permissions TO ssga_app;
GRANT USAGE, SELECT ON SEQUENCE public.user_permissions_id_seq TO ssga_app;

-- ---------------------------------------------------------------------------
-- Self-check
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.permissions WHERE code = 'employee.bank_view')
       OR NOT EXISTS (SELECT 1 FROM public.permissions WHERE code = 'employee.pan_view')
       OR NOT EXISTS (SELECT 1 FROM public.permissions WHERE code = 'salary.view_sensitive') THEN
        RAISE EXCEPTION '49_admin_rbac: sensitive-data permissions were not created';
    END IF;

    -- Every new permission must reach at least one active role, or it is dead.
    IF EXISTS (
        SELECT p.code
          FROM public.permissions p
         WHERE p.code IN ('employee.bank_view','employee.pan_view','salary.view_sensitive')
           AND NOT EXISTS (
               SELECT 1
                 FROM public.role_permissions rp
                 JOIN public.roles r ON r.id = rp.role_id
                WHERE rp.permission_id = p.id
                  AND r.is_active
           )
    ) THEN
        RAISE EXCEPTION '49_admin_rbac: a sensitive-data permission is held by no role';
    END IF;
END
$$;

COMMIT;