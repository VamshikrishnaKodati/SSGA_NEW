import { useCallback, useMemo, useState } from 'react'
import { adminRoleApi } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { friendlyError } from '../lib/format.js'
import {
  Alert,
  Chip,
  DataTable,
  Drawer,
  EmptyState,
  LoadingState,
  PageHeader,
  SearchField,
} from '../components/ui.jsx'

export default function RoleManagement({ ability }) {
  const canRead = ability.hasAny('role.view', 'role.assign', 'user.view')
  const canAssign = ability.has('role.assign')

  const [query, setQuery] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const roles = useLoader(() => adminRoleApi.list(), [], { enabled: canRead })
  const catalogue = useLoader(() => adminRoleApi.permissions(), [], { enabled: canRead })

  const [editTarget, setEditTarget] = useState(null)
  const [selected, setSelected] = useState([])
  const [busy, setBusy] = useState(false)

  const reloadAll = useCallback(() => {
    roles.reload()
    catalogue.reload()
  }, [roles, catalogue])

  const roleRows = useMemo(() => roles.data || [], [roles.data])
  const modules = useMemo(() => catalogue.data || [], [catalogue.data])

  const rows = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return roleRows
    return roleRows.filter((role) =>
      `${role.name} ${role.code} ${role.maxScope}`.toLowerCase().includes(needle),
    )
  }, [roleRows, query])

  function openPermissions(role) {
    setEditTarget(role)
    setSelected(role.permissions || [])
  }

  function togglePermission(code) {
    setSelected((current) =>
      current.includes(code) ? current.filter((entry) => entry !== code) : [...current, code],
    )
  }

  function toggleModule(permissions, grant) {
    const codes = permissions.map((permission) => permission.code)
    setSelected((current) =>
      grant
        ? [...new Set([...current, ...codes])]
        : current.filter((code) => !codes.includes(code)),
    )
  }

  async function save() {
    setBusy(true)

    try {
      await adminRoleApi.replacePermissions(editTarget.id, selected)
      setEditTarget(null)
      setNotice(`Updated permissions for ${editTarget.name}`)
      reloadAll()
    } catch (requestError) {
      setError(friendlyError(requestError))
    } finally {
      setBusy(false)
    }
  }

  if (!canRead) {
    return (
      <>
        <PageHeader title="Role management" breadcrumbs={['Administration', 'Role management']} />
        <EmptyState
          title="You do not have access to role management"
          description="This workspace requires the role management permission."
          icon="lock"
        />
      </>
    )
  }

  const columns = [
    {
      key: 'name',
      label: 'Role',
      render: (row) => (
        <div>
          <strong>{row.name}</strong>
          <div className="muted small">{row.code}</div>
        </div>
      ),
    },
    {
      key: 'maxScope',
      label: 'Max scope',
      render: (row) => <Chip tone="neutral">{row.maxScope}</Chip>,
    },
    {
      key: 'permissions',
      label: 'Permissions',
      align: 'right',
      render: (row) => `${(row.permissions || []).length}`,
    },
    {
      key: 'systemRole',
      label: 'Protection',
      render: (row) =>
        row.systemRole ? <Chip tone="info">Fixed role</Chip> : <Chip tone="neutral">Custom</Chip>,
    },
    {
      key: 'actions',
      label: '',
      render: (row) => (
        <div className="row-actions">
          {canAssign ? (
            <button type="button" className="btn subtle sm" onClick={() => openPermissions(row)}>
              Permissions
            </button>
          ) : null}
        </div>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        title="Role management"
        subtitle="The role register and the permission set each role carries"
        breadcrumbs={['Administration', 'Role management']}
        actions={<Chip tone="info">{roleRows.length} roles</Chip>}
      />

      {error && <Alert kind="error" title="Action failed" onDismiss={() => setError('')}>{error}</Alert>}
      {notice && <Alert kind="success" title="Saved" onDismiss={() => setNotice('')}>{notice}</Alert>}

      <section className="panel">
        <div className="panel-head toolbar">
          <SearchField value={query} onChange={setQuery} placeholder="Search role name or code" />
        </div>
        <div className="panel-body">
          {roles.loading ? (
            <LoadingState label="Loading roles…" />
          ) : rows.length === 0 ? (
            <EmptyState title="No roles match this filter" icon="shield" />
          ) : (
            <DataTable columns={columns} rows={rows} rowKey={(row) => row.id} footer={`${rows.length} roles`} />
          )}
        </div>
      </section>

      <Drawer
        open={Boolean(editTarget)}
        title={`Permissions · ${editTarget?.name || ''}`}
        subtitle="Every change is written to the audit trail with the previous and new set."
        onClose={() => setEditTarget(null)}
        footer={
          <>
            <button type="button" className="btn subtle" onClick={() => setEditTarget(null)}>
              Cancel
            </button>
            <button type="button" className="btn primary" disabled={busy} onClick={save}>
              {busy ? 'Saving…' : `Save ${selected.length} permissions`}
            </button>
          </>
        }
      >
        {catalogue.loading ? (
          <LoadingState label="Loading permission catalogue…" />
        ) : (
          modules.map((module) => {
            const codes = module.permissions.map((permission) => permission.code)
            const granted = codes.filter((code) => selected.includes(code)).length
            const all = granted === codes.length
            return (
              <section className="perm-module" key={module.module}>
                <header>
                  <strong>{module.module}</strong>
                  <span className="muted small">
                    {granted}/{codes.length}
                  </span>
                  <button
                    type="button"
                    className="btn subtle sm"
                    onClick={() => toggleModule(module.permissions, !all)}
                  >
                    {all ? 'Clear all' : 'Grant all'}
                  </button>
                </header>
                <div className="check-list">
                  {module.permissions.map((permission) => (
                    <label className="check-row" key={permission.code}>
                      <input
                        type="checkbox"
                        checked={selected.includes(permission.code)}
                        onChange={() => togglePermission(permission.code)}
                      />
                      <span>{permission.code}</span>
                      <span className="muted small">{permission.description}</span>
                    </label>
                  ))}
                </div>
              </section>
            )
          })
        )}
      </Drawer>
    </>
  )
}