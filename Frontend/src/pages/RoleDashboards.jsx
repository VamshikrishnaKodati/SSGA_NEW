import { useEffect, useState } from 'react'
import {
  auditApi,
  divisionApi,
  employeeApi,
  payrollApi,
  periodApi,
  reportApi,
  statementApi,
  systemApi,
} from '../services/api.js'
import {
  formatDateTime,
  formatNumber,
  friendlyError,
  humanizeStatus,
} from '../lib/format.js'
import { canonicalRole, ROLES } from '../lib/permissions.js'
import {
  Alert,
  EmptyState,
  Icon,
  Kpi,
  LoadingState,
  PageHeader,
  StatusChip,
} from '../components/ui.jsx'

const DASHBOARDS = {
  [ROLES.SYSTEM_ADMIN]: SystemAdminDashboard,
  [ROLES.ORG_ADMIN]: OrganizationAdminDashboard,
  [ROLES.WING_ADMIN]: WingAdminDashboard,
  [ROLES.DIVISION_ADMIN]: DivisionAdminDashboard,
  [ROLES.PAYROLL_OPERATOR]: PayrollOperatorDashboard,
  [ROLES.APPROVING_AUTHORITY]: ApproverDashboard,
  [ROLES.REPORT_USER]: ReportUserDashboard,
  [ROLES.EMPLOYEE_SELF]: EmployeeDashboard,
  [ROLES.AUDITOR]: AuditorDashboard,
  [ROLES.PAYROLL_FINALIZER]: PayrollFinalizerDashboard,
}

function useRoleDashboardData(ability, onSessionExpired) {
  const [state, setState] = useState({ loading: true, data: {}, errors: [] })

  useEffect(() => {
    let active = true
    const requests = []
    const workflowRead = ability.hasAny(
      'payroll.view',
      'payroll.prepare',
      'payroll.verify',
      'payroll.submit',
      'payroll.approve',
      'payroll.finalize',
      'payroll.reopen',
    )
    const scopeRead = ability.hasAny(
      'division.view',
      'payroll.view',
      'payroll.prepare',
      'payroll.verify',
      'payroll.submit',
      'payroll.approve',
      'payroll.finalize',
      'payroll.reopen',
    )

    function add(key, enabled, load) {
      if (enabled) requests.push([key, load])
    }

    add('employees', ability.has('employee.view'), () => employeeApi.list())
    add('divisions', scopeRead, () => divisionApi.myScope())
    add('periods', workflowRead, () => periodApi.list())
    add('batches', workflowRead, () => payrollApi.listBatches())
    add('reports', ability.has('report.view'), () => reportApi.divisionSummary())
    add(
      'statements',
      ability.hasAny('statement.view', 'statement.download'),
      () => statementApi.list(),
    )
    add('audit', ability.has('audit.view'), () => auditApi.list({ limit: 5, offset: 0 }))
    add('health', ability.hasRole(ROLES.SYSTEM_ADMIN), () => systemApi.health())

    Promise.allSettled(requests.map(([, load]) => load()))
      .then((results) => {
        const data = {}
        const errors = []
        results.forEach((result, index) => {
          if (result.status === 'fulfilled') {
            data[requests[index][0]] = result.value
          } else {
            if (
              active
              && result.reason?.status === 401
              && onSessionExpired
            ) {
              onSessionExpired()
            }
            errors.push(friendlyError(result.reason))
          }
        })
        if (active) setState({ loading: false, data, errors })
      })

    return () => {
      active = false
    }
  }, [ability, onSessionExpired])

  return state
}

export default function RoleDashboard({ user, ability, onNavigate, onSessionExpired }) {
  const state = useRoleDashboardData(ability, onSessionExpired)
  const Dashboard = DASHBOARDS[canonicalRole(user.activeAssignment?.roleCode)]

  if (!Dashboard) {
    return (
      <>
        <PageHeader title="Workspace unavailable" subtitle="No dashboard is configured for the active role" />
        <section className="panel">
          <div className="panel-body">
            <EmptyState
              title="No role dashboard"
              description="Contact an administrator to review this role assignment"
              icon="warning"
            />
          </div>
        </section>
      </>
    )
  }

  if (state.loading) {
    return (
      <>
        <PageHeader title={`${user.activeAssignment.roleName} dashboard`} subtitle={user.activeAssignment.scopeLabel} />
        <section className="panel"><div className="panel-body"><LoadingState rows={5} /></div></section>
      </>
    )
  }

  return (
    <Dashboard
      user={user}
      ability={ability}
      data={state.data}
      errors={state.errors}
      onNavigate={onNavigate}
    />
  )
}

