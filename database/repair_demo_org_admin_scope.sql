-- Restore only the seeded local/staging org_admin assignment when it is
-- missing or expired. This does not reset passwords or remove assignments.
-- Run as a database administrator after migration 28 is applied.

BEGIN;

DO $repair$
DECLARE
    v_user_id BIGINT;
    v_role_id BIGINT;
    v_granted_by BIGINT;
BEGIN
    IF to_regprocedure('app.find_user_role_assignments(bigint)') IS NULL THEN
        RAISE EXCEPTION
            'Migration 28 must be applied before repairing demo role assignments';
    END IF;

    SELECT id
    INTO v_user_id
    FROM public.users
    WHERE username = 'org_admin'
      AND is_active
    FOR UPDATE;

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Active demo account org_admin was not found';
    END IF;

    SELECT id
    INTO v_role_id
    FROM public.roles
    WHERE code = 'ORG_ADMIN'
      AND max_scope = 'ORG'
      AND is_active;

    IF v_role_id IS NULL THEN
        RAISE EXCEPTION 'Active organization-scoped ORG_ADMIN role was not found';
    END IF;

    SELECT id
    INTO v_granted_by
    FROM public.users
    WHERE username = 'admin'
      AND is_active;

    IF v_granted_by IS NULL THEN
        RAISE EXCEPTION 'Active bootstrap admin account was not found';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.user_role_scopes
        WHERE user_id = v_user_id
          AND role_id = v_role_id
          AND is_active
          AND valid_from <= CURRENT_DATE
          AND (valid_to IS NULL OR valid_to >= CURRENT_DATE)
          AND wing_id IS NULL
          AND division_id IS NULL
    ) THEN
        INSERT INTO public.user_role_scopes (
            user_id,
            role_id,
            valid_from,
            is_active,
            granted_by
        )
        VALUES (
            v_user_id,
            v_role_id,
            CURRENT_DATE,
            true,
            v_granted_by
        );

        RAISE NOTICE 'Restored the organization-wide ORG_ADMIN demo assignment';
    ELSE
        RAISE NOTICE 'An active organization-wide ORG_ADMIN assignment already exists';
    END IF;
END;
$repair$;

COMMIT;
