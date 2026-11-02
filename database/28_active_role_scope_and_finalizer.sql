-- SSGA 28: bind authenticated sessions to one server-validated role/scope.
-- Adds a separate payroll finalizer role; does not alter payroll transitions.
-- Apply only after 27_payroll_history_imports.sql.

BEGIN;

INSERT INTO public.roles (code, name, description, max_scope, is_system)
VALUES (
    'PAYROLL_FINALIZER',
    'Payroll Finalizer',
    'Finalize approved payroll and issue authorized statements',
    'DIVISION',
    true
)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r
JOIN public.permissions p ON p.code IN (
    'employee.view',
    'salary.view',
    'payroll.view',
    'payroll.finalize',
    'statement.generate',
    'statement.view',
    'statement.download',
    'report.view',
    'audit.view'
)
WHERE r.code = 'PAYROLL_FINALIZER'
ON CONFLICT DO NOTHING;

DELETE FROM public.role_permissions rp
USING public.roles r, public.permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'APPROVING_AUTHORITY'
  AND p.code = 'payroll.finalize';

CREATE OR REPLACE FUNCTION app.current_role_scope_id()
RETURNS BIGINT
LANGUAGE SQL
STABLE
AS $function$
    SELECT NULLIF(
        current_setting('app.active_role_scope_id', true),
        ''
    )::BIGINT;
$function$;

CREATE OR REPLACE FUNCTION app.find_user_role_assignments(p_user_id BIGINT)
RETURNS TABLE (
    assignment_id BIGINT,
    role_code TEXT,
    role_name TEXT,
    max_scope TEXT,
    wing_id BIGINT,
    wing_name TEXT,
    division_id BIGINT,
    division_name TEXT,
    scope_label TEXT
)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path TO pg_catalog, public, app
AS $function$
    SELECT
        s.id,
        r.code::TEXT,
        r.name::TEXT,
        r.max_scope::TEXT,
        s.wing_id,
        w.name::TEXT,
        s.division_id,
        d.name::TEXT,
        CASE r.max_scope
            WHEN 'ORG' THEN 'Organization-wide'
            WHEN 'SELF' THEN 'Personal records'
            WHEN 'WING' THEN COALESCE(w.name, 'Assigned wing')
            WHEN 'DIVISION' THEN COALESCE(d.name, 'Assigned division')
            ELSE 'Assigned scope'
        END
    FROM public.user_role_scopes s
    JOIN public.roles r ON r.id = s.role_id
    JOIN public.users u ON u.id = s.user_id
    LEFT JOIN public.wings w ON w.id = s.wing_id
    LEFT JOIN public.divisions d ON d.id = s.division_id
    WHERE s.user_id = p_user_id
      AND s.is_active
      AND r.is_active
      AND u.is_active
      AND CURRENT_DATE >= s.valid_from
      AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
    ORDER BY r.name, s.id;
$function$;

CREATE OR REPLACE FUNCTION app.find_assignment_authorities(
    p_user_id BIGINT,
    p_assignment_id BIGINT
)
RETURNS TABLE (authority TEXT)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path TO pg_catalog, public, app
AS $function$
    SELECT 'ROLE_' || r.code
    FROM public.user_role_scopes s
    JOIN public.roles r ON r.id = s.role_id
    JOIN public.users u ON u.id = s.user_id
    WHERE s.id = p_assignment_id
      AND s.user_id = p_user_id
      AND s.is_active
      AND r.is_active
      AND u.is_active
      AND CURRENT_DATE >= s.valid_from
      AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)

    UNION

    SELECT p.code
    FROM public.user_role_scopes s
    JOIN public.roles r ON r.id = s.role_id
    JOIN public.role_permissions rp ON rp.role_id = r.id
    JOIN public.permissions p ON p.id = rp.permission_id
    JOIN public.users u ON u.id = s.user_id
    WHERE s.id = p_assignment_id
      AND s.user_id = p_user_id
      AND s.is_active
      AND r.is_active
      AND u.is_active
      AND CURRENT_DATE >= s.valid_from
      AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
    ORDER BY 1;
