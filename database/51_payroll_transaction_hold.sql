-- ---------------------------------------------------------------------------
-- 51: Partial salary hold (ON_HOLD rows)
--
-- A disputed employee must not block the other 140 rows in a division batch.
-- A row is put ON_HOLD with a reason, excluded from totals, verification and
-- submission, and picked up later by a supplementary run.
-- ---------------------------------------------------------------------------

ALTER TABLE public.salary_transactions
    ADD COLUMN IF NOT EXISTS hold_reason text,
    ADD COLUMN IF NOT EXISTS held_by bigint REFERENCES public.users(id),
    ADD COLUMN IF NOT EXISTS held_at timestamptz;

ALTER TABLE public.salary_transactions
    DROP CONSTRAINT IF EXISTS salary_transactions_status_check;

ALTER TABLE public.salary_transactions
    ADD CONSTRAINT salary_transactions_status_check
    CHECK (status IN ('CALCULATED', 'VERIFIED', 'EXCEPTION', 'ON_HOLD'));

-- A held row must say why, so the checker and the auditor can see it.
ALTER TABLE public.salary_transactions
    DROP CONSTRAINT IF EXISTS salary_transactions_hold_reason_check;

ALTER TABLE public.salary_transactions
    ADD CONSTRAINT salary_transactions_hold_reason_check
    CHECK (status <> 'ON_HOLD' OR (hold_reason IS NOT NULL AND length(btrim(hold_reason)) > 0));

COMMENT ON COLUMN public.salary_transactions.hold_reason IS
    'Why this row is excluded from the payout run. Required while status = ON_HOLD.';

COMMENT ON COLUMN public.salary_transactions.held_by IS
    'User who placed or last confirmed the hold.';

COMMENT ON COLUMN public.salary_transactions.held_at IS
    'When the hold was placed.';