function DashboardFrame({ title, description, errors, metrics, links, children }) {
  return (
    <>
      <PageHeader title={title} subtitle={description} />

      {errors.length > 0 && (
        <div className="section-gap">
          <Alert kind="warning" title="Some workspace data could not be loaded.">
            {errors[0]}{errors.length > 1 ? ` (+${errors.length - 1} more)` : ''}
          </Alert>
        </div>
      )}

      {metrics.length > 0 && (
        <section className="kpi-strip role-dashboard-metrics" aria-label={`${title} summary`}>
          {metrics.map((metric) => (
            <Kpi
              key={metric.label}
              label={metric.label}
              value={metric.value}
              foot={metric.foot}
              icon={metric.icon}
              onClick={metric.route ? () => links.navigate(metric.route) : undefined}
            />
          ))}
        </section>
      )}

      {children}

      <section className="panel role-dashboard-links">
        <div className="panel-head">
          <div>
            <h2>Workspaces</h2>
            <p>Available modules for this active role</p>
          </div>
        </div>
        <div className="panel-body role-dashboard-link-grid">
          {links.items.length === 0 ? (
            <EmptyState
              title="No workspaces available"
              description="No modules are enabled for this role and scope"
              icon="lock"
            />
          ) : links.items.map((item) => (
            <button
              key={`${item.route}-${item.label}`}
              className="service-card"
              type="button"
              onClick={() => links.navigate(item.route)}
            >
              <span className="panel-icon"><Icon name={item.icon} size={16} /></span>
              <span>
                <strong>{item.label}</strong>
                <small>{item.description}</small>
              </span>
              <Icon name="chevronRight" size={14} />
            </button>
          ))}
        </div>
      </section>
    </>
  )
}

function linkTools(onNavigate, ability, items) {
  return {
    navigate: onNavigate,
    items: items.filter((item) => item.always || ability.hasAny(...(item.permissions || []))),
  }
}

function statusCount(batches, status) {
  return batches.filter((batch) => batch.status === status).length
}

function periodSortKey(period) {
  return (Number(period.year) || 0) * 12 + (Number(period.month) || 0)
}

function uniqueWingCount(divisions, employees) {
  const rows = divisions.length > 0 ? divisions : employees
  return new Set(
    rows
      .map((row) => row.wingId ?? row.wingName)
      .filter((wing) => wing !== null && wing !== undefined && wing !== ''),
  ).size
}

function SystemAdminDashboard({ user, ability, data, errors, onNavigate }) {
  const employees = data.employees || []
  const scopeCount = (data.divisions || []).length
  const links = linkTools(onNavigate, ability, [
    { route: 'access', label: 'User management', description: 'Review access assigned to this account', icon: 'shield', permissions: ['user.manage', 'scope.manage'] },
    { route: 'organization', label: 'Organization master', description: 'Maintain the organization hierarchy', icon: 'building', permissions: ['organization.view', 'wing.view', 'division.view'] },
    { route: 'salary-components', label: 'Salary components', description: 'Review salary components', icon: 'layers', permissions: ['salary.view'] },
    { route: 'audit', label: 'Audit trail', description: 'Search scoped audit events', icon: 'history', permissions: ['audit.view'] },
    { route: 'reports', label: 'Reports', description: 'Review reports available to your role', icon: 'chart', permissions: ['report.view'] },
    { route: 'system', label: 'System health', description: 'Check service availability', icon: 'server', permissions: ['user.manage'] },
  ])
  return (
    <DashboardFrame user={user} title="Administrator dashboard" description="System health and administration for your scope" errors={errors} links={links} metrics={[
      { label: 'Employees in scope', value: formatNumber(employees.length), icon: 'users', route: 'employees' },
      { label: 'Divisions in scope', value: formatNumber(scopeCount), icon: 'building', route: 'organization' },
      { label: 'Service health', value: data.health ? 'Reachable' : 'Not checked', foot: data.health?.status || 'Latest status check', icon: 'server', route: 'system' },
    ]} />
  )
}

