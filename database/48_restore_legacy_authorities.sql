-- ============================================================================
-- 48_restore_legacy_authorities.sql
--
-- Migration 41 rebuilt role_permissions from the 25 spec codes (section 3.2)
-- and deleted the legacy grants, but the backend controllers still authorize on
-- the pre-existing permission vocabulary. The result was that 12 of the 13
-- authority strings the controllers check were held by no role at all, so every
-- payroll and import endpoint returned 403 for every user, SYS_ADMIN included.
--
-- This migration restores those grants. It does not change the spec matrix:
-- every row below is added alongside the spec grants 41 already made.
--
-- The mapping is a compatibility judgement, not a spec rule, and is written out
-- explicitly so it can be reviewed:
--   read codes (payroll.view, salary.view, statement.view, report.view) follow
--     whoever can already see that data under the spec matrix, plus AUDITOR and
--     REPORT_USER for the read-only roles;
--   write codes go only to the role that performs the action under the spec
--     (PAYROLL_OPERATOR makes, APPROVING_AUTH checks, FINANCE_FINALIZER locks);
--   administrative codes stay with SYS_ADMIN, plus ORG_ADMIN where the spec
--     already gives ORG_ADMIN organization-wide duties.
--
-- Row Level Security still bounds every one of these reads to the caller's
-- wing / division / organization, so a broad grant does not widen visibility.
--
-- Idempotent: safe to re-run.
-- ============================================================================

BEGIN;

-- The controllers check salary.approve, but no migration ever created it: the
-- permission was missing from the catalogue rather than merely ungranted.
INSERT INTO public.permissions (code, module, description)
VALUES ('salary.approve','APPROVAL','Approve a salary input batch')
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM public.roles r
  JOIN public.permissions p ON p.code = ANY (CASE r.code
    WHEN 'SYS_ADMIN' THEN ARRAY[
        'user.manage','organization.manage','audit.view',
        'division.view','employee.view','employee.create','employee.update',
        'export.download','import.manage',
        'payroll.period.manage',
        'payroll.view','payroll.prepare','payroll.verify','payroll.submit',
        'payroll.approve','payroll.finalize','payroll.reopen',
        'salary.view','salary.manage','salary.prepare','salary.approve',
        'statement.view','statement.download','statement.generate','statement.reissue',
        'report.view']

    WHEN 'ORG_ADMIN' THEN ARRAY[
        'user.manage','organization.manage','audit.view',
        'division.view','employee.view','employee.create','employee.update',
        'export.download','import.manage',
        'payroll.period.manage',
        'payroll.view','payroll.reopen',
        'salary.view',
        'statement.view','statement.download',
        'report.view']

    WHEN 'WING_ADMIN' THEN ARRAY[
        'division.view','employee.view','employee.create','employee.update',
        'export.download','import.manage',
        'payroll.view','salary.view',
        'statement.view','statement.download',
        'report.view']

    WHEN 'DIV_ADMIN' THEN ARRAY[
        'division.view','employee.view','employee.create','employee.update',
        'export.download','import.manage',
        'payroll.view','salary.view',
        'statement.view','statement.download',
        'report.view']

    WHEN 'PAYROLL_OPERATOR' THEN ARRAY[
        'import.manage','export.download',
        'payroll.view','payroll.prepare','payroll.verify','payroll.submit',
        'salary.view','salary.manage','salary.prepare',
        'statement.view','statement.download','statement.generate',
        'report.view']

    WHEN 'APPROVING_AUTH' THEN ARRAY[
        'export.download',
        'payroll.view','payroll.approve',
        'salary.view','salary.approve',
        'statement.view','statement.download',
        'report.view']

    WHEN 'FINANCE_FINALIZER' THEN ARRAY[
        'export.download',
        'payroll.view','payroll.finalize',
        'salary.view',
        'statement.view','statement.download','statement.generate','statement.reissue',
        'report.view']

    WHEN 'AUDITOR' THEN ARRAY[
        'audit.view','division.view',
        'payroll.view','salary.view',
        'statement.view',
        'report.view']

    WHEN 'REPORT_USER' THEN ARRAY[
        'export.download',
        'payroll.view','salary.view',
        'statement.view',
        'report.view']

    ELSE ARRAY[]::text[]
  END)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Self-check: every authority the backend checks must be held by at least one
-- active role, or the endpoint it guards is unreachable.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_missing text;
BEGIN
    SELECT string_agg(c.code, ', ' ORDER BY c.code) INTO v_missing
      FROM (VALUES
        ('audit.view'), ('division.view'),
        ('employee.create'), ('employee.update'), ('employee.view'),
        ('export.download'), ('import.manage'), ('organization.manage'),
        ('payroll.approve'), ('payroll.finalize'), ('payroll.period.manage'),
        ('payroll.prepare'), ('payroll.reopen'), ('payroll.submit'),
        ('payroll.verify'), ('payroll.view'),
        ('report.view'),
        ('salary.approve'), ('salary.manage'), ('salary.prepare'), ('salary.view'),
        ('statement.download'), ('statement.generate'), ('statement.reissue'),
        ('statement.view'), ('user.manage')
      ) AS c(code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM public.role_permissions rp
           JOIN public.roles r ON r.id = rp.role_id
          WHERE r.is_active
            AND rp.permission_id = (SELECT id FROM public.permissions WHERE code = c.code)
     );

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            '48_restore_legacy_authorities: no active role holds %', v_missing;
    END IF;
END
$$;

COMMIT;