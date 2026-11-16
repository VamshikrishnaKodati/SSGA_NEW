-- ---------------------------------------------------------------------------
-- 52: Supplementary payroll runs and held-row transfer
--
-- A supplementary run pays rows that a regular run left out. Rows move out of
-- the original batch explicitly and are audited; a finalized batch is never
-- recalculated, because an ON_HOLD row was already excluded from its totals.
-- ---------------------------------------------------------------------------

ALTER TABLE public.salary_transactions
    DROP CONSTRAINT IF EXISTS salary_transactions_status_check;

ALTER TABLE public.salary_transactions
    ADD CONSTRAINT salary_transactions_status_check
    CHECK (status IN ('CALCULATED', 'VERIFIED', 'EXCEPTION', 'ON_HOLD', 'TRANSFERRED'));

ALTER TABLE public.salary_transactions
    ADD COLUMN IF NOT EXISTS transferred_to_batch_id bigint
        REFERENCES public.payroll_batches(id),
    ADD COLUMN IF NOT EXISTS transferred_at timestamptz;

COMMENT ON COLUMN public.salary_transactions.transferred_to_batch_id IS
    'Supplementary batch that now pays this row. Set when status = TRANSFERRED.';

COMMENT ON COLUMN public.salary_transactions.transferred_at IS
    'When the row was moved to the supplementary batch.';

-- A transferred row must name its destination, so the audit trail is complete.
ALTER TABLE public.salary_transactions
    DROP CONSTRAINT IF EXISTS salary_transactions_transfer_target_check;

ALTER TABLE public.salary_transactions
    ADD CONSTRAINT salary_transactions_transfer_target_check
    CHECK (status <> 'TRANSFERRED' OR transferred_to_batch_id IS NOT NULL);