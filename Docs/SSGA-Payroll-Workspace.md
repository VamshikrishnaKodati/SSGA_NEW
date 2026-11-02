# Payroll Workspace

## Existing integration

- `GET /api/payroll/periods` returns accessible periods with organization name,
  dates, and actual status. The period schema and response do not currently
  contain a description field.
- `GET /api/divisions/my-scope` returns only divisions visible through
  division-scoped RLS; each division includes its wing ID and name. The
  payroll workspace also grants read access to this endpoint to users with
  `payroll.view` or `payroll.prepare`.
- `GET /api/payroll/batches?periodId=...` and `GET /api/payroll/batches/{id}`
  return batches protected by payroll permissions and division-scoped RLS.
- `GET /api/payroll/batches/{id}/summary` returns batch context and totals
  without loading every transaction.
- `GET /api/payroll/batches/{id}/transactions?page=...&size=...&search=...&hrmsId=...`
  returns a server-filtered and server-paginated employee register. Page
  numbering starts at 1; page size is limited to 100.
- `GET /api/payroll/batches/{id}/transactions/{transactionId}` returns one
  authorized transaction and its salary-component lines for the detail drawer.
- `GET /api/payroll/batches/{id}/readiness` returns batch-level readiness.
- `GET /api/payroll/batches/{id}/approvals` returns approval history with the
  acting user's display name.
- `GET /api/payroll/batches/{id}/finalization-eligibility?version=...`
  confirms current approval status, batch version, readiness, and maker-checker
  eligibility for a caller who has `payroll.finalize`.
- `POST /api/payroll/batches/{id}/finalize` requires explicit confirmation and
  the reviewed version; the update is conditional on that version.
- `GET /api/payroll/periods/{periodId}/scope-summary?divisionId=...` returns
  employee eligibility counts and the most recent batch for the selected,
  authorized period/division.
- `POST /api/payroll/batches/generate` already requires `payroll.prepare`,
  checks that the period is OPEN, checks division scope in the backend, and
  rejects duplicate period/division/run-type batches. It also checks that the
  division belongs to the selected period's organization.

## Gaps identified before implementation and disposition

1. **Resolved:** No existing endpoint reported total, active-and-payroll-eligible, and
   inactive/excluded employees for a selected period and division. The
   employee-list endpoint requires `employee.view`, and inferring payroll
   eligibility in the browser would not reliably match the generation rules.
   The new read-only endpoint applies division-scoped RLS, verifies the
   division/period organization match, uses the payroll effective date, and
   returns aggregate counts and the latest batch status.
2. **Resolved:** Batch generation previously did not verify that the division
   belongs to the period's organization. It now rejects that mismatch in the
   backend.
3. **Remaining data-model gap:** Payroll period descriptions are not present in the database schema or
   response. The workspace must not fabricate a description.
4. **Resolved:** The division-scope endpoint now also permits payroll workflow
   permissions, while returned hierarchy remains restricted by division-scoped
   RLS.
5. **Current assignment model:** Payroll approvals are permission- and
   division-scope based; the schema does not record an individual pending
   reviewer assignment. `checker_id` and checker name are shown after a
   reviewer acts. The workspace states when no individual assignment is
   recorded instead of inventing one.

The workspace does not generate salary for locked or closed periods. Approval
and finalization remain backend-authoritative; finalization is shown only when
the eligibility endpoint confirms it for the user's permission, batch version,
readiness, and maker-checker state.
