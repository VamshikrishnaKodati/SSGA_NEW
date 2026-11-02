-- ============================================================================
-- 01_schema.sql - COMPLETE schema baseline (squash of migrations 01..14).
--
-- This is the single source of truth for database structure. It was
-- generated from the live ssga_new_db schema at migration version 14
-- (pg_dump --schema-only --no-owner, privileges kept).
--
-- Fresh database order:
--   00_roles.sql -> 01_schema.sql -> seed_bootstrap_admin.sql
--   -> seed_reference_data.sql -> 15_*.sql ... (all migrations > 14)
--
-- Historical increment files 02..14 are retained for existing databases
-- that were created before this baseline existed. Do not replay them on a
-- fresh database: this file already contains their effect.
--
-- Requires: PostgreSQL >= 15, roles from 00_roles.sql, pgcrypto.
-- ============================================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 17.10
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: app; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA app;


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: can_access_division(bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.can_access_division(p_division_id bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public', 'app', 'pg_catalog'
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM divisions d
        JOIN wings w ON w.id = d.wing_id
        JOIN user_role_scopes s
          ON s.user_id = app.current_user_id()
        JOIN roles r
          ON r.id = s.role_id
        JOIN users u
          ON u.id = s.user_id
        WHERE d.id = p_division_id
          AND s.is_active = true
          AND r.is_active = true
          AND u.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
          AND (
              (
                  r.max_scope = 'ORG'
                  AND s.wing_id IS NULL
                  AND s.division_id IS NULL
              )
              OR (
                  r.max_scope = 'WING'
                  AND s.wing_id = w.id
              )
              OR (
                  r.max_scope = 'DIVISION'
                  AND s.division_id = d.id
              )
          )
    );
$$;


--
-- Name: can_access_employee(bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.can_access_employee(p_employee_id bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public', 'app', 'pg_catalog'
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM employees e
        JOIN users u
          ON u.id = app.current_user_id()
        JOIN user_role_scopes s
          ON s.user_id = u.id
        JOIN roles r
          ON r.id = s.role_id
        WHERE e.id = p_employee_id
          AND s.is_active = true
          AND r.is_active = true
          AND u.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
          AND (
              (
                  r.max_scope = 'ORG'
                  AND s.wing_id IS NULL
                  AND s.division_id IS NULL
              )
              OR (
                  r.max_scope = 'WING'
                  AND EXISTS (
                      SELECT 1
                      FROM divisions d
                      WHERE d.id = e.division_id
                        AND d.wing_id = s.wing_id
                  )
              )
              OR (
                  r.max_scope = 'DIVISION'
                  AND e.division_id = s.division_id
              )
              OR (
                  r.max_scope = 'SELF'
                  AND u.employee_id = e.id
              )
          )
    );
$$;


--
-- Name: can_access_wing(bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.can_access_wing(p_wing_id bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public', 'app', 'pg_catalog'
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM user_role_scopes s
        JOIN roles r ON r.id = s.role_id
        JOIN users u ON u.id = s.user_id
        WHERE s.user_id = app.current_user_id()
          AND s.is_active = true
          AND r.is_active = true
          AND u.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
          AND (
              (
                  r.max_scope = 'ORG'
                  AND s.wing_id IS NULL
                  AND s.division_id IS NULL
              )
              OR (
                  r.max_scope = 'WING'
                  AND s.wing_id = p_wing_id
              )
          )
    );
$$;


--
-- Name: can_assign_import_role(bigint, text, bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.can_assign_import_role(p_user_id bigint, p_assignment_role text, p_division_id bigint DEFAULT NULL::bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        JOIN public.users u ON u.id = s.user_id
        LEFT JOIN public.divisions d ON d.id = p_division_id
        WHERE s.user_id = p_user_id
          AND s.is_active AND r.is_active AND u.is_active
          AND CURRENT_DATE >= s.valid_from
          AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
          AND (
              (p_assignment_role = 'RESOLVER'
               AND p.code IN ('import.validate','salary.manage','payroll.prepare','salary.prepare'))
              OR
              (p_assignment_role = 'APPROVER'
               AND p.code IN ('import.approve','salary.approve','payroll.approve'))
              OR
              (p_assignment_role = 'FINALIZER'
               AND p.code IN ('salary.finalize','payroll.finalize','import.finalize'))
              OR
              (p_assignment_role = 'VIEWER'
               AND p.code IN ('salary.view','payroll.view','import.validate','report.view'))
          )
          AND (
              (p_division_id IS NULL
               AND r.max_scope = 'ORG'
               AND s.wing_id IS NULL AND s.division_id IS NULL)
              OR
              (p_division_id IS NOT NULL AND d.id IS NOT NULL AND (
                   (r.max_scope = 'ORG' AND s.wing_id IS NULL AND s.division_id IS NULL)
                   OR (r.max_scope IN ('WING','ORG') AND s.wing_id = d.wing_id)
                   OR (r.max_scope IN ('DIVISION','WING','ORG') AND s.division_id = d.id)
              ))
          )
    );
$$;


--
-- Name: can_create_division(bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.can_create_division(p_wing_id bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public', 'app', 'pg_catalog'
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM wings w
        JOIN user_role_scopes s
          ON s.user_id = app.current_user_id()
        JOIN roles r
          ON r.id = s.role_id
        JOIN users u
          ON u.id = s.user_id
        WHERE w.id = p_wing_id
          AND s.is_active = true
          AND r.is_active = true
          AND u.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
          AND (
              (
                  r.max_scope = 'ORG'
                  AND s.wing_id IS NULL
                  AND s.division_id IS NULL
              )
              OR
              (
                  r.max_scope = 'WING'
                  AND s.wing_id = w.id
              )
          )
    );
$$;


--
-- Name: can_create_employee(bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.can_create_employee(p_division_id bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public', 'app', 'pg_catalog'
    SET row_security TO 'off'
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM divisions d
        JOIN wings w
          ON w.id = d.wing_id
        JOIN user_role_scopes s
          ON s.user_id = app.current_user_id()
        JOIN roles r
          ON r.id = s.role_id
        JOIN users u
          ON u.id = s.user_id
        WHERE d.id = p_division_id
          AND s.is_active = true
          AND r.is_active = true
          AND u.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
          AND (
              (
                  r.max_scope = 'ORG'
                  AND s.wing_id IS NULL
                  AND s.division_id IS NULL
              )
              OR
              (
                  r.max_scope = 'WING'
                  AND s.wing_id = w.id
              )
              OR
              (
                  r.max_scope = 'DIVISION'
                  AND s.division_id = d.id
              )
          )
    );
$$;


--
-- Name: can_manage_import_assignments(bigint, bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.can_manage_import_assignments(p_import_job_id bigint, p_division_id bigint DEFAULT NULL::bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
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
                          WHERE s.user_id = app.current_user_id()
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
                  WHERE s.user_id = app.current_user_id()
                    AND s.is_active AND r.is_active AND u.is_active
                    AND CURRENT_DATE >= s.valid_from
                    AND (s.valid_to IS NULL OR CURRENT_DATE <= s.valid_to)
                    AND r.max_scope = 'ORG'
                    AND s.wing_id IS NULL AND s.division_id IS NULL
                    AND p.code IN ('import.approve', 'import.create', 'salary.manage',
                                  'salary.approve', 'salary.prepare', 'payroll.prepare',
                                  'user.manage')
              )
          )
    );
$$;


--
-- Name: current_user_id(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.current_user_id() RETURNS bigint
    LANGUAGE sql STABLE
    AS $$
    SELECT NULLIF(
        current_setting('app.user_id', true),
        ''
    )::BIGINT;
$$;


--
-- Name: find_auth_user(character varying); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.find_auth_user(p_username character varying) RETURNS TABLE(user_id bigint, username character varying, password_hash text, is_active boolean, employee_id bigint, must_change_password boolean, failed_attempts integer, locked_until timestamp with time zone)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
    SELECT
        u.id,
        u.username,
        u.password_hash,
        u.is_active,
        u.employee_id,
        u.must_change_password,
        u.failed_attempts,
        u.locked_until
    FROM public.users u
    WHERE u.username = p_username;
$$;


--
-- Name: find_user_authorities(bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.find_user_authorities(p_user_id bigint) RETURNS TABLE(authority text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
    SELECT DISTINCT authority
    FROM (
        SELECT 'ROLE_' || r.code AS authority
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        WHERE s.user_id = p_user_id
          AND s.is_active = true
          AND r.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )

        UNION

        SELECT p.code AS authority
        FROM public.user_role_scopes s
        JOIN public.roles r ON r.id = s.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE s.user_id = p_user_id
          AND s.is_active = true
          AND r.is_active = true
          AND CURRENT_DATE >= s.valid_from
          AND (
              s.valid_to IS NULL
              OR CURRENT_DATE <= s.valid_to
          )
    ) x
    ORDER BY authority;
$$;


--
-- Name: guard_finalized_payroll_batch(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.guard_finalized_payroll_batch() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'FINALIZED' THEN
            RAISE EXCEPTION
                'Payroll batch % is finalized and locked; it cannot be deleted.',
                OLD.id USING ERRCODE = 'check_violation';
        END IF;

        RETURN OLD;
    END IF;

    IF OLD.status = 'FINALIZED' AND NEW.status <> 'REOPENED' THEN
        RAISE EXCEPTION
            'Payroll batch % is finalized and locked; reopen it before changing it.',
            OLD.id USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'FINALIZED'
       AND (NEW.period_id IS DISTINCT FROM OLD.period_id
         OR NEW.division_id IS DISTINCT FROM OLD.division_id
         OR NEW.wing_id IS DISTINCT FROM OLD.wing_id
         OR NEW.run_type IS DISTINCT FROM OLD.run_type
         OR NEW.run_no IS DISTINCT FROM OLD.run_no
         OR NEW.employee_count IS DISTINCT FROM OLD.employee_count
         OR NEW.gross_total IS DISTINCT FROM OLD.gross_total
         OR NEW.deduction_total IS DISTINCT FROM OLD.deduction_total
         OR NEW.net_total IS DISTINCT FROM OLD.net_total
         OR NEW.division_name_snapshot IS DISTINCT FROM OLD.division_name_snapshot) THEN
        RAISE EXCEPTION
            'Payroll batch % is finalized; only its status may change while reopening.',
            OLD.id USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: guard_finalized_salary_transaction(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.guard_finalized_salary_transaction() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
DECLARE
    v_status VARCHAR(20);
    v_id BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_id := OLD.id;
    ELSE
        v_id := NEW.id;
    END IF;

    SELECT b.status INTO v_status
      FROM public.salary_transactions st
      JOIN public.payroll_batches b ON b.id = st.payroll_batch_id
     WHERE st.id = v_id;

    IF v_status = 'FINALIZED' THEN
        RAISE EXCEPTION
            'Salary transaction % belongs to a finalized payroll batch and cannot be changed.',
            v_id USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: guard_finalized_salary_transaction_line(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.guard_finalized_salary_transaction_line() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
DECLARE
    v_status VARCHAR(20);
    v_id BIGINT;
    v_transaction_id BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_id := OLD.id;
        v_transaction_id := OLD.salary_transaction_id;
    ELSE
        v_id := NEW.id;
        v_transaction_id := NEW.salary_transaction_id;
    END IF;

    SELECT b.status INTO v_status
      FROM public.salary_transactions st
      JOIN public.payroll_batches b ON b.id = st.payroll_batch_id
     WHERE st.id = v_transaction_id;

    IF v_status = 'FINALIZED' THEN
        RAISE EXCEPTION
            'Salary transaction line % belongs to a finalized payroll batch and cannot be changed.',
            v_id USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: guard_import_job_assignment_identity_v08(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.guard_import_job_assignment_identity_v08() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
BEGIN
    IF NEW.import_job_id IS DISTINCT FROM OLD.import_job_id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.assignment_role IS DISTINCT FROM OLD.assignment_role
       OR NEW.division_id IS DISTINCT FROM OLD.division_id
       OR NEW.assigned_by IS DISTINCT FROM OLD.assigned_by THEN
        RAISE EXCEPTION 'Import assignment identity and scope are immutable; revoke and create a new assignment';
    END IF;
    IF OLD.is_active = FALSE AND NEW.is_active = TRUE THEN
        RAISE EXCEPTION 'A revoked import assignment cannot be reactivated; create a new assignment';
    END IF;
    IF OLD.is_active = TRUE AND NEW.is_active = FALSE AND NEW.revoked_at IS NULL THEN
        NEW.revoked_at := now();
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: guard_import_job_source_identity_v08(); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.guard_import_job_source_identity_v08() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
BEGIN
    IF NEW.uploaded_by IS DISTINCT FROM OLD.uploaded_by
       OR NEW.file_name IS DISTINCT FROM OLD.file_name
       OR NEW.file_key IS DISTINCT FROM OLD.file_key
       OR NEW.file_sha256 IS DISTINCT FROM OLD.file_sha256 THEN
        RAISE EXCEPTION 'Import job owner and source-file identity are immutable';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: has_import_job_assignment(bigint, text[], bigint); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.has_import_job_assignment(p_import_job_id bigint, p_roles text[] DEFAULT NULL::text[], p_division_id bigint DEFAULT NULL::bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app'
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.import_job_assignments a
        WHERE a.import_job_id = p_import_job_id
          AND a.user_id = app.current_user_id()
          AND a.is_active
          AND (p_roles IS NULL OR a.assignment_role = ANY (p_roles))
          AND (
              p_division_id IS NULL
              OR a.division_id IS NULL
              OR a.division_id = p_division_id
          )
    );
$$;


--
-- Name: review_payroll_batch(bigint, character varying, text); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.review_payroll_batch(p_batch_id bigint, p_action character varying, p_remarks text DEFAULT NULL::text) RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public', 'app', 'pg_temp'
    AS $$
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
    v_required_permission VARCHAR(80);

    v_approval_id BIGINT;
BEGIN

    ------------------------------------------------------------
    -- 1. Current authenticated user
    ------------------------------------------------------------
    v_user_id := app.current_user_id();

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authenticated application user is required';
    END IF;

    SELECT u.username
    INTO v_username
    FROM public.users u
    WHERE u.id = v_user_id
      AND u.is_active = true;

    IF v_username IS NULL THEN
        RAISE EXCEPTION 'Active application user not found';
    END IF;


    ------------------------------------------------------------
    -- 2. Validate action
    ------------------------------------------------------------
    IF UPPER(p_action) NOT IN ('APPROVE', 'REJECT') THEN
        RAISE EXCEPTION 'Invalid action. Allowed actions: APPROVE, REJECT';
    END IF;


    ------------------------------------------------------------
    -- 3. Load payroll batch
    ------------------------------------------------------------
    SELECT
        pb.status,
        pb.division_id,
        pb.wing_id,
        pb.maker_id
    INTO
        v_batch_status,
        v_division_id,
        v_wing_id,
        v_maker_id
    FROM public.payroll_batches pb
    WHERE pb.id = p_batch_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Payroll batch % not found', p_batch_id;
    END IF;


    ------------------------------------------------------------
    -- 4. Check organizational scope
    ------------------------------------------------------------
    IF NOT app.can_access_division(v_division_id) THEN
        RAISE EXCEPTION 'You do not have access to this payroll division';
    END IF;


    ------------------------------------------------------------
    -- 5. Required permission
    ------------------------------------------------------------
    IF UPPER(p_action) = 'APPROVE' THEN
        v_required_permission := 'payroll.approve';
        v_new_status := 'APPROVED';
    ELSE
        v_required_permission := 'payroll.approve';
        v_new_status := 'REJECTED';
    END IF;


    ------------------------------------------------------------
    -- 6. Check permission through RBAC
    ------------------------------------------------------------
    IF NOT EXISTS
    (
        SELECT 1
        FROM public.user_role_scopes urs
        JOIN public.roles r
          ON r.id = urs.role_id
        JOIN public.role_permissions rp
          ON rp.role_id = r.id
        JOIN public.permissions p
          ON p.id = rp.permission_id
        WHERE urs.user_id = v_user_id
          AND urs.is_active = true
          AND r.is_active = true
          AND p.code = v_required_permission
    )
    THEN
        RAISE EXCEPTION
            'User % does not have permission %',
            v_username,
            v_required_permission;
    END IF;


    ------------------------------------------------------------
    -- 7. Valid workflow status
    ------------------------------------------------------------
    IF v_batch_status NOT IN ('SUBMITTED', 'UNDER_REVIEW') THEN
        RAISE EXCEPTION
            'Payroll batch % cannot be reviewed from status %',
            p_batch_id,
            v_batch_status;
    END IF;


    ------------------------------------------------------------
    -- 8. Maker-checker protection
    ------------------------------------------------------------
    IF UPPER(p_action) = 'APPROVE'
       AND v_maker_id IS NOT NULL
       AND v_maker_id = v_user_id
    THEN
        RAISE EXCEPTION
            'Maker cannot approve the same payroll batch';
    END IF;


    ------------------------------------------------------------
    -- 9. Verify all employee transactions before approval
    ------------------------------------------------------------
    SELECT
        COUNT(*),
        COUNT(*) FILTER (
            WHERE st.status = 'VERIFIED'
        )
    INTO
        v_total_transactions,
        v_verified_transactions
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


    ------------------------------------------------------------
    -- 10. Update payroll batch status
    ------------------------------------------------------------
    UPDATE public.payroll_batches
    SET
        status = v_new_status,
        checker_id = v_user_id
    WHERE id = p_batch_id;


    ------------------------------------------------------------
    -- 11. Record approval history
    ------------------------------------------------------------
    INSERT INTO public.salary_approvals
    (
        entity_type,
        entity_id,
        from_status,
        to_status,
        action,
        acted_by,
        remarks,
        acted_at
    )
    VALUES
    (
        'PAYROLL_BATCH',
        p_batch_id,
        v_batch_status,
        v_new_status,
        UPPER(p_action),
        v_user_id,
        p_remarks,
        now()
    )
    RETURNING id
    INTO v_approval_id;


    ------------------------------------------------------------
    -- 12. Record audit trail
    ------------------------------------------------------------
    PERFORM app.write_audit_log
    (
        'PAYROLL_' || UPPER(p_action),
        'PAYROLL_BATCH',
        p_batch_id::VARCHAR,
        v_wing_id,
        v_division_id,
        jsonb_build_object(
            'status', v_batch_status,
            'batch_id', p_batch_id
        ),
        jsonb_build_object(
            'status', v_new_status,
            'batch_id', p_batch_id
        ),
        p_remarks,
        NULL
    );


    ------------------------------------------------------------
    -- 13. Return approval history ID
    ------------------------------------------------------------
    RETURN v_approval_id;

END;
$$;


--
-- Name: write_audit_log(character varying, character varying, character varying, bigint, bigint, jsonb, jsonb, text, uuid); Type: FUNCTION; Schema: app; Owner: -
--

CREATE FUNCTION app.write_audit_log(p_action character varying, p_entity_type character varying, p_entity_id character varying, p_wing_id bigint DEFAULT NULL::bigint, p_division_id bigint DEFAULT NULL::bigint, p_old_values jsonb DEFAULT NULL::jsonb, p_new_values jsonb DEFAULT NULL::jsonb, p_reason text DEFAULT NULL::text, p_request_id uuid DEFAULT NULL::uuid) RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public', 'app'
    AS $$
DECLARE
    v_user_id BIGINT;
    v_username VARCHAR(100);
    v_audit_id BIGINT;
BEGIN
    v_user_id := app.current_user_id();

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authenticated application user is required';
    END IF;

    SELECT u.username
    INTO v_username
    FROM users u
    WHERE u.id = v_user_id
      AND u.is_active = true;

    IF v_username IS NULL THEN
        RAISE EXCEPTION 'Active application user not found';
    END IF;

    INSERT INTO audit_logs
    (
        occurred_at,
        user_id,
        username,
        action,
        entity_type,
        entity_id,
        wing_id,
        division_id,
        old_values,
        new_values,
        reason,
        request_id
    )
    VALUES
    (
        now(),
        v_user_id,
        v_username,
        p_action,
        p_entity_type,
        p_entity_id,
        p_wing_id,
        p_division_id,
        p_old_values,
        p_new_values,
        p_reason,
        p_request_id
    )
    RETURNING id INTO v_audit_id;

    RETURN v_audit_id;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    user_id bigint,
    username character varying(100),
    ip_address inet,
    action character varying(40) NOT NULL,
    entity_type character varying(60) NOT NULL,
    entity_id character varying(64) NOT NULL,
    wing_id bigint,
    division_id bigint,
    old_values jsonb,
    new_values jsonb,
    reason text,
    request_id uuid
);

ALTER TABLE ONLY public.audit_logs FORCE ROW LEVEL SECURITY;


--
-- Name: audit_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit_logs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.audit_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: circles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.circles (
    id bigint NOT NULL,
    organization_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    code character varying(50) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: circles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.circles ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.circles_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: designations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.designations (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    name character varying(150) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: designations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.designations ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.designations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: divisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.divisions (
    id bigint NOT NULL,
    wing_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    code character varying(50) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.divisions FORCE ROW LEVEL SECURITY;


--
-- Name: divisions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.divisions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.divisions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employee_bank_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_bank_accounts (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    bank_name character varying(150) NOT NULL,
    branch_name character varying(150),
    account_number character varying(34) NOT NULL,
    ifsc_code character varying(11),
    is_primary boolean DEFAULT true NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT employee_bank_accounts_dates_check CHECK (((effective_to IS NULL) OR (effective_to >= effective_from)))
);

ALTER TABLE ONLY public.employee_bank_accounts FORCE ROW LEVEL SECURITY;


--
-- Name: employee_bank_accounts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employee_bank_accounts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_bank_accounts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employee_code_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.employee_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: employee_identity_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_identity_mappings (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    identity_type character varying(30) NOT NULL,
    identity_value character varying(100) NOT NULL,
    source_system character varying(100),
    is_primary boolean DEFAULT false NOT NULL,
    valid_from date DEFAULT CURRENT_DATE NOT NULL,
    valid_to date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT employee_identity_mappings_check CHECK (((valid_to IS NULL) OR (valid_to >= valid_from)))
);

ALTER TABLE ONLY public.employee_identity_mappings FORCE ROW LEVEL SECURITY;


--
-- Name: employee_identity_mappings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employee_identity_mappings ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_identity_mappings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employee_salary_values; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_salary_values (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    salary_component_id bigint NOT NULL,
    amount numeric(15,2) DEFAULT 0 NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    source character varying(30),
    revision_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    import_job_id bigint,
    import_job_sheet_id bigint,
    import_row_number integer,
    CONSTRAINT employee_salary_values_check CHECK (((effective_to IS NULL) OR (effective_to >= effective_from)))
);

ALTER TABLE ONLY public.employee_salary_values FORCE ROW LEVEL SECURITY;


--
-- Name: employee_salary_values_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employee_salary_values ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_salary_values_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employee_status_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_status_history (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    old_status character varying(20),
    new_status character varying(20) NOT NULL,
    effective_date date NOT NULL,
    reason character varying(250),
    remarks text,
    changed_by bigint,
    changed_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: employee_status_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employee_status_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_status_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employee_temp_hrms_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.employee_temp_hrms_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: employee_transfers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_transfers (
    id bigint NOT NULL,
    employee_id bigint NOT NULL,
    from_division_id bigint,
    to_division_id bigint NOT NULL,
    effective_date date NOT NULL,
    reason character varying(250),
    order_number character varying(100),
    order_date date,
    remarks text,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: employee_transfers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employee_transfers ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employee_transfers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: employees; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employees (
    id bigint NOT NULL,
    employee_code character varying(50) NOT NULL,
    hrms_id character varying(20) NOT NULL,
    division_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    designation_id bigint,
    grade_id bigint,
    contact character varying(20),
    place_of_work character varying(150),
    date_of_birth date,
    date_of_joining date,
    employment_type character varying(30),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    pay_scale character varying(100),
    department character varying(150),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    circle_id bigint,
    ward_id bigint,
    payroll_unit_id bigint,
    CONSTRAINT employees_hrms_id_check CHECK (((hrms_id)::text ~ '^(T-[0-9]{4}|[0-9]{4})$'::text))
);

ALTER TABLE ONLY public.employees FORCE ROW LEVEL SECURITY;


--
-- Name: employees_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.employees ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.employees_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: grades; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.grades (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    name character varying(100) NOT NULL,
    level_no smallint,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: grades_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.grades ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.grades_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_batches (
    id bigint NOT NULL,
    import_type character varying(20) NOT NULL,
    status character varying(12) DEFAULT 'UPLOADED'::character varying NOT NULL,
    division_id bigint,
    period_id bigint,
    file_name character varying(255) NOT NULL,
    file_key text NOT NULL,
    file_sha256 character(64) NOT NULL,
    total_rows integer DEFAULT 0 NOT NULL,
    valid_rows integer DEFAULT 0 NOT NULL,
    error_rows integer DEFAULT 0 NOT NULL,
    uploaded_by bigint NOT NULL,
    uploaded_at timestamp with time zone DEFAULT now() NOT NULL,
    validated_at timestamp with time zone,
    approved_by bigint,
    approved_at timestamp with time zone,
    imported_at timestamp with time zone,
    failure_reason text,
    notes text,
    import_job_id bigint,
    import_job_sheet_id bigint,
    CONSTRAINT chk_import_maker_checker CHECK (((approved_by IS NULL) OR (approved_by <> uploaded_by))),
    CONSTRAINT import_batches_error_rows_check CHECK ((error_rows >= 0)),
    CONSTRAINT import_batches_file_sha256_check CHECK ((file_sha256 ~ '^[0-9a-fA-F]{64}$'::text)),
    CONSTRAINT import_batches_import_type_check CHECK (((import_type)::text = ANY ((ARRAY['EMPLOYEES'::character varying, 'SALARY_VALUES'::character varying, 'REVISIONS'::character varying, 'PAYROLL_HISTORY'::character varying])::text[]))),
    CONSTRAINT import_batches_status_check CHECK (((status)::text = ANY ((ARRAY['UPLOADED'::character varying, 'VALIDATING'::character varying, 'VALIDATED'::character varying, 'HAS_ERRORS'::character varying, 'APPROVED'::character varying, 'IMPORTING'::character varying, 'IMPORTED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT import_batches_total_rows_check CHECK ((total_rows >= 0)),
    CONSTRAINT import_batches_valid_rows_check CHECK ((valid_rows >= 0))
);

ALTER TABLE ONLY public.import_batches FORCE ROW LEVEL SECURITY;


--
-- Name: import_batches_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_batches ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_errors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_errors (
    id bigint NOT NULL,
    batch_id bigint NOT NULL,
    row_no integer,
    field_name character varying(80),
    severity character varying(7) DEFAULT 'ERROR'::character varying NOT NULL,
    error_code character varying(50) NOT NULL,
    message text NOT NULL,
    CONSTRAINT import_errors_severity_check CHECK (((severity)::text = ANY ((ARRAY['ERROR'::character varying, 'WARNING'::character varying])::text[])))
);

ALTER TABLE ONLY public.import_errors FORCE ROW LEVEL SECURITY;


--
-- Name: import_errors_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_errors ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_errors_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_job_assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_job_assignments (
    id bigint NOT NULL,
    import_job_id bigint NOT NULL,
    user_id bigint NOT NULL,
    assignment_role character varying(20) NOT NULL,
    division_id bigint,
    assigned_by bigint NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    assigned_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    notes text,
    CONSTRAINT chk_import_job_assignment_revoke_v08 CHECK (((is_active AND (revoked_at IS NULL)) OR ((NOT is_active) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT chk_import_job_assignment_role_v08 CHECK (((assignment_role)::text = ANY ((ARRAY['RESOLVER'::character varying, 'APPROVER'::character varying, 'FINALIZER'::character varying, 'VIEWER'::character varying])::text[])))
);

ALTER TABLE ONLY public.import_job_assignments FORCE ROW LEVEL SECURITY;


--
-- Name: import_job_assignments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_job_assignments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_job_assignments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_job_corrections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_job_corrections (
    id bigint NOT NULL,
    import_job_id bigint NOT NULL,
    import_job_sheet_id bigint NOT NULL,
    source_row_number integer NOT NULL,
    target_division_id bigint,
    field_name character varying(150) NOT NULL,
    error_code character varying(100) NOT NULL,
    correction_action character varying(40) NOT NULL,
    original_value jsonb,
    proposed_value jsonb,
    corrected_value jsonb,
    status character varying(24) DEFAULT 'PROPOSED'::character varying NOT NULL,
    confidence numeric(5,4),
    reason text,
    created_by bigint NOT NULL,
    assigned_to bigint,
    reviewed_by bigint,
    applied_by bigint,
    reviewed_at timestamp with time zone,
    applied_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_import_job_correction_action_v08 CHECK (((correction_action)::text = ANY ((ARRAY['NORMALIZE_TEXT'::character varying, 'NORMALIZE_DATE'::character varying, 'NORMALIZE_AMOUNT'::character varying, 'MATCH_EMPLOYEE'::character varying, 'MAP_COMPONENT'::character varying, 'MANUAL_EDIT'::character varying, 'ROUTE_FOR_REVIEW'::character varying, 'SKIP_ROW'::character varying])::text[]))),
    CONSTRAINT chk_import_job_correction_confidence_v08 CHECK (((confidence IS NULL) OR ((confidence >= (0)::numeric) AND (confidence <= (1)::numeric)))),
    CONSTRAINT chk_import_job_correction_row_v08 CHECK ((source_row_number > 0)),
    CONSTRAINT chk_import_job_correction_status_v08 CHECK (((status)::text = ANY ((ARRAY['PROPOSED'::character varying, 'AWAITING_REVIEW'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'APPLIED'::character varying, 'AUTO_APPLIED'::character varying, 'RESOLVED'::character varying, 'NEEDS_REVIEW'::character varying])::text[])))
);

ALTER TABLE ONLY public.import_job_corrections FORCE ROW LEVEL SECURITY;


--
-- Name: import_job_corrections_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_job_corrections ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_job_corrections_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_job_sheet_issues; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_job_sheet_issues (
    id bigint NOT NULL,
    import_job_sheet_id bigint NOT NULL,
    source_row_number integer NOT NULL,
    severity character varying(16) NOT NULL,
    field_name character varying(100),
    error_code character varying(80) NOT NULL,
    message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    target_division_id bigint,
    CONSTRAINT import_job_sheet_issues_severity_check CHECK (((severity)::text = ANY ((ARRAY['ERROR'::character varying, 'WARNING'::character varying])::text[]))),
    CONSTRAINT import_job_sheet_issues_source_row_number_check CHECK ((source_row_number > 0))
);

ALTER TABLE ONLY public.import_job_sheet_issues FORCE ROW LEVEL SECURITY;


--
-- Name: import_job_sheet_issues_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_job_sheet_issues ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_job_sheet_issues_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_job_sheets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_job_sheets (
    id bigint NOT NULL,
    import_job_id bigint NOT NULL,
    sheet_index integer NOT NULL,
    sheet_name character varying(255) NOT NULL,
    suggested_type character varying(64) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    confirmed_type character varying(32),
    status character varying(24) DEFAULT 'DETECTED'::character varying NOT NULL,
    header_row_number integer,
    column_count integer DEFAULT 0 NOT NULL,
    non_empty_rows integer DEFAULT 0 NOT NULL,
    data_rows_estimate integer DEFAULT 0 NOT NULL,
    headers jsonb DEFAULT '[]'::jsonb NOT NULL,
    column_mapping jsonb DEFAULT '{}'::jsonb NOT NULL,
    validation_summary jsonb DEFAULT '{}'::jsonb NOT NULL,
    valid_rows integer DEFAULT 0 NOT NULL,
    error_rows integer DEFAULT 0 NOT NULL,
    warning_rows integer DEFAULT 0 NOT NULL,
    confirmed_by bigint,
    confirmed_at timestamp with time zone,
    validated_at timestamp with time zone,
    imported_at timestamp with time zone,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT import_job_sheets_column_count_check CHECK ((column_count >= 0)),
    CONSTRAINT import_job_sheets_confirmed_type_check CHECK (((confirmed_type IS NULL) OR ((confirmed_type)::text = ANY ((ARRAY['EMPLOYEE_MASTER'::character varying, 'SALARY_ENTITLEMENTS'::character varying, 'PAYROLL_HISTORY'::character varying, 'SALARY_REVISIONS'::character varying, 'REFERENCE_ONLY'::character varying, 'SKIP'::character varying])::text[])))),
    CONSTRAINT import_job_sheets_data_rows_estimate_check CHECK ((data_rows_estimate >= 0)),
    CONSTRAINT import_job_sheets_error_rows_check CHECK ((error_rows >= 0)),
    CONSTRAINT import_job_sheets_header_row_number_check CHECK (((header_row_number IS NULL) OR (header_row_number > 0))),
    CONSTRAINT import_job_sheets_non_empty_rows_check CHECK ((non_empty_rows >= 0)),
    CONSTRAINT import_job_sheets_sheet_index_check CHECK ((sheet_index >= 0)),
    CONSTRAINT import_job_sheets_status_check CHECK (((status)::text = ANY ((ARRAY['DETECTED'::character varying, 'MAPPED'::character varying, 'VALIDATING'::character varying, 'VALIDATED'::character varying, 'HAS_ERRORS'::character varying, 'READY'::character varying, 'IMPORTED'::character varying, 'SKIPPED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT import_job_sheets_valid_rows_check CHECK ((valid_rows >= 0)),
    CONSTRAINT import_job_sheets_warning_rows_check CHECK ((warning_rows >= 0))
);

ALTER TABLE ONLY public.import_job_sheets FORCE ROW LEVEL SECURITY;


--
-- Name: import_job_sheets_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_job_sheets ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_job_sheets_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_job_workflow_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_job_workflow_events (
    id bigint NOT NULL,
    import_job_id bigint NOT NULL,
    division_id bigint,
    action character varying(50) NOT NULL,
    previous_status character varying(30),
    new_status character varying(30),
    actor_user_id bigint NOT NULL,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.import_job_workflow_events FORCE ROW LEVEL SECURITY;


--
-- Name: import_job_workflow_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_job_workflow_events ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_job_workflow_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_jobs (
    id bigint NOT NULL,
    source_type character varying(32) NOT NULL,
    status character varying(32) DEFAULT 'UPLOADED'::character varying NOT NULL,
    file_name character varying(255) NOT NULL,
    file_key text NOT NULL,
    file_sha256 character(64) NOT NULL,
    detected_format character varying(100),
    source_mapping jsonb DEFAULT '{}'::jsonb NOT NULL,
    date_range_start date,
    date_range_end date,
    period_count integer DEFAULT 0 NOT NULL,
    total_rows integer DEFAULT 0 NOT NULL,
    valid_rows integer DEFAULT 0 NOT NULL,
    error_rows integer DEFAULT 0 NOT NULL,
    warning_rows integer DEFAULT 0 NOT NULL,
    uploaded_by bigint NOT NULL,
    uploaded_at timestamp with time zone DEFAULT now() NOT NULL,
    approved_by bigint,
    approved_at timestamp with time zone,
    validated_at timestamp with time zone,
    imported_at timestamp with time zone,
    failure_reason text,
    notes text,
    submitted_by bigint,
    submitted_at timestamp with time zone,
    finalized_by bigint,
    finalized_at timestamp with time zone,
    rejection_reason text,
    resolution_version integer DEFAULT 1 NOT NULL,
    imported_amount_total numeric(18,2),
    imported_earnings_total numeric(18,2),
    imported_deductions_total numeric(18,2),
    CONSTRAINT chk_import_jobs_approval_separation_v08 CHECK (((approved_by IS NULL) OR ((approved_by <> uploaded_by) AND ((submitted_by IS NULL) OR (approved_by <> submitted_by))))),
    CONSTRAINT chk_import_jobs_date_range CHECK (((date_range_start IS NULL) OR (date_range_end IS NULL) OR (date_range_end >= date_range_start))),
    CONSTRAINT chk_import_jobs_finalizer_separation_v08 CHECK (((finalized_by IS NULL) OR ((finalized_by <> uploaded_by) AND ((submitted_by IS NULL) OR (finalized_by <> submitted_by)) AND ((approved_by IS NULL) OR (finalized_by <> approved_by))))),
    CONSTRAINT chk_import_jobs_maker_checker CHECK (((approved_by IS NULL) OR (approved_by <> uploaded_by))),
    CONSTRAINT chk_import_jobs_resolution_version_v08 CHECK ((resolution_version >= 1)),
    CONSTRAINT chk_import_jobs_source_type CHECK (((source_type)::text = ANY ((ARRAY['UNCLASSIFIED'::character varying, 'EMPLOYEE_MASTER'::character varying, 'SALARY_ENTITLEMENTS'::character varying, 'PAYROLL_HISTORY'::character varying, 'SALARY_REVISIONS'::character varying])::text[]))),
    CONSTRAINT import_jobs_error_rows_check CHECK ((error_rows >= 0)),
    CONSTRAINT import_jobs_file_sha256_check CHECK ((file_sha256 ~ '^[0-9a-fA-F]{64}$'::text)),
    CONSTRAINT import_jobs_period_count_check CHECK ((period_count >= 0)),
    CONSTRAINT import_jobs_status_check CHECK (((status)::text = ANY ((ARRAY['UPLOADED'::character varying, 'VALIDATING'::character varying, 'VALIDATED'::character varying, 'HAS_ERRORS'::character varying, 'AWAITING_APPROVAL'::character varying, 'APPROVED'::character varying, 'IMPORTING'::character varying, 'PARTIALLY_IMPORTED'::character varying, 'IMPORTED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT import_jobs_total_rows_check CHECK ((total_rows >= 0)),
    CONSTRAINT import_jobs_valid_rows_check CHECK ((valid_rows >= 0)),
    CONSTRAINT import_jobs_warning_rows_check CHECK ((warning_rows >= 0))
);

ALTER TABLE ONLY public.import_jobs FORCE ROW LEVEL SECURITY;


--
-- Name: import_jobs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_jobs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_jobs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_mapping_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_mapping_profiles (
    id bigint NOT NULL,
    name character varying(120) NOT NULL,
    import_type character varying(32) NOT NULL,
    structure_signature character varying(64) NOT NULL,
    schema_version integer DEFAULT 1 NOT NULL,
    column_mapping jsonb DEFAULT '{}'::jsonb NOT NULL,
    options jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_by bigint NOT NULL,
    is_shared boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT import_mapping_profiles_import_type_check CHECK (((import_type)::text = ANY ((ARRAY['EMPLOYEE_MASTER'::character varying, 'SALARY_ENTITLEMENTS'::character varying, 'PAYROLL_HISTORY'::character varying, 'SALARY_REVISIONS'::character varying])::text[]))),
    CONSTRAINT import_mapping_profiles_schema_version_check CHECK ((schema_version > 0)),
    CONSTRAINT import_mapping_profiles_structure_signature_check CHECK (((structure_signature)::text ~ '^[0-9a-fA-F]{64}$'::text))
);

ALTER TABLE ONLY public.import_mapping_profiles FORCE ROW LEVEL SECURITY;


--
-- Name: import_mapping_profiles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_mapping_profiles ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_mapping_profiles_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: import_rows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_rows (
    id bigint NOT NULL,
    batch_id bigint NOT NULL,
    row_no integer NOT NULL,
    raw jsonb NOT NULL,
    parsed jsonb,
    status character varying(9) DEFAULT 'PENDING'::character varying NOT NULL,
    target_entity character varying(60),
    target_id bigint,
    CONSTRAINT import_rows_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'VALID'::character varying, 'ERROR'::character varying, 'IMPORTED'::character varying, 'SKIPPED'::character varying])::text[])))
);

ALTER TABLE ONLY public.import_rows FORCE ROW LEVEL SECURITY;


--
-- Name: import_rows_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.import_rows ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.import_rows_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: organizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organizations (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    code character varying(50) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: organizations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.organizations ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.organizations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: parent_groups; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.parent_groups (
    id bigint NOT NULL,
    organization_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    code character varying(50) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT parent_groups_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);


--
-- Name: parent_groups_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.parent_groups ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.parent_groups_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payroll_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payroll_batches (
    id bigint NOT NULL,
    period_id bigint NOT NULL,
    wing_id bigint,
    division_id bigint NOT NULL,
    run_type character varying(30) NOT NULL,
    run_no integer NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    maker_id bigint,
    checker_id bigint,
    employee_count integer DEFAULT 0 NOT NULL,
    gross_total numeric(18,2) DEFAULT 0 NOT NULL,
    deduction_total numeric(18,2) DEFAULT 0 NOT NULL,
    net_total numeric(18,2) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    division_name_snapshot character varying(150) NOT NULL,
    CONSTRAINT payroll_batches_run_no_check CHECK ((run_no > 0)),
    CONSTRAINT payroll_batches_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'UNDER_REVIEW'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'FINALIZED'::character varying, 'REOPENED'::character varying])::text[])))
);

ALTER TABLE ONLY public.payroll_batches FORCE ROW LEVEL SECURITY;


--
-- Name: payroll_batches_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payroll_batches ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.payroll_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payroll_periods; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payroll_periods (
    id bigint NOT NULL,
    organization_id bigint NOT NULL,
    period_year smallint NOT NULL,
    period_month smallint NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    status character varying(10) DEFAULT 'OPEN'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT payroll_periods_check CHECK ((period_end >= period_start)),
    CONSTRAINT payroll_periods_period_month_check CHECK (((period_month >= 1) AND (period_month <= 12))),
    CONSTRAINT payroll_periods_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'LOCKED'::character varying, 'CLOSED'::character varying])::text[])))
);


--
-- Name: payroll_periods_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payroll_periods ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.payroll_periods_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payroll_units; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payroll_units (
    id bigint NOT NULL,
    parent_group_id bigint NOT NULL,
    name character varying(200) NOT NULL,
    code character varying(50) NOT NULL,
    unit_type character varying(40) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    verification_status character varying(30) DEFAULT 'CONFIRMED'::character varying NOT NULL,
    verification_note text,
    display_order smallint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT payroll_units_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[]))),
    CONSTRAINT payroll_units_unit_type_check CHECK (((unit_type)::text = ANY ((ARRAY['DIVISION'::character varying, 'CIRCLE'::character varying, 'PROJECT_DIVISION'::character varying, 'PCC'::character varying, 'TRANSMISSION_CIRCLE'::character varying, 'STP_CIRCLE'::character varying, 'ELECTRICAL_DIVISION'::character varying, 'QUALITY_VIGILANCE'::character varying, 'CENTRAL_STORES'::character varying, 'BOARD_OFFICE'::character varying])::text[]))),
    CONSTRAINT payroll_units_verification_status_check CHECK (((verification_status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'PENDING_VERIFICATION'::character varying])::text[])))
);


--
-- Name: payroll_units_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payroll_units ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.payroll_units_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.permissions (
    id bigint NOT NULL,
    code character varying(80) NOT NULL,
    module character varying(50) NOT NULL,
    description text
);


--
-- Name: permissions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.permissions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.permissions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: role_permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role_permissions (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);


--
-- Name: roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.roles (
    id bigint NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    max_scope character varying(12) DEFAULT 'DIVISION'::character varying NOT NULL,
    is_system boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT roles_max_scope_check CHECK (((max_scope)::text = ANY ((ARRAY['ORG'::character varying, 'WING'::character varying, 'DIVISION'::character varying, 'SELF'::character varying])::text[])))
);


--
-- Name: roles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.roles ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.roles_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_adjustments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_adjustments (
    id bigint NOT NULL,
    payroll_batch_id bigint,
    employee_id bigint NOT NULL,
    salary_component_id bigint,
    adjustment_type character varying(30) NOT NULL,
    amount numeric(15,2) NOT NULL,
    effective_period date NOT NULL,
    reason text,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_by bigint,
    approved_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT salary_adjustments_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'APPLIED'::character varying, 'REJECTED'::character varying])::text[])))
);

ALTER TABLE ONLY public.salary_adjustments FORCE ROW LEVEL SECURITY;


--
-- Name: salary_adjustments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_adjustments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_adjustments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_approvals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_approvals (
    id bigint NOT NULL,
    entity_type character varying(40) NOT NULL,
    entity_id bigint NOT NULL,
    from_status character varying(20),
    to_status character varying(20) NOT NULL,
    action character varying(30) NOT NULL,
    acted_by bigint NOT NULL,
    remarks text,
    acted_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: salary_approvals_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_approvals ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_approvals_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_component_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_component_rules (
    id bigint NOT NULL,
    salary_component_id bigint NOT NULL,
    rule_version integer NOT NULL,
    rule_type character varying(30) NOT NULL,
    rate numeric(12,4),
    fixed_amount numeric(15,2),
    effective_from date NOT NULL,
    effective_to date,
    is_active boolean DEFAULT true NOT NULL,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT salary_component_rules_check CHECK (((effective_to IS NULL) OR (effective_to >= effective_from)))
);


--
-- Name: salary_component_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_component_rules ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_component_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_components; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_components (
    id bigint NOT NULL,
    component_code character varying(50) NOT NULL,
    component_name character varying(150) NOT NULL,
    category character varying(20) NOT NULL,
    calculation_type character varying(30) NOT NULL,
    rate numeric(12,4),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    display_order smallint,
    CONSTRAINT salary_components_category_check CHECK (((category)::text = ANY ((ARRAY['EARNING'::character varying, 'DEDUCTION'::character varying])::text[])))
);


--
-- Name: salary_components_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_components ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_components_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_revision_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_revision_batches (
    id bigint NOT NULL,
    batch_no character varying(50) NOT NULL,
    effective_date date NOT NULL,
    reason text,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    maker_id bigint,
    checker_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT salary_revision_batches_check CHECK (((checker_id IS NULL) OR (checker_id <> maker_id))),
    CONSTRAINT salary_revision_batches_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'APPLIED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: salary_revision_batches_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_revision_batches ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_revision_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_revisions (
    id bigint NOT NULL,
    revision_batch_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    salary_component_id bigint NOT NULL,
    old_amount numeric(15,2),
    new_amount numeric(15,2) NOT NULL,
    effective_from date NOT NULL,
    reason text,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_by bigint,
    approved_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT salary_revisions_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'APPLIED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[])))
);

ALTER TABLE ONLY public.salary_revisions FORCE ROW LEVEL SECURITY;


--
-- Name: salary_revisions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_revisions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_revisions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_rule_slabs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_rule_slabs (
    id bigint NOT NULL,
    rule_id bigint NOT NULL,
    slab_no smallint NOT NULL,
    min_value numeric(15,2),
    max_value numeric(15,2),
    rate numeric(12,4),
    fixed_amount numeric(15,2),
    CONSTRAINT salary_rule_slabs_check CHECK (((max_value IS NULL) OR (min_value IS NULL) OR (max_value >= min_value)))
);


--
-- Name: salary_rule_slabs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_rule_slabs ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_rule_slabs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_statements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_statements (
    id bigint NOT NULL,
    statement_no character varying(60) NOT NULL,
    transaction_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    period_id bigint NOT NULL,
    wing_id bigint NOT NULL,
    division_id bigint NOT NULL,
    version integer NOT NULL,
    status character varying(10) DEFAULT 'ISSUED'::character varying NOT NULL,
    template_version character varying(20) NOT NULL,
    payload jsonb NOT NULL,
    payload_sha256 character(64) NOT NULL,
    file_key text NOT NULL,
    file_sha256 character(64) NOT NULL,
    file_size_bytes bigint NOT NULL,
    generated_by bigint NOT NULL,
    generated_at timestamp with time zone DEFAULT now() NOT NULL,
    withdrawn_at timestamp with time zone,
    withdrawn_reason text,
    CONSTRAINT salary_statements_file_size_bytes_check CHECK ((file_size_bytes > 0)),
    CONSTRAINT salary_statements_status_check CHECK (((status)::text = ANY ((ARRAY['ISSUED'::character varying, 'SUPERSEDED'::character varying, 'WITHDRAWN'::character varying])::text[]))),
    CONSTRAINT salary_statements_version_check CHECK ((version > 0))
);

ALTER TABLE ONLY public.salary_statements FORCE ROW LEVEL SECURITY;


--
-- Name: salary_statements_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_statements ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_statements_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_transaction_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_transaction_lines (
    id bigint NOT NULL,
    salary_transaction_id bigint NOT NULL,
    salary_component_id bigint NOT NULL,
    component_code_snapshot character varying(50) NOT NULL,
    component_name_snapshot character varying(150) NOT NULL,
    category_snapshot character varying(20) NOT NULL,
    rule_version integer,
    amount numeric(15,2) DEFAULT 0 NOT NULL,
    calculation_basis numeric(15,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT salary_transaction_lines_category_snapshot_check CHECK (((category_snapshot)::text = ANY ((ARRAY['EARNING'::character varying, 'DEDUCTION'::character varying])::text[])))
);

ALTER TABLE ONLY public.salary_transaction_lines FORCE ROW LEVEL SECURITY;


--
-- Name: salary_transaction_lines_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_transaction_lines ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_transaction_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: salary_transactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salary_transactions (
    id bigint NOT NULL,
    payroll_batch_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    employee_code_snapshot character varying(50) NOT NULL,
    hrms_id_snapshot character varying(20) NOT NULL,
    name_snapshot character varying(150) NOT NULL,
    wing_id_snapshot bigint,
    division_id_snapshot bigint,
    designation_id_snapshot bigint,
    grade_id_snapshot bigint,
    gross_salary numeric(15,2) DEFAULT 0 NOT NULL,
    total_deductions numeric(15,2) DEFAULT 0 NOT NULL,
    net_salary numeric(15,2) DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'CALCULATED'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT salary_transactions_check CHECK ((net_salary = (gross_salary - total_deductions))),
    CONSTRAINT salary_transactions_gross_salary_check CHECK ((gross_salary >= (0)::numeric)),
    CONSTRAINT salary_transactions_status_check CHECK (((status)::text = ANY ((ARRAY['CALCULATED'::character varying, 'VERIFIED'::character varying, 'EXCEPTION'::character varying])::text[]))),
    CONSTRAINT salary_transactions_total_deductions_check CHECK ((total_deductions >= (0)::numeric))
);

ALTER TABLE ONLY public.salary_transactions FORCE ROW LEVEL SECURITY;


--
-- Name: salary_transactions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.salary_transactions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.salary_transactions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: system_parameters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_parameters (
    id bigint NOT NULL,
    param_key character varying(100) NOT NULL,
    param_value text NOT NULL,
    value_type character varying(10) NOT NULL,
    organization_id bigint,
    description text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by bigint,
    CONSTRAINT system_parameters_value_type_check CHECK (((value_type)::text = ANY ((ARRAY['TEXT'::character varying, 'NUMBER'::character varying, 'BOOLEAN'::character varying, 'DATE'::character varying, 'JSON'::character varying])::text[])))
);


--
-- Name: system_parameters_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.system_parameters ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.system_parameters_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: user_role_scopes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_role_scopes (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    wing_id bigint,
    division_id bigint,
    valid_from date DEFAULT CURRENT_DATE NOT NULL,
    valid_to date,
    is_active boolean DEFAULT true NOT NULL,
    granted_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_role_scopes_check CHECK ((num_nonnulls(wing_id, division_id) <= 1)),
    CONSTRAINT user_role_scopes_check1 CHECK (((valid_to IS NULL) OR (valid_to >= valid_from)))
);


--
-- Name: user_role_scopes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.user_role_scopes ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_role_scopes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    username character varying(100) NOT NULL,
    display_name character varying(200) NOT NULL,
    email character varying(254),
    employee_id bigint,
    auth_provider character varying(20) DEFAULT 'LOCAL'::character varying NOT NULL,
    password_hash text,
    must_change_password boolean DEFAULT false NOT NULL,
    failed_attempts smallint DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    last_login_at timestamp with time zone,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT users_auth_provider_check CHECK (((auth_provider)::text = ANY ((ARRAY['LOCAL'::character varying, 'LDAP'::character varying, 'OIDC'::character varying])::text[])))
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: wards; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wards (
    id bigint NOT NULL,
    division_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    code character varying(50) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: wards_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.wards ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.wards_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: wings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wings (
    id bigint NOT NULL,
    organization_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    code character varying(50) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: wings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.wings ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.wings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: circles circles_organization_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circles
    ADD CONSTRAINT circles_organization_code_key UNIQUE (organization_id, code);


--
-- Name: circles circles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circles
    ADD CONSTRAINT circles_pkey PRIMARY KEY (id);


--
-- Name: designations designations_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.designations
    ADD CONSTRAINT designations_code_key UNIQUE (code);


--
-- Name: designations designations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.designations
    ADD CONSTRAINT designations_pkey PRIMARY KEY (id);


--
-- Name: divisions divisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.divisions
    ADD CONSTRAINT divisions_pkey PRIMARY KEY (id);


--
-- Name: divisions divisions_wing_id_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.divisions
    ADD CONSTRAINT divisions_wing_id_code_key UNIQUE (wing_id, code);


--
-- Name: employee_bank_accounts employee_bank_accounts_employee_account_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_bank_accounts
    ADD CONSTRAINT employee_bank_accounts_employee_account_key UNIQUE (employee_id, account_number);


--
-- Name: employee_bank_accounts employee_bank_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_bank_accounts
    ADD CONSTRAINT employee_bank_accounts_pkey PRIMARY KEY (id);


--
-- Name: employee_identity_mappings employee_identity_mappings_identity_type_identity_value_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_identity_mappings
    ADD CONSTRAINT employee_identity_mappings_identity_type_identity_value_key UNIQUE (identity_type, identity_value);


--
-- Name: employee_identity_mappings employee_identity_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_identity_mappings
    ADD CONSTRAINT employee_identity_mappings_pkey PRIMARY KEY (id);


--
-- Name: employee_salary_values employee_salary_values_employee_id_salary_component_id_effe_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_salary_values
    ADD CONSTRAINT employee_salary_values_employee_id_salary_component_id_effe_key UNIQUE (employee_id, salary_component_id, effective_from);


--
-- Name: employee_salary_values employee_salary_values_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_salary_values
    ADD CONSTRAINT employee_salary_values_pkey PRIMARY KEY (id);


--
-- Name: employee_status_history employee_status_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_status_history
    ADD CONSTRAINT employee_status_history_pkey PRIMARY KEY (id);


--
-- Name: employee_transfers employee_transfers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_transfers
    ADD CONSTRAINT employee_transfers_pkey PRIMARY KEY (id);


--
-- Name: employees employees_employee_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_employee_code_key UNIQUE (employee_code);


--
-- Name: employees employees_hrms_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_hrms_id_key UNIQUE (hrms_id);


--
-- Name: employees employees_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_pkey PRIMARY KEY (id);


--
-- Name: grades grades_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grades
    ADD CONSTRAINT grades_code_key UNIQUE (code);


--
-- Name: grades grades_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grades
    ADD CONSTRAINT grades_pkey PRIMARY KEY (id);


--
-- Name: import_batches import_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_batches
    ADD CONSTRAINT import_batches_pkey PRIMARY KEY (id);


--
-- Name: import_errors import_errors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_errors
    ADD CONSTRAINT import_errors_pkey PRIMARY KEY (id);


--
-- Name: import_job_assignments import_job_assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_assignments
    ADD CONSTRAINT import_job_assignments_pkey PRIMARY KEY (id);


--
-- Name: import_job_corrections import_job_corrections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_corrections
    ADD CONSTRAINT import_job_corrections_pkey PRIMARY KEY (id);


--
-- Name: import_job_sheet_issues import_job_sheet_issues_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_sheet_issues
    ADD CONSTRAINT import_job_sheet_issues_pkey PRIMARY KEY (id);


--
-- Name: import_job_sheets import_job_sheets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_sheets
    ADD CONSTRAINT import_job_sheets_pkey PRIMARY KEY (id);


--
-- Name: import_job_workflow_events import_job_workflow_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_workflow_events
    ADD CONSTRAINT import_job_workflow_events_pkey PRIMARY KEY (id);


--
-- Name: import_jobs import_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_jobs
    ADD CONSTRAINT import_jobs_pkey PRIMARY KEY (id);


--
-- Name: import_mapping_profiles import_mapping_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_profiles
    ADD CONSTRAINT import_mapping_profiles_pkey PRIMARY KEY (id);


--
-- Name: import_rows import_rows_batch_id_row_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_rows
    ADD CONSTRAINT import_rows_batch_id_row_no_key UNIQUE (batch_id, row_no);


--
-- Name: import_rows import_rows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_rows
    ADD CONSTRAINT import_rows_pkey PRIMARY KEY (id);


--
-- Name: organizations organizations_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_code_key UNIQUE (code);


--
-- Name: organizations organizations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);


--
-- Name: parent_groups parent_groups_organization_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parent_groups
    ADD CONSTRAINT parent_groups_organization_code_key UNIQUE (organization_id, code);


--
-- Name: parent_groups parent_groups_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parent_groups
    ADD CONSTRAINT parent_groups_pkey PRIMARY KEY (id);


--
-- Name: payroll_batches payroll_batches_period_id_division_id_run_type_run_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_batches
    ADD CONSTRAINT payroll_batches_period_id_division_id_run_type_run_no_key UNIQUE (period_id, division_id, run_type, run_no);


--
-- Name: payroll_batches payroll_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_batches
    ADD CONSTRAINT payroll_batches_pkey PRIMARY KEY (id);


--
-- Name: payroll_periods payroll_periods_organization_id_period_year_period_month_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_periods
    ADD CONSTRAINT payroll_periods_organization_id_period_year_period_month_key UNIQUE (organization_id, period_year, period_month);


--
-- Name: payroll_periods payroll_periods_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_periods
    ADD CONSTRAINT payroll_periods_pkey PRIMARY KEY (id);


--
-- Name: payroll_units payroll_units_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_units
    ADD CONSTRAINT payroll_units_code_key UNIQUE (code);


--
-- Name: payroll_units payroll_units_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_units
    ADD CONSTRAINT payroll_units_pkey PRIMARY KEY (id);


--
-- Name: permissions permissions_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_code_key UNIQUE (code);


--
-- Name: permissions permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);


--
-- Name: role_permissions role_permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);


--
-- Name: roles roles_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_code_key UNIQUE (code);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- Name: salary_adjustments salary_adjustments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_adjustments
    ADD CONSTRAINT salary_adjustments_pkey PRIMARY KEY (id);


--
-- Name: salary_approvals salary_approvals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_approvals
    ADD CONSTRAINT salary_approvals_pkey PRIMARY KEY (id);


--
-- Name: salary_component_rules salary_component_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_component_rules
    ADD CONSTRAINT salary_component_rules_pkey PRIMARY KEY (id);


--
-- Name: salary_component_rules salary_component_rules_salary_component_id_rule_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_component_rules
    ADD CONSTRAINT salary_component_rules_salary_component_id_rule_version_key UNIQUE (salary_component_id, rule_version);


--
-- Name: salary_components salary_components_component_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_components
    ADD CONSTRAINT salary_components_component_code_key UNIQUE (component_code);


--
-- Name: salary_components salary_components_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_components
    ADD CONSTRAINT salary_components_pkey PRIMARY KEY (id);


--
-- Name: salary_revision_batches salary_revision_batches_batch_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_revision_batches
    ADD CONSTRAINT salary_revision_batches_batch_no_key UNIQUE (batch_no);


--
-- Name: salary_revision_batches salary_revision_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_revision_batches
    ADD CONSTRAINT salary_revision_batches_pkey PRIMARY KEY (id);


--
-- Name: salary_revisions salary_revisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_revisions
    ADD CONSTRAINT salary_revisions_pkey PRIMARY KEY (id);


--
-- Name: salary_rule_slabs salary_rule_slabs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_rule_slabs
    ADD CONSTRAINT salary_rule_slabs_pkey PRIMARY KEY (id);


--
-- Name: salary_rule_slabs salary_rule_slabs_rule_id_slab_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_rule_slabs
    ADD CONSTRAINT salary_rule_slabs_rule_id_slab_no_key UNIQUE (rule_id, slab_no);


--
-- Name: salary_statements salary_statements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_pkey PRIMARY KEY (id);


--
-- Name: salary_statements salary_statements_statement_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_statement_no_key UNIQUE (statement_no);


--
-- Name: salary_statements salary_statements_transaction_id_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_transaction_id_version_key UNIQUE (transaction_id, version);


--
-- Name: salary_transaction_lines salary_transaction_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_transaction_lines
    ADD CONSTRAINT salary_transaction_lines_pkey PRIMARY KEY (id);


--
-- Name: salary_transaction_lines salary_transaction_lines_salary_transaction_id_salary_compo_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_transaction_lines
    ADD CONSTRAINT salary_transaction_lines_salary_transaction_id_salary_compo_key UNIQUE (salary_transaction_id, salary_component_id);


--
-- Name: salary_transactions salary_transactions_payroll_batch_id_employee_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_transactions
    ADD CONSTRAINT salary_transactions_payroll_batch_id_employee_id_key UNIQUE (payroll_batch_id, employee_id);


--
-- Name: salary_transactions salary_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_transactions
    ADD CONSTRAINT salary_transactions_pkey PRIMARY KEY (id);


--
-- Name: system_parameters system_parameters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_parameters
    ADD CONSTRAINT system_parameters_pkey PRIMARY KEY (id);


--
-- Name: import_job_sheets uq_import_job_sheet_index; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_sheets
    ADD CONSTRAINT uq_import_job_sheet_index UNIQUE (import_job_id, sheet_index);


--
-- Name: import_job_sheets uq_import_job_sheets_id_job_v08; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_sheets
    ADD CONSTRAINT uq_import_job_sheets_id_job_v08 UNIQUE (id, import_job_id);


--
-- Name: import_mapping_profiles uq_import_mapping_profile; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_profiles
    ADD CONSTRAINT uq_import_mapping_profile UNIQUE (created_by, import_type, name);


--
-- Name: user_role_scopes user_role_scopes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role_scopes
    ADD CONSTRAINT user_role_scopes_pkey PRIMARY KEY (id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: wards wards_division_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wards
    ADD CONSTRAINT wards_division_code_key UNIQUE (division_id, code);


--
-- Name: wards wards_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wards
    ADD CONSTRAINT wards_pkey PRIMARY KEY (id);


--
-- Name: wings wings_organization_id_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wings
    ADD CONSTRAINT wings_organization_id_code_key UNIQUE (organization_id, code);


--
-- Name: wings wings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wings
    ADD CONSTRAINT wings_pkey PRIMARY KEY (id);


--
-- Name: ix_audit_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_audit_entity ON public.audit_logs USING btree (entity_type, entity_id, occurred_at DESC);


--
-- Name: ix_divisions_wing; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_divisions_wing ON public.divisions USING btree (wing_id);


--
-- Name: ix_employee_bank_accounts_employee_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employee_bank_accounts_employee_id ON public.employee_bank_accounts USING btree (employee_id);


--
-- Name: ix_employee_salary_values_import_job_v14; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employee_salary_values_import_job_v14 ON public.employee_salary_values USING btree (import_job_id);


--
-- Name: ix_employee_salary_values_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employee_salary_values_lookup ON public.employee_salary_values USING btree (employee_id, salary_component_id, effective_from DESC);


--
-- Name: ix_employees_circle_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employees_circle_id ON public.employees USING btree (circle_id);


--
-- Name: ix_employees_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employees_code ON public.employees USING btree (employee_code);


--
-- Name: ix_employees_division_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employees_division_status ON public.employees USING btree (division_id, status);


--
-- Name: ix_employees_hrms; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employees_hrms ON public.employees USING btree (hrms_id);


--
-- Name: ix_employees_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employees_name ON public.employees USING btree (lower((name)::text));


--
-- Name: ix_employees_payroll_unit_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employees_payroll_unit_id ON public.employees USING btree (payroll_unit_id);


--
-- Name: ix_employees_ward_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_employees_ward_id ON public.employees USING btree (ward_id);


--
-- Name: ix_import_batches_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_batches_job ON public.import_batches USING btree (import_job_id);


--
-- Name: ix_import_batches_job_sheet; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_batches_job_sheet ON public.import_batches USING btree (import_job_sheet_id);


--
-- Name: ix_import_batches_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_batches_scope ON public.import_batches USING btree (division_id, period_id, status);


--
-- Name: ix_import_errors_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_errors_batch ON public.import_errors USING btree (batch_id, row_no);


--
-- Name: ix_import_job_assignments_user_active_v08; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_job_assignments_user_active_v08 ON public.import_job_assignments USING btree (user_id, import_job_id, assignment_role) WHERE is_active;


--
-- Name: ix_import_job_corrections_assignee_status_v08; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_job_corrections_assignee_status_v08 ON public.import_job_corrections USING btree (assigned_to, status) WHERE (assigned_to IS NOT NULL);


--
-- Name: ix_import_job_corrections_division_v08; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_job_corrections_division_v08 ON public.import_job_corrections USING btree (import_job_id, target_division_id, status);


--
-- Name: ix_import_job_corrections_job_row_v08; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_job_corrections_job_row_v08 ON public.import_job_corrections USING btree (import_job_id, import_job_sheet_id, source_row_number);


--
-- Name: ix_import_job_sheets_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_job_sheets_job ON public.import_job_sheets USING btree (import_job_id, sheet_index);


--
-- Name: ix_import_job_sheets_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_job_sheets_status ON public.import_job_sheets USING btree (import_job_id, status);


--
-- Name: ix_import_job_workflow_events_job_v08; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_job_workflow_events_job_v08 ON public.import_job_workflow_events USING btree (import_job_id, created_at DESC);


--
-- Name: ix_import_jobs_file_sha256; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_jobs_file_sha256 ON public.import_jobs USING btree (file_sha256);


--
-- Name: ix_import_jobs_uploaded_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_jobs_uploaded_by ON public.import_jobs USING btree (uploaded_by, uploaded_at DESC);


--
-- Name: ix_import_mapping_profiles_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_mapping_profiles_owner ON public.import_mapping_profiles USING btree (created_by, is_active);


--
-- Name: ix_import_mapping_profiles_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_mapping_profiles_type ON public.import_mapping_profiles USING btree (import_type, structure_signature);


--
-- Name: ix_import_rows_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_rows_batch ON public.import_rows USING btree (batch_id, row_no);


--
-- Name: ix_import_sheet_issues_row; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_sheet_issues_row ON public.import_job_sheet_issues USING btree (import_job_sheet_id, source_row_number);


--
-- Name: ix_import_sheet_issues_severity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_sheet_issues_severity ON public.import_job_sheet_issues USING btree (import_job_sheet_id, severity);


--
-- Name: ix_payroll_batches; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payroll_batches ON public.payroll_batches USING btree (period_id, division_id, status);


--
-- Name: ix_payroll_periods; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payroll_periods ON public.payroll_periods USING btree (organization_id, period_year, period_month);


--
-- Name: ix_payroll_units_parent_group_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payroll_units_parent_group_id ON public.payroll_units USING btree (parent_group_id);


--
-- Name: ix_salary_approvals_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_salary_approvals_entity ON public.salary_approvals USING btree (entity_type, entity_id, acted_at DESC);


--
-- Name: ix_salary_lines_transaction; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_salary_lines_transaction ON public.salary_transaction_lines USING btree (salary_transaction_id);


--
-- Name: ix_salary_revisions_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_salary_revisions_employee ON public.salary_revisions USING btree (employee_id, effective_from DESC);


--
-- Name: ix_salary_rules_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_salary_rules_lookup ON public.salary_component_rules USING btree (salary_component_id, effective_from DESC);


--
-- Name: ix_salary_transactions_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_salary_transactions_batch ON public.salary_transactions USING btree (payroll_batch_id);


--
-- Name: ix_salary_transactions_employee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_salary_transactions_employee ON public.salary_transactions USING btree (employee_id);


--
-- Name: ix_statement_employee_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_statement_employee_period ON public.salary_statements USING btree (employee_id, period_id, version DESC);


--
-- Name: ix_wings_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_wings_org ON public.wings USING btree (organization_id);


--
-- Name: uq_import_job_assignment_active_v08; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_import_job_assignment_active_v08 ON public.import_job_assignments USING btree (import_job_id, user_id, assignment_role, COALESCE(division_id, (0)::bigint)) WHERE is_active;


--
-- Name: ux_salary_statement_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_salary_statement_current ON public.salary_statements USING btree (transaction_id) WHERE ((status)::text = 'ISSUED'::text);


--
-- Name: payroll_batches trg_guard_finalized_payroll_batch; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_guard_finalized_payroll_batch BEFORE DELETE OR UPDATE ON public.payroll_batches FOR EACH ROW EXECUTE FUNCTION app.guard_finalized_payroll_batch();


--
-- Name: salary_transaction_lines trg_guard_finalized_salary_transaction_lines; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_guard_finalized_salary_transaction_lines BEFORE DELETE OR UPDATE ON public.salary_transaction_lines FOR EACH ROW EXECUTE FUNCTION app.guard_finalized_salary_transaction_line();


--
-- Name: salary_transactions trg_guard_finalized_salary_transactions; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_guard_finalized_salary_transactions BEFORE DELETE OR UPDATE ON public.salary_transactions FOR EACH ROW EXECUTE FUNCTION app.guard_finalized_salary_transaction();


--
-- Name: import_job_assignments trg_guard_import_job_assignment_identity_v08; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_guard_import_job_assignment_identity_v08 BEFORE UPDATE ON public.import_job_assignments FOR EACH ROW EXECUTE FUNCTION app.guard_import_job_assignment_identity_v08();


--
-- Name: import_jobs trg_guard_import_job_source_identity_v08; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_guard_import_job_source_identity_v08 BEFORE UPDATE ON public.import_jobs FOR EACH ROW EXECUTE FUNCTION app.guard_import_job_source_identity_v08();


--
-- Name: circles circles_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circles
    ADD CONSTRAINT circles_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id);


--
-- Name: divisions divisions_wing_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.divisions
    ADD CONSTRAINT divisions_wing_id_fkey FOREIGN KEY (wing_id) REFERENCES public.wings(id);


--
-- Name: employee_bank_accounts employee_bank_accounts_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_bank_accounts
    ADD CONSTRAINT employee_bank_accounts_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: employee_identity_mappings employee_identity_mappings_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_identity_mappings
    ADD CONSTRAINT employee_identity_mappings_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: employee_salary_values employee_salary_values_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_salary_values
    ADD CONSTRAINT employee_salary_values_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: employee_salary_values employee_salary_values_import_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_salary_values
    ADD CONSTRAINT employee_salary_values_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES public.import_jobs(id) ON DELETE SET NULL;


--
-- Name: employee_salary_values employee_salary_values_import_job_sheet_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_salary_values
    ADD CONSTRAINT employee_salary_values_import_job_sheet_id_fkey FOREIGN KEY (import_job_sheet_id) REFERENCES public.import_job_sheets(id) ON DELETE SET NULL;


--
-- Name: employee_salary_values employee_salary_values_salary_component_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_salary_values
    ADD CONSTRAINT employee_salary_values_salary_component_id_fkey FOREIGN KEY (salary_component_id) REFERENCES public.salary_components(id);


--
-- Name: employee_status_history employee_status_history_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_status_history
    ADD CONSTRAINT employee_status_history_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: employee_transfers employee_transfers_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_transfers
    ADD CONSTRAINT employee_transfers_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: employee_transfers employee_transfers_from_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_transfers
    ADD CONSTRAINT employee_transfers_from_division_id_fkey FOREIGN KEY (from_division_id) REFERENCES public.divisions(id);


--
-- Name: employee_transfers employee_transfers_to_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_transfers
    ADD CONSTRAINT employee_transfers_to_division_id_fkey FOREIGN KEY (to_division_id) REFERENCES public.divisions(id);


--
-- Name: employees employees_circle_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_circle_id_fkey FOREIGN KEY (circle_id) REFERENCES public.circles(id);


--
-- Name: employees employees_designation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_designation_id_fkey FOREIGN KEY (designation_id) REFERENCES public.designations(id);


--
-- Name: employees employees_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_division_id_fkey FOREIGN KEY (division_id) REFERENCES public.divisions(id);


--
-- Name: employees employees_grade_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_grade_id_fkey FOREIGN KEY (grade_id) REFERENCES public.grades(id);


--
-- Name: employees employees_payroll_unit_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_payroll_unit_id_fkey FOREIGN KEY (payroll_unit_id) REFERENCES public.payroll_units(id);


--
-- Name: employees employees_ward_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employees
    ADD CONSTRAINT employees_ward_id_fkey FOREIGN KEY (ward_id) REFERENCES public.wards(id);


--
-- Name: import_job_corrections fk_import_job_correction_sheet_v08; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_corrections
    ADD CONSTRAINT fk_import_job_correction_sheet_v08 FOREIGN KEY (import_job_sheet_id, import_job_id) REFERENCES public.import_job_sheets(id, import_job_id) ON DELETE CASCADE;


--
-- Name: import_batches import_batches_approved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_batches
    ADD CONSTRAINT import_batches_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES public.users(id);


--
-- Name: import_batches import_batches_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_batches
    ADD CONSTRAINT import_batches_division_id_fkey FOREIGN KEY (division_id) REFERENCES public.divisions(id);


--
-- Name: import_batches import_batches_import_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_batches
    ADD CONSTRAINT import_batches_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES public.import_jobs(id);


--
-- Name: import_batches import_batches_import_job_sheet_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_batches
    ADD CONSTRAINT import_batches_import_job_sheet_id_fkey FOREIGN KEY (import_job_sheet_id) REFERENCES public.import_job_sheets(id);


--
-- Name: import_batches import_batches_period_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_batches
    ADD CONSTRAINT import_batches_period_id_fkey FOREIGN KEY (period_id) REFERENCES public.payroll_periods(id);


--
-- Name: import_batches import_batches_uploaded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_batches
    ADD CONSTRAINT import_batches_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES public.users(id);


--
-- Name: import_errors import_errors_batch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_errors
    ADD CONSTRAINT import_errors_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES public.import_batches(id) ON DELETE CASCADE;


--
-- Name: import_job_assignments import_job_assignments_assigned_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_assignments
    ADD CONSTRAINT import_job_assignments_assigned_by_fkey FOREIGN KEY (assigned_by) REFERENCES public.users(id);


--
-- Name: import_job_assignments import_job_assignments_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_assignments
    ADD CONSTRAINT import_job_assignments_division_id_fkey FOREIGN KEY (division_id) REFERENCES public.divisions(id);


--
-- Name: import_job_assignments import_job_assignments_import_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_assignments
    ADD CONSTRAINT import_job_assignments_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES public.import_jobs(id) ON DELETE CASCADE;


--
-- Name: import_job_assignments import_job_assignments_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_assignments
    ADD CONSTRAINT import_job_assignments_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: import_job_corrections import_job_corrections_applied_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_corrections
    ADD CONSTRAINT import_job_corrections_applied_by_fkey FOREIGN KEY (applied_by) REFERENCES public.users(id);


--
-- Name: import_job_corrections import_job_corrections_assigned_to_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_corrections
    ADD CONSTRAINT import_job_corrections_assigned_to_fkey FOREIGN KEY (assigned_to) REFERENCES public.users(id);


--
-- Name: import_job_corrections import_job_corrections_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_corrections
    ADD CONSTRAINT import_job_corrections_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: import_job_corrections import_job_corrections_import_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_corrections
    ADD CONSTRAINT import_job_corrections_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES public.import_jobs(id) ON DELETE CASCADE;


--
-- Name: import_job_corrections import_job_corrections_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_corrections
    ADD CONSTRAINT import_job_corrections_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES public.users(id);


--
-- Name: import_job_corrections import_job_corrections_target_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_corrections
    ADD CONSTRAINT import_job_corrections_target_division_id_fkey FOREIGN KEY (target_division_id) REFERENCES public.divisions(id);


--
-- Name: import_job_sheet_issues import_job_sheet_issues_import_job_sheet_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_sheet_issues
    ADD CONSTRAINT import_job_sheet_issues_import_job_sheet_id_fkey FOREIGN KEY (import_job_sheet_id) REFERENCES public.import_job_sheets(id) ON DELETE CASCADE;


--
-- Name: import_job_sheet_issues import_job_sheet_issues_target_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_sheet_issues
    ADD CONSTRAINT import_job_sheet_issues_target_division_id_fkey FOREIGN KEY (target_division_id) REFERENCES public.divisions(id);


--
-- Name: import_job_sheets import_job_sheets_confirmed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_sheets
    ADD CONSTRAINT import_job_sheets_confirmed_by_fkey FOREIGN KEY (confirmed_by) REFERENCES public.users(id);


--
-- Name: import_job_sheets import_job_sheets_import_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_sheets
    ADD CONSTRAINT import_job_sheets_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES public.import_jobs(id) ON DELETE CASCADE;


--
-- Name: import_job_workflow_events import_job_workflow_events_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_workflow_events
    ADD CONSTRAINT import_job_workflow_events_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES public.users(id);


--
-- Name: import_job_workflow_events import_job_workflow_events_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_workflow_events
    ADD CONSTRAINT import_job_workflow_events_division_id_fkey FOREIGN KEY (division_id) REFERENCES public.divisions(id);


--
-- Name: import_job_workflow_events import_job_workflow_events_import_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_job_workflow_events
    ADD CONSTRAINT import_job_workflow_events_import_job_id_fkey FOREIGN KEY (import_job_id) REFERENCES public.import_jobs(id) ON DELETE CASCADE;


--
-- Name: import_jobs import_jobs_approved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_jobs
    ADD CONSTRAINT import_jobs_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES public.users(id);


--
-- Name: import_jobs import_jobs_finalized_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_jobs
    ADD CONSTRAINT import_jobs_finalized_by_fkey FOREIGN KEY (finalized_by) REFERENCES public.users(id);


--
-- Name: import_jobs import_jobs_submitted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_jobs
    ADD CONSTRAINT import_jobs_submitted_by_fkey FOREIGN KEY (submitted_by) REFERENCES public.users(id);


--
-- Name: import_jobs import_jobs_uploaded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_jobs
    ADD CONSTRAINT import_jobs_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES public.users(id);


--
-- Name: import_mapping_profiles import_mapping_profiles_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_profiles
    ADD CONSTRAINT import_mapping_profiles_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: import_rows import_rows_batch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_rows
    ADD CONSTRAINT import_rows_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES public.import_batches(id) ON DELETE CASCADE;


--
-- Name: parent_groups parent_groups_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parent_groups
    ADD CONSTRAINT parent_groups_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id);


--
-- Name: payroll_batches payroll_batches_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_batches
    ADD CONSTRAINT payroll_batches_division_id_fkey FOREIGN KEY (division_id) REFERENCES public.divisions(id);


--
-- Name: payroll_batches payroll_batches_period_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_batches
    ADD CONSTRAINT payroll_batches_period_id_fkey FOREIGN KEY (period_id) REFERENCES public.payroll_periods(id);


--
-- Name: payroll_batches payroll_batches_wing_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_batches
    ADD CONSTRAINT payroll_batches_wing_id_fkey FOREIGN KEY (wing_id) REFERENCES public.wings(id);


--
-- Name: payroll_periods payroll_periods_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_periods
    ADD CONSTRAINT payroll_periods_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id);


--
-- Name: payroll_units payroll_units_parent_group_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payroll_units
    ADD CONSTRAINT payroll_units_parent_group_id_fkey FOREIGN KEY (parent_group_id) REFERENCES public.parent_groups(id);


--
-- Name: role_permissions role_permissions_permission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.permissions(id) ON DELETE CASCADE;


--
-- Name: role_permissions role_permissions_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;


--
-- Name: salary_adjustments salary_adjustments_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_adjustments
    ADD CONSTRAINT salary_adjustments_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: salary_adjustments salary_adjustments_payroll_batch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_adjustments
    ADD CONSTRAINT salary_adjustments_payroll_batch_id_fkey FOREIGN KEY (payroll_batch_id) REFERENCES public.payroll_batches(id);


--
-- Name: salary_adjustments salary_adjustments_salary_component_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_adjustments
    ADD CONSTRAINT salary_adjustments_salary_component_id_fkey FOREIGN KEY (salary_component_id) REFERENCES public.salary_components(id);


--
-- Name: salary_approvals salary_approvals_acted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_approvals
    ADD CONSTRAINT salary_approvals_acted_by_fkey FOREIGN KEY (acted_by) REFERENCES public.users(id);


--
-- Name: salary_component_rules salary_component_rules_salary_component_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_component_rules
    ADD CONSTRAINT salary_component_rules_salary_component_id_fkey FOREIGN KEY (salary_component_id) REFERENCES public.salary_components(id);


--
-- Name: salary_revisions salary_revisions_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_revisions
    ADD CONSTRAINT salary_revisions_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: salary_revisions salary_revisions_revision_batch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_revisions
    ADD CONSTRAINT salary_revisions_revision_batch_id_fkey FOREIGN KEY (revision_batch_id) REFERENCES public.salary_revision_batches(id);


--
-- Name: salary_revisions salary_revisions_salary_component_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_revisions
    ADD CONSTRAINT salary_revisions_salary_component_id_fkey FOREIGN KEY (salary_component_id) REFERENCES public.salary_components(id);


--
-- Name: salary_rule_slabs salary_rule_slabs_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_rule_slabs
    ADD CONSTRAINT salary_rule_slabs_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.salary_component_rules(id);


--
-- Name: salary_statements salary_statements_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_division_id_fkey FOREIGN KEY (division_id) REFERENCES public.divisions(id);


--
-- Name: salary_statements salary_statements_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: salary_statements salary_statements_generated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_generated_by_fkey FOREIGN KEY (generated_by) REFERENCES public.users(id);


--
-- Name: salary_statements salary_statements_period_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_period_id_fkey FOREIGN KEY (period_id) REFERENCES public.payroll_periods(id);


--
-- Name: salary_statements salary_statements_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.salary_transactions(id);


--
-- Name: salary_statements salary_statements_wing_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_statements
    ADD CONSTRAINT salary_statements_wing_id_fkey FOREIGN KEY (wing_id) REFERENCES public.wings(id);


--
-- Name: salary_transaction_lines salary_transaction_lines_salary_component_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_transaction_lines
    ADD CONSTRAINT salary_transaction_lines_salary_component_id_fkey FOREIGN KEY (salary_component_id) REFERENCES public.salary_components(id);


--
-- Name: salary_transaction_lines salary_transaction_lines_salary_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_transaction_lines
    ADD CONSTRAINT salary_transaction_lines_salary_transaction_id_fkey FOREIGN KEY (salary_transaction_id) REFERENCES public.salary_transactions(id);


--
-- Name: salary_transactions salary_transactions_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_transactions
    ADD CONSTRAINT salary_transactions_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: salary_transactions salary_transactions_payroll_batch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salary_transactions
    ADD CONSTRAINT salary_transactions_payroll_batch_id_fkey FOREIGN KEY (payroll_batch_id) REFERENCES public.payroll_batches(id);


--
-- Name: system_parameters system_parameters_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_parameters
    ADD CONSTRAINT system_parameters_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id);


--
-- Name: user_role_scopes user_role_scopes_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role_scopes
    ADD CONSTRAINT user_role_scopes_division_id_fkey FOREIGN KEY (division_id) REFERENCES public.divisions(id);


--
-- Name: user_role_scopes user_role_scopes_granted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role_scopes
    ADD CONSTRAINT user_role_scopes_granted_by_fkey FOREIGN KEY (granted_by) REFERENCES public.users(id);


--
-- Name: user_role_scopes user_role_scopes_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role_scopes
    ADD CONSTRAINT user_role_scopes_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id);


--
-- Name: user_role_scopes user_role_scopes_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role_scopes
    ADD CONSTRAINT user_role_scopes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: user_role_scopes user_role_scopes_wing_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role_scopes
    ADD CONSTRAINT user_role_scopes_wing_id_fkey FOREIGN KEY (wing_id) REFERENCES public.wings(id);


--
-- Name: users users_employee_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES public.employees(id);


--
-- Name: wards wards_division_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wards
    ADD CONSTRAINT wards_division_id_fkey FOREIGN KEY (division_id) REFERENCES public.divisions(id);


--
-- Name: wings wings_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wings
    ADD CONSTRAINT wings_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(id);


--
-- Name: audit_logs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;

--
-- Name: audit_logs audit_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY audit_scope_policy ON public.audit_logs FOR SELECT TO ssga_app USING ((((division_id IS NOT NULL) AND app.can_access_division(division_id)) OR ((division_id IS NULL) AND (EXISTS ( SELECT 1
   FROM (public.user_role_scopes s
     JOIN public.roles r ON ((r.id = s.role_id)))
  WHERE ((s.user_id = app.current_user_id()) AND (s.is_active = true) AND (r.is_active = true) AND ((r.max_scope)::text = 'ORG'::text) AND (s.wing_id IS NULL) AND (s.division_id IS NULL)))))));


--
-- Name: circles; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.circles ENABLE ROW LEVEL SECURITY;

--
-- Name: circles circles_read_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY circles_read_policy ON public.circles FOR SELECT USING (true);


--
-- Name: divisions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.divisions ENABLE ROW LEVEL SECURITY;

--
-- Name: divisions divisions_insert_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY divisions_insert_policy ON public.divisions FOR INSERT TO ssga_app WITH CHECK (app.can_create_division(wing_id));


--
-- Name: divisions divisions_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY divisions_scope_policy ON public.divisions TO ssga_app USING (app.can_access_division(id)) WITH CHECK (app.can_access_division(id));


--
-- Name: employee_bank_accounts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.employee_bank_accounts ENABLE ROW LEVEL SECURITY;

--
-- Name: employee_bank_accounts employee_bank_accounts_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY employee_bank_accounts_scope_policy ON public.employee_bank_accounts USING (app.can_access_employee(employee_id)) WITH CHECK (app.can_access_employee(employee_id));


--
-- Name: employee_identity_mappings; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.employee_identity_mappings ENABLE ROW LEVEL SECURITY;

--
-- Name: employee_identity_mappings employee_identity_mappings_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY employee_identity_mappings_scope_policy ON public.employee_identity_mappings USING (app.can_access_employee(employee_id)) WITH CHECK (app.can_access_employee(employee_id));


--
-- Name: employee_salary_values employee_salary_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY employee_salary_scope_policy ON public.employee_salary_values TO ssga_app USING (app.can_access_employee(employee_id)) WITH CHECK (app.can_access_employee(employee_id));


--
-- Name: employee_salary_values; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.employee_salary_values ENABLE ROW LEVEL SECURITY;

--
-- Name: employees; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.employees ENABLE ROW LEVEL SECURITY;

--
-- Name: employees employees_insert_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY employees_insert_policy ON public.employees FOR INSERT TO ssga_app WITH CHECK (app.can_create_employee(division_id));


--
-- Name: employees employees_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY employees_scope_policy ON public.employees TO ssga_app USING (app.can_access_employee(id)) WITH CHECK (app.can_access_division(division_id));


--
-- Name: import_batches; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_batches ENABLE ROW LEVEL SECURITY;

--
-- Name: import_batches import_batches_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_batches_scope_policy ON public.import_batches TO ssga_app USING ((((division_id IS NOT NULL) AND app.can_access_division(division_id)) OR ((division_id IS NULL) AND (EXISTS ( SELECT 1
   FROM (public.user_role_scopes urs
     JOIN public.roles r ON ((r.id = urs.role_id)))
  WHERE ((urs.user_id = app.current_user_id()) AND (urs.is_active = true) AND (r.is_active = true) AND ((r.max_scope)::text = 'ORG'::text) AND (urs.wing_id IS NULL) AND (urs.division_id IS NULL))))))) WITH CHECK ((((division_id IS NOT NULL) AND app.can_access_division(division_id)) OR ((division_id IS NULL) AND (EXISTS ( SELECT 1
   FROM (public.user_role_scopes urs
     JOIN public.roles r ON ((r.id = urs.role_id)))
  WHERE ((urs.user_id = app.current_user_id()) AND (urs.is_active = true) AND (r.is_active = true) AND ((r.max_scope)::text = 'ORG'::text) AND (urs.wing_id IS NULL) AND (urs.division_id IS NULL)))))));


--
-- Name: import_errors; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_errors ENABLE ROW LEVEL SECURITY;

--
-- Name: import_errors import_errors_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_errors_scope_policy ON public.import_errors TO ssga_app USING ((EXISTS ( SELECT 1
   FROM public.import_batches b
  WHERE (b.id = import_errors.batch_id)))) WITH CHECK ((EXISTS ( SELECT 1
   FROM public.import_batches b
  WHERE (b.id = import_errors.batch_id))));


--
-- Name: import_job_assignments; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_job_assignments ENABLE ROW LEVEL SECURITY;

--
-- Name: import_job_assignments import_job_assignments_insert_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_assignments_insert_v08 ON public.import_job_assignments FOR INSERT TO ssga_app WITH CHECK (((assigned_by = app.current_user_id()) AND app.can_assign_import_role(user_id, (assignment_role)::text, division_id) AND app.can_manage_import_assignments(import_job_id, division_id) AND (EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_assignments.import_job_id) AND ((j.status)::text <> ALL ((ARRAY['IMPORTED'::character varying, 'CANCELLED'::character varying])::text[])))))));


--
-- Name: import_job_assignments import_job_assignments_manager_select_v09; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_assignments_manager_select_v09 ON public.import_job_assignments FOR SELECT TO ssga_app USING (app.can_manage_import_assignments(import_job_id, NULL::bigint));


--
-- Name: import_job_assignments import_job_assignments_select_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_assignments_select_v08 ON public.import_job_assignments FOR SELECT TO ssga_app USING (((user_id = app.current_user_id()) OR (assigned_by = app.current_user_id())));


--
-- Name: import_job_assignments import_job_assignments_update_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_assignments_update_v08 ON public.import_job_assignments FOR UPDATE TO ssga_app USING (((assigned_by = app.current_user_id()) OR app.can_manage_import_assignments(import_job_id, division_id))) WITH CHECK (((assigned_by = app.current_user_id()) OR app.can_manage_import_assignments(import_job_id, division_id)));


--
-- Name: import_job_assignments import_job_assignments_uploader_select_v09; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_assignments_uploader_select_v09 ON public.import_job_assignments FOR SELECT TO ssga_app USING ((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_assignments.import_job_id) AND (j.uploaded_by = app.current_user_id())))));


--
-- Name: import_job_corrections; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_job_corrections ENABLE ROW LEVEL SECURITY;

--
-- Name: import_job_corrections import_job_corrections_insert_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_corrections_insert_v08 ON public.import_job_corrections FOR INSERT TO ssga_app WITH CHECK (((created_by = app.current_user_id()) AND ((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_corrections.import_job_id) AND (j.uploaded_by = app.current_user_id())))) OR app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text], target_division_id))));