$function$;

REVOKE ALL ON FUNCTION app.find_user_role_assignments(BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION app.find_assignment_authorities(BIGINT, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.find_user_role_assignments(BIGINT) TO ssga_app;
GRANT EXECUTE ON FUNCTION app.find_assignment_authorities(BIGINT, BIGINT) TO ssga_app;

CREATE OR REPLACE FUNCTION app.has_org_scope()
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.users u ON u.id = s.user_id
        WHERE s.id = app.current_role_scope_id()
          AND s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND r.max_scope = 'ORG'
          AND s.wing_id IS NULL AND s.division_id IS NULL
    );
$function$;

REVOKE ALL ON FUNCTION app.has_org_scope() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.has_org_scope() TO ssga_app;

CREATE OR REPLACE FUNCTION app.can_access_wing(p_wing_id BIGINT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.users u ON u.id = s.user_id
        WHERE s.id = app.current_role_scope_id()
          AND s.user_id = app.current_user_id()
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND (
              (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
              OR (r.max_scope = 'WING' AND s.wing_id = p_wing_id)
          )
    );
$function$;

CREATE OR REPLACE FUNCTION app.can_access_division(p_division_id BIGINT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.divisions d
        JOIN public.wings w ON w.id = d.wing_id
        JOIN public.user_role_scopes s
          ON s.id = app.current_role_scope_id()
         AND s.user_id = app.current_user_id()
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.users u ON u.id = s.user_id
        WHERE d.id = p_division_id
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND (
              (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
              OR (r.max_scope = 'WING' AND s.wing_id = w.id)
              OR (r.max_scope = 'DIVISION' AND s.division_id = d.id)
          )
    );
$function$;

CREATE OR REPLACE FUNCTION app.can_access_employee(p_employee_id BIGINT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.employees e
        JOIN public.users u ON u.id = app.current_user_id()
        JOIN public.user_role_scopes s
          ON s.id = app.current_role_scope_id()
         AND s.user_id = u.id
        JOIN public.roles r ON r.id = s.role_id
        WHERE e.id = p_employee_id
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND (
              (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
              OR (
                  r.max_scope = 'WING'
                  AND EXISTS (
                      SELECT 1 FROM public.divisions d
                      WHERE d.id = e.division_id AND d.wing_id = s.wing_id
                  )
              )
              OR (r.max_scope = 'DIVISION' AND e.division_id = s.division_id)
              OR (r.max_scope = 'SELF' AND u.employee_id = e.id)
          )
    );
$function$;

CREATE OR REPLACE FUNCTION app.can_create_division(p_wing_id BIGINT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.wings w
        JOIN public.user_role_scopes s
          ON s.id = app.current_role_scope_id()
         AND s.user_id = app.current_user_id()
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.users u ON u.id = s.user_id
        WHERE w.id = p_wing_id
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND (
              (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
              OR (r.max_scope = 'WING' AND s.wing_id = w.id)
          )
    );
$function$;

CREATE OR REPLACE FUNCTION app.can_create_employee(p_division_id BIGINT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, app, pg_catalog
SET row_security = off
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.divisions d
        JOIN public.wings w ON w.id = d.wing_id
        JOIN public.user_role_scopes s
          ON s.id = app.current_role_scope_id()
         AND s.user_id = app.current_user_id()
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.users u ON u.id = s.user_id
        WHERE d.id = p_division_id
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND (
              (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
              OR (r.max_scope = 'WING' AND s.wing_id = w.id)
              OR (r.max_scope = 'DIVISION' AND s.division_id = d.id)
          )
    );
$function$;

CREATE OR REPLACE FUNCTION app.can_manage_payroll_period(p_organization_id BIGINT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $function$
    SELECT p_organization_id IS NOT NULL
       AND EXISTS (
            SELECT 1 FROM public.organizations o
            WHERE o.id = p_organization_id
       )
       AND EXISTS (
            SELECT 1
            FROM public.user_role_scopes s
            JOIN public.roles r ON r.id = s.role_id
            JOIN public.users u ON u.id = s.user_id
            JOIN public.role_permissions rp ON rp.role_id = r.id
            JOIN public.permissions p ON p.id = rp.permission_id
            WHERE s.id = app.current_role_scope_id()
              AND s.user_id = app.current_user_id()
              AND s.is_active
              AND s.valid_from <= CURRENT_DATE
              AND (s.valid_to IS NULL OR s.valid_to >= CURRENT_DATE)
              AND s.wing_id IS NULL AND s.division_id IS NULL
              AND r.is_active AND r.max_scope = 'ORG'
              AND u.is_active
              AND p.code = 'payroll.period.manage'
       );
$function$;

CREATE OR REPLACE FUNCTION app.can_manage_import_assignments(
    p_import_job_id BIGINT,
    p_division_id BIGINT DEFAULT NULL
)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.import_jobs j
        WHERE j.id = p_import_job_id
          AND (
              (
                  j.uploaded_by = app.current_user_id()
                  AND (
                      (p_division_id IS NOT NULL
                       AND app.can_access_division(p_division_id))
                      OR
                      (p_division_id IS NULL AND EXISTS (
                          SELECT 1
                          FROM public.user_role_scopes s
                          JOIN public.roles r ON r.id = s.role_id
                          JOIN public.users u ON u.id = s.user_id
                          WHERE s.id = app.current_role_scope_id()
                            AND s.user_id = app.current_user_id()
                            AND s.is_active AND r.is_active AND u.is_active
                            AND CURRENT_DATE >= s.valid_from
                            AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
                            AND r.max_scope = 'ORG'
                            AND s.wing_id IS NULL AND s.division_id IS NULL
                      ))
                  )
              )
              OR EXISTS (
                  SELECT 1
                  FROM public.user_role_scopes s
                  JOIN public.roles r ON r.id = s.role_id
                  JOIN public.role_permissions rp ON rp.role_id = r.id
                  JOIN public.permissions p ON p.id = rp.permission_id
                  JOIN public.users u ON u.id = s.user_id
                  WHERE s.id = app.current_role_scope_id()
                    AND s.user_id = app.current_user_id()
                    AND s.is_active AND r.is_active AND u.is_active
                    AND CURRENT_DATE >= s.valid_from
                    AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
                    AND r.max_scope = 'ORG'
                    AND s.wing_id IS NULL AND s.division_id IS NULL
                    AND p.code IN (
                        'import.approve', 'import.create', 'salary.manage',
                        'salary.approve', 'salary.prepare', 'payroll.prepare',
                        'user.manage'
                    )
              )
          )
    );
$function$;

DROP POLICY audit_scope_policy ON public.audit_logs;
CREATE POLICY audit_scope_policy
ON public.audit_logs
FOR SELECT
TO ssga_app
USING (
    (division_id IS NOT NULL AND app.can_access_division(division_id))
    OR (division_id IS NULL AND app.has_org_scope())
);

DROP POLICY import_batches_scope_policy ON public.import_batches;
CREATE POLICY import_batches_scope_policy
ON public.import_batches
TO ssga_app
USING (
    (division_id IS NOT NULL AND app.can_access_division(division_id))
    OR (division_id IS NULL AND app.has_org_scope())
)
WITH CHECK (
    (division_id IS NOT NULL AND app.can_access_division(division_id))
    OR (division_id IS NULL AND app.has_org_scope())
);

CREATE OR REPLACE FUNCTION app.review_payroll_batch(
    p_batch_id BIGINT,
    p_action CHARACTER VARYING,
    p_remarks TEXT DEFAULT NULL
)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app', 'pg_temp'
AS $function$
DECLARE
    v_user_id BIGINT;
    v_username VARCHAR(100);
    v_batch_status VARCHAR(20);
    v_division_id BIGINT;
    v_wing_id BIGINT;
    v_maker_id BIGINT;
    v_total_transactions INTEGER;
    v_verified_transactions INTEGER;
    v_new_status VARCHAR(20);
    v_approval_id BIGINT;
BEGIN
    v_user_id := app.current_user_id();

    IF v_user_id IS NULL OR app.current_role_scope_id() IS NULL THEN
        RAISE EXCEPTION 'Authenticated user and active role/scope are required';
    END IF;

    SELECT u.username
    INTO v_username
    FROM public.users u
    WHERE u.id = v_user_id
      AND u.is_active = true;

    IF v_username IS NULL THEN
        RAISE EXCEPTION 'Active application user not found';
    END IF;

    IF p_action IS NULL
       OR UPPER(p_action) NOT IN ('APPROVE', 'REJECT') THEN
        RAISE EXCEPTION 'Invalid action. Allowed actions: APPROVE, REJECT';
    END IF;

    SELECT pb.status, pb.division_id, pb.wing_id, pb.maker_id
    INTO v_batch_status, v_division_id, v_wing_id, v_maker_id
    FROM public.payroll_batches pb
    WHERE pb.id = p_batch_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Payroll batch % not found', p_batch_id;
    END IF;

    IF NOT app.can_access_division(v_division_id) THEN
        RAISE EXCEPTION 'You do not have access to this payroll division';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.user_role_scopes urs
        JOIN public.roles r ON r.id = urs.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE urs.id = app.current_role_scope_id()
          AND urs.user_id = v_user_id
          AND urs.is_active
          AND r.is_active
          AND urs.valid_from <= CURRENT_DATE
          AND (urs.valid_to IS NULL OR urs.valid_to >= CURRENT_DATE)
          AND p.code = 'payroll.approve'
    ) THEN
        RAISE EXCEPTION
            'User % does not have permission payroll.approve',
            v_username;
    END IF;

    IF v_batch_status NOT IN ('SUBMITTED', 'UNDER_REVIEW') THEN
        RAISE EXCEPTION
            'Payroll batch % cannot be reviewed from status %',
            p_batch_id,
            v_batch_status;
    END IF;

    IF v_maker_id IS NOT NULL
       AND v_maker_id = v_user_id THEN
        RAISE EXCEPTION 'Maker cannot review the same payroll batch';
    END IF;

    SELECT COUNT(*),
           COUNT(*) FILTER (WHERE st.status = 'VERIFIED')
    INTO v_total_transactions, v_verified_transactions
    FROM public.salary_transactions st
    WHERE st.payroll_batch_id = p_batch_id;

    IF v_total_transactions = 0 THEN
        RAISE EXCEPTION
            'Payroll batch % contains no salary transactions',
            p_batch_id;
    END IF;

    IF v_total_transactions <> v_verified_transactions THEN
        RAISE EXCEPTION
            'All salary transactions must be VERIFIED before %',
            UPPER(p_action);
    END IF;

    v_new_status := CASE UPPER(p_action)
        WHEN 'APPROVE' THEN 'APPROVED'
        ELSE 'REJECTED'
    END;

    UPDATE public.payroll_batches
    SET status = v_new_status,
        checker_id = v_user_id
    WHERE id = p_batch_id
      AND status = v_batch_status;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Payroll batch % changed while it was being reviewed',
            p_batch_id;
    END IF;

    INSERT INTO public.salary_approvals (
        entity_type,
        entity_id,
        from_status,
        to_status,
        action,
        acted_by,
        remarks,
        acted_at
    )
    VALUES (
        'PAYROLL_BATCH',
        p_batch_id,
        v_batch_status,
        v_new_status,
        UPPER(p_action),
        v_user_id,
        p_remarks,
        now()
    )
    RETURNING id INTO v_approval_id;

    PERFORM app.write_audit_log(
        'PAYROLL_' || UPPER(p_action),
        'PAYROLL_BATCH',
        p_batch_id::VARCHAR,
        v_wing_id,
        v_division_id,
        jsonb_build_object('status', v_batch_status, 'batch_id', p_batch_id),
        jsonb_build_object('status', v_new_status, 'batch_id', p_batch_id),
        p_remarks,
        NULL
    );

    RETURN v_approval_id;
END;
$function$;

INSERT INTO public.schema_migrations (version, name)
VALUES (28, '28_active_role_scope_and_finalizer')
ON CONFLICT (version) DO NOTHING;

COMMIT;
