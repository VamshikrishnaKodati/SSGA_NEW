import { useCallback, useMemo, useState } from 'react'
import { divisionApi, employeeApi, organizationApi } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { friendlyError, formatCurrency, formatDate, humanizeStatus } from '../lib/format.js'
import {
  Alert,
  DataTable,
  DefinitionList,
  Drawer,
  Icon,
  LoadingState,
  PageHeader,
  Pagination,
  SearchField,
  SelectField,
  StatusChip,
} from '../components/ui.jsx'
import { usePagedRows } from '../lib/usePagedRows.js'
import { validateEmployeeForm } from '../lib/employeeValidation.js'

const EMPTY_FORM = {
  employeeCode: '',
  hrmsId: '',
  name: '',
  divisionId: '',
  designationId: '',
  gradeId: '',
  contact: '',
  placeOfWork: '',
  dateOfBirth: '',
  dateOfJoining: '',
  employmentType: '',
  status: 'ACTIVE',
  payScale: '',
  department: '',
  circleId: '',
  wardId: '',
  payrollUnitId: '',
  pan: '',
  aadhaar: '',
  pf: '',
  nps: '',
  gpf: '',
  email: '',
  bankName: '',
  branchName: '',
  bankAccountNumber: '',
  ifscCode: '',
}

function toForm(employee) {
  return {
    employeeCode: employee.employeeCode || '',
    hrmsId: employee.hrmsId || '',
    name: employee.name || '',
    divisionId: employee.divisionId ? String(employee.divisionId) : '',
    designationId: employee.designationId ? String(employee.designationId) : '',
    gradeId: employee.gradeId ? String(employee.gradeId) : '',
    contact: employee.contact || '',
    placeOfWork: employee.placeOfWork || '',
    dateOfBirth: employee.dateOfBirth || '',
    dateOfJoining: employee.dateOfJoining || '',
    employmentType: employee.employmentType || '',
    status: employee.status || 'ACTIVE',
    payScale: employee.payScale || '',
    department: employee.department || '',
    circleId: employee.circleId ? String(employee.circleId) : '',
    wardId: employee.wardId ? String(employee.wardId) : '',
    payrollUnitId: employee.payrollUnitId ? String(employee.payrollUnitId) : '',
    pan: '',
    aadhaar: '',
    pf: '',
    nps: '',
    gpf: '',
    email: '',
    bankName: '',
    branchName: '',
    bankAccountNumber: '',
    ifscCode: '',
  }
}

function wingFilterValue(employee) {
  if (employee.wingId !== null && employee.wingId !== undefined && employee.wingId !== '') {
    return String(employee.wingId)
  }

  const name = String(employee.wingName || '').trim()
  return name ? `name:${name.toLowerCase()}` : ''
}

