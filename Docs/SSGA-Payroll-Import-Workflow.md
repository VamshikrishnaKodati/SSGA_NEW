# SSGA payroll and data import workflow

## Purpose and scope

This guide explains the current SSGA workflow to client users and identifies
the differences that still need to be resolved before the application can be
described as fully meeting the stated role requirements. It describes the
application behavior reviewed on 1 November 2026; it is not a payroll
compliance certification or a substitute for the organization's approved
payroll policy.

The client-confirmed interpretation used here is that an assigned payroll
"unit" means the user's assigned **Division**.

## Responsibilities and access

| Role | Scope | Intended responsibility | Demo username |
|---|---|---|---|
| System Administrator | Entire organization | Full system administration | `admin` |
| Organization Administrator | Organization | Employee and salary administration | `org_admin` |
| Wing Administrator | Assigned wing | Wing-scoped administration and oversight | `wing_admin_eng` |
| Division Administrator | Assigned division | Division-scoped administration and oversight | `division_admin_a1` |
| Salary / Payroll Operator | Assigned division | Prepare imports and process salary batches | `payroll_maker` |
| Approving Authority | Assigned division | Independently review and approve payroll | `payroll_checker` |
| Report User | Assigned division | View and download reports for the assigned division | `report_user` |
| Employee | Self | View own salary statements | `employee_ravi` |
| Auditor | Organization or assigned scope | Read-only audit and report access | `auditor` |

`payroll_finalizer` is a second **Approving Authority** account for demonstrating
the separate-user finalization handoff. It is not a distinct role in the
current role catalogue. The import workflow requires a different user to
finalize an approved job.

Demo accounts are for local or staging environments only. Their password is
set by the operator when running `database/seed_demo_users.sql`; the login
screen never embeds or displays a password. The demo account list is shown in
Vite development mode, or when `VITE_SHOW_DEMO_ACCOUNTS=true` is explicitly
set for a controlled staging build. Do not run demo seeds in production.

## Monthly data import

The current import-job workflow is:

1. **Upload a source file.** The operator uploads an Excel workbook
   (`.xlsx`, `.xlsm`, `.xls`) or CSV. The import job records a SHA-256 file hash
   for integrity tracking.
2. **Analyse the workbook.** Open or analyse the job, inspect its sheets, and
   classify each sheet for the type of data it contains.
3. **Map source columns.** Run auto-map, review its suggestions, correct the
   mapping, or apply a saved mapping profile. Mapping profiles can be reused
   for workbooks with a matching structure.
4. **Preview and validate.** Preview the mapped data, run sheet validation,
   review valid, warning, and error row counts, and download an error report
   when available. Resolve input errors before submitting.
5. **Assign and submit.** Record the responsible resolver and division as
   required by the job, then submit the prepared import for review.
6. **Independent review.** An Approving Authority reviews the submitted job
   and approves it or returns it with rejection remarks. A user cannot approve
   their own uploaded or submitted job.
7. **Finalize and load.** A different authorized user finalizes the approved
   job. Salary entitlements are committed to salary values; Payroll History is
   committed to the separate historical-record store. The workflow records
   imported, skipped, and error row totals.

During this final import operation, the job page reads persisted backend
processing events for its progress percentage, row counts, elapsed time, and
event log. Progress counts are provisional while the database transaction is
running; a failed import rolls the transaction back and reports no partially
committed rows. The estimated time remaining is calculated from the server's
recorded processing rate, not from a client-side progress timer. Period, Wing,
and Division are shown only when the import job actually records them; the
current import-job model does not capture Wing or Division as job-level
metadata.

Importing data remains separate from generating or finalizing a monthly
payroll batch. After a successful import, review the server-reported import
results before proceeding through the monthly payroll workflow.

### Import limitations to explain to users

- The legacy `/salary-values/preview` endpoint parses a file for preview; it
  does **not** load or persist salary values. Use the import-job workflow for
  the reviewed import process.
- `EMPLOYEE_MASTER` remains preview-only.
  `EMPLOYEE_MASTER_AND_COMPONENTS` commits: it creates employees that do not
  exist yet and inserts their component values. Existing employees are matched
  by employee code or HRMS ID and their master fields are never updated; a
  component value that already exists for the same effective date is reported
  as an error and left unchanged. New employees require employee-creation
  authority and a mapped division.
  `PAYROLL_HISTORY` commits only in the component-detail layout to a separate
  historical-record table. None of these commit types create operational
  payroll batches, transactions, approvals, or statements. Keep history
  imports in a separate job from other commit types.
