-- ============================================================
-- SSGA NEW - 25. ORGANIZATION-SCOPED PAYROLL PERIOD MANAGEMENT
-- PostgreSQL 17
-- ============================================================

BEGIN;

INSERT INTO public.permissions (code, module, description)
VALUES (
    'payroll.period.manage',
    'PAYROLL',
    'Manage organization-wide payroll periods'
)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
CROSS JOIN public.permissions p
WHERE r.code = 'SYSTEM_ADMIN'
  AND p.code = 'payroll.period.manage'
ON CONFLICT DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
JOIN public.permissions p ON p.code = 'payroll.period.manage'
WHERE r.code = 'ORG_ADMIN'
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION app.can_manage_payroll_period(
    p_organization_id BIGINT
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $function$
    SELECT p_organization_id IS NOT NULL
       AND EXISTS (
            SELECT 1
            FROM public.organizations o
            WHERE o.id = p_organization_id
       )
       AND EXISTS (
            SELECT 1
            FROM public.user_role_scopes s
            JOIN public.roles r ON r.id = s.role_id
            JOIN public.users u ON u.id = s.user_id
            JOIN public.role_permissions rp ON rp.role_id = r.id
            JOIN public.permissions p ON p.id = rp.permission_id
            WHERE s.user_id = app.current_user_id()
              AND s.is_active
              AND s.valid_from <= CURRENT_DATE
              AND (s.valid_to IS NULL OR s.valid_to >= CURRENT_DATE)
              AND s.wing_id IS NULL
              AND s.division_id IS NULL
              AND r.is_active
              AND r.max_scope = 'ORG'
              AND u.is_active
              AND p.code = 'payroll.period.manage'
       );
$function$;

REVOKE ALL ON FUNCTION app.can_manage_payroll_period(BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.can_manage_payroll_period(BIGINT) TO ssga_app;

COMMIT;
