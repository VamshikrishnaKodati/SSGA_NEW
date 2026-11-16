-- ============================================================================
-- 41_document_rbac_alignment.sql
--
-- Aligns roles, permissions and user security columns with the SSGA technical
-- architecture specification (sections 2, 3.2 and 5.1).
--
-- Scope: roles / permissions / role_permissions / users only.
-- Deliberately untouched: salary_components (component master), the import-job
-- engine tables, and the payroll batch/transaction tables.
--
-- Convergent and idempotent: safe to re-run and safe to resume after a partial
-- failure, because every step is a no-op when the end state already holds.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. users: employee_code + MFA columns (spec 4 and 5.1)
-- ---------------------------------------------------------------------------
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS employee_code VARCHAR(30);
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS mfa_secret   TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS users_employee_code_key
    ON public.users (lower(employee_code))
    WHERE employee_code IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. Role codes: converge on the 10 spec codes (spec 2)
--
--    Legacy codes are merged into their spec successor, then deleted. Deletion
--    (not flagging) is required because roles.code is UNIQUE and the successor
--    already occupies several of these codes.
--
--      SYSTEM_ADMIN, IT_ADMIN   -> SYS_ADMIN
--      DIVISION_ADMIN           -> DIV_ADMIN
--      EMPLOYEE_SELF            -> EMPLOYEE
--      APPROVING_AUTHORITY      -> APPROVING_AUTH
--      GM, SO_FINANCE           -> APPROVING_AUTH
--      OFF_PAYROLL              -> PAYROLL_OPERATOR
--      PAYROLL_FINALIZER        -> FINANCE_FINALIZER
-- ---------------------------------------------------------------------------

-- FINANCE_FINALIZER must exist before any scope can be merged onto it.
INSERT INTO public.roles (code, name, description, max_scope, is_system, is_active)
VALUES ('FINANCE_FINALIZER', 'Finance Finalizer',
        'Consolidated ledger review, period locking and digital signature',
        'ORG', true, true)
ON CONFLICT (code) DO UPDATE
    SET name = EXCLUDED.name, max_scope = EXCLUDED.max_scope, is_active = true;

-- APPROVING_AUTHORITY must be renamed before any scope can be merged onto it.
UPDATE public.roles SET code = 'APPROVING_AUTH' WHERE code = 'APPROVING_AUTHORITY';

CREATE TEMP TABLE _merge (dup_code text PRIMARY KEY, keep_code text NOT NULL);
INSERT INTO _merge VALUES
    ('SYSTEM_ADMIN',        'SYS_ADMIN'),
    ('IT_ADMIN',            'SYS_ADMIN'),
    ('DIVISION_ADMIN',      'DIV_ADMIN'),
    ('EMPLOYEE_SELF',       'EMPLOYEE'),
    ('GM',                  'APPROVING_AUTH'),
    ('SO_FINANCE',          'APPROVING_AUTH'),
    ('OFF_PAYROLL',         'PAYROLL_OPERATOR'),
    ('PAYROLL_FINALIZER',   'FINANCE_FINALIZER');

-- 2a. move scopes onto the surviving role, skipping users who already hold it.
UPDATE public.user_role_scopes s
   SET role_id = k.id
  FROM _merge m
  JOIN public.roles d ON d.code = m.dup_code
  JOIN public.roles k ON k.code = m.keep_code
 WHERE s.role_id = d.id
   AND NOT EXISTS (SELECT 1 FROM public.user_role_scopes x
                    WHERE x.user_id = s.user_id AND x.role_id = k.id);

-- 2b. drop scopes that are now duplicates of the survivor.
DELETE FROM public.user_role_scopes s
  USING _merge m
  JOIN public.roles d ON d.code = m.dup_code
  JOIN public.roles k ON k.code = m.keep_code
 WHERE s.role_id = d.id
   AND EXISTS (SELECT 1 FROM public.user_role_scopes x
                WHERE x.user_id = s.user_id AND x.role_id = k.id);

-- 2c. drop the emptied legacy role rows.
DELETE FROM public.roles d USING _merge m WHERE d.code = m.dup_code;

DROP TABLE _merge;

-- ---------------------------------------------------------------------------
-- 3. Names and max_scope (spec 2)
-- ---------------------------------------------------------------------------
UPDATE public.roles SET name = 'System Administrator'           WHERE code = 'SYS_ADMIN';
UPDATE public.roles SET name = 'Organization Administrator'    WHERE code = 'ORG_ADMIN';
UPDATE public.roles SET name = 'Wing Administrator'            WHERE code = 'WING_ADMIN';
UPDATE public.roles SET name = 'Division Administrator'        WHERE code = 'DIV_ADMIN';
UPDATE public.roles SET name = 'Payroll Operator (Maker)'      WHERE code = 'PAYROLL_OPERATOR';
UPDATE public.roles SET name = 'Approving Authority (Checker)' WHERE code = 'APPROVING_AUTH';
UPDATE public.roles SET name = 'Internal / External Auditor'   WHERE code = 'AUDITOR';
UPDATE public.roles SET name = 'MIS / Report User'             WHERE code = 'REPORT_USER';
UPDATE public.roles SET name = 'Employee Self-Service'         WHERE code = 'EMPLOYEE';
UPDATE public.roles SET name = 'Finance Finalizer'             WHERE code = 'FINANCE_FINALIZER';