export default function EmployeeMaster({ ability, onSessionExpired }) {
  const [query, setQuery] = useState('')
  const [wingFilter, setWingFilter] = useState('')
  const [divisionFilter, setDivisionFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [sort, setSort] = useState({ key: 'name', direction: 'asc' })

  const [detail, setDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
  const [formMode, setFormMode] = useState(null)
  const [formEmployee, setFormEmployee] = useState(null)

  const canViewDivisions = ability.has('division.view')
  const canViewOrgUnits = ability.hasAny('organization.view', 'wing.view', 'division.view')

  const employees = useLoader(() => employeeApi.list(), [])
  const divisions = useLoader(
    () => divisionApi.myScope(),
    [],
    { enabled: canViewDivisions },
  )
  const payrollUnits = useLoader(
    () => organizationApi.payrollUnits(),
    [],
    { enabled: canViewOrgUnits },
  )
  const divisionName = useCallback(
    (id) => {
      const found = (divisions.data || []).find((division) => division.id === id)
      return found ? found.name : `Division ${id}`
    },
    [divisions.data],
  )

  const tableRowDivision = useCallback(
    (employee) => employee.divisionName || (employee.divisionId ? divisionName(employee.divisionId) : '—'),
    [divisionName],
  )

  const wingOptions = useMemo(() => {
    const wings = new Map()

    ;(employees.data || []).forEach((employee) => {
      const value = wingFilterValue(employee)
      if (!value) return

      wings.set(value, employee.wingName || `Wing ${employee.wingId}`)
    })

    return [...wings.entries()]
      .map(([value, label]) => ({ value, label }))
      .sort((left, right) => left.label.localeCompare(right.label))
  }, [employees.data])

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    const rows = (employees.data || []).filter((employee) => {
      if (wingFilter && wingFilterValue(employee) !== wingFilter) return false
      if (divisionFilter && String(employee.divisionId) !== divisionFilter) return false
      if (statusFilter && employee.status !== statusFilter) return false
      if (!needle) return true
      return [
        employee.employeeCode,
        employee.hrmsId,
        employee.name,
        employee.designationName,
        employee.department,
        employee.placeOfWork,
        employee.wingName,
        employee.divisionName,
      ]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(needle))
    })

    return rows.sort((a, b) => {
      const left = String(a[sort.key] ?? '')
      const right = String(b[sort.key] ?? '')
      const comparison = left.localeCompare(right, undefined, { numeric: true })
      return sort.direction === 'asc' ? comparison : -comparison
    })
  }, [employees.data, query, wingFilter, divisionFilter, statusFilter, sort])

  const { page, setPage, totalPages, paged, total, pageSize } = usePagedRows(filtered, 15)

  function toggleSort(key) {
    setSort((previous) => ({
      key,
      direction: previous.key === key && previous.direction === 'asc' ? 'desc' : 'asc',
    }))
  }

  const openDetail = useCallback(async (employee) => {
    setDetail({ id: employee.id })
    setDetailLoading(true)

    try {
      const full = await employeeApi.getById(employee.id)
      setDetail(full)
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setDetail({ id: employee.id, error: friendlyError(requestError) })
    } finally {
      setDetailLoading(false)
    }
  }, [onSessionExpired])

  function openEdit(employee) {
    setFormEmployee(employee)
    setFormMode('edit')
  }

  const divisionOptions = (divisions.data || []).map((division) => ({
    value: String(division.id),
    label: `${division.name}${division.wingName ? ` · ${division.wingName}` : ''}`,
  }))

  const visibleDivisionOptions = divisionOptions.length > 0
    ? divisionOptions
    : [...new Map((employees.data || [])
      .filter((employee) => employee.divisionId)
      .map((employee) => [
        String(employee.divisionId),
        {
          value: String(employee.divisionId),
          label: employee.divisionName || `Division ${employee.divisionId}`,
        },
      ])).values()]

  const payrollUnitOptions = (payrollUnits.data || [])
    .filter((unit) => unit.status === 'ACTIVE')
    .map((unit) => ({
      value: String(unit.id),
      label: `${unit.name} · ${unit.parentGroupName}`,
    }))

  return (
    <>
      <PageHeader
        title="Employee master"
        subtitle="Employees visible under your organizational scope, with personal, posting, bank and statutory details."
        breadcrumbs={['Records', 'Employee master']}
        actions={(
          <>
            <button type="button" className="btn ghost" onClick={employees.reload}>
              <Icon name="refresh" size={14} /> Refresh
            </button>
            {ability.has('employee.create') ? (
              <button type="button" className="btn" onClick={() => { setFormEmployee(null); setFormMode('create') }}>
                <Icon name="plus" size={14} /> New employee
              </button>
            ) : (
              <button type="button" className="btn" disabled title="Requires employee.create permission">
                <Icon name="plus" size={14} /> New employee
              </button>
            )}
          </>
        )}
      />

      <section className="panel employee-master-panel">
        <div className="toolbar">
          <SearchField value={query} onChange={setQuery} placeholder="Search HRMS ID, employee name, code or designation" />
          <SelectField
            label="Wing"
            value={wingFilter}
            onChange={setWingFilter}
            options={wingOptions}
            placeholder="All wings"
            disabled={wingOptions.length === 0}
          />
          <SelectField
            label="Division"
            value={divisionFilter}
            onChange={setDivisionFilter}
            options={visibleDivisionOptions}
            placeholder="All divisions"
            disabled={visibleDivisionOptions.length === 0}
          />
          <SelectField
            label="Status"
            value={statusFilter}
            onChange={setStatusFilter}
            options={[
              { value: 'ACTIVE', label: 'Active' },
              { value: 'INACTIVE', label: 'Inactive' },
            ]}
            placeholder="All statuses"
          />
        </div>

        {employees.loading ? (
          <LoadingState rows={6} />
        ) : employees.error ? (
          <div className="panel-body"><Alert kind="error">{employees.error}</Alert></div>
        ) : (
          <>
            <div className="employee-master-content">
              <div className="employee-master-list">
                <DataTable
                  columns={[
                    { key: 'hrmsId', label: 'HRMS ID', nowrap: true, render: (row) => row.hrmsId || row.employeeCode || '—' },
                    { key: 'name', label: 'Employee name', sortable: true, nowrap: true, render: (row) => <span className="cell-primary">{row.name}</span> },
                    { key: 'designationName', label: 'Designation', sortable: true, render: (row) => row.designationName || (row.designationId ? `#${row.designationId}` : '—') },
                    { key: 'wingName', label: 'Wing', render: (row) => row.wingName || '—' },
                    { key: 'divisionName', label: 'Division', render: (row) => tableRowDivision(row) },
                    { key: 'status', label: 'Status', render: (row) => <StatusChip value={row.status} /> },
                  ]}
                  rows={paged}
                  rowKey={(row) => row.id}
                  onRowClick={openDetail}
                  selectedKey={detail?.id}
                  sort={sort}
                  onSort={toggleSort}
                  emptyTitle={employees.data?.length ? 'No matching employees' : 'No employees'}
                  emptyDescription={employees.data?.length
                    ? 'Adjust the search or filters to see employees.'
                    : 'No employee records are visible in your scope.'}
                />
                <Pagination page={page} totalPages={totalPages} total={total} pageSize={pageSize} onChange={setPage} />
              </div>
              <EmployeeSummary
                detail={detail}
                loading={detailLoading}
                divisionName={tableRowDivision}
                onViewProfile={() => setProfileOpen(true)}
              />
            </div>
          </>
        )}
      </section>

      <DetailDrawer
        open={profileOpen}
        detail={detail}
        loading={detailLoading}
        divisionName={tableRowDivision}
        ability={ability}
        onEdit={openEdit}
        onClose={() => setProfileOpen(false)}
      />

      {formMode && (
        <EmployeeFormDrawer
          mode={formMode}
          employee={formEmployee}
          divisions={divisions.data || []}
          divisionsReady={canViewDivisions}
          payrollUnits={payrollUnitOptions}
          payrollUnitsReady={canViewOrgUnits}
          onClose={() => { setFormMode(null); setFormEmployee(null) }}
          onSaved={(saved) => {
            setFormMode(null)
            setFormEmployee(null)
            employees.reload()
            if (formMode === 'edit' && saved) setDetail(saved)
          }}
          onSessionExpired={onSessionExpired}
        />
      )}
    </>
  )
}