function OrganizationAdminDashboard({ user, ability, data, errors, onNavigate }) {
  const employees = data.employees || []
  const divisions = data.divisions || []
  const periods = data.periods || []
  const batches = data.batches || []
  const active = employees.filter((employee) => employee.status === 'ACTIVE').length
  const wingCount = uniqueWingCount(divisions, employees)
  const currentPeriod = periods.find((period) => period.status === 'OPEN')
    || [...periods].sort((left, right) => periodSortKey(right) - periodSortKey(left))[0]
  const periodBatches = currentPeriod
    ? batches.filter((batch) => batch.periodId === currentPeriod.id)
    : []
  const finalized = periodBatches.filter((batch) => batch.status === 'FINALIZED').length
  const pendingApproval = statusCount(periodBatches, 'SUBMITTED')
  const inProgress = periodBatches.length - finalized
  const processingValue = !currentPeriod
    ? 'No period available'
    : periodBatches.length === 0
      ? 'Not started'
      : finalized === periodBatches.length
        ? 'Finalized'
        : `${formatNumber(inProgress)} in progress`

  const links = linkTools(onNavigate, ability, [
    { route: 'employees', label: 'Employee master', description: 'Search and maintain employee records', icon: 'users', permissions: ['employee.view'] },
    { route: 'organization', label: 'Organization master', description: 'Manage organization and payroll units', icon: 'building', permissions: ['organization.manage'] },
    { route: 'salary-components', label: 'Salary components', description: 'Review active salary components', icon: 'layers', permissions: ['salary.view'] },
    { route: 'imports', label: 'Data import', description: 'Upload and validate authorized data', icon: 'upload', permissions: ['salary.manage', 'employee.create'] },
    { route: 'imports', label: 'Salary revisions', description: 'Import and validate approved salary revision sheets', icon: 'trendUp', permissions: ['salary.manage'] },
    { route: 'reports', label: 'Reports and salary history', description: 'Review scoped reports and employee history', icon: 'chart', permissions: ['report.view'] },
    { route: 'payroll', label: 'Payroll overview', description: 'Monitor periods and batch workflow', icon: 'calculator', permissions: ['payroll.view', 'payroll.prepare'] },
    {
      route: ability.has('audit.view') ? 'audit' : 'notifications',
      label: ability.has('audit.view') ? 'Audit & notifications' : 'Notifications',
      description: ability.has('audit.view')
        ? 'Search scoped audit events and review workflow alerts.'
        : 'Review workflow alerts and approval updates.',
      icon: ability.has('audit.view') ? 'history' : 'bell',
      always: true,
    },
  ])

  return (
    <DashboardFrame user={user} title="Organization dashboard" description="Employee administration and payroll oversight for your organization" errors={errors} links={links} metrics={[
      { label: 'Total employees', value: formatNumber(employees.length), foot: 'Employees in your organization', icon: 'users', route: 'employees' },
      { label: 'Active employees', value: formatNumber(active), foot: employees.length ? `${Math.round((active / employees.length) * 100)}% of employees in scope` : 'No employee records in scope', icon: 'check', route: 'employees' },
      { label: 'Wings', value: formatNumber(wingCount), foot: wingCount ? 'Wings represented in your scope' : 'No wings in scope', icon: 'layers', route: 'organization' },
      { label: 'Divisions', value: formatNumber(divisions.length), foot: divisions.length ? 'Divisions in your organization' : 'No divisions in scope', icon: 'building', route: 'organization' },
    ]}>
      <section className="panel section-gap" aria-label="Payroll context">
        <div className="panel-head">
          <div>
            <h3>Payroll context</h3>
            <p>Current period and workflow position for your organization</p>
          </div>
          <div className="btn-row">
            {currentPeriod && <StatusChip value={currentPeriod.status} />}
            <button type="button" className="btn subtle sm" onClick={() => onNavigate('payroll')}>
              Open payroll
            </button>
          </div>
        </div>
        <div className="panel-body">
          <div className="overview-totals">
            <div>
              <span>Current period</span>
              <strong>{currentPeriod?.label || 'No period available'}</strong>
              <span>
                {currentPeriod
                  ? `${currentPeriod.startDate} to ${currentPeriod.endDate}`
                  : 'No payroll period returned for this scope'}
              </span>
            </div>
            <div>
              <span>Processing status</span>
              <strong>{processingValue}</strong>
              <span>
                {currentPeriod
                  ? periodBatches.length
                    ? `${formatNumber(finalized)} of ${formatNumber(periodBatches.length)} batches finalized`
                    : 'No batches generated for this period'
                  : 'Payroll context will appear once a period exists'}
              </span>
            </div>
            <div>
              <span>Pending approval</span>
              <strong>{formatNumber(pendingApproval)}</strong>
              <span>{pendingApproval ? 'Submitted batches awaiting checker review' : 'No batches awaiting approval'}</span>
            </div>
          </div>
        </div>
      </section>
    </DashboardFrame>
  )
}