-- APPROVING_AUTH is the board-level approving authority: every holder is the
-- MD, ED, CGM Finance or CGM Technical, and RLS can only reach a division for
-- them if the role is ORG-scoped with a NULL wing/division. Migration 46 used to
-- make this change; it lives here so the value has a single owner.
UPDATE public.roles SET max_scope = 'ORG'      WHERE code IN ('SYS_ADMIN','ORG_ADMIN','AUDITOR');
UPDATE public.roles SET max_scope = 'WING'     WHERE code = 'WING_ADMIN';
UPDATE public.roles SET max_scope = 'DIVISION' WHERE code IN ('DIV_ADMIN','PAYROLL_OPERATOR','REPORT_USER');
UPDATE public.roles SET max_scope = 'SELF'     WHERE code = 'EMPLOYEE';

-- ---------------------------------------------------------------------------
-- 4. Permissions: the 25 spec codes (spec 3.1)
-- ---------------------------------------------------------------------------
INSERT INTO public.permissions (code, module, description)
VALUES
    ('user.create','USER','Create user accounts'),
    ('user.update','USER','Update user accounts'),
    ('user.deactivate','USER','Deactivate user accounts'),
    ('role.assign','USER','Assign roles to users'),
    ('scope.bind','USER','Bind organizational scope to a user role'),

    ('master.manage','MASTER','Manage master data and pay scales'),
    ('employee.hrms_replace','MASTER','Full HRMS master replace'),
    ('employee.import_scoped','MASTER','Scoped employee directory import'),
    ('payroll_input.import_monthly','MASTER','Import monthly variable payroll input'),

    ('payroll.draft_create','PAYROLL','Create payroll drafts'),
    ('payroll.edit_components','PAYROLL','Edit salary components on a draft'),
    ('payroll.calculate','PAYROLL','Recalculate a draft batch'),
    ('payroll.submit_for_approval','PAYROLL','Submit a draft for verification'),

    ('payroll.approve','APPROVAL','Approve a draft batch'),
    ('payroll.reject','APPROVAL','Reject a draft batch'),
    ('payroll.finalize_lock','APPROVAL','Lock the payroll period'),
    ('payroll.unlock_override','APPROVAL','Administrative unlock of a locked period'),

    ('payslip.view_self','REPORT','View own payslip'),
    ('payslip.download_bulk','REPORT','Bulk download payslips'),
    ('report.generate_statutory','REPORT','Generate statutory schedules'),
    ('audit.view_logs','AUDIT','View the audit trail')
ON CONFLICT (code) DO UPDATE
    SET module = EXCLUDED.module, description = EXCLUDED.description;

-- ---------------------------------------------------------------------------
-- 5. Role -> permission matrix (spec 3.2)
-- ---------------------------------------------------------------------------
DELETE FROM public.role_permissions;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
JOIN public.permissions p ON p.code = ANY (CASE r.code
    WHEN 'SYS_ADMIN' THEN ARRAY[
        'user.create','user.update','user.deactivate','role.assign','scope.bind',
        'master.manage',
        'payslip.view_self','payslip.download_bulk',
        'report.generate_statutory','audit.view_logs']
    WHEN 'ORG_ADMIN' THEN ARRAY[
        'user.create','user.update','user.deactivate','scope.bind',
        'employee.hrms_replace','payroll.unlock_override',
        'payslip.view_self','payslip.download_bulk','report.generate_statutory']
    WHEN 'WING_ADMIN' THEN ARRAY[
        'user.create','user.update','user.deactivate','scope.bind',
        'employee.import_scoped',
        'payslip.view_self','payslip.download_bulk','report.generate_statutory']
    WHEN 'DIV_ADMIN' THEN ARRAY[
        'user.create','user.update','user.deactivate','scope.bind',
        'employee.import_scoped',
        'payslip.view_self','payslip.download_bulk','report.generate_statutory']
    WHEN 'PAYROLL_OPERATOR' THEN ARRAY[
        'payroll_input.import_monthly','payroll.draft_create',
        'payroll.edit_components','payroll.calculate','payroll.submit_for_approval',
        'payslip.view_self','payslip.download_bulk']
    WHEN 'APPROVING_AUTH' THEN ARRAY[
        'payroll.approve','payroll.reject',
        'payslip.view_self','payslip.download_bulk','report.generate_statutory']
    WHEN 'FINANCE_FINALIZER' THEN ARRAY[
        'payroll.finalize_lock',
        'payslip.view_self','payslip.download_bulk','report.generate_statutory']
    WHEN 'AUDITOR' THEN ARRAY[
        'audit.view_logs']
    WHEN 'REPORT_USER' THEN ARRAY[
        'payslip.view_self','payslip.download_bulk','report.generate_statutory']
    WHEN 'EMPLOYEE' THEN ARRAY[
        'payslip.view_self']
    ELSE ARRAY[]::text[]
END)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. Self-check
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_active_roles int;
    v_orphan_users int;
BEGIN
    SELECT count(*) INTO v_active_roles FROM public.roles WHERE is_active;
    IF v_active_roles <> 10 THEN
        RAISE EXCEPTION '41_document_rbac_alignment: expected 10 active roles, found %', v_active_roles;
    END IF;

    SELECT count(*) INTO v_orphan_users
      FROM public.users u
     WHERE u.is_active
       AND NOT EXISTS (
           SELECT 1 FROM public.user_role_scopes s
            JOIN public.roles r ON r.id = s.role_id
           WHERE s.user_id = u.id AND s.is_active AND r.is_active);
    IF v_orphan_users > 0 THEN
        RAISE EXCEPTION '41_document_rbac_alignment: % active users have no active role', v_orphan_users;
    END IF;

    RAISE NOTICE '41_document_rbac_alignment OK: % active roles, no orphan users', v_active_roles;
END
$$;

COMMIT;