--
-- Name: import_job_corrections import_job_corrections_select_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_corrections_select_v08 ON public.import_job_corrections FOR SELECT TO ssga_app USING (((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_corrections.import_job_id) AND (j.uploaded_by = app.current_user_id())))) OR (app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text, 'APPROVER'::text, 'FINALIZER'::text, 'VIEWER'::text], NULL::bigint) AND ((EXISTS ( SELECT 1
   FROM public.import_job_assignments a
  WHERE ((a.import_job_id = import_job_corrections.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND (a.division_id IS NULL) AND ((a.assignment_role)::text = ANY ((ARRAY['RESOLVER'::character varying, 'APPROVER'::character varying, 'FINALIZER'::character varying, 'VIEWER'::character varying])::text[]))))) OR ((target_division_id IS NOT NULL) AND (EXISTS ( SELECT 1
   FROM public.import_job_assignments a
  WHERE ((a.import_job_id = import_job_corrections.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND (a.division_id = import_job_corrections.target_division_id) AND ((a.assignment_role)::text = ANY ((ARRAY['RESOLVER'::character varying, 'APPROVER'::character varying, 'FINALIZER'::character varying, 'VIEWER'::character varying])::text[]))))))))));


--
-- Name: import_job_corrections import_job_corrections_update_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_corrections_update_v08 ON public.import_job_corrections FOR UPDATE TO ssga_app USING (((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_corrections.import_job_id) AND (j.uploaded_by = app.current_user_id())))) OR app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text, 'APPROVER'::text], target_division_id))) WITH CHECK (((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_corrections.import_job_id) AND (j.uploaded_by = app.current_user_id())))) OR app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text, 'APPROVER'::text], target_division_id)));