function WingAdminDashboard({ user, ability, data, errors, onNavigate }) {
  const batches = data.batches || []
  const links = linkTools(onNavigate, ability, [
    { route: 'employees', label: 'Employee master', description: 'Browse employees visible in this wing', icon: 'users', permissions: ['employee.view'] },
    { route: 'organization', label: 'Division overview', description: 'Review the divisions in your assigned scope', icon: 'building', permissions: ['division.view'] },
    { route: 'payroll', label: 'Payroll overview', description: 'Monitor batch status across your wing', icon: 'calculator', permissions: ['payroll.view'] },
    { route: 'reports', label: 'Wing reports and history', description: 'Review reports available within scope', icon: 'chart', permissions: ['report.view'] },
    { route: 'imports', label: 'Salary imports', description: 'Use authorized import workflows', icon: 'upload', permissions: ['salary.manage'] },
  ])
  return (
    <DashboardFrame user={user} title="Wing dashboard" description="Employee and payroll overview for your assigned wing" errors={errors} links={links} metrics={[
      { label: 'Employees in scope', value: formatNumber((data.employees || []).length), icon: 'users', route: 'employees' },
      { label: 'Divisions in scope', value: formatNumber((data.divisions || []).length), icon: 'building', route: 'organization' },
      { label: 'Open payroll batches', value: formatNumber(batches.filter((batch) => batch.status !== 'FINALIZED').length), icon: 'calculator', route: 'payroll' },
      { label: 'Awaiting approval', value: formatNumber(statusCount(batches, 'SUBMITTED')), icon: 'clock', route: 'payroll' },
    ]} />
  )
}

function DivisionAdminDashboard({ user, ability, data, errors, onNavigate }) {
  const batches = data.batches || []
  const links = linkTools(onNavigate, ability, [
    { route: 'employees', label: 'Employee master', description: 'Review employee records in your division', icon: 'users', permissions: ['employee.view'] },
    { route: 'payroll', label: 'Division payroll', description: 'Review payroll batch workflow', icon: 'calculator', permissions: ['payroll.view'] },
    { route: 'reports', label: 'Division reports and history', description: 'View reports within your assigned scope', icon: 'chart', permissions: ['report.view'] },
    { route: 'organization', label: 'Division details', description: 'View organization hierarchy', icon: 'building', permissions: ['division.view'] },
  ])
  return (
    <DashboardFrame user={user} title="Division dashboard" description="Employee and payroll overview for your assigned division" errors={errors} links={links} metrics={[
      { label: 'Employees', value: formatNumber((data.employees || []).length), icon: 'users', route: 'employees' },
      { label: 'Payroll batches', value: formatNumber(batches.length), icon: 'calculator', route: 'payroll' },
      { label: 'Awaiting approval', value: formatNumber(statusCount(batches, 'SUBMITTED')), icon: 'clock', route: 'payroll' },
      { label: 'Rejected batches', value: formatNumber(statusCount(batches, 'REJECTED')), icon: 'warning', route: 'payroll' },
    ]} />
  )
}

