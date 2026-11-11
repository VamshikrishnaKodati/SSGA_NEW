import { useCallback, useMemo, useState } from 'react'
import { payrollApi, periodApi } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { formatAmount, friendlyError, humanizeStatus } from '../lib/format.js'
import {
  canListApprovalBatches,
  filterApprovalBatches,
  isAwaitingApproval,
  isApprovalReadinessPassed,
} from '../lib/approvalInbox.js'
import {
  Alert,
  DataTable,
  DefinitionList,
  Drawer,
  Icon,
  LoadingState,
  PageHeader,
  ReasonDialog,
  SelectField,
  StatusChip,
  Unavailable,
  WorkflowTimeline,
} from '../components/ui.jsx'

const TABS = [
  { id: 'pending', label: 'Awaiting approval' },
  { id: 'approved', label: 'Approved' },
  { id: 'all', label: 'All open batches' },
]

export default function ApprovalInbox({ user, ability, onSessionExpired, onNavigate }) {
  const canList = canListApprovalBatches(ability)
  const [tab, setTab] = useState('pending')
  const [periodFilter, setPeriodFilter] = useState('')
  const [detail, setDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [readiness, setReadiness] = useState(null)
  const [readinessLoading, setReadinessLoading] = useState(false)
  const [readinessError, setReadinessError] = useState('')
  const [action, setAction] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  const periods = useLoader(() => periodApi.list(), [], { enabled: canList })
  const batches = useLoader(
    () => payrollApi.listBatches(periodFilter || undefined),
    [periodFilter],
    { enabled: canList },
  )

  const canApprove = ability.has('payroll.approve')
  const canFinalize = ability.has('payroll.finalize')
  const rows = useMemo(() => {
    return filterApprovalBatches(batches.data || [], tab)
  }, [batches.data, tab])

  const loadDetail = useCallback(async (batchId) => {
    setDetail({ batchId })
    setDetailLoading(true)
    setReadiness(null)
    setReadinessLoading(true)
    setReadinessError('')
    setError('')

    const [detailResult, readinessResult] = await Promise.allSettled([
      payrollApi.getBatch(batchId),
      payrollApi.readiness(batchId),
    ])

    if (detailResult.status === 'fulfilled') {
      setDetail(detailResult.value)
    } else {
      if (detailResult.reason?.status === 401 && onSessionExpired) onSessionExpired()
      setDetail({ batchId, error: friendlyError(detailResult.reason) })
    }

    if (readinessResult.status === 'fulfilled') {
      setReadiness(readinessResult.value)
    } else {
      if (readinessResult.reason?.status === 401 && onSessionExpired) onSessionExpired()
      setReadinessError(friendlyError(readinessResult.reason))
    }

    setDetailLoading(false)
    setReadinessLoading(false)
  }, [onSessionExpired])

  const runAction = useCallback(async (fn, batchId, success) => {
    setBusy(true)
    setError('')
    setMessage('')

    try {
      const result = await fn()
      setMessage(result?.message || success)
      setAction(null)
      await batches.reload()
      await loadDetail(batchId)
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setError(friendlyError(requestError))
    } finally {
      setBusy(false)
    }
  }, [batches, loadDetail, onSessionExpired])

  const isMaker = detail && detail.makerId === user.userId

  const periodOptions = (periods.data || []).map((period) => ({ value: String(period.id), label: period.label }))

  return (
    <>
      <PageHeader
        title="Approval inbox"
        subtitle="Payroll batches awaiting your decision, with a full transaction view before you approve"
        breadcrumbs={['Operations', 'Approval inbox']}
        actions={canList ? (
          <button type="button" className="btn ghost" onClick={batches.reload}>
            <Icon name="refresh" size={14} /> Refresh
          </button>
        ) : undefined}
      />

      {error && <Alert kind="error">{error}</Alert>}
      {message && <Alert kind="success">{message}</Alert>}
      {periods.error && <Alert kind="error">Payroll periods could not be loaded: {periods.error}</Alert>}

      {!canList && (
        <section className="panel">
          <div className="panel-body">
            <Unavailable
              title="Payroll batch access is unavailable"
              description="Your role cannot open batch details. Ask an administrator for access."
            />
          </div>
        </section>
      )}

      {canList && (
        <section className="panel">
          <div className="tabs">
            {TABS.map((entry) => (
              <button key={entry.id} type="button" className={`tab${tab === entry.id ? ' active' : ''}`} onClick={() => setTab(entry.id)}>
                {entry.label}
              </button>
            ))}
          </div>

          <div className="toolbar">
            <SelectField label="Period" value={periodFilter} onChange={setPeriodFilter} options={periodOptions} placeholder="All periods" />
            <span className="inline-meta">{rows.length} batch{rows.length === 1 ? '' : 'es'}</span>
          </div>

          {batches.loading ? (
            <LoadingState rows={5} />
          ) : batches.error ? (
            <div className="panel-body"><Alert kind="error">{batches.error}</Alert></div>
          ) : (
            <DataTable
              columns={[
                { key: 'batchId', label: 'Batch', render: (row) => <span className="cell-primary">#{row.batchId}</span> },
                { key: 'periodLabel', label: 'Period' },
                { key: 'divisionName', label: 'Division', render: (row) => row.divisionName || `Division ${row.divisionId}` },
                { key: 'employeeCount', label: 'Employees', align: 'right' },
                { key: 'netTotal', label: 'Net total', align: 'right', render: (row) => formatAmount(row.netTotal) },
                { key: 'status', label: 'Status', render: (row) => <StatusChip value={row.status} /> },
                { key: 'makerId', label: 'Raised by', render: (row) => (row.makerId === user.userId ? 'You' : `User ${row.makerId}`) },
              ]}
              rows={rows}
              rowKey={(row) => row.batchId}
              onRowClick={(row) => loadDetail(row.batchId)}
              emptyTitle={
                tab === 'pending'
                  ? 'Nothing awaiting approval'
                  : tab === 'approved'
                    ? 'No approved batches'
                    : 'No open batches'
              }
              emptyDescription="No batches match this filter in your authorized scope"
            />
          )}
        </section>
      )}

      {detail && (
        <Drawer
          open
          title={detail.batchId ? `Batch #${detail.batchId}` : 'Batch'}
          subtitle={detail.periodLabel ? `${detail.periodLabel} · ${detail.divisionName || `Division ${detail.divisionId}`}` : undefined}
          onClose={() => setDetail(null)}
          footer={(
            <>
              {canApprove && isAwaitingApproval(detail.status) && !isMaker && (
                <>
                  <button
                    type="button"
                    className="btn danger"
                    disabled={busy}
                    onClick={() => setAction({ type: 'reject', batchId: detail.batchId })}
                  >
                    Reject
                  </button>
                  <button
                    type="button"
                    className="btn"
                    disabled={busy || readinessLoading || !isApprovalReadinessPassed(readiness)}
                    onClick={() => runAction(() => payrollApi.approve(detail.batchId), detail.batchId, 'Batch approved.')}
                  >
                    {busy ? 'Working…' : 'Approve batch'}
                  </button>
                </>
              )}
              {canFinalize
                && detail.status === 'APPROVED'
                && !isMaker
                && detail.checkerId !== user.userId && (
                <button
                  type="button"
                  className="btn ghost"
                  disabled={busy}
                  onClick={() => onNavigate?.('statements')}
                >
                  Continue to Statements
                </button>
              )}
              <button type="button" className="btn subtle" onClick={() => setDetail(null)}>Close</button>
            </>
          )}
        >
          {detailLoading ? (
            <LoadingState rows={6} />
          ) : detail.error ? (
            <Alert kind="error">{detail.error}</Alert>
          ) : (
            <div className="stack">
              {isMaker && (
                <Alert kind="warning">
                  You raised this batch. Maker–checker control requires a different authorized user
                  to approve and finalize it.
                </Alert>
              )}

              <DefinitionList
                items={[
                  { term: 'Status', value: <StatusChip value={detail.status} /> },
                  { term: 'Run', value: `${detail.runType} #${detail.runNo}` },
                  { term: 'Employees', value: detail.employeeCount },
                  { term: 'Gross total', value: formatAmount(detail.grossTotal) },
                  { term: 'Deductions', value: formatAmount(detail.deductionTotal) },
                  { term: 'Net total', value: <strong>{formatAmount(detail.netTotal)}</strong> },
                  { term: 'Raised by', value: detail.makerId === user.userId ? 'You' : `User ${detail.makerId}` },
                  { term: 'Decided by', value: detail.checkerId ? `User ${detail.checkerId}` : '—' },
                ]}
              />

              <WorkflowTimeline
                steps={[
                  { label: 'Generated', detail: `Raised by ${detail.makerId === user.userId ? 'you' : `user ${detail.makerId}`}`, state: 'done' },
                  { label: 'Verified & submitted', detail: 'Submitted for approval', state: detail.status === 'DRAFT' ? 'pending' : 'done' },
                  {
                    label: 'Approval',
                    detail: isAwaitingApproval(detail.status) ? 'Awaiting decision' : detail.checkerId ? `Decided by user ${detail.checkerId}` : 'Not decided',
                    state: isAwaitingApproval(detail.status) ? 'current' : detail.checkerId ? 'done' : 'pending',
                  },
                  {
                    label: 'Finalized',
                    detail: detail.status === 'FINALIZED' ? 'Locked' : 'Awaits approval',
                    state: detail.status === 'FINALIZED' ? 'done' : 'pending',
                  },
                ]}
              />

              {isAwaitingApproval(detail.status) && (
                <section className="stack" aria-label="Approval readiness">
                  <h4 style={{ fontSize: 13, margin: '4px 0 0' }}>Approval readiness</h4>
                  {readinessLoading ? (
                    <LoadingState rows={2} label="Loading approval readiness" />
                  ) : readinessError ? (
                    <Alert kind="error">
                      Readiness could not be confirmed, so approval is unavailable: {readinessError}
                    </Alert>
                  ) : !readiness ? (
                    <Alert kind="warning">Readiness information is unavailable; approval is disabled.</Alert>
                  ) : !Array.isArray(readiness.issues) ? (
                    <Alert kind="warning">Readiness data is incomplete; approval is disabled.</Alert>
                  ) : readiness.issues.length > 0 ? (
                    <Alert kind="warning">
                      {readiness.issues.length} payroll integrity issue{readiness.issues.length === 1 ? '' : 's'} must be resolved before approval.
                      <ul style={{ margin: '8px 0 0', paddingLeft: 20 }}>
                        {readiness.issues.map((issue) => <li key={issue}>{issue}</li>)}
                      </ul>
                    </Alert>
                  ) : readiness.verifiedTransactions !== readiness.totalTransactions ? (
                    <Alert kind="warning">
                      {readiness.verifiedTransactions} of {readiness.totalTransactions} transactions are verified. Approval requires all transactions to be verified.
                    </Alert>
                  ) : readiness.totalTransactions === 0 ? (
                    <Alert kind="warning">The batch has no transactions to approve.</Alert>
                  ) : (
                    <Alert kind="success">
                      All {readiness.totalTransactions} transactions are verified and no payroll integrity issues were found.
                    </Alert>
                  )}
                </section>
              )}

              <div>
                <h4 style={{ fontSize: 13, margin: '4px 0 8px' }}>Transactions</h4>
                <DataTable
                  columns={[
                    { key: 'employeeCode', label: 'Employee', render: (row) => `${row.employeeCode} · ${row.name}` },
                    { key: 'gross', label: 'Gross', align: 'right', render: (row) => formatAmount(row.gross) },
                    { key: 'deductions', label: 'Deductions', align: 'right', render: (row) => formatAmount(row.deductions) },
                    { key: 'net', label: 'Net', align: 'right', render: (row) => formatAmount(row.net) },
                    { key: 'status', label: 'Status', render: (row) => humanizeStatus(row.status) },
                  ]}
                  rows={detail.transactions || []}
                  rowKey={(row) => row.transactionId}
                  emptyTitle="No transactions"
                />
              </div>
            </div>
          )}
        </Drawer>
      )}

      <ReasonDialog
        open={Boolean(action)}
        title="Reject batch"
        description="The rejection reason is recorded against the batch"
        label="Rejection reason"
        confirmLabel="Reject batch"
        busy={busy}
        onCancel={() => setAction(null)}
        onConfirm={(reason) => runAction(() => payrollApi.reject(action.batchId, reason), action.batchId, 'Batch rejected and returned to the preparer.')}
      />
    </>
  )
}
