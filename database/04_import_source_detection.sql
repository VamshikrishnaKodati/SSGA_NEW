BEGIN;

-- Remove existing CHECK constraints that specifically restrict source_type.
DO $$
DECLARE
    item RECORD;
BEGIN
    FOR item IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'public.import_jobs'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%source_type%'
    LOOP
        EXECUTE format(
            'ALTER TABLE public.import_jobs DROP CONSTRAINT %I',
            item.conname
        );
    END LOOP;
END $$;

ALTER TABLE public.import_jobs
ADD CONSTRAINT chk_import_jobs_source_type
CHECK (
    source_type IN (
        'UNCLASSIFIED',
        'EMPLOYEE_MASTER',
        'SALARY_ENTITLEMENTS',
        'PAYROLL_HISTORY',
        'SALARY_REVISIONS'
    )
);

COMMIT;