/* ------------------------------------------------------------ detail drawer -- */

function Section({ title, items }) {
  return (
    <div>
      <h4 className="section-title">{title}</h4>
      <DefinitionList items={items} />
    </div>
  )
}

function EmployeeSummary({ detail, loading, divisionName, onViewProfile }) {
  return (
    <aside className="panel employee-summary" aria-label="Selected employee details">
      <div className="panel-head">
        <div>
          <h3>Employee details</h3>
          <p>Quick view of the selected employee</p>
        </div>
      </div>
      {loading ? (
        <div className="panel-body"><LoadingState rows={4} /></div>
      ) : detail?.error ? (
        <div className="panel-body"><Alert kind="error">{detail.error}</Alert></div>
      ) : detail ? (
        <div className="panel-body stack">
          <div className="employee-summary-identity">
            <div className="employee-summary-avatar" aria-hidden="true">
              {String(detail.name || '?').trim().charAt(0).toUpperCase()}
            </div>
            <div className="employee-summary-name">
              <strong>{detail.name || 'Employee record'}</strong>
              <span>{detail.employeeCode || detail.hrmsId || 'Employee'}</span>
            </div>
            <StatusChip value={detail.status} />
          </div>
          <DefinitionList
            items={[
              { term: 'HRMS ID', value: detail.hrmsId || '—' },
              { term: 'Designation', value: detail.designationName || (detail.designationId ? `#${detail.designationId}` : '—') },
              { term: 'Wing', value: detail.wingName || '—' },
              { term: 'Division', value: divisionName(detail) },
              { term: 'Date of joining', value: formatDate(detail.dateOfJoining) },
              { term: 'Contact number', value: detail.contact || '—' },
            ]}
          />
          <button type="button" className="btn block" onClick={onViewProfile}>
            View full profile <Icon name="chevronRight" size={14} />
          </button>
        </div>
      ) : (
        <div className="panel-body">
          <p className="inline-meta">Select an employee from the list to see their details.</p>
        </div>
      )}
    </aside>
  )
}

