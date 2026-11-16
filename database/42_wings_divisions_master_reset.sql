-- ============================================================================
-- 42_wings_divisions_master_reset.sql
--
-- Replaces the wings and divisions master data with the board-approved list:
-- 6 wings and 50 divisions / billing units.
--
-- Existing rows are deleted, not merged, per instruction.
--
-- Live schema mapping (the source list uses different column names):
--   wing_code     -> wings.code        wing_name   -> wings.name
--   is_active     -> wings.status      division_code -> divisions.code
--   division_name -> divisions.name    is_active     -> divisions.status
--   unit_type     -> divisions.unit_type (added by this migration; the column
--                    did not exist and the divisions table has no such field)
--
-- The one payroll batch (id=1, REOPENED, period 2026-07, 0 salary
-- transactions) is deleted as instructed: its division 25 / wing 7 no longer
-- exist in the approved list, so it has no valid scope.
--
-- hmwssb_divisions is a separate table used by the SRS staging import path and
-- is deliberately NOT touched.
--
-- NOT safe to re-run once the database holds real data. The master rows are
-- deleted and re-inserted, and every reference to a division is nulled out, so
-- a second run silently unassigns every employee and wipes all user scope
-- bindings (migrations 44 and 46 must then be replayed). The guard below turns
-- that into a loud refusal instead.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 0. Refuse to wipe master data that something already depends on
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_assigned int;
BEGIN
    SELECT count(*) INTO v_assigned
      FROM public.employees
     WHERE division_id IS NOT NULL;

    IF v_assigned > 0 THEN
        RAISE EXCEPTION
            '42_wings_divisions_master_reset: % employees are assigned to a division; refusing to wipe master data. Run this only on a fresh database, or back up and clear the assignments deliberately.',
            v_assigned;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. Clear every foreign key reference to wings / divisions
--
--    Order matters: wards and the payroll batch reference divisions, and the
--    user scope references both. Everything else is empty today but is cleared
--    defensively so a later run cannot fail on a row added since this script
--    was written.
-- ---------------------------------------------------------------------------

-- Delete the one unscopable payroll batch (id 1, header only, no transactions).
-- Scoped by id on purpose: a predicate on division_id/wing_id would also match
-- any real batch that happens to carry a division, destroying its history.
DELETE FROM public.payroll_batches WHERE id = 1;

DELETE FROM public.wards;

UPDATE public.employees              SET division_id = NULL WHERE division_id IS NOT NULL;
UPDATE public.employee_transfers     SET from_division_id = NULL WHERE from_division_id IS NOT NULL;
UPDATE public.employee_transfers     SET to_division_id   = NULL WHERE to_division_id   IS NOT NULL;
UPDATE public.import_batches         SET division_id = NULL WHERE division_id IS NOT NULL;
UPDATE public.import_job_assignments SET division_id = NULL WHERE division_id IS NOT NULL;
UPDATE public.import_job_corrections SET target_division_id = NULL WHERE target_division_id IS NOT NULL;
UPDATE public.import_job_sheet_issues SET target_division_id = NULL WHERE target_division_id IS NOT NULL;
UPDATE public.import_job_workflow_events SET division_id = NULL WHERE division_id IS NOT NULL;

-- Users lose their wing / division scope and must be re-bound afterwards.
UPDATE public.user_role_scopes SET wing_id = NULL, division_id = NULL
 WHERE wing_id IS NOT NULL OR division_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. Delete the existing master data and restart the identity sequences
-- ---------------------------------------------------------------------------
DELETE FROM public.divisions;
DELETE FROM public.wings;

ALTER SEQUENCE public.divisions_id_seq RESTART WITH 1;
ALTER SEQUENCE public.wings_id_seq     RESTART WITH 1;

