import { useMemo, useState } from 'react'
import { organizationApi } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { usePagedRows } from '../lib/usePagedRows.js'
import { friendlyError, humanizeStatus } from '../lib/format.js'
import {
  Alert,
  Chip,
  ConfirmDialog,
  DataTable,
  DefinitionList,
  Drawer,
  EmptyState,
  Icon,
  LoadingState,
  PageHeader,
  Pagination,
  ReasonDialog,
  SearchField,
  SelectField,
  StatusChip,
} from '../components/ui.jsx'

const UNIT_TYPES = [
  { value: 'DIVISION', label: 'Division' },
  { value: 'CIRCLE', label: 'Circle' },
  { value: 'PROJECT_DIVISION', label: 'Project division' },
  { value: 'PCC', label: 'PCC' },
  { value: 'TRANSMISSION_CIRCLE', label: 'Transmission circle' },
  { value: 'STP_CIRCLE', label: 'STP circle' },
  { value: 'ELECTRICAL_DIVISION', label: 'Electrical division' },
  { value: 'QUALITY_VIGILANCE', label: 'Quality & vigilance' },
  { value: 'CENTRAL_STORES', label: 'Central stores' },
  { value: 'BOARD_OFFICE', label: 'Board office' },
]

const UNIT_TYPE_LABEL = Object.fromEntries(UNIT_TYPES.map((type) => [type.value, type.label]))

const EMPTY_UNIT_FORM = {
  parentGroupId: '',
  code: '',
  name: '',
  unitType: 'DIVISION',
  status: 'ACTIVE',
  verificationStatus: 'CONFIRMED',
  verificationNote: '',
}

const EMPTY_GROUP_FORM = { code: '', name: '', status: 'ACTIVE' }

function unitToForm(unit) {
  return {
    parentGroupId: unit.parentGroupId ? String(unit.parentGroupId) : '',
    code: unit.code || '',
    name: unit.name || '',
    unitType: unit.unitType || 'DIVISION',
    status: unit.status || 'ACTIVE',
    verificationStatus: unit.verificationStatus || 'CONFIRMED',
    verificationNote: unit.verificationNote || '',
  }
}

function verificationChip(unit) {
  if (unit.verificationStatus === 'PENDING_VERIFICATION') {
    return <Chip tone="warning">Pending verification</Chip>
  }
  return <Chip tone="neutral">Confirmed</Chip>
}

