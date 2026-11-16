-- ============================================================================
-- 37_sync_hmwssb_circles_to_divisions.sql
-- Mirror active HMWSSB circles (and any active division rows still missing)
-- into public.divisions so imported circle/division names resolve for every
-- importer whose scope covers them, including organization administrators.
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

INSERT INTO public.divisions (wing_id, name, code, status)
SELECT w.id,
       hd.division_name,
       hd.division_code,
       'ACTIVE'
FROM public.hmwssb_divisions hd
JOIN public.wings w
  ON w.organization_id = 2
 AND w.code = hd.wing_code
WHERE hd.category IN ('Division', 'Circle')
  AND hd.status = 'ACTIVE'
ON CONFLICT (wing_id, code) DO UPDATE
SET name = EXCLUDED.name,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO public.schema_migrations (version, name)
VALUES (37, '37_sync_hmwssb_circles_to_divisions')
ON CONFLICT (version) DO NOTHING;

COMMIT;