function DetailDrawer({ open, detail, loading, divisionName, ability, onEdit, onClose }) {
  if (!open || !detail) return null

  const canEdit = ability.has('employee.update')

  const identityValue = (type) => {
    const found = (detail.identities || []).find((entry) => entry.identityType === type)
    return found ? found.identityValue : '—'
  }

  const primaryBank = (detail.bankAccounts || []).find((account) => account.primary)
    || (detail.bankAccounts || [])[0]

  return (
    <Drawer
      open
      title={detail.name || 'Employee record'}
      subtitle={detail.employeeCode ? `${detail.employeeCode} · ${detail.hrmsId || 'no HRMS id'}` : undefined}
      onClose={onClose}
      footer={(
        <>
          <button
            type="button"
            className="btn"
            onClick={() => onEdit(detail)}
            disabled={!canEdit || loading || !!detail.error}
            title={canEdit ? 'Edit employee details' : 'Requires employee.update permission'}
          >
            Edit details
          </button>
          <button type="button" className="btn subtle" onClick={onClose}>Close</button>
        </>
      )}
    >
      {loading ? (
        <LoadingState rows={5} />
      ) : detail.error ? (
        <Alert kind="error">{detail.error}</Alert>
      ) : (
        <div className="stack">
          <Section
            title="Employee information"
            items={[
              { term: 'Employee ID', value: detail.id },
              { term: 'Employee code', value: detail.employeeCode },
              { term: 'HRMS id', value: detail.hrmsId || '—' },
              { term: 'Name', value: detail.name },
              { term: 'Status', value: humanizeStatus(detail.status) },
            ]}
          />

          <Section
            title="Personal information"
            items={[
              { term: 'Date of birth', value: formatDate(detail.dateOfBirth) },
              { term: 'Contact number', value: detail.contact || '—' },
              { term: 'Email', value: identityValue('EMAIL') },
            ]}
          />

          <Section
            title="Employment information"
            items={[
              { term: 'Date of joining', value: formatDate(detail.dateOfJoining) },
              { term: 'Employment type', value: detail.employmentType || '—' },
              { term: 'Designation', value: detail.designationName || (detail.designationId ? `#${detail.designationId}` : '—') },
              { term: 'Grade', value: detail.gradeName || (detail.gradeId ? `#${detail.gradeId}` : '—') },
              { term: 'Pay scale', value: detail.payScale || '—' },
              { term: 'Department', value: detail.department || '—' },
              { term: 'Place of work', value: detail.placeOfWork || '—' },
            ]}
          />

          <Section
            title="Organizational information"
            items={[
              { term: 'Organization', value: detail.organizationName || '—' },
              { term: 'Wing', value: detail.wingName || '—' },
              { term: 'Circle', value: detail.circleName || (detail.circleId ? `#${detail.circleId}` : '—') },
              { term: 'Division', value: divisionName(detail) },
              { term: 'Ward', value: detail.wardName || (detail.wardId ? `#${detail.wardId}` : '—') },
              { term: 'Payroll unit', value: detail.payrollUnitName || (detail.payrollUnitId ? `#${detail.payrollUnitId}` : '—') },
            ]}
          />

          <Section
            title="Bank account details"
            items={primaryBank ? [
              { term: 'Bank', value: primaryBank.bankName || '—' },
              { term: 'Branch', value: primaryBank.branchName || '—' },
              { term: 'Account number', value: primaryBank.accountNumber || '—' },
              { term: 'IFSC', value: primaryBank.ifscCode || '—' },
              { term: 'Status', value: humanizeStatus(primaryBank.status) },
            ] : [
              { term: 'Bank account', value: 'Not recorded' },
            ]}
          />

          <Section
            title="Statutory / identity information"
            items={[
              { term: 'PAN', value: identityValue('PAN') },
              { term: 'Aadhaar', value: identityValue('AADHAAR') },
              { term: 'PF', value: identityValue('PF') },
              { term: 'NPS', value: identityValue('NPS') },
              { term: 'GPF', value: identityValue('GPF') },
            ]}
          />

          {!canEdit && (
            <p className="inline-meta">Editing requires the employee.update permission, which your account does not hold.</p>
          )}

          <SalarySection employeeId={detail.id} ability={ability} />

          <p className="inline-meta">
            Statutory identifiers and bank account numbers are masked unless your account holds
            employee.update or salary.manage.
          </p>
        </div>
      )}
    </Drawer>
  )
}