- A saved column mapping speeds up repeat uploads, but the operator still
  needs to review the mapping and validation results for each file.

### Approved requirements for Employee Master

The following Employee Master behavior is still a requirement, not a claim
that the current application implements it. The combined
`EMPLOYEE_MASTER_AND_COMPONENTS` type implements the creation and component
rules below; plain `EMPLOYEE_MASTER` remains preview-only.

- **Employee Master:** Require HRMS ID and employee name; validate optional
  fields against the actual schema, resolve division and wing unambiguously,
  and enforce their relationship and the importing user's scope on the
  server. New employees may be inserted only with employee-creation authority.
  Existing HRMS IDs require an explicit update/skip decision; never overwrite
  silently. Preserve the internal employee ID, reject conflicting workbook
  duplicates, and require a separate audited identity-correction path for an
  HRMS ID change. Employee imports must not create payroll batches, salary
  transactions, salary entitlements, or statements.
### Payroll History import model

Payroll History uses the shared upload, map, validate, preview, independent
review, and finalize workflow. The implemented layout is component-detail:
one row per employee, payroll month, and salary component. Summary-only and
wide layouts are not inferred or mixed into this format. Optional gross,
deduction, and net totals must be mapped together and reconcile on every row.

Finalization re-reads and validates the source workbook inside the import
transaction. The new `public.payroll_history_records` table preserves recorded
amounts, raw source status when mapped, source file hash/name, worksheet, row
number, and source row values. It references employees and salary components,
including inactive components, but does not calculate from current salary
entitlements. Row-level authorization follows the employee's accessible
division. Duplicate employee/period/component records and changed or invalid
source rows reject and roll back the whole history job; history jobs cannot be
mixed with other commit types. Imported history does not create payroll
periods, batches, transactions, approvals, or statements and does not infer a
paid status. The Employee-wise salary history report shows imported component
rows alongside operational payroll rows, keeping raw source status distinct
from payroll batch status.

The history storage migration is additive and pending application to existing
databases. Apply `database/27_payroll_history_imports.sql` before deploying the
history commit path.

## Monthly payroll

1. **Select the period and division.** Work against an open payroll period
   and the operator's assigned division.
2. **Load prepared salary inputs.** Ensure the current employee and salary
   values are available. Where data arrives via workbook, complete the import
   workflow above before generating the batch.
3. **Generate a batch.** The operator creates the monthly batch for the period,
   division, and run type. Duplicate batches are guarded by the backend.
4. **Review readiness and verify.** Check the batch readiness results and
   employee transactions. Resolve readiness issues and verify the batch before
   submission.
5. **Submit for approval.** Submit only after verification. The batch moves to
   the approval queue.
6. **Approve or reject.** The Approving Authority reviews the submitted batch.
   Rejection requires a reason; the operator can correct and resubmit.
7. **Finalize.** Finalization requires an approved, ready batch, an explicit
   confirmation, and separation from the maker. Finalized payroll data is
   protected against ordinary edits.
8. **Issue statements and report.** Authorized users generate or download
   salary statements and view reports within their scope.
9. **Close the period.** Period close is intended only after all payroll
   batches for that period are finalized. Reopening a finalized batch is an
   audited correction path and supersedes previously issued statements.

## Comparison with free public references

These are reference implementations and general access-control guidance, not
the organization's governing payroll rules.