--
-- Name: import_job_sheet_issues; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_job_sheet_issues ENABLE ROW LEVEL SECURITY;

--
-- Name: import_job_sheet_issues import_job_sheet_issues_assigned_resolver_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheet_issues_assigned_resolver_v08 ON public.import_job_sheet_issues TO ssga_app USING ((EXISTS ( SELECT 1
   FROM public.import_job_sheets s
  WHERE ((s.id = import_job_sheet_issues.import_job_sheet_id) AND app.has_import_job_assignment(s.import_job_id, ARRAY['RESOLVER'::text], NULL::bigint) AND ((EXISTS ( SELECT 1
           FROM public.import_job_assignments a
          WHERE ((a.import_job_id = s.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND ((a.assignment_role)::text = 'RESOLVER'::text) AND (a.division_id IS NULL)))) OR ((import_job_sheet_issues.target_division_id IS NOT NULL) AND (EXISTS ( SELECT 1
           FROM public.import_job_assignments a
          WHERE ((a.import_job_id = s.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND ((a.assignment_role)::text = 'RESOLVER'::text) AND (a.division_id = import_job_sheet_issues.target_division_id)))))))))) WITH CHECK ((EXISTS ( SELECT 1
   FROM public.import_job_sheets s
  WHERE ((s.id = import_job_sheet_issues.import_job_sheet_id) AND app.has_import_job_assignment(s.import_job_id, ARRAY['RESOLVER'::text], NULL::bigint) AND ((EXISTS ( SELECT 1
           FROM public.import_job_assignments a
          WHERE ((a.import_job_id = s.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND ((a.assignment_role)::text = 'RESOLVER'::text) AND (a.division_id IS NULL)))) OR ((import_job_sheet_issues.target_division_id IS NOT NULL) AND (EXISTS ( SELECT 1
           FROM public.import_job_assignments a
          WHERE ((a.import_job_id = s.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND ((a.assignment_role)::text = 'RESOLVER'::text) AND (a.division_id = import_job_sheet_issues.target_division_id))))))))));


--
-- Name: import_job_sheet_issues import_job_sheet_issues_assigned_select_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheet_issues_assigned_select_v08 ON public.import_job_sheet_issues FOR SELECT TO ssga_app USING ((EXISTS ( SELECT 1
   FROM public.import_job_sheets s
  WHERE ((s.id = import_job_sheet_issues.import_job_sheet_id) AND (app.has_import_job_assignment(s.import_job_id, ARRAY['RESOLVER'::text, 'APPROVER'::text, 'FINALIZER'::text, 'VIEWER'::text], NULL::bigint) AND ((EXISTS ( SELECT 1
           FROM public.import_job_assignments a
          WHERE ((a.import_job_id = s.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND (a.division_id IS NULL) AND ((a.assignment_role)::text = ANY ((ARRAY['RESOLVER'::character varying, 'APPROVER'::character varying, 'FINALIZER'::character varying, 'VIEWER'::character varying])::text[]))))) OR ((import_job_sheet_issues.target_division_id IS NOT NULL) AND (EXISTS ( SELECT 1
           FROM public.import_job_assignments a
          WHERE ((a.import_job_id = s.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND (a.division_id = import_job_sheet_issues.target_division_id) AND ((a.assignment_role)::text = ANY ((ARRAY['RESOLVER'::character varying, 'APPROVER'::character varying, 'FINALIZER'::character varying, 'VIEWER'::character varying])::text[]))))))))))));


--
-- Name: import_job_sheet_issues import_job_sheet_issues_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheet_issues_scope_policy ON public.import_job_sheet_issues TO ssga_app USING ((EXISTS ( SELECT 1
   FROM (public.import_job_sheets s
     JOIN public.import_jobs j ON ((j.id = s.import_job_id)))
  WHERE ((s.id = import_job_sheet_issues.import_job_sheet_id) AND (j.uploaded_by = app.current_user_id()))))) WITH CHECK ((EXISTS ( SELECT 1
   FROM (public.import_job_sheets s
     JOIN public.import_jobs j ON ((j.id = s.import_job_id)))
  WHERE ((s.id = import_job_sheet_issues.import_job_sheet_id) AND (j.uploaded_by = app.current_user_id())))));


--
-- Name: import_job_sheets; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_job_sheets ENABLE ROW LEVEL SECURITY;

--
-- Name: import_job_sheets import_job_sheets_assigned_insert_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheets_assigned_insert_v08 ON public.import_job_sheets FOR INSERT TO ssga_app WITH CHECK (app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text], NULL::bigint));


--
-- Name: import_job_sheets import_job_sheets_assigned_select_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheets_assigned_select_v08 ON public.import_job_sheets FOR SELECT TO ssga_app USING (app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text, 'APPROVER'::text, 'FINALIZER'::text, 'VIEWER'::text], NULL::bigint));


--
-- Name: import_job_sheets import_job_sheets_assigned_update_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheets_assigned_update_v08 ON public.import_job_sheets FOR UPDATE TO ssga_app USING (app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text], NULL::bigint)) WITH CHECK (app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text], NULL::bigint));


