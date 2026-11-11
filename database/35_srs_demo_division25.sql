-- ============================================================================
-- 35_srs_demo_division25.sql - Demo scenario for the SRS import walkthrough
-- Payroll Operator (payroll_off1) scoped to Division 25, August 2026.
-- Seeds July 2026 payroll so the August import can be compared to it.
-- ============================================================================

BEGIN;

INSERT INTO public.divisions (id, wing_id, name, code, status)
VALUES (25, 7, 'DEWATERING (D25)', 'D25', 'ACTIVE')
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    code = EXCLUDED.code,
    status = EXCLUDED.status;

SELECT setval(
    pg_get_serial_sequence('public.divisions', 'id'),
    GREATEST((SELECT MAX(id) FROM public.divisions), 25)
);

INSERT INTO public.payroll_periods
    (organization_id, period_year, period_month, period_start, period_end, status)
VALUES
    (2, 2026, 7, DATE '2026-07-01', DATE '2026-07-31', 'OPEN'),
    (2, 2026, 8, DATE '2026-08-01', DATE '2026-08-31', 'OPEN')
ON CONFLICT (organization_id, period_year, period_month) DO NOTHING;

UPDATE public.employees
SET division_id = 25,
    hmwssb_division_id = 25,
    updated_at = now()
WHERE id BETWEEN 2 AND 14;

UPDATE public.user_role_scopes
SET division_id = 25
WHERE user_id = 25
  AND is_active;

DO $$
DECLARE
    v_period bigint;
    v_batch bigint;
    v_user bigint;
    v_employee record;
    v_transaction bigint;
    v_basic numeric(15,2);
    v_da numeric(15,2);
    v_hra numeric(15,2);
    v_gpf numeric(15,2);
    v_gross numeric(15,2);
    v_net numeric(15,2);
BEGIN
    SELECT id INTO v_period
    FROM public.payroll_periods
    WHERE organization_id = 2
      AND period_year = 2026
      AND period_month = 7;

    SELECT id INTO v_user FROM public.users WHERE username = 'sysadmin';

    SELECT id INTO v_batch
    FROM public.payroll_batches
    WHERE period_id = v_period
      AND division_id = 25
      AND run_type = 'REGULAR'
      AND run_no = 1;

    IF v_batch IS NULL THEN
        INSERT INTO public.payroll_batches (
            period_id, wing_id, division_id, run_type, run_no, status,
            maker_id, employee_count, gross_total, deduction_total, net_total,
            created_at, division_name_snapshot
        ) VALUES (
            v_period, 7, 25, 'REGULAR', 1, 'DRAFT',
            v_user, 0, 0, 0, 0, now(), 'DEWATERING (D25)'
        ) RETURNING id INTO v_batch;

        FOR v_employee IN
            SELECT * FROM public.employees
            WHERE id BETWEEN 2 AND 14
            ORDER BY id
        LOOP
            v_basic := 50000 + (v_employee.id - 2) * 500;
            v_da := round(v_basic * 0.20, 2);
            v_hra := round(v_basic * 0.10, 2);
            v_gpf := round(v_basic * 0.10, 2);
            v_gross := v_basic + v_da + v_hra;
            v_net := v_gross - v_gpf;

            INSERT INTO public.salary_transactions (
                payroll_batch_id, employee_id, employee_code_snapshot,
                hrms_id_snapshot, name_snapshot, wing_id_snapshot,
                division_id_snapshot, designation_id_snapshot,
                grade_id_snapshot, gross_salary, total_deductions,
                net_salary, status
            ) VALUES (
                v_batch, v_employee.id, v_employee.employee_code,
                v_employee.hrms_id, v_employee.name, 7,
                25, v_employee.designation_id, v_employee.grade_id,
                v_gross, v_gpf, v_net, 'VERIFIED'
            ) RETURNING id INTO v_transaction;

            INSERT INTO public.salary_transaction_lines (
                salary_transaction_id, salary_component_id,
                component_code_snapshot, component_name_snapshot,
                category_snapshot, amount
            ) VALUES
                (v_transaction, 146, 'BASIC', 'Basic Pay', 'EARNING', v_basic),
                (v_transaction, 147, 'DA', 'Dearness Allowance', 'EARNING', v_da),
                (v_transaction, 148, 'HRA', 'House Rent Allowance', 'EARNING', v_hra),
                (v_transaction, 162, 'GPF_DED', 'GPF Subscription', 'DEDUCTION', v_gpf);
        END LOOP;

        UPDATE public.payroll_batches b
        SET employee_count = agg.employee_count,
            gross_total = agg.gross_total,
            deduction_total = agg.deduction_total,
            net_total = agg.net_total
        FROM (
            SELECT COUNT(*)::integer AS employee_count,
                   COALESCE(SUM(gross_salary), 0) AS gross_total,
                   COALESCE(SUM(total_deductions), 0) AS deduction_total,
                   COALESCE(SUM(net_salary), 0) AS net_total
            FROM public.salary_transactions
            WHERE payroll_batch_id = v_batch
        ) agg
        WHERE b.id = v_batch;

        UPDATE public.payroll_batches
        SET status = 'FINALIZED',
            finalized_by = v_user,
            finalized_at = now()
        WHERE id = v_batch;
    END IF;
END $$;

COMMIT;