-- ---------------------------------------------------------------------------
-- 3. Insert the 6 approved wings (spec section 1.1)
-- ---------------------------------------------------------------------------
INSERT INTO public.wings (organization_id, code, name, status)
SELECT o.id, v.code, v.name, 'ACTIVE'
  FROM (SELECT id FROM public.organizations ORDER BY id LIMIT 1) o
  CROSS JOIN (VALUES
    ('WING_OM',        'Operations & Maintenance (O&M) Wing'),
    ('WING_PROJ',      'Projects Wing'),
    ('WING_TRANS',     'Transmission Wing'),
    ('WING_ELEC',      'Electrical & Technical Wing'),
    ('WING_VIG_QC',    'Vigilance & Quality Control Wing'),
    ('WING_ADMIN_FIN', 'Administration, Revenue & Finance Wing')
  ) AS v(code, name);

-- ---------------------------------------------------------------------------
-- 4. Insert the 50 approved divisions / billing units
--
--    Resolved by wing code rather than a hard-coded id, so the mapping survives
--    a re-run.
-- ---------------------------------------------------------------------------
ALTER TABLE public.divisions
    ADD COLUMN IF NOT EXISTS unit_type VARCHAR(20) NOT NULL DEFAULT 'DIVISION';

INSERT INTO public.divisions (wing_id, code, name, status, unit_type)
SELECT w.id, v.code, v.name, 'ACTIVE', v.unit_type
  FROM (VALUES
    ('WING_OM',        'DIV_OM_01',   'O&M Division No.I',                     'DIVISION'),
    ('WING_OM',        'DIV_OM_02',   'O&M Division No.II',                    'DIVISION'),
    ('WING_OM',        'DIV_OM_03',   'O&M Division No.III',                   'DIVISION'),
    ('WING_OM',        'DIV_OM_04',   'O&M Division No.IV',                    'DIVISION'),
    ('WING_OM',        'DIV_OM_05',   'O&M Division No.V',                     'DIVISION'),
    ('WING_OM',        'DIV_OM_06',   'O&M Division No.VI',                    'DIVISION'),
    ('WING_OM',        'DIV_OM_07',   'O&M Division No.VII',                   'DIVISION'),
    ('WING_OM',        'DIV_OM_08',   'O&M Division No.VIII',                  'DIVISION'),
    ('WING_OM',        'DIV_OM_09',   'O&M Division No.IX',                    'DIVISION'),
    ('WING_OM',        'DIV_OM_10',   'O&M Division No.X',                     'DIVISION'),
    ('WING_OM',        'DIV_OM_11',   'O&M Division No.XI',                    'DIVISION'),
    ('WING_OM',        'DIV_OM_12',   'O&M Division No.XII',                   'DIVISION'),
    ('WING_OM',        'DIV_OM_13',   'O&M Division No.XIII',                  'DIVISION'),
    ('WING_OM',        'DIV_OM_14',   'O&M Division No.XIV',                   'DIVISION'),
    ('WING_OM',        'DIV_OM_15',   'O&M Division No.XV',                    'DIVISION'),
    ('WING_OM',        'DIV_OM_16',   'O&M Division No.XVI',                   'DIVISION'),
    ('WING_OM',        'DIV_OM_17',   'O&M Division No.XVII',                  'DIVISION'),
    ('WING_OM',        'DIV_OM_18',   'O&M Division No.XVIII',                 'DIVISION'),
    ('WING_OM',        'DIV_OM_19',   'O&M Division No.XIX',                   'DIVISION'),
    ('WING_OM',        'DIV_OM_20',   'O&M Division No.XX',                    'DIVISION'),
    ('WING_OM',        'DIV_OM_21',   'O&M Division No.XXI',                   'DIVISION'),
    ('WING_OM',        'DIV_OM_22',   'O&M Division No.XXII',                  'DIVISION'),
    ('WING_OM',        'DIV_OM_23',   'O&M Division No.XXIII',                 'DIVISION'),
    ('WING_OM',        'DIV_OM_24',   'O&M Division No.XXIV',                  'DIVISION'),
    ('WING_OM',        'DIV_OM_25',   'O&M Division No.XXV',                   'DIVISION'),
    ('WING_PROJ',      'DIV_PROJ_01', 'Project Division No.I',                 'DIVISION'),
    ('WING_PROJ',      'DIV_PROJ_02', 'Project Division No.II',                'DIVISION'),
    ('WING_PROJ',      'DIV_PROJ_03', 'Project Division No.III',               'DIVISION'),
    ('WING_PROJ',      'DIV_PROJ_04', 'Project Division No.IV',                'DIVISION'),
    ('WING_PROJ',      'DIV_PROJ_05', 'Project Division No.V',                 'DIVISION'),
    ('WING_PROJ',      'DIV_PROJ_06', 'Project Division No.VI',                'DIVISION'),
    ('WING_PROJ',      'DIV_PROJ_07', 'Project Division No.VII',               'DIVISION'),
    ('WING_PROJ',      'DIV_PROJ_08', 'Project Division No.VIII',              'DIVISION'),
    ('WING_OM',        'CIR_OM_01',   'O&M Circle-I',                          'CIRCLE'),
    ('WING_OM',        'CIR_OM_02',   'O&M Circle-II',                         'CIRCLE'),
    ('WING_OM',        'CIR_OM_03',   'O&M Circle-III',                        'CIRCLE'),
    ('WING_OM',        'CIR_OM_04',   'O&M Circle-IV',                         'CIRCLE'),
    ('WING_OM',        'CIR_OM_05',   'O&M Circle-V',                          'CIRCLE'),
    ('WING_PROJ',      'CIR_PCC_01',  'PCC-I',                                 'CIRCLE'),
    ('WING_PROJ',      'CIR_PCC_02',  'PCC-II',                                'CIRCLE'),
    ('WING_PROJ',      'CIR_PCC_03',  'PCC-III',                               'CIRCLE'),
    ('WING_TRANS',     'CIR_TR_00',   'Transmission Circle',                   'CIRCLE'),
    ('WING_TRANS',     'CIR_TR_01',   'Transmission Circle-I',                 'CIRCLE'),
    ('WING_TRANS',     'CIR_TR_02',   'Transmission Circle-II',                'CIRCLE'),
    ('WING_TRANS',     'CIR_TR_03',   'Transmission Circle-III',               'CIRCLE'),
    ('WING_TRANS',     'CIR_TR_04',   'Transmission Circle-IV',                'CIRCLE'),
    ('WING_ELEC',      'DIV_EL_PROJ', 'Electrical Project Division',           'DIVISION'),
    ('WING_VIG_QC',    'DIV_QC_VIG',  'Quality Control & Vigilance Division',  'DIVISION'),
    ('WING_ADMIN_FIN', 'DIV_CEN_STR', 'Central Stores Division',               'DIVISION'),
    ('WING_ADMIN_FIN', 'DIV_BRD_OFF', 'Board Office',                          'HEAD_OFFICE')
  ) AS v(wing_code, code, name, unit_type)
  JOIN public.wings w ON w.code = v.wing_code;

-- ---------------------------------------------------------------------------
-- 5. Self-check
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_wings int;
    v_divs  int;
    v_bad   int;
BEGIN
    SELECT count(*) INTO v_wings FROM public.wings;
    SELECT count(*) INTO v_divs  FROM public.divisions;

    IF v_wings <> 6 THEN
        RAISE EXCEPTION '42_wings_divisions_master_reset: expected 6 wings, found %', v_wings;
    END IF;
    IF v_divs <> 50 THEN
        RAISE EXCEPTION '42_wings_divisions_master_reset: expected 50 divisions, found %', v_divs;
    END IF;

    SELECT count(*) INTO v_bad
      FROM public.divisions d
      LEFT JOIN public.wings w ON w.id = d.wing_id
     WHERE w.id IS NULL;
    IF v_bad > 0 THEN
        RAISE EXCEPTION '42_wings_divisions_master_reset: % divisions have no wing', v_bad;
    END IF;

    RAISE NOTICE '42_wings_divisions_master_reset OK: % wings, % divisions', v_wings, v_divs;
END
$$;

COMMIT;