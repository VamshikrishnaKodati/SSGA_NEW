-- ============================================================
-- SSGA NEW - 26. PAYROLL WORKFLOW HISTORY INTEGRITY (additive)
-- PostgreSQL 17
-- ============================================================
--
-- Serializes payroll reviews and permits only the audited statement
-- supersession transition required by reissue and batch reopening.
-- Existing tables, keys, indexes, approval history, and audit logs remain
-- unchanged.
-- ============================================================

BEGIN;

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
    v_required_permission VARCHAR(80);
    v_approval_id BIGINT;
BEGIN
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

    IF UPPER(p_action) NOT IN ('APPROVE', 'REJECT') THEN
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

    v_required_permission := 'payroll.approve';
    IF UPPER(p_action) = 'APPROVE' THEN
        v_new_status := 'APPROVED';
    ELSE
        v_new_status := 'REJECTED';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.user_role_scopes urs
        JOIN public.roles r ON r.id = urs.role_id
        JOIN public.role_permissions rp ON rp.role_id = r.id
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE urs.user_id = v_user_id
          AND urs.is_active = true
          AND r.is_active = true
          AND p.code = v_required_permission
    ) THEN
        RAISE EXCEPTION
            'User % does not have permission %',
            v_username,
            v_required_permission;
    END IF;

    IF v_batch_status NOT IN ('SUBMITTED', 'UNDER_REVIEW') THEN
        RAISE EXCEPTION
            'Payroll batch % cannot be reviewed from status %',
            p_batch_id,
            v_batch_status;
    END IF;

    IF UPPER(p_action) = 'APPROVE'
       AND v_maker_id IS NOT NULL
       AND v_maker_id = v_user_id THEN
        RAISE EXCEPTION
            'Maker cannot approve the same payroll batch';
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

    RETURN v_approval_id;
END;
$function$;

CREATE OR REPLACE FUNCTION app.guard_immutable_salary_statement()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'app'
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('ISSUED', 'SUPERSEDED') THEN
            RAISE EXCEPTION
                'Salary statement % is issued/superseded and cannot be deleted.',
                OLD.id USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status = 'ISSUED'
       AND NEW.status = 'SUPERSEDED'
       AND OLD.withdrawn_at IS NULL
       AND OLD.withdrawn_reason IS NULL
       AND NEW.withdrawn_at IS NOT NULL
       AND NULLIF(BTRIM(NEW.withdrawn_reason), '') IS NOT NULL
       AND NEW.id IS NOT DISTINCT FROM OLD.id
       AND NEW.statement_no IS NOT DISTINCT FROM OLD.statement_no
       AND NEW.transaction_id IS NOT DISTINCT FROM OLD.transaction_id
       AND NEW.employee_id IS NOT DISTINCT FROM OLD.employee_id
       AND NEW.period_id IS NOT DISTINCT FROM OLD.period_id
       AND NEW.wing_id IS NOT DISTINCT FROM OLD.wing_id
       AND NEW.division_id IS NOT DISTINCT FROM OLD.division_id
       AND NEW.version IS NOT DISTINCT FROM OLD.version
       AND NEW.template_version IS NOT DISTINCT FROM OLD.template_version
       AND NEW.payload IS NOT DISTINCT FROM OLD.payload
       AND NEW.payload_sha256 IS NOT DISTINCT FROM OLD.payload_sha256
       AND NEW.file_key IS NOT DISTINCT FROM OLD.file_key
       AND NEW.file_sha256 IS NOT DISTINCT FROM OLD.file_sha256
       AND NEW.file_size_bytes IS NOT DISTINCT FROM OLD.file_size_bytes
       AND NEW.generated_by IS NOT DISTINCT FROM OLD.generated_by
       AND NEW.generated_at IS NOT DISTINCT FROM OLD.generated_at THEN
        RETURN NEW;
    END IF;

    IF OLD.status IN ('ISSUED', 'SUPERSEDED') THEN
        RAISE EXCEPTION
            'Salary statement % is issued/superseded and cannot be changed.',
            OLD.id USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$function$;

INSERT INTO public.schema_migrations (version, name)
VALUES (26, '26_payroll_workflow_integrity_hardening')
ON CONFLICT (version) DO NOTHING;

COMMIT;