| Reference | Useful pattern | SSGA comparison |
|---|---|---|
| [NIST Role-Based Access Controls](https://www.nist.gov/publications/role-based-access-controls) | Assign capabilities through roles and control access through role and scope. | SSGA defines role permissions and organization/wing/division scopes. Organization-wide period actions now have a separate permission; apply migration 25 to activate it in an existing database. |
| [Frappe HR payroll setup](https://docs.frappe.io/hr/payroll-setup) | Configure periods and salary components/structures before producing salary slips. | SSGA has periods, salary components, employee salary values, imports, and payroll batches. The unsupported import types remain an open implementation gap. |
| [Frappe HR Payroll Entry](https://docs.frappe.io/hr/payroll-entry) | Select payroll frequency and employee scope, fetch employees, generate salary slips, verify, then submit in bulk. | SSGA similarly selects a period/division, generates a batch, checks readiness, verifies, submits, approves, and finalizes. SSGA adds a distinct maker/checker flow and import review. |

The product references above are freely readable online. They do not establish
legal or statutory compliance for HMWSSB. Statutory calculations, filings,
retention periods, and payment controls must be validated against the
organization's applicable rules and approved procedures.

## UI library review

- **Skiper UI:** its [official site](https://skiper-ui.com/) describes
  copy-in components for shadcn/ui. The site advertises premium components and
  a paid tier; it is not a drop-in React component dependency for SSGA's
  current Vite/CSS UI. Avoid adding paid or license-unclear source without
  client approval.
- **Animaster** and **Vengeance AI:** no authoritative UI-library repository
  or license could be verified from the supplied names. Do not add code from
  similarly named projects until the client supplies their exact URLs and
  licenses.
- The current SSGA UI already uses shared React components and a design-token
  stylesheet. The role navigation and import-stage shortcut were updated in
  that system rather than adding unverified packages.

## Gaps and actions

| Priority | Finding | Requirement impact / next action |
|---|---|---|
| High | `EMPLOYEE_MASTER` imports cannot be finalized. | Employee creation and update behavior still needs an approved matching/authority policy. Payroll History instead uses the separate `payroll_history_records` model and does not reuse operational payroll workflow tables. |
| Addressed in migration 27 (pending application and validation) | Payroll History had no safe persistence target that preserved source evidence without fabricating payroll workflow events. | `27_payroll_history_imports.sql` adds the separate history table; the import path revalidates in one transaction, preserves source evidence/status, enforces employee-scope RLS and duplicate protection, and rolls back the complete history job on failure. |
| Addressed in migration 26 (pending application) | Live review routine did not lock a payroll batch while deciding its status, allowing concurrent stale reviews; the statement immutability trigger rejected the `ISSUED` to `SUPERSEDED` transition used by reissue and reopening. | `26_payroll_workflow_integrity_hardening.sql` locks the batch during review, conditionally changes its status before appending approval history, and allows only supersession with withdrawal metadata while keeping statement snapshots immutable. Apply the migration before deploying this workflow fix. |
| Addressed | Payroll periods are organization-wide, but division-scoped payroll permissions could reach period lifecycle operations. Period close also checked batches before establishing the actor's RLS context. | Period creation and lifecycle changes now require `payroll.period.manage`, granted only to System and Organization Administrators; period lists are scope-filtered and close establishes the actor context before checking batches. Apply migration `25_payroll_period_scope.sql` before deploying these backend changes. |
| Addressed | The audit page had no API to search or display server-side audit records. | A read-only audit query and filter UI now use the database's existing row-level scope policy. Sensitive old/new audit payload values are deliberately excluded from the response. |
| Addressed | Payroll Operator has `payroll.prepare`, while the import navigation was gated only on `salary.manage`. | The import menu now follows the same preparation permissions as the import page. |
| Addressed | The seeded Report User was organization-scoped although the role is division-scoped. | The demo seed now assigns `report_user` to `ENG-A1`. |
| Addressed | A frontend `FINALIZER` role did not exist in the database; the finalizer demo is actually an Approving Authority. | The unused role was removed from the frontend catalogue and the demo label now describes the handoff accurately. |
| Open for multi-organization deployments | Organization-level role scopes are global in the current schema; they are not tied to a specific `organization_id`. | This seed assumes one HMWSSB organization. Before operating multiple customer organizations in one database, bind organization-level role grants to an organization and enforce that binding in authorization. |

## Source files

- Import UI and workflow: `Frontend/src/pages/ImportCenter.jsx`
- Payroll workspace: `Frontend/src/pages/payroll/PayrollWorkspace.jsx`
- Audit API: `backend/src/main/java/SSGA_Backend/audit/AuditEventController.java`
- Period authorization: `backend/src/main/java/SSGA_Backend/payroll/PayrollPeriodService.java`
- Period permission migration: `database/25_payroll_period_scope.sql`
- Payroll workflow integrity migration: `database/26_payroll_workflow_integrity_hardening.sql`
- Payroll history import migration: `database/27_payroll_history_imports.sql`
- Role navigation: `Frontend/src/lib/permissions.js`
- Demo users: `database/seed_demo_users.sql`
- Role and permission reference data: `database/seed_reference_data.sql`
