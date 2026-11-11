-- ============================================================================
-- 36_sync_hmwssb_divisions.sql
-- Mirror the HMWSSB wing/division reference into the app's public tables so
-- imported division names resolve against public.divisions (and employees can
-- store both the public division id and the hmwssb_division_id).
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

INSERT INTO public.wings (organization_id, name, code, status)
SELECT DISTINCT ON (hd.wing_code)
       2,
       hd.wing_name,
       hd.wing_code,
       'ACTIVE'
FROM public.hmwssb_divisions hd
WHERE hd.category = 'Division'
  AND hd.wing_code IS NOT NULL
ORDER BY hd.wing_code, hd.id
ON CONFLICT (organization_id, code) DO UPDATE
SET name = EXCLUDED.name,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO public.divisions (wing_id, name, code, status)
SELECT w.id,
       hd.division_name,
       hd.division_code,
       'ACTIVE'
FROM public.hmwssb_divisions hd
JOIN public.wings w
  ON w.organization_id = 2
 AND w.code = hd.wing_code
WHERE hd.category = 'Division'
  AND hd.status = 'ACTIVE'
ON CONFLICT (wing_id, code) DO UPDATE
SET name = EXCLUDED.name,
    status = EXCLUDED.status,
    updated_at = now();

COMMIT;