/* ----------------------------------------------------------- salary section -- */

function SalarySection({ employeeId, ability }) {
  const canViewSalary = ability.has('salary.view')
  const canEditSalary = ability.has('salary.manage') || ability.has('salary.prepare')
  const [editing, setEditing] = useState(null)
  const [savingId, setSavingId] = useState(null)
  const [saveError, setSaveError] = useState('')

  const salary = useLoader(
    () => employeeApi.salary(employeeId),
    [employeeId],
    { enabled: canViewSalary },
  )

  async function save(component) {
    const value = String(editing?.amount ?? '').trim()

    if (value === '' || Number.isNaN(Number(value)) || Number(value) < 0) {
      setSaveError('Enter an amount of zero or greater')
      return
    }

    setSavingId(component.componentId)
    setSaveError('')

    try {
      await employeeApi.setSalaryComponent(employeeId, component.componentId, {
        amount: Number(value),
      })
      setEditing(null)
      salary.reload()
    } catch (requestError) {
      setSaveError(friendlyError(requestError))
    } finally {
      setSavingId(null)
    }
  }

  if (!canViewSalary) {
    return <Alert kind="info">Salary structure requires the salary.view permission.</Alert>
  }

  if (salary.loading) return <LoadingState rows={4} />
  if (salary.error) return <Alert kind="error">{salary.error}</Alert>

  const data = salary.data || {}
  const components = data.components || []

  const earnings = components.filter((component) => component.category === 'EARNING')
  const deductions = components.filter((component) => component.category === 'DEDUCTION')

  const columns = [
    { key: 'componentCode', label: 'Code', nowrap: true, render: (row) => <span className="cell-primary">{row.componentCode}</span> },
    { key: 'componentName', label: 'Component' },
    {
      key: 'amount',
      label: 'Amount',
      align: 'right',
      nowrap: true,
      render: (row) => (row.amount === null || row.amount === undefined
        ? <span className="muted">Not configured</span>
        : formatCurrency(row.amount)),
    },
  ]

  if (canEditSalary) {
    columns.push({
      key: 'actions',
      label: '',
      nowrap: true,
      render: (row) => (editing?.componentId === row.componentId ? (
        <span className="row-actions">
          <input
            type="number"
            min="0"
            step="0.01"
            autoFocus
            value={editing.amount}
            onChange={(event) => setEditing({ componentId: row.componentId, amount: event.target.value })}
            style={{ width: '110px' }}
          />
          <button
            type="button"
            className="btn"
            onClick={() => save(row)}
            disabled={savingId === row.componentId}
          >
            {savingId === row.componentId ? 'Saving' : 'Save'}
          </button>
          <button type="button" className="btn subtle" onClick={() => { setEditing(null); setSaveError('') }}>
            Cancel
          </button>
        </span>
      ) : (
        <button
          type="button"
          className="btn subtle"
          onClick={() => { setEditing({ componentId: row.componentId, amount: row.amount ?? '' }); setSaveError('') }}
        >
          Set
        </button>
      )),
    })
  }

  return (
    <div className="stack">
      <h4 className="section-title">Salary structure</h4>

      {data.note && <Alert kind="warning">{data.note}</Alert>}
      {saveError && <Alert kind="error">{saveError}</Alert>}

      <DefinitionList
        items={[
          { term: 'Basic pay', value: data.basicPay === null || data.basicPay === undefined ? 'Not configured' : formatCurrency(data.basicPay) },
          { term: 'Gross earnings', value: formatCurrency(data.gross) },
          { term: 'Total deductions', value: formatCurrency(data.totalDeductions) },
          { term: 'Net salary', value: <strong>{formatCurrency(data.net)}</strong> },
          { term: 'Configured components', value: `${data.configuredCount ?? earnings.length + deductions.length} of ${data.masterCount ?? components.length}` },
          { term: 'Effective as of', value: formatDate(data.asOf) },
        ]}
      />

      {data.latestRun && (
        <DefinitionList
          items={[
            { term: 'Latest run period', value: data.latestRun.period },
            { term: 'Latest run status', value: humanizeStatus(data.latestRun.status) },
            { term: 'Latest run gross', value: formatCurrency(data.latestRun.gross) },
            { term: 'Latest run deductions', value: formatCurrency(data.latestRun.deductions) },
            { term: 'Latest run net', value: formatCurrency(data.latestRun.net) },
          ]}
        />
      )}

      <div>
        <h4 className="section-title">Earnings</h4>
        <DataTable
          columns={columns}
          rows={earnings}
          rowKey={(row) => `e-${row.componentCode}`}
          emptyTitle="No earning components"
        />
      </div>

      <div>
        <h4 className="section-title">Deductions</h4>
        <DataTable
          columns={columns}
          rows={deductions}
          rowKey={(row) => `d-${row.componentCode}`}
          emptyTitle="No deduction components"
        />
      </div>

      <p className="inline-meta">
        Amounts are the employee&apos;s configured values (employee_salary_values) effective today.
        Components with no configured value are shown as not configured; the payroll calculation
        treats them as zero.
        {canEditSalary
          ? ' Use Set to enter or update an amount; it takes effect from today.'
          : ' Editing amounts requires salary.manage or salary.prepare.'}
      </p>
    </div>
  )
}

