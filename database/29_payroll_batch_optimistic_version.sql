-- Optimistic concurrency for payroll batch workflow actions.
--
-- Every UPDATE on a batch increments version, so the client can prove that the
-- APPROVED batch it displayed is still the same batch the server is finalizing.
-- submitted_at records the latest submission for approval.

BEGIN;

ALTER TABLE public.payroll_batches
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE public.payroll_batches
    ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP WITH TIME ZONE;

CREATE OR REPLACE FUNCTION app.bump_payroll_batch_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.version := OLD.version + 1;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_bump_payroll_batch_version
    ON public.payroll_batches;

CREATE TRIGGER trg_bump_payroll_batch_version
    BEFORE UPDATE ON public.payroll_batches
    FOR EACH ROW
    EXECUTE FUNCTION app.bump_payroll_batch_version();

GRANT EXECUTE ON FUNCTION app.bump_payroll_batch_version() TO ssga_app;

COMMIT;
