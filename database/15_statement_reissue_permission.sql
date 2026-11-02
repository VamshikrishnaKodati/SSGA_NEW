-- ============================================================================
-- 15_statement_reissue_permission.sql
--
-- Adds the missing statement.reissue permission (the reissue endpoint and the
-- frontend already reference it) and introduces a migration registry so future
-- increments are applied once and in order.
--
-- Idempotent. Applies to existing databases (created before the 01_schema.sql
-- baseline) and to fresh databases built from 00_roles + 01_schema + seeds.
--
-- Policy note: reissue is granted to exactly the roles that already hold
-- statement.generate. Whether reissue must be maker-checker approved is a
-- business policy decision that requires HMWSSB confirmation; no checker rule
-- is imposed here.
-- ============================================================================

BEGIN;

-- ---- migration registry ---------------------------------------------------
CREATE TABLE IF NOT EXISTS public.schema_migrations (
    version     INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO public.schema_migrations (version, name)
VALUES (14, '14_payroll_integrity_and_provenance')
ON CONFLICT (version) DO NOTHING;

GRANT SELECT ON public.schema_migrations TO ssga_app;

-- ---- permission ------------------------------------------------------------
INSERT INTO permissions (code, module, description)
VALUES ('statement.reissue', 'STATEMENT', 'Reissue a superseded salary statement')
ON CONFLICT (code) DO NOTHING;

-- Grant to every role that can already generate statements (keeps the
-- existing privilege boundary; no new role gains statement access).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'statement.reissue'
WHERE r.code IN (
    SELECT r2.code
    FROM roles r2
    JOIN role_permissions rp2 ON rp2.role_id = r2.id
    JOIN permissions p2 ON p2.id = rp2.permission_id
    WHERE p2.code = 'statement.generate'
)
ON CONFLICT DO NOTHING;

INSERT INTO public.schema_migrations (version, name)
VALUES (15, '15_statement_reissue_permission')
ON CONFLICT (version) DO NOTHING;

COMMIT;