function PayrollOperatorDashboard({ user, ability, data, errors, onNavigate }) {
  const batches = data.batches || []
  const currentPeriod = (data.periods || []).find((period) => period.status === 'OPEN')
  const links = linkTools(onNavigate, ability, [
    { route: 'payroll', label: 'Payroll processing', description: 'Prepare, verify and submit payroll batches', icon: 'calculator', permissions: ['payroll.prepare', 'payroll.verify', 'payroll.submit'] },
    { route: 'imports', label: 'Monthly salary inputs', description: 'Upload and validate approved salary input files', icon: 'upload', permissions: ['salary.prepare', 'salary.manage'] },
    { route: 'employees', label: 'Employee master', description: 'Read employee data available in your scope', icon: 'users', permissions: ['employee.view'] },
    { route: 'reports', label: 'Payroll reports and history', description: 'Open reports available to your role', icon: 'chart', permissions: ['report.view'] },
    { route: 'statements', label: 'Authorized statements', description: 'View statements permitted by your role', icon: 'file', permissions: ['statement.view', 'statement.download'] },
  ])
  return (
    <DashboardFrame user={user} title="Payroll operator dashboard" description="Prepare and submit payroll for independent review" errors={errors} links={links} metrics={[
      { label: 'Active period', value: currentPeriod?.label || 'None open', icon: 'calendar', route: 'payroll' },
      { label: 'Needs preparation', value: formatNumber(batches.filter((batch) => ['DRAFT', 'REJECTED', 'REOPENED'].includes(batch.status)).length), icon: 'calculator', route: 'payroll' },
      { label: 'Awaiting approval', value: formatNumber(statusCount(batches, 'SUBMITTED')), icon: 'clock', route: 'payroll' },
      { label: 'Employees in scope', value: formatNumber((data.employees || []).length), icon: 'users', route: 'employees' },
    ]} />
  )
}

function ApproverDashboard({ user, ability, data, errors, onNavigate }) {
  const batches = data.batches || []
  const links = linkTools(onNavigate, ability, [
    { route: 'approvals', label: 'Approval inbox', description: 'Review batch details and make the independent decision', icon: 'check', permissions: ['payroll.approve'] },
    { route: 'payroll', label: 'Batch verification', description: 'Read scoped batch details and readiness', icon: 'calculator', permissions: ['payroll.view', 'payroll.verify'] },
    { route: 'employees', label: 'Employee salary review', description: 'Review employee data permitted by your role', icon: 'users', permissions: ['employee.view', 'salary.view'] },
    { route: 'reports', label: 'Reports', description: 'View authorized payroll reports', icon: 'chart', permissions: ['report.view'] },
  ])
  return (
    <DashboardFrame user={user} title="Approver dashboard" description="Review submitted batches as an independent checker" errors={errors} links={links} metrics={[
      { label: 'Awaiting decision', value: formatNumber(statusCount(batches, 'SUBMITTED')), icon: 'clock', route: 'approvals' },
      { label: 'Approved batches', value: formatNumber(statusCount(batches, 'APPROVED')), icon: 'check', route: 'approvals' },
      { label: 'Employees in scope', value: formatNumber((data.employees || []).length), icon: 'users', route: 'employees' },
    ]} />
  )
}

function ReportUserDashboard({ user, ability, data, errors, onNavigate }) {
  const links = linkTools(onNavigate, ability, [
    { route: 'reports', label: 'Reports', description: 'Filter and preview available payroll reports', icon: 'chart', permissions: ['report.view'] },
    { route: 'statements', label: 'Salary statements', description: 'Read or download authorized statements', icon: 'file', permissions: ['statement.view', 'statement.download'] },
    { route: 'employees', label: 'Employee history', description: 'Open employee history where permitted', icon: 'users', permissions: ['employee.view', 'report.view'] },
  ])
  const reportRows = Array.isArray(data.reports) ? data.reports : []
  return (
    <DashboardFrame user={user} title="Report user dashboard" description="Reports and statement access within your assigned scope" errors={errors} links={links} metrics={[
      { label: 'Report rows available', value: formatNumber(reportRows.length), icon: 'chart', route: 'reports' },
      { label: 'Payroll periods', value: formatNumber((data.periods || []).length), icon: 'calendar', route: 'reports' },
      { label: 'Statements', value: formatNumber((data.statements || []).length), icon: 'file', route: 'statements' },
    ]} />
  )
}