/* ------------------------------------------------------------- form drawer -- */

function trimmedOrNull(value) {
  const trimmed = (value || '').trim()
  return trimmed === '' ? null : trimmed
}

function EmployeeFormDrawer({ mode, employee, divisions, divisionsReady, payrollUnits, payrollUnitsReady, onClose, onSaved, onSessionExpired }) {
  const isEdit = mode === 'edit'
  const [form, setForm] = useState(() => (isEdit && employee ? toForm(employee) : EMPTY_FORM))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState(null)

  function update(key, value) {
    setForm((previous) => ({ ...previous, [key]: value }))
  }

  async function submit(event) {
    event.preventDefault()
    setError('')

    const validationError = validateEmployeeForm(form, isEdit)
    if (validationError) return setError(validationError)

    const payload = {
      name: form.name.trim(),
      hrmsId: form.hrmsId.trim() || null,
      designationId: form.designationId ? Number(form.designationId) : null,
      gradeId: form.gradeId ? Number(form.gradeId) : null,
      contact: form.contact.trim() || null,
      placeOfWork: form.placeOfWork.trim() || null,
      dateOfBirth: form.dateOfBirth || null,
      dateOfJoining: form.dateOfJoining || null,
      employmentType: form.employmentType.trim() || null,
      status: form.status,
      payScale: form.payScale.trim() || null,
      department: form.department.trim() || null,
      circleId: form.circleId ? Number(form.circleId) : null,
      wardId: form.wardId ? Number(form.wardId) : null,
      payrollUnitId: form.payrollUnitId ? Number(form.payrollUnitId) : (isEdit ? 0 : null),
      pan: trimmedOrNull(form.pan),
      aadhaar: trimmedOrNull(form.aadhaar),
      pf: trimmedOrNull(form.pf),
      nps: trimmedOrNull(form.nps),
      gpf: trimmedOrNull(form.gpf),
      email: trimmedOrNull(form.email),
      bankName: trimmedOrNull(form.bankName),
      branchName: trimmedOrNull(form.branchName),
      bankAccountNumber: trimmedOrNull(form.bankAccountNumber),
      ifscCode: trimmedOrNull(form.ifscCode),
    }

    if (!isEdit) {
      payload.employeeCode = form.employeeCode.trim()
      payload.divisionId = Number(form.divisionId)
    }

    setBusy(true)

    try {
      const saved = isEdit
        ? await employeeApi.update(employee.id, payload)
        : await employeeApi.create(payload)
      setResult(saved)
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setError(friendlyError(requestError))
    } finally {
      setBusy(false)
    }
  }

  const field = (label, key, props = {}) => (
    <label className="field">
      <span>{label}</span>
      <input value={form[key]} onChange={(event) => update(key, event.target.value)} disabled={busy} {...props} />
    </label>
  )

  return (
    <Drawer
      open
      title={isEdit ? 'Edit employee' : 'Create employee'}
      subtitle={isEdit ? `PATCH /api/employees/${employee?.id}` : 'POST /api/employees'}
      onClose={onClose}
      footer={result ? (
        <button type="button" className="btn" onClick={() => onSaved(result)}>Done</button>
      ) : (
        <>
          <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" form="employee-form" className="btn" disabled={busy}>
            {busy ? 'Saving…' : isEdit ? 'Save changes' : 'Create employee'}
          </button>
        </>
      )}
    >
      {result ? (
        <div className="stack">
          <Alert kind="success" title={isEdit ? 'Employee updated' : 'Employee created'}>
            {result.name} ({result.employeeCode}) was saved.
          </Alert>
          <DefinitionList
            items={[
              { term: 'Employee ID', value: result.id },
              { term: 'HRMS id', value: result.hrmsId || '— (temporary/none)' },
              { term: 'Status', value: humanizeStatus(result.status) },
            ]}
          />
        </div>
      ) : (
        <form id="employee-form" className="stack" onSubmit={submit}>
          {!isEdit && !divisionsReady && (
            <Alert kind="warning">
              Division list requires division.view. Creation is limited until that grant is available.
            </Alert>
          )}

          {error && <Alert kind="error">{error}</Alert>}

          <h4 className="section-title">Employee information</h4>
          <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <label className="field">
              <span>Employee code *</span>
              <input value={form.employeeCode} onChange={(event) => update('employeeCode', event.target.value)} disabled={busy || isEdit} />
            </label>
            <label className="field">
              <span>HRMS id</span>
              <input value={form.hrmsId} onChange={(event) => update('hrmsId', event.target.value)} disabled={busy} placeholder="Optional — auto if blank" />
            </label>
            <label className="field" style={{ gridColumn: '1 / -1' }}>
              <span>Full name *</span>
              <input value={form.name} onChange={(event) => update('name', event.target.value)} disabled={busy} />
            </label>
            <label className="field">
              <span>Division</span>
              {isEdit ? (
                <input value={employee?.divisionName || (employee?.divisionId ? `Division ${employee.divisionId}` : '—')} disabled />
              ) : (
                <select value={form.divisionId} onChange={(event) => update('divisionId', event.target.value)} disabled={busy || !divisionsReady}>
                  <option value="">Select division</option>
                  {divisions.map((division) => (
                    <option key={division.id} value={division.id}>{division.name}</option>
                  ))}
                </select>
              )}
            </label>
            <label className="field">
              <span>Status</span>
              <select value={form.status} onChange={(event) => update('status', event.target.value)} disabled={busy}>
                <option value="ACTIVE">Active</option>
                <option value="INACTIVE">Inactive</option>
              </select>
            </label>
          </div>

          <h4 className="section-title">Employment information</h4>
          <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <label className="field">
              <span>Date of joining</span>
              <input type="date" value={form.dateOfJoining} onChange={(event) => update('dateOfJoining', event.target.value)} disabled={busy} />
            </label>
            {field('Employment type', 'employmentType', { placeholder: 'e.g. PERMANENT' })}
            {field('Department', 'department')}
            {field('Place of work', 'placeOfWork')}
            {field('Designation id', 'designationId', { type: 'number', min: '1', placeholder: 'Optional' })}
            {field('Grade id', 'gradeId', { type: 'number', min: '1', placeholder: 'Optional' })}
            <label className="field" style={{ gridColumn: '1 / -1' }}>
              <span>Pay scale</span>
              <input value={form.payScale} onChange={(event) => update('payScale', event.target.value)} disabled={busy} />
            </label>
          </div>

          <h4 className="section-title">Organizational detail</h4>
          <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            {field('Circle id', 'circleId', { type: 'number', min: '0', placeholder: 'Optional' })}
            {field('Ward id', 'wardId', { type: 'number', min: '0', placeholder: 'Optional' })}
            <label className="field" style={{ gridColumn: '1 / -1' }}>
              <span>Payroll unit</span>
              <select
                value={form.payrollUnitId}
                onChange={(event) => update('payrollUnitId', event.target.value)}
                disabled={busy || !payrollUnitsReady}
              >
                <option value="">{payrollUnitsReady ? 'Not assigned' : 'Unavailable (no organizational read access)'}</option>
                {payrollUnits.map((unit) => (
                  <option key={unit.value} value={unit.value}>{unit.label}</option>
                ))}
              </select>
            </label>
          </div>

          <h4 className="section-title">Personal &amp; contact</h4>
          <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <label className="field">
              <span>Date of birth</span>
              <input type="date" value={form.dateOfBirth} onChange={(event) => update('dateOfBirth', event.target.value)} disabled={busy} />
            </label>
            {field('Contact number', 'contact')}
            {field('Email', 'email', { type: 'email', placeholder: 'name@example.com' })}
          </div>

          <h4 className="section-title">Bank account</h4>
          <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            {field('Bank name', 'bankName')}
            {field('Branch', 'branchName')}
            {field('Account number', 'bankAccountNumber', { inputMode: 'numeric' })}
            {field('IFSC', 'ifscCode', { placeholder: 'SBIN0001234' })}
          </div>

          <h4 className="section-title">Statutory / identity</h4>
          <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            {field('PAN', 'pan', { placeholder: 'ABCDE1234F' })}
            {field('Aadhaar', 'aadhaar', { inputMode: 'numeric' })}
            {field('PF', 'pf')}
            {field('NPS', 'nps')}
            {field('GPF', 'gpf')}
          </div>

          <p className="field-hint">
            Circle, ward, designation and grade reference master tables that have no lookup endpoint;
            ids are entered directly if known. {isEdit
              ? 'Existing statutory and bank values are masked and only change when you type a new value.'
              : 'HRMS id is generated by the server when left blank.'}
          </p>
        </form>
      )}
    </Drawer>
  )
}
