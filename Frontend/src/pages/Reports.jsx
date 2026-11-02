import { useMemo, useState } from 'react'
import { employeeApi, periodApi, reportApi, saveBlob } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { formatAmount, formatCompactCurrency, formatNumber, friendlyError } from '../lib/format.js'
import {
  Alert,
  DataTable,
  Icon,
  LoadingState,
  PageHeader,
  SelectField,
  StatusChip,
  Unavailable,
} from '../components/ui.jsx'

const TABS = [
  { id: 'division', label: 'Division-wise' },
  { id: 'wing', label: 'Wing-wise' },
  { id: 'organization', label: 'Organization-wide' },
  { id: 'employee', label: 'Employee-wise' },
  { id: 'salary', label: 'Salary history' },
]

const STATUS_OPTIONS = [
  { value: 'DRAFT', label: 'Draft' },
  { value: 'SUBMITTED', label: 'Submitted' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'FINALIZED', label: 'Finalized' },
]

export default function Reports({ ability, onSessionExpired }) {
  const [tab, setTab] = useState('division')
  const [periodFilter, setPeriodFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [error, setError] = useState('')

  const canFilterPeriod = ability.hasAny('payroll.view', 'payroll.prepare')
  const canExport = ability.has('export.download')

  const periods = useLoader(() => periodApi.list(), [], { enabled: canFilterPeriod })

  const periodOptions = (periods.data || []).map((period) => ({ value: String(period.id), label: period.label }))

  return (
    <>
      <PageHeader
        title="Reports"
        subtitle="Payroll and salary reporting across your organizational scope."
        breadcrumbs={['Insights', 'Reports']}
      />

      <section className="panel">
        <div className="tabs">
          {TABS.map((entry) => (
            <button key={entry.id} type="button" className={`tab${tab === entry.id ? ' active' : ''}`} onClick={() => setTab(entry.id)}>
              {entry.label}
            </button>
          ))}
        </div>

        {(tab === 'division' || tab === 'wing' || tab === 'organization') && (
          <div className="toolbar">
            <SelectField
              label="Period"
              value={periodFilter}
              onChange={setPeriodFilter}
              options={periodOptions}
              placeholder="All periods"
              disabled={!canFilterPeriod}
            />
            {!canFilterPeriod && (
              <span className="inline-meta"><Icon name="lock" size={12} /> Period filter requires payroll.view</span>
            )}
            {tab === 'organization' && (
              <SelectField label="Batch status" value={statusFilter} onChange={setStatusFilter} options={STATUS_OPTIONS} placeholder="All statuses" />
            )}
          </div>
        )}

        <div className="panel-body">
          {error && <Alert kind="error">{error}</Alert>}

          {tab === 'division' && (
            <DivisionReport periodFilter={periodFilter} canExport={canExport} onError={setError} />
          )}
          {tab === 'wing' && (
            <WingReport periodFilter={periodFilter} canExport={canExport} onError={setError} />
          )}
          {tab === 'organization' && (
            <OrganizationReport periodFilter={periodFilter} statusFilter={statusFilter} canExport={canExport} onError={setError} />
          )}
          {tab === 'employee' && (
            <EmployeeReport ability={ability} onSessionExpired={onSessionExpired} onError={setError} />
          )}
          {tab === 'salary' && <SalaryHistoryReport />}
        </div>
      </section>
    </>
  )
}

function TotalsFooter({ rows }) {
  const totals = rows.reduce(
    (accumulator, row) => ({
      employees: accumulator.employees + Number(row.employeeCount || 0),
      gross: accumulator.gross + Number(row.gross || 0),
      deductions: accumulator.deductions + Number(row.deductions || 0),
      net: accumulator.net + Number(row.net || 0),
    }),
    { employees: 0, gross: 0, deductions: 0, net: 0 },
  )

  return (
    <tr>
      <td colSpan={2}><strong>Total</strong></td>
      <td className="num">{formatNumber(totals.employees)}</td>
      <td className="num">{formatAmount(totals.gross)}</td>
      <td className="num">{formatAmount(totals.deductions)}</td>
      <td className="num">{formatAmount(totals.net)}</td>
    </tr>
  )
}

function DivisionReport({ periodFilter, canExport, onError }) {
  const summary = useLoader(() => reportApi.divisionSummary(periodFilter || undefined), [periodFilter])
  const [exporting, setExporting] = useState(false)

  async function exportCsv() {
    onError('')
    setExporting(true)

    try {
      const blob = await reportApi.downloadDivisionSummary(periodFilter || undefined)
      saveBlob(blob, 'division-summary.csv')
    } catch (requestError) {
      onError(friendlyError(requestError))
    } finally {
      setExporting(false)
    }
  }

  if (summary.loading) return <LoadingState rows={5} />
  if (summary.error) return <Alert kind="error">{summary.error}</Alert>

  return (
    <>
      <div className="panel-head" style={{ border: 'none', paddingTop: 0 }}>
        <div>
          <h3>Division-wise payroll</h3>
          <p>Finalized payroll totals per division in your scope</p>
        </div>
        {canExport && (
          <button type="button" className="btn ghost sm" onClick={exportCsv} disabled={exporting}>
            <Icon name="down" size={13} /> {exporting ? 'Preparing…' : 'Export CSV'}
          </button>
        )}
      </div>

      <DataTable
        columns={[
          { key: 'wingName', label: 'Wing', render: (row) => row.wingName || '—' },
          { key: 'divisionName', label: 'Division', render: (row) => <span className="cell-primary">{row.divisionName}</span> },
          { key: 'employeeCount', label: 'Employees', align: 'right', render: (row) => formatNumber(row.employeeCount) },
          { key: 'gross', label: 'Gross', align: 'right', render: (row) => formatAmount(row.gross) },
          { key: 'deductions', label: 'Deductions', align: 'right', render: (row) => formatAmount(row.deductions) },
          { key: 'net', label: 'Net', align: 'right', render: (row) => formatAmount(row.net) },
        ]}
        rows={summary.data || []}
        rowKey={(row) => row.divisionId}
        footer={summary.data?.length ? <TotalsFooter rows={summary.data} /> : null}
        emptyTitle="No finalized payroll"
        emptyDescription="No finalized payroll is available for the selected scope and period."
      />
    </>
  )
}

function WingReport({ periodFilter, canExport, onError }) {
  const summary = useLoader(() => reportApi.divisionSummary(periodFilter || undefined), [periodFilter])
  const [exporting, setExporting] = useState(false)

  const wings = useMemo(() => {
    const map = new Map()
    ;(summary.data || []).forEach((row) => {
      const key = row.wingName || 'Unassigned'
      if (!map.has(key)) {
        map.set(key, { wing: key, employeeCount: 0, gross: 0, deductions: 0, net: 0, divisionCount: 0 })
      }
      const entry = map.get(key)
      entry.employeeCount += Number(row.employeeCount || 0)
      entry.gross += Number(row.gross || 0)
      entry.deductions += Number(row.deductions || 0)
      entry.net += Number(row.net || 0)
      entry.divisionCount += 1
    })
    return [...map.values()].sort((a, b) => b.net - a.net)
  }, [summary.data])

  async function exportCsv() {
    onError('')
    setExporting(true)

    try {
      const blob = await reportApi.downloadDivisionSummary(periodFilter || undefined)
      saveBlob(blob, 'division-summary.csv')
    } catch (requestError) {
      onError(friendlyError(requestError))
    } finally {
      setExporting(false)
    }
  }

  if (summary.loading) return <LoadingState rows={4} />
  if (summary.error) return <Alert kind="error">{summary.error}</Alert>

  return (
    <>
      <div className="panel-head" style={{ border: 'none', paddingTop: 0 }}>
        <div>
          <h3>Wing-wise payroll</h3>
          <p>Division figures aggregated to their wing (computed in the browser)</p>
        </div>
        {canExport && (
          <button type="button" className="btn ghost sm" onClick={exportCsv} disabled={exporting}>
            <Icon name="down" size={13} /> Export CSV (divisions)
          </button>
        )}
      </div>

      <DataTable
        columns={[
          { key: 'wing', label: 'Wing', render: (row) => <span className="cell-primary">{row.wing}</span> },
          { key: 'divisionCount', label: 'Divisions', align: 'right', render: (row) => formatNumber(row.divisionCount) },
          { key: 'employeeCount', label: 'Employees', align: 'right', render: (row) => formatNumber(row.employeeCount) },
          { key: 'gross', label: 'Gross', align: 'right', render: (row) => formatAmount(row.gross) },
          { key: 'deductions', label: 'Deductions', align: 'right', render: (row) => formatAmount(row.deductions) },
          { key: 'net', label: 'Net', align: 'right', render: (row) => formatAmount(row.net) },
        ]}
        rows={wings}
        rowKey={(row) => row.wing}
        emptyTitle="No wing totals"
        emptyDescription="No finalized payroll is available to aggregate by wing."
      />
    </>
  )
}

function OrganizationReport({ periodFilter, statusFilter, canExport, onError }) {
  const summary = useLoader(
    () => reportApi.payrollSummary(periodFilter || undefined, statusFilter || undefined),
    [periodFilter, statusFilter],
  )
  const [exporting, setExporting] = useState(false)

  async function exportCsv() {
    onError('')
    setExporting(true)

    try {
      const blob = await reportApi.downloadPayrollSummary(periodFilter || undefined, statusFilter || undefined)
      saveBlob(blob, 'payroll-summary.csv')
    } catch (requestError) {
      onError(friendlyError(requestError))
    } finally {
      setExporting(false)
    }
  }

  if (summary.loading) return <LoadingState rows={5} />
  if (summary.error) return <Alert kind="error">{summary.error}</Alert>

  const data = summary.data
  const divisions = data?.divisions || []
  const totals = data?.totals

  return (
    <>
      <div className="panel-head" style={{ border: 'none', paddingTop: 0 }}>
        <div>
          <h3>Organization-wide payroll summary</h3>
          <p>{data?.periodLabel ? `Period ${data.periodLabel}` : 'All periods'}{data?.statusFilter ? ` · status ${data.statusFilter}` : ''}</p>
        </div>
        {canExport && (
          <button type="button" className="btn ghost sm" onClick={exportCsv} disabled={exporting}>
            <Icon name="down" size={13} /> Export CSV
          </button>
        )}
      </div>

      {totals && (
        <div className="kpi-strip" style={{ marginBottom: 14 }}>
          <div className="kpi">
            <div className="kpi-label">Employees</div>
            <div className="kpi-value">{formatNumber(totals.employeeCount)}</div>
          </div>
          <div className="kpi">
            <div className="kpi-label">Gross</div>
            <div className="kpi-value">{formatCompactCurrency(totals.gross)}</div>
          </div>
          <div className="kpi">
            <div className="kpi-label">Deductions</div>
            <div className="kpi-value">{formatCompactCurrency(totals.deductions)}</div>
          </div>
          <div className="kpi">
            <div className="kpi-label">Net</div>
            <div className="kpi-value">{formatCompactCurrency(totals.net)}</div>
          </div>
        </div>
      )}

      <DataTable
        columns={[
          { key: 'wingName', label: 'Wing', render: (row) => row.wingName || '—' },
          { key: 'divisionName', label: 'Division', render: (row) => <span className="cell-primary">{row.divisionName}</span> },
          { key: 'employeeCount', label: 'Employees', align: 'right', render: (row) => formatNumber(row.employeeCount) },
          { key: 'gross', label: 'Gross', align: 'right', render: (row) => formatAmount(row.gross) },
          { key: 'deductions', label: 'Deductions', align: 'right', render: (row) => formatAmount(row.deductions) },
          { key: 'net', label: 'Net', align: 'right', render: (row) => formatAmount(row.net) },
        ]}
        rows={divisions}
        rowKey={(row) => row.divisionId}
        emptyTitle="No summary available"
        emptyDescription="No payroll matches the selected period and status in your scope."
      />
    </>
  )
}

function EmployeeReport({ ability, onSessionExpired, onError }) {
  const canListEmployees = ability.has('employee.view')
  const [employeeId, setEmployeeId] = useState('')
  const [history, setHistory] = useState(null)
  const [loading, setLoading] = useState(false)

  const employees = useLoader(() => employeeApi.list(), [], { enabled: canListEmployees })

  async function load(event) {
    event.preventDefault()
    onError('')
    setHistory(null)

    if (!employeeId) {
      onError('Select or enter an employee to view their salary report.')
      return
    }

    setLoading(true)

    try {
      setHistory(await reportApi.employeeHistory(Number(employeeId)))
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      onError(friendlyError(requestError))
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <div className="panel-head" style={{ border: 'none', paddingTop: 0 }}>
        <div>
          <h3>Employee-wise salary</h3>
          <p>Per-period gross, deductions and net for a single employee</p>
        </div>
      </div>

      <form className="inline-form" onSubmit={load}>
        <label className="field grow">
          <span>Employee</span>
          {canListEmployees ? (
            <select value={employeeId} onChange={(event) => setEmployeeId(event.target.value)}>
              <option value="">Select employee</option>
              {(employees.data || []).map((employee) => (
                <option key={employee.id} value={employee.id}>{employee.employeeCode} · {employee.name}</option>
              ))}
            </select>
          ) : (
            <input type="number" min="1" value={employeeId} onChange={(event) => setEmployeeId(event.target.value)} placeholder="Employee id" />
          )}
        </label>
        {!canListEmployees && <span className="inline-meta">Employee lookup requires employee.view; enter an id directly.</span>}
        <button type="submit" className="btn" disabled={loading || !employeeId}>
          {loading ? 'Loading…' : 'View report'}
        </button>
      </form>

      {loading && <LoadingState rows={4} />}

      {history && (
        <div style={{ marginTop: 14 }}>
          <h4 style={{ fontSize: 13, margin: '0 0 8px' }}>{history.employeeCode} · {history.name}</h4>
          <DataTable
            columns={[
              { key: 'period', label: 'Period' },
              { key: 'recordSource', label: 'Record', render: (row) => row.recordSource === 'IMPORTED_HISTORY' ? 'Imported history' : 'Payroll batch' },
              { key: 'componentName', label: 'Component', render: (row) => row.componentName || row.componentCode || '—' },
              { key: 'amount', label: 'Recorded amount', align: 'right', render: (row) => formatAmount(row.amount) },
              { key: 'status', label: 'Status', render: (row) => row.recordSource === 'IMPORTED_HISTORY' ? (row.sourceStatus || 'Not provided') : <StatusChip value={row.batchStatus} /> },
              { key: 'gross', label: 'Gross', align: 'right', render: (row) => formatAmount(row.gross) },
              { key: 'deductions', label: 'Deductions', align: 'right', render: (row) => formatAmount(row.deductions) },
              { key: 'net', label: 'Net', align: 'right', render: (row) => formatAmount(row.net) },
            ]}
            rows={history.rows || []}
            rowKey={(row) => `${row.recordSource}-${row.recordId}`}
            emptyTitle="No salary history"
            emptyDescription="No payroll history is available for this employee."
          />
        </div>
      )}
    </>
  )
}

function SalaryHistoryReport() {
  return (
    <Unavailable
      title="Employee salary revision history is not exposed"
      description="Pay scales, grade revisions and salary-component changes over time are stored server-side but no endpoint returns an employee's revision timeline. Per-period salary figures remain available under the Employee-wise report."
      missingEndpoint="GET /api/reports/salary-revisions?employeeId="
    />
  )
}