function EmployeeDashboard({ user, ability, data, errors, onNavigate }) {
  const employee = (data.employees || [])[0]
  const statements = data.statements || []
  const latest = [...statements].sort(
    (left, right) => String(right.generatedAt || '').localeCompare(String(left.generatedAt || '')),
  )[0]
  const links = linkTools(onNavigate, ability, [
    { route: 'statements', label: 'My salary statements', description: 'View and download statements issued to you', icon: 'file', permissions: ['statement.view', 'statement.download'] },
    { route: 'profile', label: 'My profile', description: 'Review your linked employee information', icon: 'badge', permissions: ['employee.view'] },
    { route: 'reports', label: 'My salary history', description: 'Open your salary history', icon: 'history', permissions: ['report.view'] },
  ])
  return (
    <DashboardFrame user={user} title="My dashboard" description="Your employee profile and issued salary records" errors={errors} links={links} metrics={[
      { label: 'Employee record', value: employee?.employeeCode || 'Not linked', foot: employee?.name || 'Contact your HR administrator', icon: 'badge', route: 'profile' },
      { label: 'Issued statements', value: formatNumber(statements.length), icon: 'file', route: 'statements' },
      { label: 'Latest statement', value: latest?.periodLabel || 'None issued', foot: latest ? humanizeStatus(latest.status) : 'No statement available', icon: 'clock', route: 'statements' },
    ]} />
  )
}

function AuditorDashboard({ user, ability, data, errors, onNavigate }) {
  const audit = data.audit?.events || []
  const links = linkTools(onNavigate, ability, [
    { route: 'audit', label: 'Audit trail search', description: 'Search read-only events within authorized scope', icon: 'history', permissions: ['audit.view'] },
    { route: 'reports', label: 'Audit and payroll reports', description: 'View reports available to the auditor role', icon: 'chart', permissions: ['report.view'] },
  ])
  return (
    <DashboardFrame user={user} title="Auditor dashboard" description="Read-only audit visibility and reports for the active scope" errors={errors} links={links} metrics={[
      { label: 'Recent audit events', value: formatNumber(audit.length), icon: 'history', route: 'audit' },
      { label: 'Report rows available', value: formatNumber((data.reports || []).length), icon: 'chart', route: 'reports' },
      { label: 'Access mode', value: 'Read-only', icon: 'lock' },
    ]}>
      {audit.length > 0 && (
        <section className="panel section-gap">
          <div className="panel-head">
              <div><h2>Recent audit activity</h2><p>Latest activity in your scope</p></div>
            <button type="button" className="btn subtle sm" onClick={() => onNavigate('audit')}>Open audit trail</button>
          </div>
          <div className="panel-body audit-summary-list">
            {audit.slice(0, 5).map((event) => (
              <div className="audit-summary-item" key={`${event.eventId || event.id}-${event.occurredAt}`}>
                <span className="audit-summary-action">{humanizeStatus(event.action)}</span>
                <span>{event.entityType || 'Record'} {event.entityId ? `#${event.entityId}` : ''}</span>
                <time>{formatDateTime(event.occurredAt)}</time>
              </div>
            ))}
          </div>
        </section>
      )}
    </DashboardFrame>
  )
}

function PayrollFinalizerDashboard({ user, ability, data, errors, onNavigate }) {
  const batches = data.batches || []
  const links = linkTools(onNavigate, ability, [
    { route: 'statements', label: 'Approved batch queue', description: 'Review approved batches and finalize through the authorized workflow', icon: 'check', permissions: ['payroll.finalize'] },
    { route: 'statements', label: 'Statement generation', description: 'Generate statements only after batch finalization', icon: 'file', permissions: ['statement.generate'] },
    { route: 'reports', label: 'Finalized register and reports', description: 'Review finalized payroll in your scope', icon: 'chart', permissions: ['report.view'] },
    { route: 'audit', label: 'Finalization history', description: 'Review read-only audit events in the assigned scope', icon: 'history', permissions: ['audit.view'] },
  ])
  const finalized = batches.filter((batch) => batch.status === 'FINALIZED')
  return (
    <DashboardFrame user={user} title="Finalizer dashboard" description="Finalize approved payroll as a distinct, authorized step; finalization is not bank disbursement" errors={errors} links={links} metrics={[
      { label: 'Approved batch queue', value: formatNumber(statusCount(batches, 'APPROVED')), icon: 'check', route: 'statements' },
      { label: 'Finalized batches', value: formatNumber(finalized.length), icon: 'lock', route: 'statements' },
      { label: 'Statements issued', value: formatNumber((data.statements || []).length), icon: 'file', route: 'statements' },
      { label: 'Payroll periods', value: formatNumber((data.periods || []).length), icon: 'calendar', route: 'statements' },
    ]}>
      {(data.statements || []).length === 0 && finalized.length > 0 && (
        <section className="section-gap">
          <Alert kind="info">Finalized payroll is available, but no statements are currently returned for this scope.</Alert>
        </section>
      )}
    </DashboardFrame>
  )
}
