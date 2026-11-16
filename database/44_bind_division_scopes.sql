-- ============================================================================
-- 44_bind_division_scopes.sql
--
-- Binds the 25 division-level user accounts that lost their scope when
-- migration 42 replaced the wings/divisions master.
--
-- Every mapping below is derived from users.display_name, not guessed from the
-- username. Where the display name does not name exactly one unit, the account
-- is left unbound and listed in the report at the bottom of this file.
--
-- Scope rules applied (spec section 1.1):
--   wing_id and division_id are both set for a division-bound role, because
--   can_access_division() matches on division_id and can_access_wing() matches
--   on wing_id, and the two must not disagree.
--   Chief Engineers are division-level in this database (max_scope DIVISION),
--   so they are bound to the division their name states.
--
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Bind the accounts whose display name names exactly one unit
--
--    Only division_id is set. user_role_scopes carries
--    CHECK (num_nonnulls(wing_id, division_id) <= 1), so a division-bound
--    account must leave wing_id NULL; can_access_division() then derives the
--    wing through divisions.wing_id. Setting both is rejected by the database.
-- ---------------------------------------------------------------------------
WITH mapping (username, target_div_code) AS (
    VALUES
        -- Payroll Operators (Makers)
        ('payroll_off1', 'DIV_OM_01'),
        ('payroll_off2', 'DIV_OM_02'),

        -- Divisional / Circle Engineers
        ('ee_div1',  'DIV_OM_01'),
        ('ee_div2',  'DIV_OM_02'),
        ('ee_div7',  'DIV_OM_07'),
        ('ee_div13', 'DIV_OM_13'),
        ('ee_div19', 'DIV_OM_19'),
        ('ee_prj1',  'DIV_PROJ_01'),
        ('ee_trn1',  'CIR_TR_01'),
        ('ee_elec',  'DIV_EL_PROJ'),
        ('ee_qc',    'DIV_QC_VIG'),
        ('ee_board', 'DIV_BRD_OFF'),

        -- Sub-Divisional Officers (circles)
        ('se_cir1', 'CIR_OM_01'),
        ('se_cir2', 'CIR_OM_02'),
        ('se_cir3', 'CIR_OM_03'),
        ('se_cir4', 'CIR_OM_04'),
        ('se_pcc1', 'CIR_PCC_01'),
        ('se_pcc2', 'CIR_PCC_02')
)
UPDATE public.user_role_scopes urs
   SET division_id = d.id,
       wing_id     = NULL
  FROM public.users u
  JOIN mapping m ON m.username = u.username
  JOIN public.divisions d ON d.code = m.target_div_code
 WHERE urs.user_id = u.id
   AND urs.is_active
   AND urs.division_id IS NULL
   AND EXISTS (
       SELECT 1
         FROM public.roles r
        WHERE r.id = urs.role_id
          AND r.code IN ('DIV_ADMIN', 'APPROVING_AUTH', 'PAYROLL_OPERATOR')
   );

-- ---------------------------------------------------------------------------
-- 2. Chief Engineers and the four board-level checkers
--
--    ce_om / ce_projects / ce_trans are named for a wing, not a division, and
--    this database gives DIV_ADMIN max_scope = DIVISION, so they cannot be
--    bound without either a role change to WING_ADMIN or a division choice.
--    gm_md / gm_ed / so_finance / so_tech are board-level checkers that spec
--    section 2 scopes to the whole board.
--    All six are left unbound on purpose. See the report below.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 3. Report
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_bound   int;
    v_pending int;
BEGIN
    SELECT count(*) INTO v_bound
      FROM public.user_role_scopes urs
      JOIN public.roles r ON r.id = urs.role_id
     WHERE urs.is_active
       AND r.code IN ('DIV_ADMIN', 'APPROVING_AUTH', 'PAYROLL_OPERATOR')
       AND urs.division_id IS NOT NULL;

    SELECT count(*) INTO v_pending
      FROM public.user_role_scopes urs
      JOIN public.roles r ON r.id = urs.role_id
     WHERE urs.is_active
       AND r.code IN ('DIV_ADMIN', 'APPROVING_AUTH', 'PAYROLL_OPERATOR')
       AND urs.division_id IS NULL;

    RAISE NOTICE '44_bind_division_scopes: % bound, % pending', v_bound, v_pending;
END
$$;

-- Report anything still unbound. This is a WARNING, not an exception, so the
-- accounts whose mapping is unambiguous still get bound; the six that need a
-- human decision are listed by name and stay NULL, which is exactly the state
-- QA-02 is meant to surface.
DO $$
DECLARE
    v_pending int;
BEGIN
    SELECT count(*) INTO v_pending
      FROM public.user_role_scopes urs
      JOIN public.roles r ON r.id = urs.role_id
     WHERE urs.is_active
       AND r.code IN ('DIV_ADMIN', 'APPROVING_AUTH', 'PAYROLL_OPERATOR')
       AND urs.division_id IS NULL;

    IF v_pending > 0 THEN
        RAISE WARNING
            '44_bind_division_scopes: % accounts remain unbound (expected: the 3 Chief Engineers and the 4 board-level checkers)',
            v_pending;
    END IF;
END
$$;

-- Final state, so the result is visible without a separate query.
SELECT u.username,
       u.display_name,
       r.code AS role,
       w.code AS wing,
       d.code AS division
  FROM public.users u
  JOIN public.user_role_scopes urs ON urs.user_id = u.id
  JOIN public.roles r ON r.id = urs.role_id
  LEFT JOIN public.divisions d ON d.id = urs.division_id
  LEFT JOIN public.wings w ON w.id = urs.wing_id
 WHERE urs.is_active
   AND r.code IN ('DIV_ADMIN', 'APPROVING_AUTH', 'PAYROLL_OPERATOR')
 ORDER BY (d.code IS NULL), d.code, u.username;

COMMIT;