--
-- Name: import_job_sheets import_job_sheets_insert_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheets_insert_policy ON public.import_job_sheets FOR INSERT TO ssga_app WITH CHECK ((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_sheets.import_job_id) AND (j.uploaded_by = app.current_user_id())))));


--
-- Name: import_job_sheets import_job_sheets_select_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheets_select_policy ON public.import_job_sheets FOR SELECT TO ssga_app USING ((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_sheets.import_job_id) AND (j.uploaded_by = app.current_user_id())))));


--
-- Name: import_job_sheets import_job_sheets_update_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_sheets_update_policy ON public.import_job_sheets FOR UPDATE TO ssga_app USING ((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_sheets.import_job_id) AND (j.uploaded_by = app.current_user_id()))))) WITH CHECK ((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_sheets.import_job_id) AND (j.uploaded_by = app.current_user_id())))));


--
-- Name: import_job_workflow_events; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_job_workflow_events ENABLE ROW LEVEL SECURITY;

--
-- Name: import_job_workflow_events import_job_workflow_events_insert_manager_v09; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_workflow_events_insert_manager_v09 ON public.import_job_workflow_events FOR INSERT TO ssga_app WITH CHECK (((actor_user_id = app.current_user_id()) AND ((action)::text = ANY ((ARRAY['ASSIGN'::character varying, 'REASSIGN'::character varying, 'REVOKE_ASSIGNMENT'::character varying])::text[])) AND app.can_manage_import_assignments(import_job_id, division_id)));


