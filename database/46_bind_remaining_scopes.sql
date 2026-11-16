-- ============================================================================
-- 46_bind_remaining_scopes.sql
--
-- Closes out the last 7 unbound division-level accounts, so all 25 accounts
-- that lost their scope in migration 42 are resolved:
--   18 division-bound  (migration 44)
--    4 APPROVING_AUTH  (promoted to ORG here)
--    3 Chief Engineers (moved to WING_ADMIN here)
--
-- Deliberately a single migration, not 46 + 47. The two halves are not
-- independently applicable: until the Chief Engineers leave DIV_ADMIN, the
-- DIVISION-scoped check that ends the first half would fail on their three
-- NULL division_ids and roll the whole thing back. One transaction, one check.
--
-- Constraint respected throughout:
--   CHECK (num_nonnulls(wing_id, division_id) <= 1)
-- so ORG scopes keep both NULL, WING scopes set wing_id only, DIVISION scopes
-- set division_id only.
--
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. The 4 board-level checkers hold the ORG-scoped approving authority
--
--    Migration 41 owns roles.max_scope for APPROVING_AUTH ('ORG'); nothing is
--    set here. All four holders (MD, ED, CGM Finance, CGM Technical) are
--    board-level, and they are the only APPROVING_AUTH accounts in the
--    database. Leaving the role at DIVISION while binding those accounts to a
--    division would let RLS block them from approving the other 49 divisions.
-- ---------------------------------------------------------------------------

-- Their scopes must be NULL/NULL; this keeps the invariant true after any
-- future partial bind and is a no-op on a clean database.
UPDATE public.user_role_scopes urs
   SET wing_id     = NULL,
       division_id = NULL
  FROM public.roles r
 WHERE urs.role_id = r.id
   AND r.code = 'APPROVING_AUTH'
   AND (urs.wing_id IS NOT NULL OR urs.division_id IS NOT NULL);

-- ---------------------------------------------------------------------------
-- 2. Chief Engineers move from DIV_ADMIN to WING_ADMIN
--
--    They administer a wing, not a division. WING_ADMIN carries the same 8
--    permissions as DIV_ADMIN (verified: the two sets are identical), so this
--    changes the scope tier only and removes no capability.
-- ---------------------------------------------------------------------------
WITH ce_mapping (username, target_wing_code) AS (
    VALUES
        ('ce_om',       'WING_OM'),
        ('ce_projects', 'WING_PROJ'),
        ('ce_trans',    'WING_TRANS')
)
UPDATE public.user_role_scopes urs
   SET role_id     = (SELECT id FROM public.roles WHERE code = 'WING_ADMIN'),
       wing_id     = w.id,
       division_id = NULL
  FROM public.users u
  JOIN ce_mapping m ON m.username = u.username
  JOIN public.wings w ON w.code = m.target_wing_code
 WHERE urs.user_id = u.id
   AND urs.is_active
   AND (
        urs.role_id <> (SELECT id FROM public.roles WHERE code = 'WING_ADMIN')
        OR urs.wing_id IS DISTINCT FROM w.id
        OR urs.division_id IS NOT NULL
   );

-- ---------------------------------------------------------------------------
-- 3. QA-02 self-check
--
--    The assertion is now tier-aware rather than "no NULL division_id", because
--    ORG and WING scopes are supposed to have a NULL division_id.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_bad_div  int;
    v_bad_wing int;
    v_bad_org  int;
    v_both     int;
BEGIN
    -- DIVISION scopes: exactly division_id, and wing_id must stay NULL
    SELECT count(*) INTO v_bad_div
      FROM public.user_role_scopes urs
      JOIN public.roles r ON r.id = urs.role_id
     WHERE urs.is_active
       AND r.max_scope = 'DIVISION'
       AND (urs.division_id IS NULL OR urs.wing_id IS NOT NULL);

    -- WING scopes: exactly wing_id, and division_id must stay NULL
    SELECT count(*) INTO v_bad_wing
      FROM public.user_role_scopes urs
      JOIN public.roles r ON r.id = urs.role_id
     WHERE urs.is_active
       AND r.max_scope = 'WING'
       AND (urs.wing_id IS NULL OR urs.division_id IS NOT NULL);

    -- ORG scopes: both NULL
    SELECT count(*) INTO v_bad_org
      FROM public.user_role_scopes urs
      JOIN public.roles r ON r.id = urs.role_id
     WHERE urs.is_active
       AND r.max_scope = 'ORG'
       AND (urs.wing_id IS NOT NULL OR urs.division_id IS NOT NULL);

    -- The database constraint itself, restated as a belt-and-braces check
    SELECT count(*) INTO v_both
      FROM public.user_role_scopes
     WHERE num_nonnulls(wing_id, division_id) > 1;

    IF v_bad_div > 0 OR v_bad_wing > 0 OR v_bad_org > 0 OR v_both > 0 THEN
        RAISE EXCEPTION
            '46_bind_remaining_scopes: QA-02 failed (div=%, wing=%, org=%, both_set=%)',
            v_bad_div, v_bad_wing, v_bad_org, v_both;
    END IF;
END
$$;

-- Final state, so the result is visible without a separate query.
SELECT u.username,
       u.display_name,
       r.code  AS role,
       r.max_scope,
       w.code  AS wing,
       d.code  AS division
  FROM public.users u
  JOIN public.user_role_scopes urs ON urs.user_id = u.id
  JOIN public.roles r ON r.id = urs.role_id
  LEFT JOIN public.divisions d ON d.id = urs.division_id
  LEFT JOIN public.wings w ON w.id = urs.wing_id
 WHERE urs.is_active
   AND r.code IN ('DIV_ADMIN', 'APPROVING_AUTH', 'PAYROLL_OPERATOR', 'WING_ADMIN')
 ORDER BY (d.code IS NULL AND w.code IS NULL), w.code, d.code, u.username;

COMMIT;