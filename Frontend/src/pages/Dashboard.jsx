import { useEffect, useState } from 'react'
import {
  divisionApi,
  employeeApi,
  payrollApi,
  periodApi,
  reportApi,
  statementApi,
} from '../services/api.js'
import {
  formatAmount,
  formatCompactCurrency,
  formatNumber,
  friendlyError,
  humanizeStatus,
} from '../lib/format.js'
import { ROLE_LABELS, ROLES } from '../lib/permissions.js'
import {
  Alert,
  DataTable,
  EmptyState,
  Icon,
  Kpi,
  LoadingState,
  PageHeader,
  StatusChip,
} from '../components/ui.jsx'

const ACCOUNT_PREVIEW_LIMIT = 6

const WORKQUEUE = {
  'ROLE_PAYROLL_OPERATOR': {
    title: 'My tasks - prepare payroll',
    description: 'Draft, rejected or reopened batches need verification and submission',
    statuses: ['DRAFT', 'REJECTED', 'REOPENED'],
    action: 'payroll',
    actionLabel: 'Open payroll processing',
  },
  'ROLE_APPROVING_AUTHORITY': {
    title: 'My tasks - approvals',
    description: 'Submitted batches awaiting approval or rejection',
    statuses: ['SUBMITTED'],
    action: 'approvals',
    actionLabel: 'Open approval inbox',
  },
  'ROLE_ORG_ADMIN': {
    title: 'Payroll oversight',
    description: 'Batches that are not yet finalized across your scope',
    statuses: ['DRAFT', 'SUBMITTED', 'APPROVED', 'REOPENED', 'REJECTED'],
    action: 'payroll',
    actionLabel: 'Open payroll processing',
  },
  'ROLE_WING_ADMIN': {
    title: 'Payroll attention',
    description: 'Batches in your wing that are not yet finalized',
    statuses: ['DRAFT', 'SUBMITTED', 'APPROVED', 'REOPENED', 'REJECTED'],
    action: 'payroll',
    actionLabel: 'Open payroll processing',
  },
  'ROLE_DIVISION_ADMIN': {
    title: 'Payroll attention',
    description: 'Batches in your division that are not yet finalized',
    statuses: ['DRAFT', 'SUBMITTED', 'APPROVED', 'REOPENED', 'REJECTED'],
    action: 'payroll',
    actionLabel: 'Open payroll processing',
  },
}

const PAYROLL_STAGES = [
  { id: 'select-period', label: 'Select period', route: 'payroll', any: ['payroll.prepare', 'payroll.view'] },
  { id: 'load-inputs', label: 'Load employee inputs', route: 'imports', any: ['salary.manage', 'payroll.prepare', 'employee.create'] },
  { id: 'calculate', label: 'Calculate salary', route: 'payroll', any: ['payroll.prepare'] },
  { id: 'validate-review', label: 'Validate & review', route: 'payroll', any: ['payroll.prepare', 'payroll.verify', 'payroll.view'] },
  { id: 'submit', label: 'Submit for approval', route: 'payroll', any: ['payroll.prepare', 'payroll.submit'] },
  { id: 'approval', label: 'Approval & finalization', route: 'payroll', any: ['payroll.approve', 'payroll.finalize'] },
]

function sum(rows, key) {
  return rows.reduce((total, row) => total + Number(row[key] || 0), 0)
}

function joinedThisMonth(value) {
  if (!value) return false

  const joined = new Date(value)
  const today = new Date()

  return joined.getFullYear() === today.getFullYear() && joined.getMonth() === today.getMonth()
}

function maskAccount(value) {
  if (!value) return '—'

  const text = String(value)

  if (text.includes('•')) return text

  return text.length <= 4 ? '••••' : `••••${text.slice(-4)}`
}

async function fetchDashboardData({ ability, onSessionExpired }) {
  const data = {
    employees: [],
    divisions: [],
    periods: [],
    batches: [],
    summary: [],
    statements: [],
    accounts: [],
  }
  const errors = []

  async function attempt(key, enabled, fn) {
    if (!enabled) return

    try {
      data[key] = await fn()
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      errors.push(friendlyError(requestError))
    }
  }

  await Promise.all([
    attempt('employees', ability.has('employee.view'), () => employeeApi.list()),
    attempt('divisions', ability.has('division.view'), () => divisionApi.myScope()),
    attempt('periods', ability.hasAny('payroll.view', 'payroll.prepare'), () => periodApi.list()),
    attempt(
      'batches',
      ability.hasAny('payroll.view', 'payroll.prepare', 'payroll.approve', 'payroll.finalize'),
      () => payrollApi.listBatches(),
    ),
    attempt('summary', ability.has('report.view'), () => reportApi.divisionSummary()),
    attempt('statements', ability.has('statement.view'), () => statementApi.list()),
  ])

  if (ability.has('employee.view') && data.employees.length > 0) {
    const preview = [...data.employees]
      .sort((left, right) => String(left.name || '').localeCompare(String(right.name || '')))
      .slice(0, ACCOUNT_PREVIEW_LIMIT)

    const results = await Promise.allSettled(preview.map((employee) => employeeApi.getById(employee.id)))

    results.forEach((result, index) => {
      if (result.status === 'fulfilled') {
        data.accounts.push({
          ...preview[index],
          bankAccounts: result.value?.bankAccounts || [],
        })
      } else if (result.reason?.status === 401 && onSessionExpired) {
        onSessionExpired()
      }
    })
  }

  return { data, errors }
}

