import { useCallback, useEffect, useState } from 'react'
import { auditApi } from '../services/api.js'
import { formatDateTime, friendlyError } from '../lib/format.js'
import {
  Alert,
  DataTable,
  Icon,
  LoadingState,
  PageHeader,
} from '../components/ui.jsx'

const PAGE_SIZE = 50
const EMPTY_FILTERS = {
  from: '',
  to: '',
  user: '',
  module: '',
  action: '',
  recordId: '',
}

export default function AuditTrail({ onSessionExpired }) {
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [page, setPage] = useState({
    events: [],
    offset: 0,
    hasMore: false,
  })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = useCallback(async (nextFilters, offset = 0) => {
    setLoading(true)
    setError('')

    try {
      const result = await auditApi.list({
        ...nextFilters,
        limit: PAGE_SIZE,
        offset,
      })
      setPage(result)
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setError(friendlyError(requestError))
    } finally {
      setLoading(false)
    }
  }, [onSessionExpired])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(EMPTY_FILTERS)
  }, [load])

  function updateFilter(event) {
    const { name, value } = event.target
    setFilters((current) => ({ ...current, [name]: value }))
  }

  function applyFilters(event) {
    event.preventDefault()
    void load(filters)
  }

  function clearFilters() {
    setFilters(EMPTY_FILTERS)
    void load(EMPTY_FILTERS)
  }

  const columns = [
    { key: 'occurredAt', label: 'Date / time', render: (row) => formatDateTime(row.occurredAt) },
    {
      key: 'username',
      label: 'User',
      render: (row) => row.username || (row.userId ? `User #${row.userId}` : '—'),
    },
    { key: 'action', label: 'Action' },
    { key: 'entityType', label: 'Module / record type' },
    { key: 'entityId', label: 'Record ID' },
    { key: 'divisionId', label: 'Division', render: (row) => row.divisionId ?? '—' },
    { key: 'reason', label: 'Reason', render: (row) => row.reason || '—' },
  ]

  return (
    <>
      <PageHeader
        title="Audit trail"
        subtitle="Read-only history of material actions, restricted to your authorized scope."
        breadcrumbs={['Administration', 'Audit trail']}
      />

      {error && <Alert kind="error" title="Audit events could not be loaded">{error}</Alert>}

      <section className="panel">
        <div className="panel-head">
          <div>
            <h3>Audit event search</h3>
            <p>Filter by date, user, module, action, or record ID.</p>
          </div>
          <span className="chip approved plain"><Icon name="lock" size={12} /> Read-only</span>
        </div>

        <form className="toolbar" onSubmit={applyFilters}>
          <label className="field">
            <span>From</span>
            <input type="date" name="from" value={filters.from} onChange={updateFilter} />
          </label>
          <label className="field">
            <span>To</span>
            <input type="date" name="to" value={filters.to} onChange={updateFilter} />
          </label>
          <label className="field">
            <span>Username</span>
            <input
              type="text"
              name="user"
              value={filters.user}
              onChange={updateFilter}
              maxLength={100}
              placeholder="Exact username"
            />
          </label>
          <label className="field">
            <span>Module</span>
            <input
              type="text"
              name="module"
              value={filters.module}
              onChange={updateFilter}
              maxLength={60}
              placeholder="e.g. PAYROLL_BATCH"
            />
          </label>
          <label className="field">
            <span>Action</span>
            <input
              type="text"
              name="action"
              value={filters.action}
              onChange={updateFilter}
              maxLength={40}
              placeholder="e.g. PAYROLL_FINALIZE"
            />
          </label>
          <label className="field">
            <span>Record ID</span>
            <input
              type="text"
              name="recordId"
              value={filters.recordId}
              onChange={updateFilter}
              maxLength={64}
            />
          </label>
          <div className="detail-actions">
            <button type="submit" className="btn" disabled={loading}>
              <Icon name="search" size={14} /> Apply filters
            </button>
            <button type="button" className="btn ghost" onClick={clearFilters} disabled={loading}>
              Clear
            </button>
          </div>
        </form>

        <div className="panel-body tight">
          {loading ? (
            <LoadingState rows={5} />
          ) : (
            <DataTable
              columns={columns}
              rows={page.events || []}
              rowKey={(row) => row.id}
              emptyTitle="No audit events found"
              emptyDescription="Try a different filter, or check back after actions have been recorded."
            />
          )}
        </div>

        <div className="panel-body row-between">
          <span className="muted-text">
            Showing {page.events?.length || 0} event(s); results are limited by your assigned scope.
          </span>
          <div className="detail-actions">
            <button
              type="button"
              className="btn ghost sm"
              disabled={loading || page.offset === 0}
              onClick={() => void load(filters, Math.max(0, page.offset - PAGE_SIZE))}
            >
              Previous
            </button>
            <button
              type="button"
              className="btn ghost sm"
              disabled={loading || !page.hasMore}
              onClick={() => void load(filters, page.offset + PAGE_SIZE)}
            >
              Next
            </button>
          </div>
        </div>
      </section>
    </>
  )
}
