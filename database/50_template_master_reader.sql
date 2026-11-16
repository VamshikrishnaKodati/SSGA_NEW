-- 50_template_master_reader.sql
--
-- The salary import template generator is a standalone CLI, not a web request,
-- so it has no RLS session context: app.user_id is NULL and the
-- divisions_scope_policy therefore matches nothing. Connecting as ssga_app
-- silently returned zero divisions.
--
-- This function is the app's own data path for the template, so the generator
-- reads the same master data the web application does instead of keeping a
-- hand-maintained copy that drifted twice.
--
-- It returns only non-sensitive organization master data: the code, name, unit
-- type and owning wing of every ACTIVE division and circle. No employee or
-- payroll data is reachable through it.
--
-- Idempotent: safe to run more than once.

CREATE OR REPLACE FUNCTION app.template_divisions()
RETURNS TABLE (
    code        text,
    name        text,
    unit_type   text,
    wing_code   text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
    SELECT d.code, d.name, d.unit_type, w.code
      FROM public.divisions d
      JOIN public.wings w ON w.id = d.wing_id
     WHERE d.status = 'ACTIVE'
     ORDER BY d.code
$function$;

GRANT EXECUTE ON FUNCTION app.template_divisions() TO ssga_app;