--
-- Name: import_job_workflow_events import_job_workflow_events_insert_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_workflow_events_insert_v08 ON public.import_job_workflow_events FOR INSERT TO ssga_app WITH CHECK (((actor_user_id = app.current_user_id()) AND ((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_workflow_events.import_job_id) AND (j.uploaded_by = app.current_user_id())))) OR app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text, 'APPROVER'::text, 'FINALIZER'::text], division_id))));


--
-- Name: import_job_workflow_events import_job_workflow_events_select_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_job_workflow_events_select_v08 ON public.import_job_workflow_events FOR SELECT TO ssga_app USING (((EXISTS ( SELECT 1
   FROM public.import_jobs j
  WHERE ((j.id = import_job_workflow_events.import_job_id) AND (j.uploaded_by = app.current_user_id())))) OR (app.has_import_job_assignment(import_job_id, ARRAY['RESOLVER'::text, 'APPROVER'::text, 'FINALIZER'::text, 'VIEWER'::text], NULL::bigint) AND ((EXISTS ( SELECT 1
   FROM public.import_job_assignments a
  WHERE ((a.import_job_id = import_job_workflow_events.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND (a.division_id IS NULL)))) OR ((division_id IS NOT NULL) AND (EXISTS ( SELECT 1
   FROM public.import_job_assignments a
  WHERE ((a.import_job_id = import_job_workflow_events.import_job_id) AND (a.user_id = app.current_user_id()) AND a.is_active AND (a.division_id = import_job_workflow_events.division_id)))))))));


--
-- Name: import_jobs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_jobs ENABLE ROW LEVEL SECURITY;

--
-- Name: import_jobs import_jobs_assigned_select_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_jobs_assigned_select_v08 ON public.import_jobs FOR SELECT TO ssga_app USING (app.has_import_job_assignment(id, ARRAY['RESOLVER'::text, 'APPROVER'::text, 'FINALIZER'::text, 'VIEWER'::text], NULL::bigint));


--
-- Name: import_jobs import_jobs_assigned_update_v08; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_jobs_assigned_update_v08 ON public.import_jobs FOR UPDATE TO ssga_app USING (app.has_import_job_assignment(id, ARRAY['RESOLVER'::text, 'APPROVER'::text, 'FINALIZER'::text], NULL::bigint)) WITH CHECK (app.has_import_job_assignment(id, ARRAY['RESOLVER'::text, 'APPROVER'::text, 'FINALIZER'::text], NULL::bigint));


--
-- Name: import_jobs import_jobs_assignment_manager_select_v09; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_jobs_assignment_manager_select_v09 ON public.import_jobs FOR SELECT TO ssga_app USING (app.can_manage_import_assignments(id, NULL::bigint));


--
-- Name: import_jobs import_jobs_insert_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_jobs_insert_policy ON public.import_jobs FOR INSERT TO ssga_app WITH CHECK ((uploaded_by = app.current_user_id()));


--
-- Name: import_jobs import_jobs_select_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_jobs_select_policy ON public.import_jobs FOR SELECT TO ssga_app USING ((uploaded_by = app.current_user_id()));


--
-- Name: import_jobs import_jobs_update_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_jobs_update_policy ON public.import_jobs FOR UPDATE TO ssga_app USING ((uploaded_by = app.current_user_id())) WITH CHECK ((uploaded_by = app.current_user_id()));


--
-- Name: import_mapping_profiles; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_mapping_profiles ENABLE ROW LEVEL SECURITY;

--
-- Name: import_mapping_profiles import_mapping_profiles_delete_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_mapping_profiles_delete_policy ON public.import_mapping_profiles FOR DELETE TO ssga_app USING ((created_by = app.current_user_id()));


--
-- Name: import_mapping_profiles import_mapping_profiles_insert_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_mapping_profiles_insert_policy ON public.import_mapping_profiles FOR INSERT TO ssga_app WITH CHECK ((created_by = app.current_user_id()));


--
-- Name: import_mapping_profiles import_mapping_profiles_select_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_mapping_profiles_select_policy ON public.import_mapping_profiles FOR SELECT TO ssga_app USING (((created_by = app.current_user_id()) OR is_shared));


--
-- Name: import_mapping_profiles import_mapping_profiles_update_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_mapping_profiles_update_policy ON public.import_mapping_profiles FOR UPDATE TO ssga_app USING ((created_by = app.current_user_id())) WITH CHECK ((created_by = app.current_user_id()));


--
-- Name: import_rows; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.import_rows ENABLE ROW LEVEL SECURITY;

--
-- Name: import_rows import_rows_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY import_rows_scope_policy ON public.import_rows TO ssga_app USING ((EXISTS ( SELECT 1
   FROM public.import_batches b
  WHERE (b.id = import_rows.batch_id)))) WITH CHECK ((EXISTS ( SELECT 1
   FROM public.import_batches b
  WHERE (b.id = import_rows.batch_id))));


--
-- Name: payroll_batches payroll_batch_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY payroll_batch_scope_policy ON public.payroll_batches TO ssga_app USING (app.can_access_division(division_id)) WITH CHECK (app.can_access_division(division_id));


--
-- Name: payroll_batches; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.payroll_batches ENABLE ROW LEVEL SECURITY;

--
-- Name: salary_adjustments salary_adjustment_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY salary_adjustment_scope_policy ON public.salary_adjustments TO ssga_app USING (app.can_access_employee(employee_id)) WITH CHECK (app.can_access_employee(employee_id));


--
-- Name: salary_adjustments; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.salary_adjustments ENABLE ROW LEVEL SECURITY;

--
-- Name: salary_transaction_lines salary_line_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY salary_line_scope_policy ON public.salary_transaction_lines TO ssga_app USING ((EXISTS ( SELECT 1
   FROM public.salary_transactions t
  WHERE ((t.id = salary_transaction_lines.salary_transaction_id) AND app.can_access_employee(t.employee_id)))));


--
-- Name: salary_revisions salary_revision_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY salary_revision_scope_policy ON public.salary_revisions TO ssga_app USING (app.can_access_employee(employee_id)) WITH CHECK (app.can_access_employee(employee_id));


--
-- Name: salary_revisions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.salary_revisions ENABLE ROW LEVEL SECURITY;

--
-- Name: salary_statements salary_statement_insert_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY salary_statement_insert_policy ON public.salary_statements FOR INSERT WITH CHECK (app.can_access_employee(employee_id));


--
-- Name: salary_statements salary_statement_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY salary_statement_scope_policy ON public.salary_statements FOR SELECT TO ssga_app USING (app.can_access_employee(employee_id));


--
-- Name: salary_statements salary_statement_update_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY salary_statement_update_policy ON public.salary_statements FOR UPDATE USING (app.can_access_employee(employee_id)) WITH CHECK (app.can_access_employee(employee_id));


--
-- Name: salary_statements; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.salary_statements ENABLE ROW LEVEL SECURITY;

--
-- Name: salary_transaction_lines; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.salary_transaction_lines ENABLE ROW LEVEL SECURITY;

--
-- Name: salary_transactions salary_transaction_scope_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY salary_transaction_scope_policy ON public.salary_transactions TO ssga_app USING (app.can_access_employee(employee_id)) WITH CHECK (app.can_access_employee(employee_id));


--
-- Name: salary_transactions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.salary_transactions ENABLE ROW LEVEL SECURITY;

--
-- Name: wards; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.wards ENABLE ROW LEVEL SECURITY;

--
-- Name: wards wards_read_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wards_read_policy ON public.wards FOR SELECT USING (true);


--
-- Name: SCHEMA app; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA app TO ssga_app;


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA public TO ssga_app;


--
-- Name: FUNCTION can_assign_import_role(p_user_id bigint, p_assignment_role text, p_division_id bigint); Type: ACL; Schema: app; Owner: -
--

REVOKE ALL ON FUNCTION app.can_assign_import_role(p_user_id bigint, p_assignment_role text, p_division_id bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION app.can_assign_import_role(p_user_id bigint, p_assignment_role text, p_division_id bigint) TO ssga_app;


--
-- Name: FUNCTION can_create_employee(p_division_id bigint); Type: ACL; Schema: app; Owner: -
--

REVOKE ALL ON FUNCTION app.can_create_employee(p_division_id bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION app.can_create_employee(p_division_id bigint) TO ssga_app;


--
-- Name: FUNCTION can_manage_import_assignments(p_import_job_id bigint, p_division_id bigint); Type: ACL; Schema: app; Owner: -
--

REVOKE ALL ON FUNCTION app.can_manage_import_assignments(p_import_job_id bigint, p_division_id bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION app.can_manage_import_assignments(p_import_job_id bigint, p_division_id bigint) TO ssga_app;


--
-- Name: FUNCTION find_auth_user(p_username character varying); Type: ACL; Schema: app; Owner: -
--

REVOKE ALL ON FUNCTION app.find_auth_user(p_username character varying) FROM PUBLIC;
GRANT ALL ON FUNCTION app.find_auth_user(p_username character varying) TO ssga_app;


--
-- Name: FUNCTION find_user_authorities(p_user_id bigint); Type: ACL; Schema: app; Owner: -
--

REVOKE ALL ON FUNCTION app.find_user_authorities(p_user_id bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION app.find_user_authorities(p_user_id bigint) TO ssga_app;


--
-- Name: FUNCTION has_import_job_assignment(p_import_job_id bigint, p_roles text[], p_division_id bigint); Type: ACL; Schema: app; Owner: -
--

REVOKE ALL ON FUNCTION app.has_import_job_assignment(p_import_job_id bigint, p_roles text[], p_division_id bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION app.has_import_job_assignment(p_import_job_id bigint, p_roles text[], p_division_id bigint) TO ssga_app;


--
-- Name: FUNCTION review_payroll_batch(p_batch_id bigint, p_action character varying, p_remarks text); Type: ACL; Schema: app; Owner: -
--

REVOKE ALL ON FUNCTION app.review_payroll_batch(p_batch_id bigint, p_action character varying, p_remarks text) FROM PUBLIC;
GRANT ALL ON FUNCTION app.review_payroll_batch(p_batch_id bigint, p_action character varying, p_remarks text) TO ssga_app;


--
-- Name: FUNCTION write_audit_log(p_action character varying, p_entity_type character varying, p_entity_id character varying, p_wing_id bigint, p_division_id bigint, p_old_values jsonb, p_new_values jsonb, p_reason text, p_request_id uuid); Type: ACL; Schema: app; Owner: -
--

REVOKE ALL ON FUNCTION app.write_audit_log(p_action character varying, p_entity_type character varying, p_entity_id character varying, p_wing_id bigint, p_division_id bigint, p_old_values jsonb, p_new_values jsonb, p_reason text, p_request_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION app.write_audit_log(p_action character varying, p_entity_type character varying, p_entity_id character varying, p_wing_id bigint, p_division_id bigint, p_old_values jsonb, p_new_values jsonb, p_reason text, p_request_id uuid) TO ssga_app;


--
-- Name: TABLE audit_logs; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT ON TABLE public.audit_logs TO ssga_app;


--
-- Name: SEQUENCE audit_logs_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.audit_logs_id_seq TO ssga_app;


--
-- Name: TABLE circles; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT ON TABLE public.circles TO ssga_app;


--
-- Name: SEQUENCE circles_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.circles_id_seq TO ssga_app;


--
-- Name: TABLE designations; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.designations TO ssga_app;


--
-- Name: SEQUENCE designations_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.designations_id_seq TO ssga_app;


--
-- Name: TABLE divisions; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.divisions TO ssga_app;


--
-- Name: SEQUENCE divisions_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.divisions_id_seq TO ssga_app;


--
-- Name: TABLE employee_bank_accounts; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.employee_bank_accounts TO ssga_app;


--
-- Name: SEQUENCE employee_bank_accounts_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.employee_bank_accounts_id_seq TO ssga_app;


--
-- Name: SEQUENCE employee_code_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.employee_code_seq TO ssga_app;


--
-- Name: TABLE employee_identity_mappings; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.employee_identity_mappings TO ssga_app;


--
-- Name: SEQUENCE employee_identity_mappings_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.employee_identity_mappings_id_seq TO ssga_app;


--
-- Name: TABLE employee_salary_values; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.employee_salary_values TO ssga_app;


--
-- Name: SEQUENCE employee_salary_values_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.employee_salary_values_id_seq TO ssga_app;


--
-- Name: SEQUENCE employee_status_history_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.employee_status_history_id_seq TO ssga_app;


--
-- Name: SEQUENCE employee_temp_hrms_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.employee_temp_hrms_seq TO ssga_app;


--
-- Name: SEQUENCE employee_transfers_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.employee_transfers_id_seq TO ssga_app;


--
-- Name: TABLE employees; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.employees TO ssga_app;


--
-- Name: SEQUENCE employees_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.employees_id_seq TO ssga_app;


--
-- Name: TABLE grades; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.grades TO ssga_app;


--
-- Name: SEQUENCE grades_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.grades_id_seq TO ssga_app;


--
-- Name: TABLE import_batches; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.import_batches TO ssga_app;


--
-- Name: SEQUENCE import_batches_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_batches_id_seq TO ssga_app;


--
-- Name: TABLE import_errors; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.import_errors TO ssga_app;


--
-- Name: SEQUENCE import_errors_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_errors_id_seq TO ssga_app;


--
-- Name: TABLE import_job_assignments; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.import_job_assignments TO ssga_app;


--
-- Name: SEQUENCE import_job_assignments_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_job_assignments_id_seq TO ssga_app;


--
-- Name: TABLE import_job_corrections; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.import_job_corrections TO ssga_app;


--
-- Name: SEQUENCE import_job_corrections_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_job_corrections_id_seq TO ssga_app;


--
-- Name: TABLE import_job_sheet_issues; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.import_job_sheet_issues TO ssga_app;


--
-- Name: SEQUENCE import_job_sheet_issues_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_job_sheet_issues_id_seq TO ssga_app;


--
-- Name: TABLE import_job_sheets; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.import_job_sheets TO ssga_app;


--
-- Name: SEQUENCE import_job_sheets_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_job_sheets_id_seq TO ssga_app;


--
-- Name: TABLE import_job_workflow_events; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT ON TABLE public.import_job_workflow_events TO ssga_app;


--
-- Name: SEQUENCE import_job_workflow_events_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_job_workflow_events_id_seq TO ssga_app;


--
-- Name: TABLE import_jobs; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.import_jobs TO ssga_app;


--
-- Name: SEQUENCE import_jobs_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_jobs_id_seq TO ssga_app;


--
-- Name: TABLE import_mapping_profiles; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.import_mapping_profiles TO ssga_app;


--
-- Name: SEQUENCE import_mapping_profiles_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_mapping_profiles_id_seq TO ssga_app;


--
-- Name: TABLE import_rows; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.import_rows TO ssga_app;


--
-- Name: SEQUENCE import_rows_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.import_rows_id_seq TO ssga_app;


--
-- Name: TABLE organizations; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.organizations TO ssga_app;


--
-- Name: SEQUENCE organizations_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.organizations_id_seq TO ssga_app;


--
-- Name: TABLE parent_groups; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.parent_groups TO ssga_app;


--
-- Name: SEQUENCE parent_groups_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.parent_groups_id_seq TO ssga_app;


--
-- Name: TABLE payroll_batches; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.payroll_batches TO ssga_app;


--
-- Name: SEQUENCE payroll_batches_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.payroll_batches_id_seq TO ssga_app;


--
-- Name: TABLE payroll_periods; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.payroll_periods TO ssga_app;


--
-- Name: SEQUENCE payroll_periods_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.payroll_periods_id_seq TO ssga_app;


--
-- Name: TABLE payroll_units; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.payroll_units TO ssga_app;


--
-- Name: SEQUENCE payroll_units_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.payroll_units_id_seq TO ssga_app;


--
-- Name: TABLE permissions; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT ON TABLE public.permissions TO ssga_app;


--
-- Name: SEQUENCE permissions_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.permissions_id_seq TO ssga_app;


--
-- Name: TABLE role_permissions; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT ON TABLE public.role_permissions TO ssga_app;


--
-- Name: TABLE roles; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT ON TABLE public.roles TO ssga_app;


--
-- Name: SEQUENCE roles_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.roles_id_seq TO ssga_app;


--
-- Name: TABLE salary_adjustments; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.salary_adjustments TO ssga_app;


--
-- Name: SEQUENCE salary_adjustments_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_adjustments_id_seq TO ssga_app;


--
-- Name: TABLE salary_approvals; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT ON TABLE public.salary_approvals TO ssga_app;


--
-- Name: SEQUENCE salary_approvals_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_approvals_id_seq TO ssga_app;


--
-- Name: TABLE salary_component_rules; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.salary_component_rules TO ssga_app;


--
-- Name: SEQUENCE salary_component_rules_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_component_rules_id_seq TO ssga_app;


--
-- Name: TABLE salary_components; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.salary_components TO ssga_app;


--
-- Name: SEQUENCE salary_components_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_components_id_seq TO ssga_app;


--
-- Name: TABLE salary_revision_batches; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.salary_revision_batches TO ssga_app;


--
-- Name: SEQUENCE salary_revision_batches_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_revision_batches_id_seq TO ssga_app;


--
-- Name: TABLE salary_revisions; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.salary_revisions TO ssga_app;


--
-- Name: SEQUENCE salary_revisions_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_revisions_id_seq TO ssga_app;


--
-- Name: TABLE salary_rule_slabs; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.salary_rule_slabs TO ssga_app;


--
-- Name: SEQUENCE salary_rule_slabs_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_rule_slabs_id_seq TO ssga_app;


--
-- Name: TABLE salary_statements; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT ON TABLE public.salary_statements TO ssga_app;


--
-- Name: COLUMN salary_statements.status; Type: ACL; Schema: public; Owner: -
--

GRANT UPDATE(status) ON TABLE public.salary_statements TO ssga_app;


--
-- Name: COLUMN salary_statements.withdrawn_at; Type: ACL; Schema: public; Owner: -
--

GRANT UPDATE(withdrawn_at) ON TABLE public.salary_statements TO ssga_app;


--
-- Name: COLUMN salary_statements.withdrawn_reason; Type: ACL; Schema: public; Owner: -
--

GRANT UPDATE(withdrawn_reason) ON TABLE public.salary_statements TO ssga_app;


--
-- Name: SEQUENCE salary_statements_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_statements_id_seq TO ssga_app;


--
-- Name: TABLE salary_transaction_lines; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.salary_transaction_lines TO ssga_app;


--
-- Name: SEQUENCE salary_transaction_lines_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_transaction_lines_id_seq TO ssga_app;


--
-- Name: TABLE salary_transactions; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.salary_transactions TO ssga_app;


--
-- Name: SEQUENCE salary_transactions_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.salary_transactions_id_seq TO ssga_app;


--
-- Name: TABLE system_parameters; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.system_parameters TO ssga_app;


--
-- Name: SEQUENCE system_parameters_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.system_parameters_id_seq TO ssga_app;


--
-- Name: SEQUENCE user_role_scopes_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.user_role_scopes_id_seq TO ssga_app;


--
-- Name: SEQUENCE users_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.users_id_seq TO ssga_app;


--
-- Name: TABLE wards; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT ON TABLE public.wards TO ssga_app;


--
-- Name: SEQUENCE wards_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.wards_id_seq TO ssga_app;


--
-- Name: TABLE wings; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.wings TO ssga_app;


--
-- Name: SEQUENCE wings_id_seq; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE public.wings_id_seq TO ssga_app;


--
-- PostgreSQL database dump complete
--



-- ============================================================================
-- END OF 01_schema.sql (baseline v14)
-- ============================================================================