export default function Dashboard({ user, ability, onNavigate, onSessionExpired }) {
  const [state, setState] = useState({ loading: true, errors: [], data: null })
  const [reloadKey, setReloadKey] = useState(0)
  const [filters, setFilters] = useState({ period: '', wing: '', division: '' })

  useEffect(() => {
    let cancelled = false

    ;(async () => {
      const result = await fetchDashboardData({ ability, onSessionExpired })

      if (!cancelled) {
        setState({ loading: false, errors: result.errors, data: result.data })
      }
    })()

    return () => {
      cancelled = true
    }
  }, [ability, onSessionExpired, reloadKey])

  const refresh = () => setReloadKey((value) => value + 1)
  const roleLabel = ability.primaryRole ? ROLE_LABELS[ability.primaryRole] : 'Authorized user'

  if (state.loading) {
    return (
      <>
        <PageHeader title={`Welcome, ${user.username}`} subtitle={`${roleLabel} · loading your workspace`} />
        <div className="panel"><LoadingState rows={6} /></div>
      </>
    )
  }

  const data = state.data || {}

  if (ability.primaryRole === ROLES.EMPLOYEE_SELF) {
    return (
      <SelfServiceDashboard
        user={user}
        roleLabel={roleLabel}
        data={data}
        errors={state.errors}
        onNavigate={onNavigate}
        onRefresh={refresh}
      />
    )
  }

  const employees = data.employees || []
  const divisions = data.divisions || []
  const periods = data.periods || []
  const batches = data.batches || []
  const summary = data.summary || []
  const accountDetails = data.accounts || []

  const canEmployees = ability.has('employee.view')
  const canPayroll = ability.hasAny('payroll.view', 'payroll.prepare', 'payroll.approve', 'payroll.finalize')
  const canReports = ability.has('report.view')
  const canPrepare = ability.hasAny('payroll.prepare', 'payroll.verify', 'payroll.submit', 'payroll.approve', 'payroll.finalize')

  const employeeTotal = employees.length
  const activeEmployees = employees.filter((employee) => employee.status === 'ACTIVE').length
  const newJoiners = employees.filter((employee) => joinedThisMonth(employee.dateOfJoining)).length
  const divisionCount = divisions.length
    || new Set(employees.map((employee) => employee.divisionId).filter(Boolean)).size
  const wingCount = new Set(
    employees.flatMap((employee) => {
      const key = employee.wingId ?? employee.wingName
      return key === undefined || key === null || key === '' ? [] : [key]
    }),
  ).size
  const activePercent = employeeTotal > 0 ? Math.round((activeEmployees / employeeTotal) * 100) : 0

  const orderedPeriods = [...periods].sort(
    (left, right) => (right.year * 12 + right.month) - (left.year * 12 + left.month),
  )
  const currentPeriod = orderedPeriods.find((period) => period.status === 'OPEN') || orderedPeriods[0]

  const currentPeriodBatches = currentPeriod
    ? batches.filter((batch) => batch.periodId === currentPeriod.id)
    : batches
  const processedCount = currentPeriodBatches.reduce(
    (total, batch) => total + Number(batch.employeeCount || 0),
    0,
  )
  const completionPercent = employeeTotal > 0
    ? Math.min(100, Math.round((processedCount / employeeTotal) * 100))
    : 0
  const netPayroll = sum(batches, 'netTotal')
  const pendingDecision = batches.filter((batch) => batch.status === 'SUBMITTED')
  const readyToFinalize = batches.filter((batch) => batch.status === 'APPROVED')

  const statusCounts = batches.reduce((counts, batch) => {
    counts[batch.status] = (counts[batch.status] || 0) + 1
    return counts
  }, {})
  const statusEntries = Object.entries(statusCounts)
  const statusPeak = Math.max(1, ...statusEntries.map(([, count]) => count))

  const workqueue = WORKQUEUE[ability.primaryRole]
  const awaitingFinalization = (ability.primaryRole === 'ROLE_APPROVING_AUTHORITY' || ability.has('payroll.finalize'))
    ? readyToFinalize
    : []

  const recentBatches = [...batches]
    .sort((left, right) => right.batchId - left.batchId)
    .slice(0, 6)
  const divisionRows = [...summary].sort((left, right) => right.net - left.net).slice(0, 6)

  const accountRows = accountDetails.map((employee) => {
    const bank = (employee.bankAccounts || []).find((account) => account.primary)
      || (employee.bankAccounts || [])[0]

    return { ...employee, bank }
  })

  const visibleStages = PAYROLL_STAGES.filter((stage) => ability.hasAny(...stage.any)).map((stage) => ({
    ...stage,
    route: stage.id === 'approval' && !ability.has('payroll.approve') ? 'payroll' : stage.route,
  }))

  const hasAnySection = canEmployees || canPayroll || canReports || canPrepare

  const wingOptions = [...new Set(employees.map((e) => e.wingName).filter(Boolean))].sort()
  const divisionOptions = [...new Set(divisions.map((d) => d.name).filter(Boolean))].sort()
  const orderedPeriodsForFilter = [...periods].sort(
    (left, right) => (left.year * 12 + left.month) - (right.year * 12 + right.month),
  )
  const periodOrder = new Map(orderedPeriodsForFilter.map((p, i) => [p.label, i]))

  const filteredEmployees = employees.filter((e) => {
    if (filters.wing && e.wingName !== filters.wing) return false
    if (filters.division && e.divisionName !== filters.division) return false
    return true
  })
  const filteredBatches = batches.filter((b) => {
    if (filters.period && b.periodId !== Number(filters.period)) return false
    if (filters.wing && b.wingName !== filters.wing) return false
    if (filters.division && b.divisionName !== filters.division) return false
    return true
  })

  const wingMap = {}
  filteredEmployees.forEach((e) => {
    const w = e.wingName || 'Unassigned'
    wingMap[w] = (wingMap[w] || 0) + 1
  })
  const wingChart = Object.entries(wingMap).sort((a, b) => b[1] - a[1])

  const activeFiltered = filteredEmployees.filter((e) => e.status === 'ACTIVE').length
  const inactiveFiltered = filteredEmployees.length - activeFiltered

  const periodTotals = {}
  filteredBatches.forEach((b) => {
    const t = periodTotals[b.periodLabel] || { net: 0, ded: 0 }
    t.net += Number(b.netTotal || 0)
    t.ded += Number(b.deductionTotal || 0)
    periodTotals[b.periodLabel] = t
  })
  const monthlyChart = Object.entries(periodTotals)
    .map(([label, t]) => ({ label, net: t.net, ded: t.ded }))
    .sort((a, b) => (periodOrder.get(a.label) || 0) - (periodOrder.get(b.label) || 0))
    .slice(-6)

  const statusMap = {}
  filteredBatches.forEach((b) => { statusMap[b.status] = (statusMap[b.status] || 0) + 1 })
  const statusChart = Object.entries(statusMap)

  const now = new Date()
  const ageBuckets = { '0-2 days': 0, '3-5 days': 0, '>5 days': 0 }
  filteredBatches.filter((b) => b.status === 'SUBMITTED').forEach((b) => {
    const created = b.createdAt ? new Date(b.createdAt) : null
    const days = created ? (now - created) / 86400000 : 0
    if (days <= 2) ageBuckets['0-2 days']++
    else if (days <= 5) ageBuckets['3-5 days']++
    else ageBuckets['>5 days']++
  })
  const ageChart = Object.entries(ageBuckets)

  return (
    <>
      <PageHeader
        title={`Welcome, ${user.username}`}
        subtitle={`${roleLabel} · your payroll workspace, scoped to your authorized access`}
        actions={(
          <button type="button" className="btn ghost" onClick={refresh}>
            <Icon name="refresh" size={14} /> Refresh
          </button>
        )}
      />

      {state.errors.length > 0 && (
        <Alert kind="warning" title="Some metrics could not be loaded.">
          {state.errors[0]}{state.errors.length > 1 ? ` (+${state.errors.length - 1} more)` : ''}
        </Alert>
      )}

      <div className="toolbar section-gap">
        <label className="field">
          <span>Period</span>
          <select
            value={filters.period}
            onChange={(event) => setFilters({ ...filters, period: event.target.value })}
          >
            <option value="">All periods</option>
            {orderedPeriodsForFilter.map((period) => (
              <option key={period.id} value={period.id}>{period.label}</option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Wing</span>
          <select
            value={filters.wing}
            onChange={(event) => setFilters({ ...filters, wing: event.target.value })}
          >
            <option value="">All wings</option>
            {wingOptions.map((wing) => (
              <option key={wing} value={wing}>{wing}</option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Division</span>
          <select
            value={filters.division}
            onChange={(event) => setFilters({ ...filters, division: event.target.value })}
          >
            <option value="">All divisions</option>
            {divisionOptions.map((division) => (
              <option key={division} value={division}>{division}</option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="btn subtle sm"
          onClick={() => setFilters({ period: '', wing: '', division: '' })}
        >
          Reset filters
        </button>
      </div>

      <section className="panel section-gap" aria-label="Organization overview charts">
        <SectionHead
          icon="chart"
          title="Organization overview"
          description="Live figures from your authorized scope, scoped by your role and the Wing/Division filters above."
        />
        <div className="chart-grid">
          {canEmployees && (
            <>
              <div className="chart-card">
                <div className="chart-head">
                  <h4>Employees by Wing</h4>
                  <span className="chart-note">Horizontal bar</span>
                </div>
                <WingBarChart data={wingChart} total={filteredEmployees.length} />
              </div>
              <div className="chart-card">
                <div className="chart-head">
                  <h4>Active vs inactive</h4>
                  <span className="chart-note">Donut · employee status</span>
                </div>
                <DonutChart
                  title="Employee status"
                  value={formatNumber(filteredEmployees.length)}
                  segments={[
                    { label: 'Active', value: activeFiltered, color: '#064734' },
                    { label: 'Inactive', value: inactiveFiltered, color: '#74859a' },
                  ].filter((s) => s.value > 0)}
                  size={150}
                />
              </div>
              <div className="chart-card">
                <div className="chart-head">
                  <h4>Monthly net salary trend</h4>
                  <span className="chart-note">Line · approved/finalized batches</span>
                </div>
                <LineChart data={monthlyChart} title="Monthly net salary trend" />
              </div>
            </>
          )}
          {canPayroll && (
            <>
              <div className="chart-card">
                <div className="chart-head">
                  <h4>Earnings vs deductions</h4>
                  <span className="chart-note">Stacked column · net vs deductions</span>
                </div>
                <StackedBarChart data={monthlyChart} title="Earnings vs deductions" />
              </div>
              <div className="chart-card">
                <div className="chart-head">
                  <h4>Payroll batch status</h4>
                  <span className="chart-note">Donut · batches in scope</span>
                </div>
                <DonutChart
                  title="Batch status"
                  value={formatNumber(filteredBatches.length)}
                  segments={statusChart.map(([status, count]) => ({
                    label: humanizeStatus(status),
                    value: count,
                    color: CHART_COLORS[statusChart.findIndex(([s]) => s === status) % CHART_COLORS.length],
                  }))}
                  size={150}
                />
              </div>
              <div className="chart-card">
                <div className="chart-head">
                  <h4>Pending approvals by age</h4>
                  <span className="chart-note">Bar · submitted batches waiting</span>
                </div>
                <VerticalBarChart
                  data={ageChart.map(([label, count]) => ({ label, count }))}
                  title="Pending approvals by age"
                  valueLabel="Days since submission"
                />
              </div>
            </>
          )}
        </div>
      </section>

      {canEmployees && (
        <section className="panel section-gap" aria-label="Organization metrics">
          <SectionHead
            icon="users"
            title="Organization metrics"
            description="Employee statistics from the Employee Master within your scope"
            action={(
              <button type="button" className="btn subtle sm" onClick={() => onNavigate('employees')}>
                Employee master <Icon name="chevronRight" size={13} />
              </button>
            )}
          />

          <div className="panel-body">
            <div className="metric-grid">
              <Kpi
                label="Total employees"
                icon="users"
                value={formatNumber(employeeTotal)}
                foot={`${formatNumber(Math.max(0, employeeTotal - activeEmployees))} inactive in scope`}
                onClick={() => onNavigate('employees')}
              />
              <Kpi
                label="Active employees"
                icon="check"
                tone="teal"
                value={formatNumber(activeEmployees)}
                foot={`${activePercent}% of employees in scope`}
                onClick={() => onNavigate('employees')}
              />
              <Kpi
                label="New joiners"
                icon="calendar"
                value={formatNumber(newJoiners)}
                foot="Joined in the current month"
                onClick={() => onNavigate('employees')}
              />
              <Kpi
                label="Divisions in scope"
                icon="building"
                value={formatNumber(divisionCount)}
                foot={wingCount > 0 ? `Across ${formatNumber(wingCount)} wing(s)` : 'From your organizational scope'}
                onClick={() => onNavigate('employees')}
              />
            </div>
          </div>
        </section>
      )}

      <div className="dashboard-split section-gap">
        {canEmployees && (
          <section className="panel" aria-label="Account summaries">
            <SectionHead
              icon="bank"
              title="Account summaries"
              description="Primary salary account details for employees in your scope"
              action={(
                <button type="button" className="btn subtle sm" onClick={() => onNavigate('employees')}>
                  View all <Icon name="chevronRight" size={13} />
                </button>
              )}
            />

            <DataTable
              columns={[
                {
                  key: 'employee',
                  label: 'Employee',
                  render: (row) => (
                    <div className="cell-stack">
                      <span className="cell-primary">{row.name || '—'}</span>
                      <span className="cell-sub">
                        {row.employeeCode || `#${row.id}`}
                        {row.divisionName ? ` · ${row.divisionName}` : ''}
                      </span>
                    </div>
                  ),
                },
                {
                  key: 'bank',
                  label: 'Bank / branch',
                  render: (row) => (row.bank ? (
                    <div className="cell-stack">
                      <span>{row.bank.bankName || '—'}</span>
                      <span className="cell-sub">
                        {row.bank.branchName || '—'}
                        {row.bank.ifscCode ? ` · ${row.bank.ifscCode}` : ''}
                      </span>
                    </div>
                  ) : <span className="muted">Not recorded</span>),
                },
                {
                  key: 'account',
                  label: 'Account',
                  nowrap: true,
                  render: (row) => (
                    <span className="account-number">{row.bank ? maskAccount(row.bank.accountNumber) : '—'}</span>
                  ),
                },
                {
                  key: 'status',
                  label: 'Status',
                  render: (row) => (
                    <span className={`chip plain ${row.bank ? 'active' : 'warning'}`}>
                      {row.bank ? (humanizeStatus(row.bank.status) || 'Active') : 'Missing'}
                    </span>
                  ),
                },
              ]}
              rows={accountRows}
              rowKey={(row) => row.id}
              onRowClick={() => onNavigate('employees')}
              emptyTitle="No account details available"
              emptyDescription="Employee bank details could not be read for your scope"
            />

            {accountRows.length > 0 && (
              <p className="panel-note">
                Showing {formatNumber(accountRows.length)} of {formatNumber(employeeTotal)} employees.
                Account numbers are always displayed masked.
              </p>
            )}
          </section>
        )}

        {canPayroll && (
          <section className="panel" aria-label="Salary processing overview">
            <SectionHead
              icon="calculator"
              title="Salary processing overview"
              description="Monthly payroll status for your scope"
              action={(
                <button type="button" className="btn subtle sm" onClick={() => onNavigate('payroll')}>
                  Open payroll <Icon name="chevronRight" size={13} />
                </button>
              )}
            />

            <div className="panel-body stack-sm">
              <div className="period-card">
                <div className="period-card-main">
                  <span className="eyebrow-sm">Current payroll period</span>
                  <strong>{currentPeriod?.label || 'No period available'}</strong>
                  <span className="inline-meta">
                    {periods.length > 0
                      ? `${formatNumber(periods.length)} period(s) visible to your account`
                      : 'No payroll periods are visible to your account'}
                  </span>
                </div>
                {currentPeriod && <StatusChip value={currentPeriod.status} />}
              </div>

              <div className="progress-block">
                <div className="progress-head">
                  <span>Employee coverage</span>
                  <strong>
                    {employeeTotal > 0
                      ? `${formatNumber(processedCount)} / ${formatNumber(employeeTotal)}`
                      : formatNumber(processedCount)}
                  </strong>
                </div>
                <div className="progress-track" role="presentation">
                  <span className="progress-fill" style={{ width: `${completionPercent}%` }} />
                </div>
                <span className="inline-meta">
                  {employeeTotal > 0
                    ? `${completionPercent}% of employees in scope are covered by ${currentPeriod ? 'batches in the current period' : 'visible payroll batches'}`
                    : 'Employee coverage requires the employee.view permission'}
                </span>
              </div>

              {statusEntries.length > 0 ? (
                <div className="status-bars">
                  {statusEntries.map(([status, count]) => (
                    <div className="status-bar-row" key={status}>
                      <StatusChip value={status} />
                      <div className="status-bar-track" role="presentation">
                        <span style={{ width: `${Math.round((count / statusPeak) * 100)}%` }} />
                      </div>
                      <strong>{formatNumber(count)}</strong>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="muted">No payroll batches are visible for your scope yet.</p>
              )}

              <div className="overview-totals">
                <div>
                  <span>Net payroll (visible batches)</span>
                  <strong>{formatCompactCurrency(netPayroll)}</strong>
                </div>
                <div>
                  <span>Awaiting approval</span>
                  <strong>{formatNumber(pendingDecision.length)}</strong>
                </div>
                <div>
                  <span>Ready to finalize</span>
                  <strong>{formatNumber(readyToFinalize.length)}</strong>
                </div>
              </div>
            </div>
          </section>
        )}
      </div>

      {canPayroll && (
        <section className="panel section-gap" aria-label="Payroll batches">
          <SectionHead
            icon="layers"
            title={workqueue ? workqueue.title : 'Recent payroll batches'}
            description={workqueue
              ? workqueue.description
              : 'Latest payroll batches visible to your scope.'}
            action={workqueue ? (
              <button type="button" className="btn subtle sm" onClick={() => onNavigate(workqueue.action)}>
                {workqueue.actionLabel} <Icon name="chevronRight" size={13} />
              </button>
            ) : undefined}
          />

          {workqueue ? (
            <BatchesTable
              batches={batches.filter((batch) => workqueue.statuses.includes(batch.status))}
              onOpen={() => onNavigate(workqueue.action)}
            />
          ) : (
            <BatchesTable batches={recentBatches} onOpen={() => onNavigate('payroll')} showEmployees />
          )}

          {awaitingFinalization.length > 0 && (
            <>
              <div className="panel-subhead">
                <div>
                  <h4>Awaiting finalization</h4>
                  <p>Approved batches are ready to be finalized.</p>
                </div>
              </div>
              <BatchesTable batches={awaitingFinalization} onOpen={() => onNavigate('payroll')} />
            </>
          )}
        </section>
      )}

      {canReports && (
        <section className="panel section-gap" aria-label="Division expenditure">
          <SectionHead
            icon="chart"
            title="Division expenditure"
            description="Finalized payroll totals for the highest net divisions in your scope"
            action={(
              <button type="button" className="btn subtle sm" onClick={() => onNavigate('reports')}>
                All reports <Icon name="chevronRight" size={13} />
              </button>
            )}
          />

          <DataTable
            columns={[
              { key: 'wingName', label: 'Wing', render: (row) => row.wingName || '—' },
              { key: 'divisionName', label: 'Division', render: (row) => <span className="cell-primary">{row.divisionName}</span> },
              { key: 'employeeCount', label: 'Employees', align: 'right', render: (row) => formatNumber(row.employeeCount) },
              { key: 'gross', label: 'Gross', align: 'right', render: (row) => formatAmount(row.gross) },
              { key: 'deductions', label: 'Deductions', align: 'right', render: (row) => formatAmount(row.deductions) },
              { key: 'net', label: 'Net', align: 'right', render: (row) => <strong>{formatAmount(row.net)}</strong> },
            ]}
            rows={divisionRows}
            rowKey={(row) => row.divisionId}
            emptyTitle="No finalized payroll"
            emptyDescription="No finalized payroll batches were found for your scope"
          />
        </section>
      )}

      {canPrepare && visibleStages.length > 0 && (
        <section className="panel section-gap" aria-label="Payroll workflow">
          <SectionHead
            icon="briefcase"
            title="Payroll workflow"
            description="Follow the monthly cycle from source data to final salary statements"
          />
          <div className="panel-body">
            <div className="workflow-grid">
              {visibleStages.map((stage, index) => (
                <button
                  key={stage.id}
                  type="button"
                  className="workflow-step"
                  onClick={() => onNavigate(stage.route)}
                  title={`Opens ${stage.route === 'imports' ? 'Salary / Data Import' : stage.route === 'approvals' ? 'Approval inbox' : 'Payroll processing'}`}
                >
                  <span className="workflow-step-index">{index + 1}</span>
                  <span className="workflow-step-label">{stage.label}</span>
                  <Icon name="chevronRight" size={13} className="workflow-step-arrow" />
                </button>
              ))}
            </div>
          </div>
        </section>
      )}

      {!hasAnySection && (
        <section className="panel section-gap">
          <div className="panel-body">
            <EmptyState
              title="Your dashboard is ready"
              description="Use the navigation to reach the modules your role can access. Metrics appear here as your grants allow."
              icon="grid"
            />
          </div>
        </section>
      )}
    </>
  )
}

function SelfServiceDashboard({ user, roleLabel, data, errors, onNavigate, onRefresh }) {
  const employee = (data.employees || [])[0]
  const statements = data.statements || []
  const latestStatement = [...statements].sort(
    (left, right) => String(right.generatedAt || '').localeCompare(String(left.generatedAt || '')),
  )[0]

  return (
    <>
      <PageHeader
        title={`Welcome, ${user.username}`}
        subtitle={`${roleLabel} · your personal salary records`}
        actions={(
          <button type="button" className="btn ghost" onClick={onRefresh}>
            <Icon name="refresh" size={14} /> Refresh
          </button>
        )}
      />

      {errors.length > 0 && (
        <Alert kind="warning" title="Some details could not be loaded.">
          {errors[0]}{errors.length > 1 ? ` (+${errors.length - 1} more)` : ''}
        </Alert>
      )}

      <section className="kpi-strip" aria-label="My records">
        <Kpi
          label="My employee record"
          icon="badge"
          value={employee?.name || 'Not linked'}
          foot={employee ? `${employee.employeeCode || `#${employee.id}`} · ${humanizeStatus(employee.status)}` : 'Contact your HR administrator'}
        />
        <Kpi
          label="Salary statements"
          icon="file"
          value={formatNumber(statements.length)}
          foot="Statements issued to you"
        />
        <Kpi
          label="Latest statement"
          icon="clock"
          value={latestStatement?.periodLabel || '—'}
          foot={latestStatement ? humanizeStatus(latestStatement.status) : 'No statement issued yet'}
        />
      </section>

      <section className="panel section-gap">
        <SectionHead
          icon="wallet"
          title="My services"
          description="View your employee details and salary statements"
        />
        <div className="panel-body service-grid">
          <button type="button" className="service-card" onClick={() => onNavigate('profile')}>
            <span className="panel-icon"><Icon name="badge" size={16} /></span>
            <span>
              <strong>My profile</strong>
              <small>Employee record and posting details</small>
            </span>
            <Icon name="chevronRight" size={14} />
          </button>
          <button type="button" className="service-card" onClick={() => onNavigate('statements')}>
            <span className="panel-icon"><Icon name="file" size={16} /></span>
            <span>
              <strong>Salary statements</strong>
              <small>{formatNumber(statements.length)} statement(s) available</small>
            </span>
            <Icon name="chevronRight" size={14} />
          </button>
        </div>
      </section>
    </>
  )
}

function SectionHead({ icon, title, description, action }) {
  return (
    <div className="section-head">
      <div className="section-head-main">
        <span className="panel-icon"><Icon name={icon} size={16} /></span>
        <div>
          <h3>{title}</h3>
          {description && <p>{description}</p>}
        </div>
      </div>
      {action}
    </div>
  )
}

function BatchesTable({ batches, onOpen, showEmployees }) {
  const rows = [...batches].sort((left, right) => right.batchId - left.batchId).slice(0, 6)

  const columns = [
    { key: 'batchId', label: 'Batch', nowrap: true, render: (row) => <span className="cell-primary">#{row.batchId}</span> },
    { key: 'periodLabel', label: 'Period', nowrap: true },
    { key: 'divisionName', label: 'Division', render: (row) => row.divisionName || row.divisionId },
    { key: 'status', label: 'Status', render: (row) => <StatusChip value={row.status} /> },
  ]

  if (showEmployees) {
    columns.push({
      key: 'employeeCount',
      label: 'Employees',
      align: 'right',
      render: (row) => formatNumber(row.employeeCount),
    })
  }

  columns.push({
    key: 'netTotal',
    label: 'Net',
    align: 'right',
    nowrap: true,
    render: (row) => formatAmount(row.netTotal),
  })

  return (
    <DataTable
      columns={columns}
      rows={rows}
      rowKey={(row) => row.batchId}
      onRowClick={onOpen}
      emptyTitle="Nothing pending"
      emptyDescription="No batches currently need action in your scope"
    />
  )
}

const CHART_COLORS = ['#064734', '#0f8360', '#1f5c9e', '#2e7d5b', '#9a6208', '#b93a34', '#74859a', '#51647c']

function polarToCartesian(cx, cy, r, angle) {
  const rad = ((angle - 90) * Math.PI) / 180
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) }
}

function arcPath(cx, cy, r, startAngle, endAngle) {
  const start = polarToCartesian(cx, cy, r, endAngle)
  const end = polarToCartesian(cx, cy, r, startAngle)
  const largeArcFlag = endAngle - startAngle <= 180 ? 0 : 1
  return [`M${start.x},${start.y}`, `A${r},${r} 0 ${largeArcFlag} 0 ${end.x},${end.y}`, `L${cx},${cy}`, 'Z'].join(' ')
}

function DonutChart({ title, value, segments, size = 150 }) {
  const total = segments.reduce((sum, seg) => sum + seg.value, 0)
  const cx = size / 2
  const cy = size / 2
  const r = size / 2 * 0.8

  const slices = segments.map((seg, i) => {
    if (!seg.value || total === 0) return null
    const prior = segments.slice(0, i).reduce((sum, s) => sum + s.value, 0)
    const start = (prior / total) * 360
    const sweep = (seg.value / total) * 360
    const end = start + sweep
    const d = arcPath(cx, cy, r, start, end)
    return <path key={i} d={d} fill={seg.color || CHART_COLORS[i % CHART_COLORS.length]} />
  })

  return (
    <svg className="chart-svg" width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={title}>
      {slices}
      <circle cx={cx} cy={cy} r={size / 2 * 0.6} fill="#fff" stroke="#e1e8f0" strokeWidth="2" />
      <text x={cx} y={cy - 7} textAnchor="middle" fontSize="13" fontWeight="600" fill="#1b3a57">{value}</text>
      <text x={cx} y={cy + 12} textAnchor="middle" fontSize="10" fill="#53665d">{total}</text>
    </svg>
  )
}

function WingBarChart({ data, total }) {
  const max = Math.max(1, ...data.map(([, v]) => v))
  const barH = 24
  const gap = 8
  const top = 22
  const left = 110
  const width = 300
  const height = Math.max(110, data.length * (barH + gap) + 56)

  return (
    <div className="chart-card">
      <div className="chart-head">
        <h4>Employees by Wing</h4>
        <span className="chart-note">Horizontal bar</span>
      </div>
      <svg className="chart-svg" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Employees by Wing">
        {data.map(([wing, count], i) => {
          const y = top + i * (barH + gap)
          const barW = (count / max) * (width - left - 44)
          return (
            <g key={wing}>
              <text x={left - 8} y={y + barH / 2 + 4} textAnchor="end" fontSize="11" fill="#53665d">{wing}</text>
              <rect x={left} y={y} width={barW} height={barH} fill="#064734" rx="3" />
              <text x={left + barW + 6} y={y + barH / 2 + 4} fontSize="11" fill="#1b3a57" fontWeight="600">{count}</text>
            </g>
          )
        })}
      </svg>
      <div className="chart-foot">Total employees in scope: {formatNumber(total)}</div>
    </div>
  )
}

function LineChart({ data, title }) {
  if (!data.length) return <EmptyChart title={title} />
  const width = 320
  const height = 170
  const pad = 38
  const max = Math.max(1, ...data.map((d) => d.net))
  const points = data.map((d, i) => {
    const x = pad + ((data.length === 1 ? 0 : i / (data.length - 1)) * (width - 2 * pad))
    const y = height - pad - (d.net / max) * (height - 2 * pad)
    return `${x},${y}`
  })

  return (
    <div className="chart-card">
      <div className="chart-head">
        <h4>{title}</h4>
        <span className="chart-note">Line · net salary by period</span>
      </div>
      <svg className="chart-svg" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={title}>
        <polyline points={points.join(' ')} fill="none" stroke="#064734" strokeWidth="2" />
        {data.map((d, i) => {
          const x = pad + ((data.length === 1 ? 0 : i / (data.length - 1)) * (width - 2 * pad))
          const y = height - pad - (d.net / max) * (height - 2 * pad)
          return <circle key={i} cx={x} cy={y} r={3} fill="#064734" />
        })}
        {data.map((d, i) => {
          const x = pad + ((data.length === 1 ? 0 : i / (data.length - 1)) * (width - 2 * pad))
          return <text key={i} x={x} y={height - 6} textAnchor="middle" fontSize="9" fill="#53665d">{d.label}</text>
        })}
        <text x={pad} y={pad - 10} fontSize="10" fill="#74859a">{formatCompactCurrency(max)}</text>
      </svg>
    </div>
  )
}

function StackedBarChart({ data, title }) {
  if (!data.length) return <EmptyChart title={title} />
  const width = 320
  const height = 160
  const pad = 36
  const barW = 30
  const gap = 14
  const max = Math.max(1, ...data.map((d) => d.net + d.ded))

  return (
    <div className="chart-card">
      <div className="chart-head">
        <h4>{title}</h4>
        <span className="chart-note">Stacked column · net vs deductions</span>
      </div>
      <svg className="chart-svg" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={title}>
        {data.map((d, i) => {
          const x = pad + i * (barW + gap)
          const totalH = ((d.net + d.ded) / max) * (height - pad - 20)
          const netH = (d.net / max) * (height - pad - 20)
          const y = height - pad - totalH
          return (
            <g key={i}>
              <rect x={x} y={y + netH} width={barW} height={Math.max(0, totalH - netH)} fill="#9a6208" />
              <rect x={x} y={y} width={barW} height={netH} fill="#064734" />
              <text x={x + barW / 2} y={height - 8} textAnchor="middle" fontSize="9" fill="#53665d">{d.label}</text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

function VerticalBarChart({ data, title, valueLabel }) {
  if (!data.length) return <EmptyChart title={title} />
  const width = 320
  const height = 160
  const pad = 36
  const barW = 44
  const gap = 18
  const max = Math.max(1, ...data.map((d) => d.count))

  return (
    <div className="chart-card">
      <div className="chart-head">
        <h4>{title}</h4>
        <span className="chart-note">{valueLabel}</span>
      </div>
      <svg className="chart-svg" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={title}>
        {data.map((d, i) => {
          const x = pad + i * (barW + gap)
          const h = (d.count / max) * (height - pad - 22)
          const y = height - pad - h
          return (
            <g key={i}>
              <rect x={x} y={y} width={barW} height={h} fill="#1f5c9e" rx="2" />
              <text x={x + barW / 2} y={y - 8} textAnchor="middle" fontSize="10" fill="#1b3a57" fontWeight="600">{d.count}</text>
              <text x={x + barW / 2} y={height - 8} textAnchor="middle" fontSize="9" fill="#53665d">{d.label}</text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

function EmptyChart({ title }) {
  return (
    <div className="chart-card">
      <div className="chart-head"><h4>{title}</h4></div>
      <div className="chart-empty">No data available for the current scope and filters.</div>
    </div>
  )
}