export default function OrganizationMaster({ ability, onSessionExpired }) {
  const canRead = ability.hasAny('organization.view', 'wing.view', 'division.view')
  const canManage = ability.has('organization.manage')

  const [query, setQuery] = useState('')
  const [groupFilter, setGroupFilter] = useState('')
  const [typeFilter, setTypeFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')

  const [detail, setDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [unitForm, setUnitForm] = useState(null)
  const [groupForm, setGroupForm] = useState(null)
  const [statusAction, setStatusAction] = useState(null)
  const [statusBusy, setStatusBusy] = useState(false)
  const [actionError, setActionError] = useState('')

  const groups = useLoader(() => organizationApi.parentGroups(), [], { enabled: canRead })
  const units = useLoader(() => organizationApi.payrollUnits(), [], { enabled: canRead })

  const groupOptions = useMemo(
    () => (groups.data || []).map((group) => ({ value: String(group.id), label: group.name })),
    [groups.data],
  )

  const summary = useMemo(() => {
    const rows = units.data || []
    return {
      groups: (groups.data || []).length,
      units: rows.length,
      active: rows.filter((unit) => unit.status === 'ACTIVE').length,
      pending: rows.filter((unit) => unit.verificationStatus === 'PENDING_VERIFICATION').length,
    }
  }, [groups.data, units.data])

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return (units.data || []).filter((unit) => {
      if (groupFilter && String(unit.parentGroupId) !== groupFilter) return false
      if (typeFilter && unit.unitType !== typeFilter) return false
      if (statusFilter && unit.status !== statusFilter) return false
      if (!needle) return true
      return [unit.code, unit.name, unit.parentGroupName, UNIT_TYPE_LABEL[unit.unitType]]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(needle))
    })
  }, [units.data, query, groupFilter, typeFilter, statusFilter])

  const { page, setPage, totalPages, paged, total, pageSize } = usePagedRows(filtered, 20)

  async function openDetail(unit) {
    setDetail({ id: unit.id })
    setDetailLoading(true)
    try {
      const full = await organizationApi.payrollUnit(unit.id)
      setDetail(full)
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setDetail({ id: unit.id, error: friendlyError(requestError) })
    } finally {
      setDetailLoading(false)
    }
  }

  function refresh() {
    groups.reload()
    units.reload()
  }

  async function runStatusChange(reason) {
    const target = statusAction
    if (!target) return
    setStatusBusy(true)
    setActionError('')
    try {
      const updated = await organizationApi.changePayrollUnitStatus(target.unit.id, target.next, reason)
      setStatusAction(null)
      refresh()
      setDetail((current) => (current && current.id === updated.id ? updated : current))
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setActionError(friendlyError(requestError))
    } finally {
      setStatusBusy(false)
    }
  }

  if (!canRead) {
    return (
      <>
        <PageHeader
          title="Organization master"
          subtitle="Organization, payroll units and assigned employees"
          breadcrumbs={['Administration', 'Organization master']}
        />
        <section className="panel">
          <div className="panel-body">
            <Alert kind="info">
              The Organization master requires organizational scope access
              (organization.view, wing.view or division.view).
            </Alert>
          </div>
        </section>
      </>
    )
  }

  return (
    <>
      <PageHeader
        title="Organization master"
        subtitle="Organization, parent groups, payroll units and employees in one editable register"
        breadcrumbs={['Administration', 'Organization master']}
        actions={(
          <>
            <button type="button" className="btn ghost" onClick={refresh}>
              <Icon name="refresh" size={14} /> Refresh
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => setUnitForm({ mode: 'create', unit: null })}
              disabled={!canManage}
                title={canManage ? 'Add a payroll unit' : 'Your role cannot add payroll units'}
            >
              <Icon name="plus" size={14} /> New unit
            </button>
          </>
        )}
      />

      <section className="kpi-strip">
        <div className="kpi">
          <div className="kpi-label">Parent groups</div>
          <div className="kpi-value">{groups.loading ? '—' : summary.groups}</div>
          <div className="kpi-foot">Groups above payroll units</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Payroll units</div>
          <div className="kpi-value">{units.loading ? '—' : summary.units}</div>
          <div className="kpi-foot">Divisions, circles, offices</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Active units</div>
          <div className="kpi-value">{units.loading ? '—' : summary.active}</div>
          <div className="kpi-foot">Currently active</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Pending verification</div>
          <div className="kpi-value">{units.loading ? '—' : summary.pending}</div>
          <div className="kpi-foot">Awaiting authorized review</div>
        </div>
      </section>

      <section className="panel section-gap">
        <div className="panel-head">
          <div>
            <h3>Payroll units</h3>
            <p>Filter by parent group, type, status or search by code and name.</p>
          </div>
          {!canManage && <span className="chip plain neutral">Read-only</span>}
        </div>

        <div className="toolbar">
          <SearchField value={query} onChange={setQuery} placeholder="Search code or name" />
          <SelectField label="Parent group" value={groupFilter} onChange={setGroupFilter} options={groupOptions} placeholder="All groups" />
          <SelectField label="Type" value={typeFilter} onChange={setTypeFilter} options={UNIT_TYPES} placeholder="All types" />
          <SelectField
            label="Status"
            value={statusFilter}
            onChange={setStatusFilter}
            options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'INACTIVE', label: 'Inactive' }]}
            placeholder="All statuses"
          />
        </div>

        {actionError && <div className="panel-body"><Alert kind="error">{actionError}</Alert></div>}

        {units.loading ? (
          <LoadingState rows={6} />
        ) : units.error ? (
          <div className="panel-body"><Alert kind="error">{units.error}</Alert></div>
        ) : (
          <>
            <DataTable
              columns={[
                { key: 'code', label: 'Code', nowrap: true, render: (row) => <span className="cell-primary">{row.code}</span> },
                { key: 'name', label: 'Name', nowrap: true },
                { key: 'unitType', label: 'Type', nowrap: true, render: (row) => UNIT_TYPE_LABEL[row.unitType] || row.unitType },
                { key: 'parentGroupName', label: 'Parent group', nowrap: true, render: (row) => row.parentGroupName || '—' },
                { key: 'employeeCount', label: 'Employees', align: 'right', nowrap: true, render: (row) => row.employeeCount ?? 0 },
                { key: 'verificationStatus', label: 'Verification', render: (row) => verificationChip(row) },
                { key: 'status', label: 'Status', render: (row) => <StatusChip value={row.status} /> },
                {
                  key: 'actions',
                  label: '',
                  render: (row) => (
                    <div className="detail-actions" onClick={(event) => event.stopPropagation()}>
                      <button type="button" className="btn ghost sm" onClick={() => openDetail(row)}>View</button>
                      {canManage && (
                        <>
                          <button type="button" className="btn subtle sm" onClick={() => setUnitForm({ mode: 'edit', unit: row })}>Edit</button>
                          <button
                            type="button"
                            className="btn subtle sm"
                            onClick={() => setStatusAction({ unit: row, next: row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' })}
                          >
                            {row.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                          </button>
                        </>
                      )}
                    </div>
                  ),
                },
              ]}
              rows={paged}
              rowKey={(row) => row.id}
              onRowClick={openDetail}
              emptyTitle={units.data?.length ? 'No matching units' : 'No payroll units'}
              emptyDescription={units.data?.length
                ? 'Adjust the search or filters to see payroll units.'
                : 'The payroll unit register has not been loaded yet.'}
            />
            <Pagination page={page} totalPages={totalPages} total={total} pageSize={pageSize} onChange={setPage} />
          </>
        )}
      </section>

      <ParentGroupsPanel
        groups={groups}
        canManage={canManage}
        onAdd={() => setGroupForm({ mode: 'create', group: null })}
        onEdit={(group) => setGroupForm({ mode: 'edit', group })}
      />

      {canManage && (
        <section className="panel section-gap">
          <div className="panel-head">
            <div>
              <h3>Register notes</h3>
              <p>How the register maps to source documents and what still needs a decision.</p>
            </div>
          </div>
          <div className="panel-body">
            <Alert kind="warning">
              <strong>Pending items for authorized review</strong>
              <p><strong>O&amp;M Divisions XIII, XXII, XXIII</strong> carry marks in the source register; they remain active and flagged for review.</p>
              <p><strong>O&amp;M Circle V</strong> appears only in the directory listing, not the register; flagged for verification.</p>
              <p><strong>PCC-III</strong> is crossed out in the source register; it remains inactive and flagged, never deleted.</p>
              <p><strong>Electrical Project Division</strong> — source register name vs. official website &ldquo;Electrical Division&rdquo;; source name preserved.</p>
            </Alert>
          </div>
        </section>
      )}

      {detail && (
        <UnitDetailDrawer
          detail={detail}
          loading={detailLoading}
          canManage={canManage}
          onEdit={(unit) => { setDetail(null); setUnitForm({ mode: 'edit', unit }) }}
          onStatus={(unit) => setStatusAction({ unit, next: unit.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' })}
          onClose={() => setDetail(null)}
        />
      )}

      {unitForm && (
        <UnitFormDrawer
          mode={unitForm.mode}
          unit={unitForm.unit}
          groupOptions={groupOptions}
          onClose={() => setUnitForm(null)}
          onSaved={() => { setUnitForm(null); refresh() }}
          onSessionExpired={onSessionExpired}
        />
      )}

      {groupForm && (
        <ParentGroupFormDrawer
          mode={groupForm.mode}
          group={groupForm.group}
          onClose={() => setGroupForm(null)}
          onSaved={() => { setGroupForm(null); refresh() }}
          onSessionExpired={onSessionExpired}
        />
      )}

      {statusAction && statusAction.next === 'INACTIVE' && (
        <ReasonDialog
          open
          title={`Deactivate ${statusAction.unit.name}?`}
          description="The unit is not deleted. Employees keep their stable reference; the unit can be reactivated later."
          label="Reason for deactivation"
          confirmLabel="Deactivate unit"
          busy={statusBusy}
          onConfirm={runStatusChange}
          onCancel={() => setStatusAction(null)}
        />
      )}

      {statusAction && statusAction.next === 'ACTIVE' && (
        <ConfirmDialog
          open
          title={`Reactivate ${statusAction.unit.name}?`}
          description="The unit will be available for assignment again"
          confirmLabel="Activate unit"
          busy={statusBusy}
          onConfirm={() => runStatusChange(null)}
          onCancel={() => setStatusAction(null)}
        />
      )}
    </>
  )
}

/* ---------------------------------------------------------- detail drawer -- */

function UnitDetailDrawer({ detail, loading, canManage, onEdit, onStatus, onClose }) {
  const ready = !loading && !detail.error

  return (
    <Drawer
      open
      title={detail.name || 'Payroll unit'}
      subtitle={detail.code ? `${detail.code} · ${UNIT_TYPE_LABEL[detail.unitType] || detail.unitType || ''}` : undefined}
      onClose={onClose}
      footer={ready && canManage ? (
        <>
          <button type="button" className="btn" onClick={() => onEdit(detail)}>Edit unit</button>
          <button
            type="button"
            className="btn subtle"
            onClick={() => onStatus(detail)}
          >
            {detail.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
          </button>
        </>
      ) : undefined}
    >
      {loading ? (
        <LoadingState rows={5} />
      ) : detail.error ? (
        <Alert kind="error">{detail.error}</Alert>
      ) : (
        <div className="stack">
          <h4 className="section-title">Unit information</h4>
          <DefinitionList
            items={[
              { term: 'Code', value: detail.code },
              { term: 'Name', value: detail.name },
              { term: 'Type', value: UNIT_TYPE_LABEL[detail.unitType] || detail.unitType },
              { term: 'Parent group', value: detail.parentGroupName },
              { term: 'Status', value: humanizeStatus(detail.status) },
              { term: 'Verification', value: verificationChip(detail) },
              { term: 'Assigned employees', value: detail.employeeCount ?? 0 },
            ]}
          />

          {detail.verificationNote && (
            <Alert kind={detail.verificationStatus === 'PENDING_VERIFICATION' ? 'warning' : 'info'} title="Verification note">
              {detail.verificationNote}
            </Alert>
          )}

          <h4 className="section-title">Assigned employees</h4>
          <DataTable
            columns={[
              { key: 'employeeCode', label: 'Employee code', nowrap: true, render: (row) => <span className="cell-primary">{row.employeeCode}</span> },
              { key: 'hrmsId', label: 'HRMS id', nowrap: true, render: (row) => row.hrmsId || '—' },
              { key: 'name', label: 'Name' },
              { key: 'designationName', label: 'Designation', render: (row) => row.designationName || '—' },
              { key: 'status', label: 'Status', render: (row) => <StatusChip value={row.status} /> },
            ]}
            rows={detail.employees || []}
            rowKey={(row) => row.id}
            emptyTitle="No employees assigned"
            emptyDescription="No employees reference this payroll unit yet. Assignments are made from the Employee master."
          />
        </div>
      )}
    </Drawer>
  )
}

/* ------------------------------------------------------ parent groups panel -- */

function ParentGroupsPanel({ groups, canManage, onAdd, onEdit }) {
  return (
    <section className="panel section-gap">
      <div className="panel-head">
        <div>
          <h3>Parent groups</h3>
          <p>Grouping of payroll units (not the existing Wing/Division tree).</p>
        </div>
        <button
          type="button"
          className="btn ghost sm"
          onClick={onAdd}
          disabled={!canManage}
                title={canManage ? 'Add a parent group' : 'Your role cannot add parent groups'}
        >
          <Icon name="plus" size={13} /> Add group
        </button>
      </div>
      <div className="panel-body">
        {groups.loading ? (
          <LoadingState rows={3} />
        ) : groups.error ? (
          <Alert kind="error">{groups.error}</Alert>
        ) : (groups.data || []).length === 0 ? (
          <EmptyState title="No parent groups" description="No parent groups are defined for the organization" />
        ) : (
          <DataTable
            columns={[
              { key: 'code', label: 'Code', nowrap: true, render: (row) => <code>{row.code}</code> },
              { key: 'name', label: 'Group', render: (row) => <span className="cell-primary">{row.name}</span> },
              { key: 'unitCount', label: 'Units', align: 'right', nowrap: true, render: (row) => row.unitCount ?? 0 },
              { key: 'status', label: 'Status', render: (row) => <StatusChip value={row.status} /> },
              {
                key: 'actions',
                label: '',
                render: (row) => (
                  canManage
                    ? <button type="button" className="btn subtle sm" onClick={() => onEdit(row)}>Edit</button>
                    : null
                ),
              },
            ]}
            rows={groups.data || []}
            rowKey={(row) => row.id}
            emptyTitle="No parent groups"
          />
        )}
      </div>
    </section>
  )
}

/* ------------------------------------------------------------- unit form -- */

function UnitFormDrawer({ mode, unit, groupOptions, onClose, onSaved, onSessionExpired }) {
  const isEdit = mode === 'edit'
  const [form, setForm] = useState(() => (isEdit && unit ? unitToForm(unit) : EMPTY_UNIT_FORM))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  function update(key, value) {
    setForm((previous) => ({ ...previous, [key]: value }))
  }

  async function submit(event) {
    event.preventDefault()
    setError('')

    if (!form.parentGroupId) return setError('Select a parent group.')
    if (!form.code.trim()) return setError('Unit code is required.')
    if (!form.name.trim()) return setError('Unit name is required.')

    const payload = {
      parentGroupId: Number(form.parentGroupId),
      code: form.code.trim(),
      name: form.name.trim(),
      unitType: form.unitType,
      status: form.status,
      verificationStatus: form.verificationStatus,
      verificationNote: form.verificationNote.trim() || null,
    }

    setBusy(true)
    try {
      if (isEdit) await organizationApi.updatePayrollUnit(unit.id, payload)
      else await organizationApi.createPayrollUnit(payload)
      onSaved()
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setError(friendlyError(requestError))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer
      open
      title={isEdit ? 'Edit payroll unit' : 'New payroll unit'}
      subtitle={isEdit ? 'Edit payroll unit' : 'New payroll unit'}
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" form="unit-form" className="btn" disabled={busy}>
            {busy ? 'Saving…' : isEdit ? 'Save changes' : 'Create unit'}
          </button>
        </>
      )}
    >
      <form id="unit-form" className="stack" onSubmit={submit}>
        {error && <Alert kind="error">{error}</Alert>}

        <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <label className="field">
            <span>Code *</span>
            <input value={form.code} onChange={(event) => update('code', event.target.value)} disabled={busy} />
          </label>
          <label className="field">
            <span>Parent group *</span>
            <select value={form.parentGroupId} onChange={(event) => update('parentGroupId', event.target.value)} disabled={busy}>
              <option value="">Select group</option>
              {groupOptions.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
          <label className="field" style={{ gridColumn: '1 / -1' }}>
            <span>Name *</span>
            <input value={form.name} onChange={(event) => update('name', event.target.value)} disabled={busy} />
          </label>
          <label className="field">
            <span>Unit type</span>
            <select value={form.unitType} onChange={(event) => update('unitType', event.target.value)} disabled={busy}>
              {UNIT_TYPES.map((type) => (
                <option key={type.value} value={type.value}>{type.label}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Status</span>
            <select value={form.status} onChange={(event) => update('status', event.target.value)} disabled={busy}>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </select>
          </label>
          <label className="field">
            <span>Verification</span>
            <select value={form.verificationStatus} onChange={(event) => update('verificationStatus', event.target.value)} disabled={busy}>
              <option value="CONFIRMED">Confirmed</option>
              <option value="PENDING_VERIFICATION">Pending verification</option>
            </select>
          </label>
        </div>

        <label className="field">
          <span>Verification note</span>
          <textarea rows={3} value={form.verificationNote} onChange={(event) => update('verificationNote', event.target.value)} disabled={busy} />
        </label>

        <p className="field-hint">
          Renaming a unit never breaks employee links or historical statements — employees reference the stable internal id, not the display name.
        </p>
      </form>
    </Drawer>
  )
}

/* ------------------------------------------------------ parent group form -- */

function ParentGroupFormDrawer({ mode, group, onClose, onSaved, onSessionExpired }) {
  const isEdit = mode === 'edit'
  const [form, setForm] = useState(() => (isEdit && group
    ? { code: group.code || '', name: group.name || '', status: group.status || 'ACTIVE' }
    : EMPTY_GROUP_FORM))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  function update(key, value) {
    setForm((previous) => ({ ...previous, [key]: value }))
  }

  async function submit(event) {
    event.preventDefault()
    setError('')

    if (!form.code.trim()) return setError('Group code is required.')
    if (!form.name.trim()) return setError('Group name is required.')

    const payload = { code: form.code.trim(), name: form.name.trim(), status: form.status }

    setBusy(true)
    try {
      if (isEdit) await organizationApi.updateParentGroup(group.id, payload)
      else await organizationApi.createParentGroup(payload)
      onSaved()
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setError(friendlyError(requestError))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer
      open
      title={isEdit ? 'Edit parent group' : 'New parent group'}
      subtitle={isEdit ? 'Edit parent group' : 'New parent group'}
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" form="group-form" className="btn" disabled={busy}>
            {busy ? 'Saving…' : isEdit ? 'Save changes' : 'Create group'}
          </button>
        </>
      )}
    >
      <form id="group-form" className="stack" onSubmit={submit}>
        {error && <Alert kind="error">{error}</Alert>}
        <label className="field">
          <span>Code *</span>
          <input value={form.code} onChange={(event) => update('code', event.target.value)} disabled={busy} />
        </label>
        <label className="field">
          <span>Name *</span>
          <input value={form.name} onChange={(event) => update('name', event.target.value)} disabled={busy} />
        </label>
        <label className="field">
          <span>Status</span>
          <select value={form.status} onChange={(event) => update('status', event.target.value)} disabled={busy}>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </select>
        </label>
      </form>
    </Drawer>
  )
}
