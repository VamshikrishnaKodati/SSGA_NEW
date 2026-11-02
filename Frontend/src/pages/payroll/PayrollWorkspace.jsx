import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  divisionApi,
  employeeApi,
  payrollApi,
  periodApi,
  reportApi,
  statementApi,
} from '../../services/api.js'
import { primaryRoleLabel } from '../../lib/permissions.js'
import {
  canFinalizePayrollBatch,
  filterScopedDivisions,
  hasMonthlyBatch,
  payrollBatchStatusLabel,
  payrollScopeIsProcessable,
  payrollWorkflowSteps,
} from '../../lib/payrollWorkspace.js'

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
]

function formatAmount(value) {
  if (value === null || value === undefined) return '—'

  return Number(value).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function formatDate(value) {
  if (!value) return '—'

  const date = new Date(value)

  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function friendlyError(error) {
  const status = error?.status

  if (status === 401) return 'Your session has expired. Please sign in again.'
  if (status === 403) return 'You do not have permission to perform this action.'
  if (status === 404) return 'The requested record was not found.'

  return error?.message || 'Something went wrong. Please try again.'
}

function handleError(error, setError, onSessionExpired) {
  if (error?.status === 401 && onSessionExpired) onSessionExpired()
  setError(friendlyError(error))
}

function StatusChip({ status }) {
  return (
    <span
      className={`status-chip ${String(status || '').toLowerCase().replaceAll('_', '-')}`}
      title={status ? `Backend status: ${status}` : undefined}
    >
      {payrollBatchStatusLabel(status)}
    </span>
  )
}

function Alert({ kind, children }) {
  if (!children) return null

  return (
    <div className={`module-alert ${kind}`} role={kind === 'error' ? 'alert' : undefined}>
      {children}
    </div>
  )
}

export default function PayrollWorkspace({ user, onSessionExpired, onNavigate }) {
  const authorities = useMemo(
    () => (Array.isArray(user.authorities) ? user.authorities : []),
    [user],
  )

  const permissions = useMemo(() => {
    const isAdmin = authorities.includes('ROLE_SYSTEM_ADMIN')

    return {
      isAdmin,
      can: (code) => isAdmin || authorities.includes(code),
    }
  }, [authorities])

  const can = permissions.can

  const tabs = []

  if (
    can('payroll.view') ||
    can('payroll.prepare') ||
    can('payroll.verify') ||
    can('payroll.submit') ||
    can('payroll.approve') ||
    can('payroll.finalize') ||
    can('payroll.reopen')
  ) {
    tabs.push({ id: 'batches', label: 'Payroll batches' })
  }

  if (can('payroll.view') || can('payroll.prepare') || can('payroll.period.manage')) {
    tabs.push({ id: 'periods', label: 'Payroll periods' })
  }

  if (
    can('statement.view') ||
    can('statement.download') ||
    can('statement.generate') ||
    can('payroll.finalize')
  ) {
    tabs.push({ id: 'statements', label: 'Statements' })
  }

  if (can('report.view')) {
    tabs.push({ id: 'reports', label: 'Reports' })
  }

  const [activeTab, setActiveTab] = useState(tabs[0]?.id || null)

  if (tabs.length === 0) {
    return (
      <section className="account-panel">
        <div className="section-heading">
          <div>
            <h3>Payroll Workspace</h3>
            <p>Your account has no payroll, statement or reporting permissions.</p>
          </div>
        </div>
      </section>
    )
  }

  return (
    <section className="account-panel">
      <div className="section-heading">
        <div>
          <h3>Payroll Workspace</h3>
          <p>Run monthly payroll from period selection to finalization and salary statements.</p>
        </div>
        <span className="status-pill">HMWSSB · SSGA</span>
      </div>

      <nav className="module-tabs" role="tablist" aria-label="Payroll modules">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={`module-tab ${activeTab === tab.id ? 'active' : ''}`}
            role="tab"
            aria-selected={activeTab === tab.id}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      {activeTab === 'periods' && (
        <PeriodsPanel permissions={permissions} onSessionExpired={onSessionExpired} />
      )}

      {activeTab === 'batches' && (
        <BatchesPanel
          permissions={permissions}
          user={user}
          onSessionExpired={onSessionExpired}
          onNavigate={onNavigate}
        />
      )}

      {activeTab === 'statements' && (
        <StatementsPanel permissions={permissions} onSessionExpired={onSessionExpired} />
      )}

      {activeTab === 'reports' && (
        <ReportsPanel permissions={permissions} onSessionExpired={onSessionExpired} />
      )}
    </section>
  )
}

function PeriodsPanel({ permissions, onSessionExpired }) {
  const [periods, setPeriods] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)

  const canCreate = permissions.can('payroll.period.manage')

  const load = useCallback(async () => {
    setLoading(true)

    try {
      setPeriods(await periodApi.list())
      setError('')
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    } finally {
      setLoading(false)
    }
  }, [onSessionExpired])

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    load()
  }, [load])
  /* eslint-enable react-hooks/set-state-in-effect */

  async function handleCreate(event) {
    event.preventDefault()
    setError('')
    setMessage('')

    const numericYear = Number(year)
    const numericMonth = Number(month)

    if (!Number.isInteger(numericYear) || numericYear < 2000 || numericYear > 2100) {
      setError('Enter a year between 2000 and 2100.')
      return
    }

    if (!Number.isInteger(numericMonth) || numericMonth < 1 || numericMonth > 12) {
      setError('Select a month between January and December.')
      return
    }

    setSubmitting(true)

    try {
      const created = await periodApi.create(numericYear, numericMonth)

      setMessage(`Created period ${created.label} (${created.status}).`)
      await load()
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="module-body">
      <Alert kind="error">{error}</Alert>
      <Alert kind="success">{message}</Alert>

      {canCreate && (
        <form className="inline-form" onSubmit={handleCreate}>
          <div className="form-field">
            <label htmlFor="period-year">Year</label>
            <input
              id="period-year"
              type="number"
              min="2000"
              max="2100"
              value={year}
              onChange={(event) => setYear(event.target.value)}
              disabled={submitting}
            />
          </div>

          <div className="form-field">
            <label htmlFor="period-month">Month</label>
            <select
              id="period-month"
              value={month}
              onChange={(event) => setMonth(event.target.value)}
              disabled={submitting}
            >
              {MONTHS.map((name, index) => (
                <option key={name} value={index + 1}>
                  {name}
                </option>
              ))}
            </select>
          </div>

          <button className="module-button" type="submit" disabled={submitting}>
            {submitting ? 'Creating…' : 'Create period'}
          </button>
        </form>
      )}

      {loading ? (
        <p className="muted-text">Loading periods…</p>
      ) : periods.length === 0 ? (
        <p className="muted-text">No payroll periods are configured yet.</p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Period</th>
                <th>Start</th>
                <th>End</th>
                <th>Organization</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {periods.map((period) => (
                <tr key={period.id}>
                  <td>{period.label}</td>
                  <td>{period.startDate}</td>
                  <td>{period.endDate}</td>
                  <td>{period.organizationName}</td>
                  <td>
                    <StatusChip status={period.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function BatchesPanel({ permissions, user, onSessionExpired, onNavigate }) {
  const [periods, setPeriods] = useState([])
  const [divisions, setDivisions] = useState([])
  const [periodId, setPeriodId] = useState('')
  const [wingId, setWingId] = useState('')
  const [divisionId, setDivisionId] = useState('')
  const [batches, setBatches] = useState([])
  const [batchSearch, setBatchSearch] = useState('')
  const [batchPage, setBatchPage] = useState(1)
  const [batchesLoaded, setBatchesLoaded] = useState(false)
  const [scopeSummary, setScopeSummary] = useState(null)
  const [scopeSummaryError, setScopeSummaryError] = useState('')
  const [summaryLoading, setSummaryLoading] = useState(false)
  const [selected, setSelected] = useState(null)
  const [readiness, setReadiness] = useState(null)
  const [readinessLoading, setReadinessLoading] = useState(false)
  const [readinessError, setReadinessError] = useState('')
  const [statementsGenerated, setStatementsGenerated] = useState(false)
  const [statementError, setStatementError] = useState('')
  const [selectedId, setSelectedId] = useState(null)
  const [transactionPage, setTransactionPage] = useState(1)
  const [transactionSearch, setTransactionSearch] = useState('')
  const [hrmsIdFilter, setHrmsIdFilter] = useState('')
  const [transactionPageData, setTransactionPageData] = useState(null)
  const [transactionsLoading, setTransactionsLoading] = useState(false)
  const [transactionsError, setTransactionsError] = useState('')
  const [componentDetail, setComponentDetail] = useState(null)
  const [componentDetailLoading, setComponentDetailLoading] = useState(false)
  const [componentDetailError, setComponentDetailError] = useState('')
  const [approvalHistory, setApprovalHistory] = useState(null)
  const [approvalStatusVisible, setApprovalStatusVisible] = useState(false)
  const [approvalLoading, setApprovalLoading] = useState(false)
  const [approvalError, setApprovalError] = useState('')
  const [finalizationEligibility, setFinalizationEligibility] = useState(null)
  const [finalizationEligibilityError, setFinalizationEligibilityError] = useState('')
  const [finalizationPrompt, setFinalizationPrompt] = useState(false)
  const [finalizationConfirmed, setFinalizationConfirmed] = useState(false)
  const [referenceLoading, setReferenceLoading] = useState(true)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [activeStage, setActiveStage] = useState(1)
  const [reasonAction, setReasonAction] = useState(null)
  const [reason, setReason] = useState('')

  const canGenerate = permissions.can('payroll.prepare')
  const canVerify = permissions.can('payroll.verify')
  const canSubmit = permissions.can('payroll.submit') || canGenerate
  const canFinalize = permissions.can('payroll.finalize')
  const canReopen = permissions.can('payroll.reopen')
  const canStatement = permissions.can('statement.generate')
    || permissions.can('payroll.finalize')
  const canViewStatements = permissions.can('statement.view')
    || permissions.can('statement.download')
  const canLoadScope = [
    'division.view',
    'payroll.view',
    'payroll.prepare',
    'payroll.verify',
    'payroll.submit',
    'payroll.approve',
    'payroll.finalize',
    'payroll.reopen',
  ].some((permission) => permissions.can(permission))

  const loadReference = useCallback(async () => {
    setReferenceLoading(true)
    setError('')

    try {
      const [periodList, divisionList] = await Promise.all([
        periodApi.list(),
        canLoadScope ? divisionApi.myScope() : Promise.resolve([]),
      ])

      setPeriods(periodList)
      setDivisions(divisionList)
      setPeriodId((current) =>
        current || String(periodList.find((period) => period.status === 'OPEN')?.id || ''),
      )
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    } finally {
      setReferenceLoading(false)
    }
  }, [canLoadScope, onSessionExpired])

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    loadReference()
  }, [loadReference])
  /* eslint-enable react-hooks/set-state-in-effect */

  const selectedPeriod = periods.find((period) => String(period.id) === String(periodId))
  const organizationDivisions = useMemo(
    () => filterScopedDivisions(divisions, selectedPeriod?.organizationId),
    [divisions, selectedPeriod],
  )
  const wingOptions = useMemo(() => {
    const wings = new Map()
    organizationDivisions.forEach((division) => {
      wings.set(String(division.wingId), division.wingName || `Wing ${division.wingId}`)
    })
    return [...wings.entries()]
      .map(([value, label]) => ({ value, label }))
      .sort((left, right) => left.label.localeCompare(right.label))
  }, [organizationDivisions])
  const visibleDivisions = useMemo(
    () => filterScopedDivisions(divisions, selectedPeriod?.organizationId, wingId),
    [divisions, selectedPeriod, wingId],
  )
  const selectedDivision = visibleDivisions.find(
    (division) => String(division.id) === String(divisionId),
  )
  const validScope = !!selectedPeriod && !!selectedDivision
  const processableScope = payrollScopeIsProcessable(
    selectedPeriod,
    selectedDivision,
    visibleDivisions,
  )
  const duplicateBatch = hasMonthlyBatch(batches, periodId, divisionId)
  const searchedBatches = useMemo(() => {
    const queryText = batchSearch.trim().toLowerCase()
    const rows = batches.filter((batch) =>
      String(batch.divisionId) === String(divisionId),
    )
    if (!queryText) return rows
    return rows.filter((batch) =>
      [
        batch.batchId,
        batch.divisionName,
        batch.runType,
        batch.runNo,
        batch.employeeCount,
        batch.status,
      ].some((value) => String(value ?? '').toLowerCase().includes(queryText)),
    )
  }, [batches, batchSearch, divisionId])
  const pageSize = 10
  const totalPages = Math.max(1, Math.ceil(searchedBatches.length / pageSize))
  const pagedBatches = searchedBatches.slice(
    (batchPage - 1) * pageSize,
    batchPage * pageSize,
  )

  useEffect(() => {
    if (batchPage > totalPages) setBatchPage(1) // eslint-disable-line react-hooks/set-state-in-effect
  }, [batchPage, totalPages])

  const workflow = payrollWorkflowSteps({
    period: selectedPeriod,
    division: selectedDivision,
    batch: selected,
    readiness,
    readinessLoading,
    readinessError,
    statementsGenerated,
  })
  const isMaker = selected && selected.makerId === user.userId

  function resetBatchState() {
    setBatches([])
    setBatchesLoaded(false)
    setScopeSummary(null)
    setScopeSummaryError('')
    setSelected(null)
    setSelectedId(null)
    setReadiness(null)
    setReadinessError('')
    setStatementsGenerated(false)
    setStatementError('')
    setTransactionPage(1)
    setTransactionPageData(null)
    setTransactionsError('')
    setComponentDetail(null)
    setApprovalHistory(null)
    setApprovalStatusVisible(false)
    setApprovalError('')
    setFinalizationEligibility(null)
    setFinalizationEligibilityError('')
    setFinalizationPrompt(false)
    setFinalizationConfirmed(false)
    setBatchPage(1)
  }

  function handlePeriodChange(value) {
    setPeriodId(value)
    setWingId('')
    setDivisionId('')
    setActiveStage(1)
    resetBatchState()
  }

  function handleWingChange(value) {
    setWingId(value)
    setDivisionId('')
    setActiveStage(1)
    resetBatchState()
  }

  async function loadBatches() {
    setError('')
    setMessage('')

    if (!selectedPeriod || !selectedDivision) {
      setError('Select an accessible payroll period and division before loading batches.')
      return
    }

    setLoading(true)
    setSummaryLoading(true)
    setScopeSummary(null)
    setScopeSummaryError('')
    setSelected(null)
    setSelectedId(null)
    setReadiness(null)
    setReadinessError('')
    setBatchesLoaded(false)

    const [batchResult, summaryResult] = await Promise.allSettled([
      payrollApi.listBatches(selectedPeriod.id),
      periodApi.scopeSummary(selectedPeriod.id, selectedDivision.id),
    ])

    if (batchResult.status === 'fulfilled') {
      setBatches(batchResult.value)
      setBatchesLoaded(true)
    } else {
      setBatches([])
      handleError(batchResult.reason, setError, onSessionExpired)
    }

    if (summaryResult.status === 'fulfilled') {
      setScopeSummary(summaryResult.value)
    } else {
      if (summaryResult.reason?.status === 401 && onSessionExpired) {
        onSessionExpired()
      }
      setScopeSummaryError(friendlyError(summaryResult.reason))
    }

    setLoading(false)
    setSummaryLoading(false)
  }

  async function loadDetail(batchId) {
    setSelectedId(batchId)
    setSelected(null)
    setReadiness(null)
    setReadinessError('')
    setStatementsGenerated(false)
    setStatementError('')
    setTransactionPage(1)
    setTransactionPageData(null)
    setTransactionsError('')
    setComponentDetail(null)
    setApprovalHistory(null)
    setApprovalStatusVisible(false)
    setApprovalError('')
    setFinalizationEligibility(null)
    setFinalizationEligibilityError('')
    setFinalizationPrompt(false)
    setFinalizationConfirmed(false)
    setReadinessLoading(true)
    setError('')

    const [detailResult, readinessResult] = await Promise.allSettled([
      payrollApi.getBatchSummary(batchId),
      payrollApi.readiness(batchId),
    ])

    if (detailResult.status === 'fulfilled') {
      setSelected(detailResult.value)
      if (
        detailResult.value.status === 'APPROVED'
        && permissions.can('payroll.finalize')
      ) {
        try {
          setFinalizationEligibility(
            await payrollApi.finalizationEligibility(
              batchId,
              detailResult.value.version,
            ),
          )
        } catch (requestError) {
          if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
          setFinalizationEligibilityError(friendlyError(requestError))
        }
      }
    } else {
      handleError(detailResult.reason, setError, onSessionExpired)
    }

    if (readinessResult.status === 'fulfilled') {
      setReadiness(readinessResult.value)
    } else {
      if (readinessResult.reason?.status === 401 && onSessionExpired) {
        onSessionExpired()
      }
      setReadinessError(
        `Readiness could not be loaded: ${friendlyError(readinessResult.reason)}`,
      )
    }

    if (
      detailResult.status === 'fulfilled'
      && detailResult.value.status === 'FINALIZED'
      && canViewStatements
    ) {
      try {
        const statements = await statementApi.list({
          periodId: selectedPeriod?.id,
          divisionId: selectedDivision?.id,
        })
        const firstPage = await payrollApi.listTransactions(
          batchId,
          { page: 1, size: 100 },
        )
        const remainingPages = await Promise.all(
          Array.from(
            { length: Math.max(0, firstPage.totalPages - 1) },
            (_, index) => payrollApi.listTransactions(
              batchId,
              { page: index + 2, size: 100 },
            ),
          ),
        )
        const transactionIds = [
          ...firstPage.items,
          ...remainingPages.flatMap((page) => page.items),
        ].map((transaction) => String(transaction.transactionId))
        setStatementsGenerated(
          transactionIds.length > 0
          && transactionIds.every((transactionId) =>
            statements.some((statement) =>
              String(statement.transactionId) === transactionId
                && statement.status === 'ISSUED',
            ),
          ),
        )
        setStatementError('')
      } catch (requestError) {
        if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
        setStatementError(friendlyError(requestError))
      }
    }

    setReadinessLoading(false)
    if (detailResult.status === 'fulfilled') {
      const next = payrollWorkflowSteps({
        period: selectedPeriod,
        division: selectedDivision,
        batch: detailResult.value,
        readiness: readinessResult.status === 'fulfilled' ? readinessResult.value : null,
        readinessError: readinessResult.status === 'rejected'
          ? friendlyError(readinessResult.reason)
          : '',
      }).find((step) => step.current)
      setActiveStage(next?.number || 4)
    }
  }

  useEffect(() => {
    if (!selectedId) return undefined

    let active = true
    const timer = window.setTimeout(async () => {
      setTransactionsLoading(true)
      setTransactionsError('')

      try {
        const result = await payrollApi.listTransactions(selectedId, {
          page: transactionPage,
          size: 25,
          search: transactionSearch.trim(),
          hrmsId: hrmsIdFilter.trim(),
        })
        if (active) setTransactionPageData(result)
      } catch (requestError) {
        if (active) {
          if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
          setTransactionsError(friendlyError(requestError))
        }
      } finally {
        if (active) setTransactionsLoading(false)
      }
    }, 250)

    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [
    selectedId,
    transactionPage,
    transactionSearch,
    hrmsIdFilter,
    onSessionExpired,
  ])

  async function openComponentDetail(transaction) {
    setComponentDetail(transaction)
    setComponentDetailLoading(true)
    setComponentDetailError('')
    try {
      setComponentDetail(
        await payrollApi.getTransaction(selected.batchId, transaction.transactionId),
      )
    } catch (requestError) {
      handleError(requestError, setComponentDetailError, onSessionExpired)
    } finally {
      setComponentDetailLoading(false)
    }
  }

  async function openApprovalStatus() {
    setApprovalStatusVisible(true)
    if (approvalHistory) return

    setApprovalLoading(true)
    setApprovalError('')
    try {
      setApprovalHistory(await payrollApi.approvals(selected.batchId))
    } catch (requestError) {
      handleError(requestError, setApprovalError, onSessionExpired)
    } finally {
      setApprovalLoading(false)
    }
  }

  async function refreshAfterAction(batchId) {
    await loadBatches()
    await loadDetail(batchId)
  }

  async function runAction(action, batchId) {
    if (busy) return false
    setBusy(true)
    setError('')
    setMessage('')

    try {
      const result = await action()
      setMessage(result?.message || 'Action completed.')
      setReasonAction(null)
      setReason('')
      setFinalizationPrompt(false)
      setFinalizationConfirmed(false)
      await refreshAfterAction(batchId)
      return true
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
      return false
    } finally {
      setBusy(false)
    }
  }

  async function handleGenerate(event) {
    event.preventDefault()
    setError('')
    setMessage('')

    if (!canGenerate) {
      setError('Batch generation requires the payroll.prepare permission.')
      return
    }
    if (!processableScope) {
      setError('Only an active division in an OPEN payroll period can be processed.')
      return
    }
    if (!batchesLoaded) {
      setError('Load batches first so an existing monthly batch can be checked.')
      return
    }
    if (duplicateBatch) {
      setError('A monthly batch already exists for this period and division.')
      return
    }
    if (busy) return

    setBusy(true)

    try {
      const result = await payrollApi.generate(
        Number(selectedPeriod.id),
        Number(selectedDivision.id),
        'MONTHLY',
      )
      setMessage(result.message || 'Monthly payroll batch generated.')
      await loadBatches()
      if (result.batchId) {
        await loadDetail(result.batchId)
      }
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    } finally {
      setBusy(false)
    }
  }

  function canOpenStage(step) {
    if (step === 1) return true
    if (step <= 3) {
      if (step === 3 && selected) return false
      return validScope && selectedPeriod?.status === 'OPEN'
    }
    if (!selected) return false
    if (step <= 5) return true
    if (step === 6) {
      return ['SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'FINALIZED'].includes(selected.status)
    }
    if (step === 7) return ['APPROVED', 'FINALIZED'].includes(selected.status)
    return selected.status === 'FINALIZED'
  }

  function moveToStage(step) {
    if (!canOpenStage(step)) {
      setError(step <= 3
        ? 'Select an authorized period and division first. Payroll processing also requires an OPEN period.'
        : 'Load and open a payroll batch before entering this workflow stage.')
      return
    }
    setError('')
    setActiveStage(step)
  }

  function moveNext() {
    if (activeStage === 1 && (!validScope || selectedPeriod?.status !== 'OPEN')) {
      setError('Select an authorized division in an OPEN period to continue to readiness.')
      return
    }
    if (activeStage === 3 && !selected) {
      setError('Generate or open a batch before continuing to verification.')
      return
    }
    if (activeStage === 4 && workflow[3].state !== 'complete') {
      setError('Verify all transactions before continuing to submission.')
      return
    }
    moveToStage(Math.min(activeStage + 1, 8))
  }

  function renderBatchActions(detail) {
    const status = detail.status
    const actions = [
      <button
        key="batch-list"
        type="button"
        className="module-button ghost"
        disabled={busy}
        onClick={() => {
          setSelected(null)
          setSelectedId(null)
          setTransactionPageData(null)
          setComponentDetail(null)
          setApprovalHistory(null)
          setApprovalStatusVisible(false)
          setFinalizationEligibility(null)
          setFinalizationPrompt(false)
          setActiveStage(1)
        }}
      >
        Back to batch list
      </button>,
    ]

    if (canVerify && ['DRAFT', 'REJECTED', 'REOPENED'].includes(status)) {
      actions.push(
        <button
          key="verify"
          type="button"
          className="module-button ghost"
          disabled={busy || readinessLoading || readinessError !== '' || readiness?.readyToVerify !== true}
          title={readiness?.readyToVerify === true
            ? 'Verify transactions'
            : 'Verification requires confirmed backend readiness.'}
          onClick={() => runAction(() => payrollApi.verify(detail.batchId), detail.batchId)}
        >
          Verify transactions
        </button>,
      )
    }

    if (canSubmit && ['DRAFT', 'REJECTED', 'REOPENED'].includes(status)) {
      actions.push(
        <button
          key="submit"
          type="button"
          className="module-button"
          disabled={busy || readinessLoading || readinessError !== '' || readiness?.readyToSubmit !== true}
          title={readiness?.readyToSubmit === true
            ? 'Submit the verified batch for approval'
            : 'Submission requires confirmed backend readiness and verified transactions.'}
          onClick={() => runAction(() => payrollApi.submit(detail.batchId), detail.batchId)}
        >
          Submit for approval
        </button>,
      )
    }

    if (canReopen && status === 'FINALIZED') {
      actions.push(
        <button
          key="reopen"
          type="button"
          className="module-button danger"
          disabled={busy}
          onClick={() => {
            setReasonAction({ type: 'reopen', batchId: detail.batchId })
            setReason('')
          }}
        >
          Reopen for correction
        </button>,
      )
    }

    if (
      canFinalizePayrollBatch({
        batch: detail,
        eligibility: finalizationEligibility,
        canFinalize,
        userId: user.userId,
      })
    ) {
      actions.push(
        <button
          key="finalize"
          type="button"
          className="module-button"
          disabled={busy}
          onClick={() => {
            setFinalizationPrompt(true)
            setFinalizationConfirmed(false)
          }}
        >
          Finalize &amp; lock
        </button>,
      )
    }

    if (
      isMaker
      && ['SUBMITTED', 'UNDER_REVIEW'].includes(status)
    ) {
      actions.push(
        <button
          key="approval-status"
          type="button"
          className="module-button ghost"
          disabled={approvalLoading}
          onClick={() => {
            if (approvalStatusVisible) {
              setApprovalStatusVisible(false)
            } else {
              openApprovalStatus()
            }
          }}
        >
          {approvalStatusVisible ? 'Hide Approval Status' : 'View Approval Status'}
        </button>,
      )
    }

    if (canStatement && status === 'FINALIZED' && !statementsGenerated) {
      actions.push(
        <button
          key="statements"
          type="button"
          className="module-button ghost"
          disabled={busy}
          onClick={async () => {
            const generated = await runAction(
              () => statementApi.generateForBatch(detail.batchId),
              detail.batchId,
            )
            if (generated) setStatementsGenerated(true)
          }}
        >
          Generate statements
        </button>,
      )
    }

    return actions.length > 0 ? <div className="detail-actions">{actions}</div> : null
  }

  function renderSummary() {
    if (summaryLoading) return <p className="muted-text">Loading authorized scope summary…</p>
    if (scopeSummaryError) {
      return <Alert kind="error">Scope summary could not be loaded: {scopeSummaryError}</Alert>
    }
    if (!scopeSummary) {
      return <p className="muted-text">Load batches to retrieve verified employee and batch summary data.</p>
    }

    return (
      <div className="payroll-summary-grid">
        <div className="payroll-summary-card">
          <span>Total employees</span>
          <strong>{scopeSummary.totalEmployees}</strong>
          <small>In the selected authorized division</small>
        </div>
        <div className="payroll-summary-card">
          <span>Active and eligible</span>
          <strong>{scopeSummary.activeEligibleEmployees}</strong>
          <small>Active with effective salary values for this period</small>
        </div>
        <div className="payroll-summary-card">
          <span>Inactive or excluded</span>
          <strong>{scopeSummary.inactiveOrExcludedEmployees}</strong>
          <small>Not eligible under current payroll generation rules</small>
        </div>
        <div className="payroll-summary-card">
          <span>Most recent batch</span>
          <strong>
            {scopeSummary.lastBatchId
              ? `#${scopeSummary.lastBatchId}`
              : 'No batch'}
          </strong>
          <small>
            {scopeSummary.lastBatchId
              ? `${scopeSummary.lastBatchRunType} · ${payrollBatchStatusLabel(scopeSummary.lastBatchStatus)}`
              : 'No batch has been created for this period and division'}
          </small>
        </div>
      </div>
    )
  }

  function renderSelectedBatch() {
    if (!selected) {
      return (
        <div className="payroll-stage-empty">
          <strong>No payroll batch selected</strong>
          <p>Load the batches for the selected period and division, then open a batch to continue.</p>
        </div>
      )
    }

    let nextAction = 'Review the batch status and readiness.'
    if (['DRAFT', 'REJECTED', 'REOPENED'].includes(selected.status)) {
      if (canVerify && readiness?.readyToVerify === true) {
        nextAction = 'Verify transactions'
      } else if (canSubmit && readiness?.readyToSubmit === true) {
        nextAction = 'Submit for approval'
      } else {
        nextAction = 'Resolve readiness items or ask an authorized preparer to continue.'
      }
    } else if (['SUBMITTED', 'UNDER_REVIEW'].includes(selected.status)) {
      nextAction = isMaker
        ? 'View Approval Status'
        : permissions.can('payroll.approve')
          ? 'Review in Approval Inbox'
          : 'Awaiting an independent authorized reviewer.'
    } else if (selected.status === 'APPROVED') {
      nextAction = finalizationEligibility?.eligible
        ? 'Finalize & Lock'
        : finalizationEligibility?.reason
          || finalizationEligibilityError
          || (canFinalize
            ? 'Checking finalization eligibility with the backend.'
            : 'Awaiting an authorized finalizer.')
    } else if (selected.status === 'FINALIZED') {
      nextAction = canViewStatements
        ? 'View or generate salary statements.'
        : 'Batch finalized and locked.'
    }

    const transactionItems = transactionPageData?.items || []
    const transactionTotal = transactionPageData?.totalElements || 0
    const transactionTotalPages = transactionPageData?.totalPages || 0

    return (
      <div className="payroll-batch-detail">
        <div className="section-heading">
          <div>
            <h4>Batch #{selected.batchId} · {selected.divisionName || selected.divisionId}</h4>
            <p>
              {selected.periodLabel} · {selected.runType} #{selected.runNo} ·{' '}
              <StatusChip status={selected.status} />
            </p>
          </div>
          {renderBatchActions(selected)}
        </div>

        <section className="payroll-batch-context" aria-label="Read-only batch context">
          <div><span>Period</span><strong>{selected.periodLabel || '—'}</strong></div>
          <div><span>Wing</span><strong>{selected.wingName || '—'}</strong></div>
          <div><span>Division</span><strong>{selected.divisionName || selected.divisionId}</strong></div>
          <div><span>Current status</span><strong><StatusChip status={selected.status} /></strong></div>
          <div><span>Submitted</span><strong>{formatDate(selected.submittedAt)}</strong></div>
          <div>
            <span>Maker</span>
            <strong>
              {selected.makerName
                || (selected.makerId === user.userId ? 'You' : selected.makerId ? `User ${selected.makerId}` : '—')}
            </strong>
          </div>
          <div>
            <span>Approver assignment</span>
            <strong>
              {selected.checkerName
                || (selected.checkerId
                  ? `User ${selected.checkerId}`
                  : 'None recorded; review follows backend permission and division scope')}
            </strong>
          </div>
          <div className="payroll-next-action">
            <span>Next permitted action</span>
            <strong>{nextAction}</strong>
          </div>
        </section>

        {isMaker && ['SUBMITTED', 'UNDER_REVIEW'].includes(selected.status) && (
          <p className="muted-text">
            You created this batch. The backend enforces maker-checker separation; you cannot approve or finalize it.
          </p>
        )}

        {readinessLoading && <p className="muted-text">Loading payroll readiness…</p>}
        {readinessError && (
          <Alert kind="error">
            {readinessError} Verification and submission remain unavailable until readiness is confirmed.
          </Alert>
        )}

        {readiness && (
          <div className="payroll-readiness">
            <strong>
              {readiness.verifiedTransactions}/{readiness.totalTransactions} transactions verified
            </strong>
            {readiness.issues?.length > 0 && (
              <ul>
                {readiness.issues.map((issue) => <li key={issue}>{issue}</li>)}
              </ul>
            )}
          </div>
        )}

        {finalizationEligibilityError && selected.status === 'APPROVED' && (
          <Alert kind="error">
            Finalization eligibility could not be confirmed: {finalizationEligibilityError}
          </Alert>
        )}
        {finalizationEligibility?.eligible === false && selected.status === 'APPROVED' && (
          <p className="muted-text">{finalizationEligibility.reason}</p>
        )}

        {reasonAction?.batchId === selected.batchId && (
          <form
            className="reason-form"
            onSubmit={(event) => {
              event.preventDefault()
              runAction(
                () => payrollApi.reopen(selected.batchId, reason),
                selected.batchId,
              )
            }}
          >
            <label htmlFor="reason-input">Correction reason (required)</label>
            <textarea
              id="reason-input"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={2}
              required
              disabled={busy}
            />
            <div className="detail-actions">
              <button className="module-button" type="submit" disabled={busy || !reason.trim()}>
                Confirm reopen
              </button>
              <button
                type="button"
                className="module-button ghost"
                disabled={busy}
                onClick={() => {
                  setReasonAction(null)
                  setReason('')
                }}
              >
                Cancel
              </button>
            </div>
          </form>
        )}

        {finalizationPrompt && selected.status === 'APPROVED' && (
          <form
            className="reason-form payroll-finalize-confirmation"
            onSubmit={(event) => {
              event.preventDefault()
              runAction(
                () => payrollApi.finalize(selected.batchId, selected.version, true),
                selected.batchId,
              )
            }}
          >
            <strong>Confirm finalization</strong>
            <p>Finalization locks this approved payroll batch. Confirm that you reviewed the current batch version.</p>
            <label>
              <input
                type="checkbox"
                checked={finalizationConfirmed}
                onChange={(event) => setFinalizationConfirmed(event.target.checked)}
                disabled={busy}
              />
              I confirm finalization of batch #{selected.batchId}, version {selected.version}.
            </label>
            <div className="detail-actions">
              <button
                className="module-button"
                type="submit"
                disabled={
                  busy
                  || !finalizationConfirmed
                  || !canFinalizePayrollBatch({
                    batch: selected,
                    eligibility: finalizationEligibility,
                    canFinalize,
                    userId: user.userId,
                  })
                }
              >
                Confirm &amp; finalize
              </button>
              <button
                type="button"
                className="module-button ghost"
                disabled={busy}
                onClick={() => {
                  setFinalizationPrompt(false)
                  setFinalizationConfirmed(false)
                }}
              >
                Cancel
              </button>
            </div>
          </form>
        )}

        {approvalStatusVisible && (
          <section className="payroll-approval-history" aria-label="Approval status">
            <h5>Approval status</h5>
            {approvalLoading ? (
              <p className="muted-text">Loading approval history…</p>
            ) : approvalError ? (
              <Alert kind="error">Approval status could not be loaded: {approvalError}</Alert>
            ) : approvalHistory?.length ? (
              <div className="table-wrap payroll-table-wrap">
                <table className="data-table">
                  <thead>
                    <tr><th>Action</th><th>Status change</th><th>Approver</th><th>When</th><th>Remarks</th></tr>
                  </thead>
                  <tbody>
                    {approvalHistory.map((entry) => (
                      <tr key={entry.id}>
                        <td>{entry.action}</td>
                        <td>{payrollBatchStatusLabel(entry.fromStatus)} → {payrollBatchStatusLabel(entry.toStatus)}</td>
                        <td>{entry.actedByName || `User ${entry.actedBy}`}</td>
                        <td>{formatDate(entry.actedAt)}</td>
                        <td>{entry.remarks || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="muted-text">No approval decisions have been recorded for this batch.</p>
            )}
          </section>
        )}

        <div className="totals-row">
          <div><span>Gross salary</span><strong>{formatAmount(selected.grossTotal)}</strong></div>
          <div><span>Total deductions</span><strong>{formatAmount(selected.deductionTotal)}</strong></div>
          <div><span>Net salary</span><strong>{formatAmount(selected.netTotal)}</strong></div>
          <div><span>Employees</span><strong>{selected.employeeCount}</strong></div>
        </div>

        <div className="payroll-transaction-toolbar">
          <label className="form-field" htmlFor="transaction-search">
            <span>Employee search</span>
            <input
              id="transaction-search"
              type="search"
              value={transactionSearch}
              onChange={(event) => {
                setTransactionSearch(event.target.value)
                setTransactionPage(1)
              }}
              placeholder="Name or employee code"
            />
          </label>
          <label className="form-field" htmlFor="transaction-hrms-filter">
            <span>HRMS ID</span>
            <input
              id="transaction-hrms-filter"
              type="search"
              value={hrmsIdFilter}
              onChange={(event) => {
                setHrmsIdFilter(event.target.value)
                setTransactionPage(1)
              }}
              placeholder="Filter by HRMS ID"
            />
          </label>
          <span className="inline-meta">
            {transactionTotal} employee transaction{transactionTotal === 1 ? '' : 's'}
          </span>
        </div>

        {transactionsError && <Alert kind="error">{transactionsError}</Alert>}
        <div className="table-wrap payroll-table-wrap payroll-transaction-table-wrap">
          <table className="data-table payroll-transaction-table">
            <thead>
              <tr>
                <th>Employee</th><th>HRMS ID</th><th>Status</th>
                <th>Gross salary</th><th>Total deductions</th><th>Net salary</th><th>Details</th>
              </tr>
            </thead>
            <tbody>
              {transactionItems.map((transaction) => (
                <tr key={transaction.transactionId}>
                  <td>{transaction.name} <span className="inline-meta">· {transaction.employeeCode}</span></td>
                  <td>{transaction.hrmsId || '—'}</td>
                  <td><StatusChip status={transaction.status} /></td>
                  <td className="amount">{formatAmount(transaction.gross)}</td>
                  <td className="amount">{formatAmount(transaction.deductions)}</td>
                  <td className="amount">{formatAmount(transaction.net)}</td>
                  <td>
                    <button
                      type="button"
                      className="module-button ghost"
                      onClick={() => openComponentDetail(transaction)}
                    >
                      Components
                    </button>
                  </td>
                </tr>
              ))}
              {!transactionsLoading && transactionItems.length === 0 && (
                <tr><td colSpan={7}>No employee transactions match these filters.</td></tr>
              )}
            </tbody>
          </table>
        </div>
        {transactionsLoading && <p className="muted-text">Loading employee transactions…</p>}
        {transactionTotalPages > 1 && (
          <nav className="payroll-pagination" aria-label="Employee transaction pages">
            <span>Page {transactionPage} of {transactionTotalPages}</span>
            <div>
              <button
                type="button"
                className="module-button ghost"
                disabled={transactionsLoading || transactionPage <= 1}
                onClick={() => setTransactionPage((page) => page - 1)}
              >
                Previous
              </button>
              <button
                type="button"
                className="module-button ghost"
                disabled={transactionsLoading || transactionPage >= transactionTotalPages}
                onClick={() => setTransactionPage((page) => page + 1)}
              >
                Next
              </button>
            </div>
          </nav>
        )}

        {componentDetail && (
          <div className="payroll-drawer-backdrop">
            <button
              type="button"
              className="payroll-drawer-scrim"
              aria-label="Close salary component details"
              onClick={() => setComponentDetail(null)}
            />
            <aside className="payroll-component-drawer" role="dialog" aria-modal="true" aria-labelledby="component-drawer-title">
              <div className="payroll-drawer-header">
                <div>
                  <span>Salary component detail</span>
                  <h4 id="component-drawer-title">{componentDetail.name}</h4>
                  <p>{componentDetail.employeeCode} · HRMS {componentDetail.hrmsId || '—'}</p>
                </div>
                <button
                  type="button"
                  className="module-button ghost"
                  onClick={() => setComponentDetail(null)}
                >
                  Close
                </button>
              </div>
              {componentDetailLoading ? (
                <p className="muted-text">Loading salary components…</p>
              ) : componentDetailError ? (
                <Alert kind="error">{componentDetailError}</Alert>
              ) : (
                <>
                  <div className="totals-row">
                    <div><span>Gross salary</span><strong>{formatAmount(componentDetail.gross)}</strong></div>
                    <div><span>Total deductions</span><strong>{formatAmount(componentDetail.deductions)}</strong></div>
                    <div><span>Net salary</span><strong>{formatAmount(componentDetail.net)}</strong></div>
                  </div>
                  <div className="table-wrap payroll-table-wrap">
                    <table className="data-table">
                      <thead><tr><th>Component</th><th>Category</th><th>Amount</th></tr></thead>
                      <tbody>
                        {(componentDetail.lines || []).map((line) => (
                          <tr key={`${line.componentId}-${line.category}`}>
                            <td>{line.componentName}</td>
                            <td>{line.category}</td>
                            <td className="amount">{formatAmount(line.amount)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              )}
            </aside>
          </div>
        )}
      </div>
    )
  }

  function renderStage() {
    if (activeStage === 1) {
      return (
        <>
          {renderSummary()}
          <div className="payroll-batch-toolbar">
            <label className="form-field payroll-search-field" htmlFor="batch-search">
              <span>Search batches</span>
              <input
                id="batch-search"
                type="search"
                value={batchSearch}
                placeholder="Batch number, division, run type or status"
                onChange={(event) => {
                  setBatchSearch(event.target.value)
                  setBatchPage(1)
                }}
                disabled={!batchesLoaded}
              />
            </label>
            {batchesLoaded && (
              <span className="inline-meta">
                {searchedBatches.length} batch{searchedBatches.length === 1 ? '' : 'es'} in this division
              </span>
            )}
          </div>

          {loading ? (
            <p className="muted-text">Loading batches…</p>
          ) : !batchesLoaded ? (
            <div className="payroll-stage-empty">
              <strong>Batches have not been loaded</strong>
              <p>Select the authorized period and scope, then choose Load Batches.</p>
            </div>
          ) : searchedBatches.length === 0 ? (
            <div className="payroll-stage-empty">
              <strong>{batches.length ? 'No matching batches' : 'No payroll batches in this scope'}</strong>
              <p>
                {batches.length
                  ? 'Clear or adjust the search to see batches.'
                  : 'If this period is OPEN and the division is active, continue to Generate Salary.'}
              </p>
            </div>
          ) : (
            <>
              <div className="table-wrap payroll-table-wrap">
                <table className="data-table payroll-batch-table">
                  <thead>
                    <tr>
                      <th>Batch number</th><th>Division</th><th>Run type</th>
                      <th>Employees</th><th>Gross salary</th><th>Deductions</th>
                      <th>Net salary</th><th>Status</th><th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pagedBatches.map((batch) => (
                      <tr
                        key={batch.batchId}
                        className={selectedId === batch.batchId ? 'selected' : ''}
                        onClick={() => loadDetail(batch.batchId)}
                      >
                        <td>#{batch.batchId}</td>
                        <td>{batch.divisionName || batch.divisionId}</td>
                        <td>{batch.runType}</td>
                        <td>{batch.employeeCount}</td>
                        <td className="amount">{formatAmount(batch.grossTotal)}</td>
                        <td className="amount">{formatAmount(batch.deductionTotal)}</td>
                        <td className="amount">{formatAmount(batch.netTotal)}</td>
                        <td><StatusChip status={batch.status} /></td>
                        <td>
                          <button
                            type="button"
                            className="module-button ghost"
                            onClick={(event) => {
                              event.stopPropagation()
                              loadDetail(batch.batchId)
                            }}
                          >
                            Open
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {totalPages > 1 && (
                <nav className="payroll-pagination" aria-label="Payroll batch pages">
                  <span>Page {batchPage} of {totalPages}</span>
                  <div>
                    <button
                      type="button"
                      className="module-button ghost"
                      disabled={batchPage <= 1}
                      onClick={() => setBatchPage((page) => page - 1)}
                    >
                      Previous
                    </button>
                    <button
                      type="button"
                      className="module-button ghost"
                      disabled={batchPage >= totalPages}
                      onClick={() => setBatchPage((page) => page + 1)}
                    >
                      Next
                    </button>
                  </div>
                </nav>
              )}
            </>
          )}
        </>
      )
    }

    if (activeStage === 2) {
      return (
        <div className="payroll-stage-content">
          <h4>Readiness check</h4>
          {renderSummary()}
          {selected ? renderSelectedBatch() : (
            <p className="muted-text">
              The backend computes transaction readiness after a batch is generated or opened. Scope counts above use the selected period's effective salary date.
            </p>
          )}
        </div>
      )
    }

    if (activeStage === 3) {
      return (
        <div className="payroll-stage-content">
          <h4>Generate salary</h4>
          {selected ? (
            <p className="muted-text">
              A batch is selected for review. Return to the batch list and clear the selection before starting a separate generation workflow.
            </p>
          ) : duplicateBatch ? (
            <Alert kind="error">
              A monthly batch already exists for this period and division. Open the existing batch instead of generating a duplicate.
            </Alert>
          ) : !processableScope ? (
            <Alert kind="error">
              Generation is available only for an active division in an OPEN period.
            </Alert>
          ) : !batchesLoaded ? (
            <p className="muted-text">Load batches first to verify that this period and division do not already have a monthly batch.</p>
          ) : !canGenerate ? (
            <p className="muted-text">Batch generation requires payroll.prepare.</p>
          ) : (
            <form className="payroll-generate-form" onSubmit={handleGenerate}>
              <div>
                <strong>{selectedPeriod?.label} · {selectedDivision?.name}</strong>
                <span>Monthly run · period status {selectedPeriod?.status}</span>
              </div>
              <button className="module-button" type="submit" disabled={busy || duplicateBatch || !batchesLoaded}>
                {busy ? 'Generating…' : 'Generate monthly salary batch'}
              </button>
            </form>
          )}
        </div>
      )
    }

    if (activeStage === 4) {
      return (
        <div className="payroll-stage-content">
          <h4>Verify &amp; review</h4>
          {renderSelectedBatch()}
        </div>
      )
    }

    if (activeStage === 5) {
      return (
        <div className="payroll-stage-content">
          <h4>Submit for approval</h4>
          {renderSelectedBatch()}
        </div>
      )
    }

    if (activeStage === 6) {
      return (
        <div className="payroll-stage-content">
          <h4>Approval</h4>
          {renderSelectedBatch()}
          {selected && ['SUBMITTED', 'UNDER_REVIEW'].includes(selected.status) && (
            <p className="muted-text">
              Approval is handled in the Approval Inbox; this workspace does not approve or reject payroll.
            </p>
          )}
          {permissions.can('payroll.approve') && onNavigate && (
            <button type="button" className="module-button ghost" onClick={() => onNavigate('approvals')}>
              Open Approval Inbox
            </button>
          )}
        </div>
      )
    }

    if (activeStage === 7) {
      return (
        <div className="payroll-stage-content">
          <h4>Finalize &amp; lock</h4>
          {renderSelectedBatch()}
          {selected?.status === 'APPROVED' && !finalizationEligibility?.eligible && (
            <p className="muted-text">
              Finalization is available only when the backend confirms approval, an unchanged batch version, readiness, and your permission to finalize.
            </p>
          )}
        </div>
      )
    }

    return (
      <div className="payroll-stage-content">
        <h4>Salary statements</h4>
        {renderSelectedBatch()}
        {selected?.status !== 'FINALIZED' ? (
          <p className="muted-text">Statements are available after the batch reaches FINALIZED.</p>
          ) : statementError ? (
            <Alert kind="error">Issued statement status could not be loaded: {statementError}</Alert>
          ) : statementsGenerated ? (
            <p className="muted-text">Issued salary statements are available for this batch.</p>
          ) : canStatement ? (
          <p className="muted-text">Generate statements for this finalized batch from the batch detail above.</p>
        ) : (
          <p className="muted-text">Statement generation requires statement.generate or payroll.finalize.</p>
        )}
      </div>
    )
  }

  const periodStatus = selectedPeriod?.status
  const nextStage = activeStage + 1

  return (
    <div className="module-body payroll-workspace">
      <div className="payroll-workspace-heading">
        <span className={`payroll-period-pill ${periodStatus?.toLowerCase() || 'unselected'}`}>
          Period: {selectedPeriod?.label || 'No period selected'}
        </span>
      </div>

      <div className="payroll-context-grid">
        <div><span>Organization</span><strong>{selectedPeriod?.organizationName || 'Select a payroll period'}</strong></div>
        <div><span>Role</span><strong>{primaryRoleLabel(user)}</strong></div>
        <div>
          <span>Authorized scope</span>
          <strong>
            {selectedDivision
              ? `${selectedDivision.wingName || 'Wing'} · ${selectedDivision.name}`
              : `${organizationDivisions.length} accessible division${organizationDivisions.length === 1 ? '' : 's'}`}
          </strong>
        </div>
      </div>

      <ol className="payroll-workflow-stepper" aria-label="Payroll workflow">
        {workflow.map((step) => (
          <li key={step.number} className={`payroll-workflow-step ${step.state}${activeStage === step.number ? ' active' : ''}`}>
            <button
              type="button"
              onClick={() => moveToStage(step.number)}
              disabled={!canOpenStage(step.number)}
              aria-current={activeStage === step.number ? 'step' : undefined}
              title={canOpenStage(step.number) ? step.title : 'Complete the previous required selection first'}
            >
              <span className="payroll-step-number">{step.number}</span>
              <span className="payroll-step-label">{step.title}</span>
              <small>{step.state}</small>
            </button>
          </li>
        ))}
      </ol>

      <section className="payroll-selection-card" aria-labelledby="payroll-scope-title">
        <div className="payroll-card-heading">
          <div>
            <h3 id="payroll-scope-title">Period &amp; organizational scope</h3>
            <p>Select an existing period and a division within your authorized scope.</p>
          </div>
          <StatusChip status={periodStatus || 'UNSELECTED'} />
        </div>

        {error && <Alert kind="error">{error}</Alert>}
        {message && <Alert kind="success">{message}</Alert>}
        {referenceLoading ? (
          <p className="muted-text">Loading accessible periods and divisions…</p>
        ) : (
          <>
            <div className="payroll-selector-grid">
              <label className="form-field" htmlFor="payroll-period-select">
                <span>Payroll period</span>
                <select
                  id="payroll-period-select"
                  value={periodId}
                  onChange={(event) => handlePeriodChange(event.target.value)}
                  disabled={referenceLoading || !!selected}
                >
                  <option value="">Select an existing period</option>
                  {periods.map((period) => (
                    <option key={period.id} value={period.id}>
                      {period.label} · {period.organizationName} · {period.status}
                    </option>
                  ))}
                </select>
              </label>

              {wingOptions.length > 0 && (
                <label className="form-field" htmlFor="payroll-wing-select">
                  <span>Wing</span>
                  <select
                    id="payroll-wing-select"
                    value={wingId}
                    onChange={(event) => handleWingChange(event.target.value)}
                    disabled={!selectedPeriod || !!selected}
                  >
                    <option value="">All authorized wings</option>
                    {wingOptions.map((wing) => (
                      <option key={wing.value} value={wing.value}>{wing.label}</option>
                    ))}
                  </select>
                </label>
              )}

              <label className="form-field" htmlFor="payroll-division-select">
                <span>Division</span>
                <select
                  id="payroll-division-select"
                  value={divisionId}
                  onChange={(event) => {
                    setDivisionId(event.target.value)
                    resetBatchState()
                    setActiveStage(1)
                  }}
                  disabled={!selectedPeriod || visibleDivisions.length === 0 || !!selected}
                >
                  <option value="">Select an authorized division</option>
                  {visibleDivisions.map((division) => (
                    <option key={division.id} value={division.id}>
                      {division.name}{division.status !== 'ACTIVE' ? ` · ${division.status}` : ''}
                    </option>
                  ))}
                </select>
              </label>

              <button
                type="button"
                className="module-button payroll-load-button"
                onClick={loadBatches}
                disabled={loading || referenceLoading || !validScope || !!selected}
                title={!validScope ? 'Select a period and authorized division first' : 'Load batches and the selected scope summary'}
              >
                {loading ? 'Loading…' : selected ? 'Batch selected' : 'Load Batches'}
              </button>
            </div>

            {!canLoadScope && (
              <p className="muted-text">A division-scoped payroll selector is unavailable for this account.</p>
            )}
            {selectedPeriod && (
              <div className="payroll-period-details">
                <div><span>Period</span><strong>{selectedPeriod.label}</strong></div>
                <div><span>Start date</span><strong>{selectedPeriod.startDate}</strong></div>
                <div><span>End date</span><strong>{selectedPeriod.endDate}</strong></div>
                <div><span>Period status</span><strong>{payrollBatchStatusLabel(periodStatus)}</strong></div>
                {selectedPeriod.description && (
                  <div className="payroll-period-description">
                    <span>Description</span><strong>{selectedPeriod.description}</strong>
                  </div>
                )}
              </div>
            )}
            {selectedPeriod && selectedDivision && selectedPeriod.status !== 'OPEN' && (
              <p className="payroll-blocked-note">
                This period is {selectedPeriod.status.toLowerCase()}. Existing batches remain viewable, but processing and generation are disabled.
              </p>
            )}
          </>
        )}
      </section>

      <section className="payroll-stage-card" aria-labelledby="payroll-stage-title">
        <div className="payroll-stage-card-heading">
          <div>
            <span>STEP {activeStage} OF 8</span>
            <h3 id="payroll-stage-title">{workflow[activeStage - 1]?.title}</h3>
          </div>
          {activeStage === 3 && duplicateBatch && (
            <StatusChip status="DUPLICATE" />
          )}
        </div>

        {renderStage()}

        <div className="payroll-stage-navigation">
          <button
            type="button"
            className="module-button ghost"
            onClick={() => moveToStage(Math.max(1, activeStage - 1))}
            disabled={activeStage <= 1}
          >
            Back
          </button>
          {activeStage < 8 && (
            <button
              type="button"
              className="module-button"
              onClick={moveNext}
              disabled={!canOpenStage(nextStage) || (activeStage === 4 && workflow[3].state !== 'complete')}
              title={!canOpenStage(nextStage)
                ? 'Complete the required previous step first'
                : 'Continue to the next payroll workflow stage'}
            >
              Next: {workflow[nextStage - 1]?.title}
            </button>
          )}
        </div>
      </section>
    </div>
  )
}

function StatementsPanel({ permissions, onSessionExpired }) {
  const [statements, setStatements] = useState([])
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  const canDownload = permissions.can('statement.download')
  const canReissue = permissions.can('statement.reissue')

  const load = useCallback(async () => {
    setLoading(true)

    try {
      setStatements(await statementApi.list())
      setError('')
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    } finally {
      setLoading(false)
    }
  }, [onSessionExpired])

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    load()
  }, [load])
  /* eslint-enable react-hooks/set-state-in-effect */

  async function openStatement(id) {
    setError('')

    try {
      setDetail(await statementApi.get(id))
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    }
  }

  async function handleDownload(statement) {
    setError('')

    try {
      const blob = await statementApi.download(statement.id)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')

      anchor.href = url
      anchor.download = `${statement.statementNo}.pdf`
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (requestError) {
      setError(friendlyError(requestError))
    }
  }

  async function handleReissue(statement) {
    setError('')
    setMessage('')

    try {
      const reissued = await statementApi.reissue(statement.id)

      setMessage(`Reissued ${reissued.statementNo} (version ${reissued.version}).`)
      await load()
      await openStatement(reissued.id)
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    }
  }

  return (
    <div className="module-body">
      <Alert kind="error">{error}</Alert>
      <Alert kind="success">{message}</Alert>

      {loading ? (
        <p className="muted-text">Loading statements…</p>
      ) : statements.length === 0 ? (
        <p className="muted-text">No salary statements are available yet.</p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Statement</th>
                <th>Employee</th>
                <th>Period</th>
                <th>Version</th>
                <th>Status</th>
                <th>Generated</th>
              </tr>
            </thead>
            <tbody>
              {statements.map((statement) => (
                <tr
                  key={statement.id}
                  className={detail?.statement?.id === statement.id ? 'selected' : ''}
                  onClick={() => openStatement(statement.id)}
                >
                  <td>{statement.statementNo}</td>
                  <td>
                    {statement.employeeCode} · {statement.employeeName}
                  </td>
                  <td>{statement.periodLabel}</td>
                  <td>
                    v{statement.version}
                    {statement.status === 'ISSUED' && (
                      <span className="current-tag"> current</span>
                    )}
                  </td>
                  <td>
                    <StatusChip status={statement.status} />
                  </td>
                  <td>{formatDate(statement.generatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {detail && (
        <div className="statement-detail">
          <div className="section-heading">
            <div>
              <h4>{detail.statement.statementNo}</h4>
              <p>
                {detail.payload?.employee?.code} · {detail.payload?.employee?.name} ·{' '}
                {detail.payload?.period?.label} · v{detail.statement.version}{' '}
                <StatusChip status={detail.statement.status} />
              </p>
            </div>

            <div className="detail-actions">
              {canDownload && (
                <button
                  type="button"
                  className="module-button"
                  onClick={() => handleDownload(detail.statement)}
                >
                  Download PDF
                </button>
              )}

              {canReissue && detail.statement.status === 'ISSUED' && (
                <button
                  type="button"
                  className="module-button ghost"
                  onClick={() => handleReissue(detail.statement)}
                >
                  Reissue
                </button>
              )}
            </div>
          </div>

          <StatementLines payload={detail.payload} />

          <p className="muted-text">
            Payload {detail.statement.payloadSha256?.slice(0, 12)}… · File{' '}
            {detail.statement.fileSha256?.slice(0, 12)}… ·{' '}
            {detail.statement.fileSizeBytes} bytes
          </p>
        </div>
      )}
    </div>
  )
}

function StatementLines({ payload }) {
  const earnings = payload?.earnings || []
  const deductions = payload?.deductions || []
  const totals = payload?.totals || {}

  return (
    <>
      <div className="statement-columns">
        <div className="line-table">
          <h5>Earnings</h5>
          <table className="data-table compact">
            <tbody>
              {earnings.map((line) => (
                <tr key={line.code}>
                  <td>{line.name}</td>
                  <td className="amount">{formatAmount(line.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="line-table">
          <h5>Deductions</h5>
          <table className="data-table compact">
            <tbody>
              {deductions.map((line) => (
                <tr key={line.code}>
                  <td>{line.name}</td>
                  <td className="amount">{formatAmount(line.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="totals-row">
        <div>
          <span>Gross</span>
          <strong>{formatAmount(totals.gross)}</strong>
        </div>
        <div>
          <span>Deductions</span>
          <strong>{formatAmount(totals.deductions)}</strong>
        </div>
        <div>
          <span>Net salary</span>
          <strong>{formatAmount(totals.net)}</strong>
        </div>
      </div>
    </>
  )
}

function ReportsPanel({ permissions, onSessionExpired }) {
  const [periods, setPeriods] = useState([])
  const [periodId, setPeriodId] = useState('')
  const [summary, setSummary] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [employees, setEmployees] = useState([])
  const [employeeId, setEmployeeId] = useState('')
  const [history, setHistory] = useState(null)

  const canExport = permissions.can('export.download')
  const canListEmployees = permissions.can('employee.view')

  const loadPeriods = useCallback(async () => {
    try {
      setPeriods(await periodApi.list())
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    }
  }, [onSessionExpired])

  const loadSummary = useCallback(async () => {
    setLoading(true)

    try {
      setSummary(await reportApi.divisionSummary(periodId || undefined))
      setError('')
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    } finally {
      setLoading(false)
    }
  }, [periodId, onSessionExpired])

  const loadEmployees = useCallback(async () => {
    if (!canListEmployees) return

    try {
      setEmployees(await employeeApi.list())
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    }
  }, [canListEmployees, onSessionExpired])

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    loadPeriods()
    loadEmployees()
  }, [loadPeriods, loadEmployees])

  useEffect(() => {
    loadSummary()
  }, [loadSummary])
  /* eslint-enable react-hooks/set-state-in-effect */

  const totals = summary.reduce(
    (accumulator, row) => ({
      employees: accumulator.employees + row.employeeCount,
      gross: accumulator.gross + Number(row.gross),
      deductions: accumulator.deductions + Number(row.deductions),
      net: accumulator.net + Number(row.net),
    }),
    { employees: 0, gross: 0, deductions: 0, net: 0 },
  )

  async function handleCsv() {
    setError('')

    try {
      const blob = await reportApi.downloadDivisionSummary(periodId || undefined)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')

      anchor.href = url
      anchor.download = 'division-summary.csv'
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (requestError) {
      setError(friendlyError(requestError))
    }
  }

  async function loadHistory(event) {
    event.preventDefault()
    setError('')
    setHistory(null)

    if (!employeeId) {
      setError('Select an employee to view salary history.')
      return
    }

    try {
      setHistory(await reportApi.employeeHistory(Number(employeeId)))
    } catch (requestError) {
      handleError(requestError, setError, onSessionExpired)
    }
  }

  return (
    <div className="module-body">
      <Alert kind="error">{error}</Alert>

      <form className="inline-form" onSubmit={(event) => event.preventDefault()}>
        <div className="form-field">
          <label htmlFor="report-period">Period</label>
          <select
            id="report-period"
            value={periodId}
            onChange={(event) => setPeriodId(event.target.value)}
          >
            <option value="">All finalized periods</option>
            {periods.map((period) => (
              <option key={period.id} value={period.id}>
                {period.label}
              </option>
            ))}
          </select>
        </div>

        {canExport && (
          <button type="button" className="module-button ghost" onClick={handleCsv}>
            Download CSV
          </button>
        )}
      </form>

      {loading ? (
        <p className="muted-text">Loading report…</p>
      ) : summary.length === 0 ? (
        <p className="muted-text">No finalized payroll is available for this scope.</p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Wing</th>
                <th>Division</th>
                <th>Employees</th>
                <th>Gross earnings</th>
                <th>Total deductions</th>
                <th>Net salary</th>
              </tr>
            </thead>
            <tbody>
              {summary.map((row) => (
                <tr key={row.divisionId}>
                  <td>{row.wingName || '—'}</td>
                  <td>{row.divisionName}</td>
                  <td>{row.employeeCount}</td>
                  <td className="amount">{formatAmount(row.gross)}</td>
                  <td className="amount">{formatAmount(row.deductions)}</td>
                  <td className="amount">{formatAmount(row.net)}</td>
                </tr>
              ))}
              <tr className="totals-row-table">
                <td colSpan={2}>Total</td>
                <td>{totals.employees}</td>
                <td className="amount">{formatAmount(totals.gross)}</td>
                <td className="amount">{formatAmount(totals.deductions)}</td>
                <td className="amount">{formatAmount(totals.net)}</td>
              </tr>
            </tbody>
          </table>
        </div>
      )}

      <form className="inline-form" onSubmit={loadHistory}>
        <div className="form-field">
          <label htmlFor="history-employee">Employee salary history</label>
          {canListEmployees ? (
            <select
              id="history-employee"
              value={employeeId}
              onChange={(event) => setEmployeeId(event.target.value)}
            >
              <option value="">Select employee</option>
              {employees.map((employee) => (
                <option key={employee.id} value={employee.id}>
                  {employee.employeeCode} · {employee.name}
                </option>
              ))}
            </select>
          ) : (
            <input
              id="history-employee"
              type="number"
              min="1"
              placeholder="Employee ID"
              value={employeeId}
              onChange={(event) => setEmployeeId(event.target.value)}
            />
          )}
        </div>

        <button className="module-button" type="submit">
          View history
        </button>
      </form>

      {history && (
        <div className="statement-detail">
          <div className="section-heading">
            <div>
              <h4>
                {history.employeeCode} · {history.name}
              </h4>
              <p>Salary history across finalized periods</p>
            </div>
          </div>

          {history.rows.length === 0 ? (
            <p className="muted-text">No salary history is available for this employee.</p>
          ) : (
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Period</th>
                    <th>Record</th>
                    <th>Component</th>
                    <th>Recorded amount</th>
                    <th>Status</th>
                    <th>Gross</th>
                    <th>Deductions</th>
                    <th>Net</th>
                  </tr>
                </thead>
                <tbody>
                  {history.rows.map((row) => (
                    <tr key={`${row.recordSource}-${row.recordId}`}>
                      <td>{row.period}</td>
                      <td>
                        {row.recordSource === 'IMPORTED_HISTORY' ? 'Imported history' : 'Payroll batch'}
                      </td>
                      <td>{row.componentName || row.componentCode || '—'}</td>
                      <td className="amount">{formatAmount(row.amount)}</td>
                      <td>
                        {row.recordSource === 'IMPORTED_HISTORY'
                          ? row.sourceStatus || 'Not provided'
                          : <StatusChip status={row.batchStatus} />}
                      </td>
                      <td className="amount">{formatAmount(row.gross)}</td>
                      <td className="amount">{formatAmount(row.deductions)}</td>
                      <td className="amount">{formatAmount(row.net)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
