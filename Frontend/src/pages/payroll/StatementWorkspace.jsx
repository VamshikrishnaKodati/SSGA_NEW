import { useCallback, useEffect, useState } from 'react'
import {
  divisionApi,
  payrollApi,
  periodApi,
  reportApi,
  statementApi,
} from '../../services/api.js'
import {
  canFinalizePayrollBatch,
  payrollBatchStatusLabel,
} from '../../lib/payrollWorkspace.js'
import { isAwaitingApproval } from '../../lib/approvalInbox.js'
import { EmptyState } from '../../components/ui.jsx'

function formatDate(value) {
  if (!value) return '—'

  const date = new Date(value)

  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function formatAmount(value) {
  if (value === null || value === undefined) return '—'

  return Number(value).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

export default function StatementWorkspace({ user }) {
  const authorities = Array.isArray(user.authorities) ? user.authorities : []
  const isAdmin = authorities.includes('ROLE_SYSTEM_ADMIN')
  const can = (authority) => isAdmin || authorities.includes(authority)

  const canViewStatements = can('statement.view') || can('statement.download')
  const canDownload = can('statement.download')
  const canGenerate = can('statement.generate') || can('payroll.finalize')
  const canReissue = can('statement.reissue')
  const canViewPayroll = can('payroll.view') || can('payroll.prepare')
  const canPrepare = can('payroll.prepare')
  const canSubmit = can('payroll.submit') || canPrepare
  const canVerify = can('payroll.verify')
  const canApprove = can('payroll.approve')
  const canFinalize = can('payroll.finalize')
  const canReopen = can('payroll.reopen')
  const canReport = can('report.view')
  const canExport = can('export.download')
  const canViewBatches = canViewPayroll || canFinalize

  const tabs = []

  if (canViewStatements) tabs.push('statements')
  if (canViewPayroll) tabs.push('periods')
  if (canViewBatches) tabs.push('batches')
  if (canReport) tabs.push('reports')

  const [activeTab, setActiveTab] = useState(tabs[0] || 'statements')

  const [statements, setStatements] = useState([])
  const [batches, setBatches] = useState([])
  const [finalizationEligibility, setFinalizationEligibility] = useState({})
  const [periods, setPeriods] = useState([])
  const [divisions, setDivisions] = useState([])
  const [summary, setSummary] = useState([])
  const [history, setHistory] = useState(null)
  const [detail, setDetail] = useState(null)
  const [selectedPeriodId, setSelectedPeriodId] = useState('')
  const [newPeriod, setNewPeriod] = useState({ year: 2026, month: 1 })
  const [genForm, setGenForm] = useState({ periodId: '', divisionId: '' })
  const [historyId, setHistoryId] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  const loadStatements = useCallback(async () => {
    try {
      setStatements(await statementApi.list())
      setError('')
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setLoading(false)
    }
  }, [])

  const loadPeriods = useCallback(async () => {
    try {
      setPeriods(await periodApi.list())
      setError('')
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setLoading(false)
    }
  }, [])

  const loadBatches = useCallback(async () => {
    try {
      const batchList = await payrollApi.listBatches(selectedPeriodId || undefined)
      setBatches(batchList)
      if (canFinalize) {
        const eligibilityEntries = await Promise.all(
          batchList
            .filter((batch) => batch.status === 'APPROVED')
            .map(async (batch) => {
              try {
                return [
                  batch.batchId,
                  await payrollApi.finalizationEligibility(
                    batch.batchId,
                    batch.version,
                  ),
                ]
              } catch (requestError) {
                return [
                  batch.batchId,
                  {
                    eligible: false,
                    version: batch.version,
                    reason: `Finalization eligibility could not be confirmed: ${requestError.message}`,
                  },
                ]
              }
            }),
        )
        setFinalizationEligibility(Object.fromEntries(eligibilityEntries))
      } else {
        setFinalizationEligibility({})
      }
      setError('')
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setLoading(false)
    }
  }, [canFinalize, selectedPeriodId])

  const loadDivisions = useCallback(async () => {
    try {
      setDivisions(await divisionApi.myScope())
    } catch (requestError) {
      setError(requestError.message)
    }
  }, [])

  const loadSummary = useCallback(async () => {
    try {
      setSummary(await reportApi.divisionSummary(selectedPeriodId || undefined))
      setError('')
    } catch (requestError) {
      setError(requestError.message)
    } finally {
      setLoading(false)
    }
  }, [selectedPeriodId])

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (activeTab === 'statements') loadStatements()
    if (activeTab === 'periods') loadPeriods()

    if (activeTab === 'batches') {
      loadPeriods()
      loadBatches()
      if (canPrepare) loadDivisions()
    }

    if (activeTab === 'reports') {
      loadPeriods()
      loadSummary()
    }
  }, [
    activeTab,
    canPrepare,
    loadBatches,
    loadDivisions,
    loadPeriods,
    loadStatements,
    loadSummary,
  ])
  /* eslint-enable react-hooks/set-state-in-effect */

  function switchTab(tab) {
    setActiveTab(tab)
    setDetail(null)
    setMessage('')
    setError('')
    setLoading(true)
  }

  async function openStatement(id) {
    setError('')

    try {
      setDetail(await statementApi.get(id))
    } catch (requestError) {
      setError(requestError.message)
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
      setError(requestError.message)
    }
  }

  async function handleReissue(statement) {
    setError('')
    setMessage('')

    try {
      const reissued = await statementApi.reissue(statement.id)

      setMessage(
        `Reissued ${reissued.statementNo} (version ${reissued.version}).`,
      )

      await loadStatements()
      await openStatement(reissued.id)
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function handleGenerate(batch) {
    setError('')
    setMessage('')

    try {
      const result = await statementApi.generateForBatch(batch.batchId)

      setMessage(result.message || 'Statements generated.')
      await loadBatches()
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function handleCreatePeriod(event) {
    event.preventDefault()
    setError('')
    setMessage('')

    try {
      const created = await periodApi.create(
        Number(newPeriod.year),
        Number(newPeriod.month),
      )

      setMessage(`Created period ${created.label} (${created.status}).`)
      await loadPeriods()
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function handleGenerateBatch(event) {
    event.preventDefault()
    setError('')
    setMessage('')

    if (!genForm.periodId || !genForm.divisionId) {
      setError('Select both a period and a division.')
      return
    }

    try {
      const batch = await payrollApi.generate(
        Number(genForm.periodId),
        Number(genForm.divisionId),
      )

      setMessage(batch.message || `Batch #${batch.batchId} created.`)
      await loadBatches()
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function handleAction(batch, action) {
    setError('')
    setMessage('')

    try {
      if (action === 'reject' || action === 'reopen') {
        const reason = window.prompt(
          action === 'reject' ? 'Rejection reason:' : 'Reopening reason:',
        )

        if (!reason) return

        const result =
          action === 'reject'
            ? await payrollApi.reject(batch.batchId, reason)
            : await payrollApi.reopen(batch.batchId, reason)

        setMessage(result.message)
      } else if (action === 'finalize') {
        const eligibility = finalizationEligibility[batch.batchId]
        if (!canFinalizePayrollBatch({
          batch,
          eligibility,
          canFinalize,
          userId: user.userId,
        })) {
          setError(
            eligibility?.reason
              || 'Finalization eligibility has not been confirmed for this batch version.',
          )
          await loadBatches()
          return
        }
        if (!window.confirm('Finalize this batch? It will be locked until an authorized user reopens it.')) return
        const result = await payrollApi.finalize(batch.batchId, batch.version, true)
        setMessage(result.message)
      } else {
        const result = await payrollApi[action](batch.batchId)
        setMessage(result.message)
      }

      await loadBatches()
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function handleHistory(event) {
    event.preventDefault()
    setError('')
    setMessage('')

    if (!historyId) return

    try {
      setHistory(await reportApi.employeeHistory(Number(historyId)))
    } catch (requestError) {
      setError(requestError.message)
      setHistory(null)
    }
  }

  async function handleDownloadReport() {
    setError('')

    try {
      const blob = await reportApi.downloadDivisionSummary(
        selectedPeriodId || undefined,
      )
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')

      anchor.href = url
      anchor.download = 'division-summary.csv'
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  if (tabs.length === 0) {
    return (
      <section className="account-panel">
        <div className="section-heading">
          <div>
            <h3>Payroll</h3>
            <p>Your account has no payroll permissions.</p>
          </div>
        </div>
      </section>
    )
  }

  return (
    <section className="account-panel">
      <nav className="module-tabs">
        {tabs.includes('statements') && (
          <button
            type="button"
            className={`module-tab ${activeTab === 'statements' ? 'active' : ''}`}
            onClick={() => switchTab('statements')}
          >
            Statements
          </button>
        )}

        {tabs.includes('periods') && (
          <button
            type="button"
            className={`module-tab ${activeTab === 'periods' ? 'active' : ''}`}
            onClick={() => switchTab('periods')}
          >
            Payroll periods
          </button>
        )}

        {tabs.includes('batches') && (
          <button
            type="button"
            className={`module-tab ${activeTab === 'batches' ? 'active' : ''}`}
            onClick={() => switchTab('batches')}
          >
            Payroll batches
          </button>
        )}

        {tabs.includes('reports') && (
          <button
            type="button"
            className={`module-tab ${activeTab === 'reports' ? 'active' : ''}`}
            onClick={() => switchTab('reports')}
          >
            Reports
          </button>
        )}
      </nav>

      {error && (
        <div className="module-alert error" role="alert">
          {error}
        </div>
      )}

      {message && <div className="module-alert success">{message}</div>}

      {loading && <p className="muted-text">Loading…</p>}

      {!loading && activeTab === 'statements' && (
        <StatementsTab
          statements={statements}
          detail={detail}
          canDownload={canDownload}
          canReissue={canReissue}
          onOpen={openStatement}
          onDownload={handleDownload}
          onReissue={handleReissue}
        />
      )}

      {!loading && activeTab === 'periods' && (
        <PeriodsTab
          periods={periods}
          canPrepare={canPrepare}
          newPeriod={newPeriod}
          onNewPeriodChange={setNewPeriod}
          onCreate={handleCreatePeriod}
        />
      )}

      {!loading && activeTab === 'batches' && (
        <BatchesTab
          batches={batches}
          finalizationEligibility={finalizationEligibility}
          userId={user.userId}
          periods={periods}
          divisions={divisions}
          selectedPeriodId={selectedPeriodId}
          onPeriodChange={setSelectedPeriodId}
          genForm={genForm}
          onGenFormChange={setGenForm}
          caps={{
            canGenerate,
            canPrepare,
            canSubmit,
            canVerify,
            canApprove,
            canFinalize,
            canReopen,
          }}
          onGenerateBatch={handleGenerateBatch}
          onAction={handleAction}
          onGenerateStatements={handleGenerate}
        />
      )}

      {!loading && activeTab === 'reports' && (
        <ReportsTab
          summary={summary}
          periods={periods}
          selectedPeriodId={selectedPeriodId}
          onPeriodChange={setSelectedPeriodId}
          canExport={canExport}
          onDownload={handleDownloadReport}
          history={history}
          historyId={historyId}
          onHistoryIdChange={setHistoryId}
          onHistory={handleHistory}
        />
      )}
    </section>
  )
}

function PeriodFilter({ periods, value, onChange }) {
  return (
    <label className="module-inline-field">
      <span>Period</span>
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">All periods</option>
        {periods.map((period) => (
          <option key={period.id} value={period.id}>
            {period.label} · {period.status}
          </option>
        ))}
      </select>
    </label>
  )
}

function PeriodsTab({
  periods,
  canPrepare,
  newPeriod,
  onNewPeriodChange,
  onCreate,
}) {
  return (
    <div className="module-body">
      {canPrepare && (
        <form className="module-form" onSubmit={onCreate}>
          <label className="module-inline-field">
            <span>Year</span>
            <input
              type="number"
              min="2000"
              max="2100"
              value={newPeriod.year}
              onChange={(event) =>
                onNewPeriodChange({ ...newPeriod, year: event.target.value })
              }
            />
          </label>

          <label className="module-inline-field">
            <span>Month</span>
            <input
              type="number"
              min="1"
              max="12"
              value={newPeriod.month}
              onChange={(event) =>
                onNewPeriodChange({ ...newPeriod, month: event.target.value })
              }
            />
          </label>

          <button type="submit" className="module-button">
            Create period
          </button>
        </form>
      )}

      {periods.length === 0 ? (
        <p className="muted-text">No payroll periods are configured.</p>
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
                    <span
                      className={`status-chip ${period.status.toLowerCase()}`}
                    >
                      {period.status}
                    </span>
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

function BatchesTab({
  batches,
  finalizationEligibility,
  userId,
  periods,
  divisions,
  selectedPeriodId,
  onPeriodChange,
  genForm,
  onGenFormChange,
  caps,
  onGenerateBatch,
  onAction,
  onGenerateStatements,
}) {
  return (
    <div className="module-body">
      {caps.canPrepare && (
        <form className="module-form" onSubmit={onGenerateBatch}>
          <label className="module-inline-field">
            <span>Period</span>
            <select
              value={genForm.periodId}
              onChange={(event) =>
                onGenFormChange({ ...genForm, periodId: event.target.value })
              }
            >
              <option value="">Select period</option>
              {periods
                .filter((period) => period.status === 'OPEN')
                .map((period) => (
                  <option key={period.id} value={period.id}>
                    {period.label}
                  </option>
                ))}
            </select>
          </label>

          <label className="module-inline-field">
            <span>Division</span>
            <select
              value={genForm.divisionId}
              onChange={(event) =>
                onGenFormChange({ ...genForm, divisionId: event.target.value })
              }
            >
              <option value="">Select division</option>
              {divisions.map((division) => (
                <option key={division.id} value={division.id}>
                  {division.name}
                </option>
              ))}
            </select>
          </label>

          <button type="submit" className="module-button">
            Generate batch
          </button>
        </form>
      )}

      <PeriodFilter
        periods={periods}
        value={selectedPeriodId}
        onChange={onPeriodChange}
      />

      {batches.length === 0 ? (
        <p className="muted-text">No payroll batches are available.</p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Batch</th>
                <th>Period</th>
                <th>Division</th>
                <th>Status</th>
                <th>Employees</th>
                <th>Net total</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {batches.map((batch) => (
                <tr key={batch.batchId}>
                  <td>#{batch.batchId}</td>
                  <td>{batch.periodLabel}</td>
                  <td>{batch.divisionName || batch.divisionId}</td>
                  <td>
                    <span
                      className={`status-chip ${batch.status.toLowerCase()}`}
                    >
                      {payrollBatchStatusLabel(batch.status)}
                    </span>
                  </td>
                  <td>{batch.employeeCount}</td>
                  <td className="amount">{formatAmount(batch.netTotal)}</td>
                  <td>
                    <BatchActions
                      batch={batch}
                      caps={caps}
                      finalizationEligibility={finalizationEligibility[batch.batchId]}
                      userId={userId}
                      onAction={onAction}
                      onGenerateStatements={onGenerateStatements}
                    />
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

function BatchActions({
  batch,
  caps,
  finalizationEligibility,
  userId,
  onAction,
  onGenerateStatements,
}) {
  const editable = ['DRAFT', 'REJECTED', 'REOPENED'].includes(batch.status)
  const buttons = []

  if (editable && caps.canVerify) {
    buttons.push(
      <button
        key="verify"
        type="button"
        className="module-button ghost"
        onClick={() => onAction(batch, 'verify')}
      >
        Verify
      </button>,
    )
  }

  if (editable && caps.canSubmit) {
    buttons.push(
      <button
        key="submit"
        type="button"
        className="module-button"
        onClick={() => onAction(batch, 'submit')}
      >
        Submit
      </button>,
    )
  }

  if (
    isAwaitingApproval(batch.status)
    && caps.canApprove
    && String(batch.makerId) !== String(userId)
  ) {
    buttons.push(
      <button
        key="approve"
        type="button"
        className="module-button"
        onClick={() => onAction(batch, 'approve')}
      >
        Approve
      </button>,
      <button
        key="reject"
        type="button"
        className="module-button danger"
        onClick={() => onAction(batch, 'reject')}
      >
        Reject
      </button>,
    )
  }

  if (canFinalizePayrollBatch({
    batch,
    eligibility: finalizationEligibility,
    canFinalize: caps.canFinalize,
    userId,
  })) {
    buttons.push(
      <button
        key="finalize"
        type="button"
        className="module-button"
        onClick={() => onAction(batch, 'finalize')}
      >
        Finalize
      </button>,
    )
  }

  if (
    batch.status === 'APPROVED'
    && caps.canFinalize
    && finalizationEligibility?.eligible !== true
  ) {
    buttons.push(
      <span
        key="finalize-unavailable"
        className="muted-text"
        title={finalizationEligibility?.reason || 'Eligibility has not been confirmed.'}
      >
        {finalizationEligibility?.reason || 'Finalization eligibility unavailable'}
      </span>,
    )
  }

  if (batch.status === 'FINALIZED') {
    if (caps.canGenerate) {
      buttons.push(
        <button
          key="statements"
          type="button"
          className="module-button ghost"
          onClick={() => onGenerateStatements(batch)}
        >
          Generate statements
        </button>,
      )
    }

    if (caps.canReopen) {
      buttons.push(
        <button
          key="reopen"
          type="button"
          className="module-button danger"
          onClick={() => onAction(batch, 'reopen')}
        >
          Reopen
        </button>,
      )
    }
  }

  if (buttons.length === 0) {
    return <span className="muted-text">—</span>
  }

  return <div className="row-actions">{buttons}</div>
}

function ReportsTab({
  summary,
  periods,
  selectedPeriodId,
  onPeriodChange,
  canExport,
  onDownload,
  history,
  historyId,
  onHistoryIdChange,
  onHistory,
}) {
  return (
    <div className="module-body">
      <div className="report-toolbar">
        <PeriodFilter
          periods={periods}
          value={selectedPeriodId}
          onChange={onPeriodChange}
        />

        {canExport && (
          <button
            type="button"
            className="module-button ghost"
            onClick={onDownload}
          >
            Export CSV
          </button>
        )}
      </div>

      {summary.length === 0 ? (
        <p className="muted-text">
          No finalized payroll to report for this selection.
        </p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Wing</th>
                <th>Division</th>
                <th>Employees</th>
                <th>Gross</th>
                <th>Deductions</th>
                <th>Net</th>
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
            </tbody>
          </table>
        </div>
      )}

      <form className="module-form" onSubmit={onHistory}>
        <label className="module-inline-field">
          <span>Employee ID</span>
          <input
            type="number"
            min="1"
            value={historyId}
            onChange={(event) => onHistoryIdChange(event.target.value)}
          />
        </label>

        <button type="submit" className="module-button">
          Salary history
        </button>
      </form>

      {history && (
        <div className="table-wrap">
          <p className="muted-text">
            Salary history for {history.employeeCode} · {history.name}
          </p>
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
                      : <span className={`status-chip ${row.batchStatus.toLowerCase()}`}>
                          {row.batchStatus}
                        </span>}
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
  )
}

function StatementsTab({
  statements,
  detail,
  canDownload,
  canReissue,
  onOpen,
  onDownload,
  onReissue,
}) {
  if (statements.length === 0) {
    return (
      <div className="module-body">
        <EmptyState
          icon="file"
          title="No statements issued yet"
          description="Statements appear here after a finalized payroll batch generates them"
        />
      </div>
    )
  }

  return (
    <div className="module-body">
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
                onClick={() => onOpen(statement.id)}
              >
                <td>{statement.statementNo}</td>
                <td>
                  {statement.employeeCode} · {statement.employeeName}
                </td>
                <td>{statement.periodLabel}</td>
                <td>v{statement.version}</td>
                <td>
                  <span
                    className={`status-chip ${statement.status.toLowerCase()}`}
                  >
                    {statement.status}
                  </span>
                </td>
                <td>{formatDate(statement.generatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {detail && (
        <StatementDetail
          detail={detail}
          canDownload={canDownload}
          canReissue={canReissue}
          onDownload={onDownload}
          onReissue={onReissue}
        />
      )}
    </div>
  )
}

function StatementDetail({ detail, canDownload, canReissue, onDownload, onReissue }) {
  const { statement, payload } = detail
  const earnings = payload?.earnings || []
  const deductions = payload?.deductions || []
  const totals = payload?.totals || {}

  return (
    <div className="statement-detail">
      <div className="section-heading">
        <div>
          <h4>{statement.statementNo}</h4>
          <p>
            {payload?.employee?.code} · {payload?.employee?.name} ·{' '}
            {payload?.period?.label}
          </p>
        </div>

        <div className="detail-actions">
          {canDownload && (
            <button
              type="button"
              className="module-button"
              onClick={() => onDownload(statement)}
            >
              Download PDF
            </button>
          )}

          {canReissue && statement.status === 'ISSUED' && (
            <button
              type="button"
              className="module-button ghost"
              onClick={() => onReissue(statement)}
            >
              Reissue
            </button>
          )}
        </div>
      </div>

      <div className="statement-columns">
        <LineTable title="Earnings" lines={earnings} />

        <LineTable title="Deductions" lines={deductions} />
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

      <p className="muted-text">
        Payload {statement.payloadSha256?.slice(0, 12)}… · File{' '}
        {statement.fileSha256?.slice(0, 12)}… · {statement.fileSizeBytes} bytes
      </p>
    </div>
  )
}

function LineTable({ title, lines }) {
  return (
    <div className="line-table">
      <h5>{title}</h5>
      <table className="data-table compact">
        <tbody>
          {lines.map((line) => (
            <tr key={line.code}>
              <td>{line.name}</td>
              <td className="amount">{formatAmount(line.amount)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
