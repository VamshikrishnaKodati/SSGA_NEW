-- 38_assignable_import_users.sql — user picker for the import assignment form.
--
-- ssga_app has no privileges on public.users by design, so the picker reads a
-- security-definer function instead of the table. This is a convenience list
-- only: app.can_assign_import_role still decides who may actually be assigned.

CREATE OR REPLACE FUNCTION app.find_assignable_users()
RETURNS TABLE (
    user_id BIGINT,
    username TEXT,
    display_name TEXT
)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $$
    SELECT u.id, u.username, u.display_name
    FROM public.users u
    WHERE u.is_active = TRUE
    ORDER BY u.display_name, u.username
$$;

REVOKE ALL ON FUNCTION app.find_assignable_users() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.find_assignable_users() TO ssga_app;