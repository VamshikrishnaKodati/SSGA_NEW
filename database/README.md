# Database setup

`01_schema.sql` is the complete version-14 baseline (schema + functions +
policies + grants). Files `02_*.sql` .. `14_*.sql` are history only: they are
already contained in the baseline and must NOT be replayed on a fresh database.

## Fresh database (empty cluster)

```powershell
$env:PGPASSWORD='<postgres password>'
$psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
$db   = 'your_new_db'

& $psql -h 127.0.0.1 -U postgres -d postgres -c "CREATE DATABASE $db"
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -f database\00_roles.sql
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -f database\01_schema.sql
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -f database\seed_reference_data.sql
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -v admin_password='<strong password>' -f database\seed_bootstrap_admin.sql
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -f database\15_statement_reissue_permission.sql
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -f database\16_import_provenance_salary_revisions.sql
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -f database\17_import_sheet_review_visibility.sql
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -f database\18_payroll_finalization_audit.sql
foreach ($migration in @(
  'database\19_finalize_immutability_hardening.sql',
  'database\20_import_integrity_hardening.sql',
  'database\21_payroll_integrity_hardening.sql',
  'database\22_rls_hardening.sql',
  'database\23_additional_import_validations.sql',
  'database\24_salary_value_amount_check.sql',
  'database\25_payroll_period_scope.sql',
  'database\26_payroll_workflow_integrity_hardening.sql',
  'database\27_payroll_history_imports.sql',
  'database\28_active_role_scope_and_finalizer.sql',
  'database\29_payroll_batch_optimistic_version.sql',
  'database\30_import_job_progress.sql'
)) {
  & $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -f $migration
  if ($LASTEXITCODE -ne 0) { throw "Migration failed: $migration" }
}
# optional demo accounts for local/staging (one per role, shared password)
& $psql -h 127.0.0.1 -U postgres -d $db -v ON_ERROR_STOP=1 -v demo_password='<demo password>' -f database\seed_demo_users.sql
```

Order matters:

- `00_roles.sql` creates the `ssga_app` / `ssga_workflow` roles and `pgcrypto`.
- `seed_reference_data.sql` creates the organization, roles, permissions,
  salary components, parent groups and payroll units. It is idempotent.
- `seed_bootstrap_admin.sql` creates the admin user (role scope needs the roles
  seeded above). The password is hashed in-database, never committed.
- `seed_demo_users.sql` creates one account per role (org/wing/division admins,
  payroll maker/checker/finalizer, report user, auditor, employee) with a shared
  demo password supplied by the operator. Demo/local use only; never run
  against production.
- Migration `25_payroll_period_scope.sql` adds the organization-scoped
  `payroll.period.manage` permission and the database authorization helper.
  Apply it after migrations `19` through `24` on an existing database; apply it
  after `18` in the fresh-database sequence above.
- Migration `26_payroll_workflow_integrity_hardening.sql` serializes batch
  reviews and permits only an immutable `ISSUED` to `SUPERSEDED` statement
  transition with withdrawal metadata. It adds no tables or constraints.
- Migration `27_payroll_history_imports.sql` creates a separately scoped,
  append-only-by-privilege store for imported component-detail payroll history.
  It does not create payroll batches, transactions, approvals, or statements.
- Migration `28_active_role_scope_and_finalizer.sql` adds the separate
  division-scoped Payroll Finalizer role and binds row-level scope checks to a
  server-selected role assignment. It removes finalization permission from
  Approving Authority so approval and finalization remain separate decisions.
  Approval review also checks permission on the selected assignment rather
  than across all of a user's assignments. Login requires selecting one active
  assignment; no permissions are merged across a user's assignments. This
  migration is unapplied until explicitly installed in the target database.
- Migration `30_import_job_progress.sql` adds an append-only, RLS-protected
  progress-event store for backend-reported import processing counts and logs.
  Apply it before deploying the import progress UI.
- If the local/staging `org_admin` demo account exists but has no valid
  organization-wide assignment, run
  `database\repair_demo_org_admin_scope.sql` as a database administrator after
  migration 28. It adds only the missing assignment; it does not reset the
  account password or remove existing role scopes. Do not use this repair for
  production accounts.

## Existing database

Apply only new files in `database/` with a number greater than the last version
recorded in `schema_migrations`. Never edit or replay applied migrations.

## Migrations registry

`schema_migrations` is created by `15_statement_reissue_permission.sql` and
backfilled with `14`, then records `15`, `16`, `17` and `18`.
