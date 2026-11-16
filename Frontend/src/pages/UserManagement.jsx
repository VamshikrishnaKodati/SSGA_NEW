import { useCallback, useMemo, useState } from 'react'
import { adminRoleApi, adminUserApi } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { friendlyError } from '../lib/format.js'
import {
  Alert,
  Chip,
  ConfirmDialog,
  DataTable,
  Drawer,
  EmptyState,
  Icon,
  LoadingState,
  PageHeader,
  SearchField,
  SelectField,
} from '../components/ui.jsx'

const EMPTY_FORM = {
  username: '',
  displayName: '',
  email: '',
  employeeCode: '',
  password: '',
  roleId: '',
  scopeKind: 'ORG',
  scopeId: '',
}

export default function UserManagement({ user, ability }) {
  const canRead = ability.hasAny('user.manage', 'user.view', 'role.assign')
  const canCreate = ability.hasAny('user.manage', 'user.create')
  const canEdit = ability.hasAny('user.manage', 'user.update')
  const canBindScope = ability.hasAny('scope.bind', 'role.assign')

  const [query, setQuery] = useState('')
  const [roleFilter, setRoleFilter] = useState('')
  const [activeFilter, setActiveFilter] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const roles = useLoader(() => adminRoleApi.list(), [], { enabled: canRead })
  const scopes = useLoader(() => adminRoleApi.scopes(), [], { enabled: canBindScope })
  const catalogue = useLoader(() => adminRoleApi.permissions(), [], { enabled: true })

  const users = useLoader(
    () => adminUserApi.list({ q: query || undefined, role: roleFilter || undefined, active: activeFilter, limit: 200 }),
    [query, roleFilter, activeFilter],
    { enabled: canRead },
  )

  const [createOpen, setCreateOpen] = useState(false)
  const [createForm, setCreateForm] = useState(EMPTY_FORM)
  const [createBusy, setCreateBusy] = useState(false)
  const [createError, setCreateError] = useState('')

  const [editTarget, setEditTarget] = useState(null)
  const [editForm, setEditForm] = useState({ displayName: '', email: '', employeeCode: '' })
  const [editBusy, setEditBusy] = useState(false)

  const [scopeTarget, setScopeTarget] = useState(null)
  const [scopeRows, setScopeRows] = useState([])
  const [scopeBusy, setScopeBusy] = useState(false)

  const [deactivateTarget, setDeactivateTarget] = useState(null)
  const [deactivateBusy, setDeactivateBusy] = useState(false)

  const [passwordTarget, setPasswordTarget] = useState(null)
  const [newPassword, setNewPassword] = useState('')
  const [passwordBusy, setPasswordBusy] = useState(false)
  const [passwordError, setPasswordError] = useState('')

  const [overrideTarget, setOverrideTarget] = useState(null)
  const [overrideReason, setOverrideReason] = useState('')
  const [overrideBusy, setOverrideBusy] = useState(false)
  const [overrideError, setOverrideError] = useState('')

  const reload = useCallback(() => users.reload?.(), [users])

  const roleList = roles.data || []
  const scopeCatalogue = scopes.data || { wings: [], divisions: [] }

  const rows = useMemo(() => users.data?.users || [], [users.data])

  const roleName = useMemo(
    () => Object.fromEntries(roleList.map((role) => [role.code, role.name])),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [roles.data],
  )

  const roleOptions = useMemo(
    () => roleList.map((role) => ({ value: String(role.id), label: `${role.name} (${role.maxScope})` })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [roles.data],
  )

  function selectedRole() {
    return roleList.find((role) => String(role.id) === createForm.roleId)
  }

  function scopeOptionsFor(kind) {
    if (kind === 'WING') {
      return scopeCatalogue.wings.map((wing) => ({ value: String(wing.id), label: `${wing.name} (${wing.code})` }))
    }
    if (kind === 'DIVISION') {
      return scopeCatalogue.divisions.map((division) => ({
        value: String(division.id),
        label: `${division.name} (${division.code})`,
      }))
    }
    return []
  }

  function defaultScopeKind(role) {
    if (!role) return 'ORG'
    if (role.maxScope === 'WING') return 'WING'
    if (role.maxScope === 'DIVISION') return 'DIVISION'
    return 'ORG'
  }

  function onRoleChange(roleId) {
    const role = roleList.find((entry) => String(entry.id) === roleId)
    setCreateForm((form) => ({ ...form, roleId, scopeKind: defaultScopeKind(role), scopeId: '' }))
  }

  async function submitCreate(event) {
    event.preventDefault()
    setCreateError('')

    const role = selectedRole()
    if (!role) {
      setCreateError('Select a role')
      return
    }

    const needsScope = role.maxScope !== 'ORG'
    if (needsScope && !createForm.scopeId) {
      setCreateError(`Select a ${createForm.scopeKind.toLowerCase()}`)
      return
    }

    setCreateBusy(true)

    try {
      await adminUserApi.create({
        username: createForm.username,
        displayName: createForm.displayName,
        email: createForm.email,
        employeeCode: createForm.employeeCode,
        password: createForm.password,
        scopes: [
          {
            roleId: Number(createForm.roleId),
            wingId: createForm.scopeKind === 'WING' && createForm.scopeId ? Number(createForm.scopeId) : null,
            divisionId: createForm.scopeKind === 'DIVISION' && createForm.scopeId ? Number(createForm.scopeId) : null,
          },
        ],
      })

      setCreateOpen(false)
      setCreateForm(EMPTY_FORM)
      setNotice(`Created ${createForm.username}`)
      reload()
    } catch (requestError) {
      setCreateError(friendlyError(requestError))
    } finally {
      setCreateBusy(false)
    }
  }

  function openEdit(target) {
    setEditTarget(target)
    setEditForm({
      displayName: target.displayName || '',
      email: target.email || '',
      employeeCode: target.employeeCode || '',
    })
  }

  async function submitEdit(event) {
    event.preventDefault()
    setEditBusy(true)

    try {
      await adminUserApi.update(editTarget.id, editForm)
      setEditTarget(null)
      setNotice(`Updated ${editTarget.username}`)
      reload()
    } catch (requestError) {
      setError(friendlyError(requestError))
    } finally {
      setEditBusy(false)
    }
  }

  function openScopes(target) {
    const byCode = new Map(roleList.map((role) => [role.code, String(role.id)]))
    setScopeTarget(target)
    setScopeRows(
      (target.assignments || []).map((assignment) => ({
        roleId: byCode.get(assignment.roleCode) || '',
        scopeKind: assignment.maxScope === 'WING' ? 'WING' : assignment.maxScope === 'DIVISION' ? 'DIVISION' : 'ORG',
        scopeId:
          assignment.wingId != null
            ? String(assignment.wingId)
            : assignment.divisionId != null
              ? String(assignment.divisionId)
              : '',
      })),
    )
  }

  function addScopeRow() {
    setScopeRows((current) => [...current, { roleId: '', scopeKind: 'ORG', scopeId: '' }])
  }

  function updateScopeRow(index, patch) {
    setScopeRows((current) => current.map((row, position) => (position === index ? { ...row, ...patch } : row)))
  }

  function removeScopeRow(index) {
    setScopeRows((current) => current.filter((_, position) => position !== index))
  }

  async function submitScopes() {
    setScopeBusy(true)

    try {
      const payload = scopeRows.map((row) => {
        const role = roleList.find((entry) => String(entry.id) === row.roleId)
        const maxScope = role?.maxScope || 'ORG'
        return {
          roleId: Number(row.roleId),
          wingId: maxScope === 'WING' && row.scopeId ? Number(row.scopeId) : null,
          divisionId: maxScope === 'DIVISION' && row.scopeId ? Number(row.scopeId) : null,
        }
      })

      await adminUserApi.replaceScopes(scopeTarget.id, payload)
      setScopeTarget(null)
      setNotice(`Updated access for ${scopeTarget.username}`)
      reload()
    } catch (requestError) {
      setError(friendlyError(requestError))
    } finally {
      setScopeBusy(false)
    }
  }

  async function confirmDeactivate() {
    setDeactivateBusy(true)

    try {
      await adminUserApi.setActive(deactivateTarget.id, false)
      setDeactivateTarget(null)
      setNotice(`Deactivated ${deactivateTarget.username}`)
      reload()
    } catch (requestError) {
      setError(friendlyError(requestError))
    } finally {
      setDeactivateBusy(false)
    }
  }

  async function reactivate(target) {
    try {
      await adminUserApi.setActive(target.id, true)
      setNotice(`Reactivated ${target.username}`)
      reload()
    } catch (requestError) {
      setError(friendlyError(requestError))
    }
  }

  function openPassword(target) {
    setPasswordTarget(target)
    setNewPassword('')
    setPasswordError('')
  }

  async function submitPassword() {
    if (newPassword.length < 10) {
      setPasswordError('The password must be at least 10 characters.')
      return
    }

    setPasswordBusy(true)

    try {
      await adminUserApi.resetPassword(passwordTarget.id, newPassword)
      setPasswordTarget(null)
      setNewPassword('')
      setNotice(`Password reset for ${passwordTarget.username}; they must change it at next sign-in`)
    } catch (requestError) {
      setPasswordError(friendlyError(requestError))
    } finally {
      setPasswordBusy(false)
    }
  }

  function openOverrides(target) {
    setOverrideTarget(target)
    setOverrideReason('')
    setOverrideError('')
  }

  async function applyOverride(permission, granted) {
    setOverrideBusy(true)
    setOverrideError('')

    try {
      await adminUserApi.setOverride(overrideTarget.id, {
        permissionId: permission.id,
        granted,
        reason: overrideReason,
      })

      const refreshed = await adminUserApi.get(overrideTarget.id)
      setOverrideTarget(refreshed)
      reload()
    } catch (requestError) {
      setOverrideError(friendlyError(requestError))
    } finally {
      setOverrideBusy(false)
    }
  }

  const overrideModules = catalogue.data || []
  const overrideState = useMemo(
    () => new Map((overrideTarget?.overrides || []).map((entry) => [entry.permissionId, entry])),
    [overrideTarget],
  )

  if (!canRead) {
    return (
      <>
        <PageHeader title="User management" breadcrumbs={['Administration', 'User management']} />
        <EmptyState
          title="You do not have access to user management"
          description="This workspace requires the user management permission."
          icon="lock"
        />
      </>
    )
  }

  const columns = [
    {
      key: 'username',
      label: 'User',
      render: (row) => (
        <div>
          <strong>{row.username}</strong>
          <div className="muted small">{row.displayName}</div>
        </div>
      ),
    },
    {
      key: 'roles',
      label: 'Roles & scope',
      render: (row) => (
        <div className="chip-row">
          {(row.assignments || []).length === 0 && <Chip tone="warning">No role</Chip>}
          {(row.assignments || []).map((assignment) => (
            <Chip key={assignment.scopeId} tone="info">
              {roleName[assignment.roleCode] || assignment.roleCode}
              {assignment.divisionCode ? ` · ${assignment.divisionCode}` : ''}
              {assignment.wingCode ? ` · ${assignment.wingCode}` : ''}
            </Chip>
          ))}
        </div>
      ),
    },
    {
      key: 'permissions',
      label: 'Permissions',
      align: 'right',
      render: (row) => `${(row.effectivePermissions || []).length}`,
    },
    {
      key: 'active',
      label: 'Status',
      render: (row) => (row.active ? <Chip tone="success">Active</Chip> : <Chip tone="danger">Inactive</Chip>),
    },
    {
      key: 'actions',
      label: '',
      render: (row) => (
        <div className="row-actions">
          {canEdit && (
            <button type="button" className="btn subtle sm" onClick={() => openEdit(row)}>
              Edit
            </button>
          )}
          {canBindScope && (
            <button type="button" className="btn subtle sm" onClick={() => openScopes(row)}>
              Access
            </button>
          )}
          {canEdit && (
            <button type="button" className="btn subtle sm" onClick={() => openPassword(row)}>
              Reset password
            </button>
          )}
          {canEdit && (
            <button type="button" className="btn subtle sm" onClick={() => openOverrides(row)}>
              Overrides
            </button>
          )}
          {row.active && canEdit && row.id !== user.userId ? (
            <button type="button" className="btn danger sm" onClick={() => setDeactivateTarget(row)}>
              Deactivate
            </button>
          ) : null}
          {!row.active && canEdit ? (
            <button type="button" className="btn subtle sm" onClick={() => reactivate(row)}>
              Reactivate
            </button>
          ) : null}
        </div>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        title="User management"
        subtitle="Accounts, their roles and the organizational scope each assignment reaches"
        breadcrumbs={['Administration', 'User management']}
        actions={
          canCreate ? (
            <button type="button" className="btn primary" onClick={() => setCreateOpen(true)}>
              <Icon name="plus" /> New user
            </button>
          ) : null
        }
      />

      {error && <Alert kind="error" title="Action failed" onDismiss={() => setError('')}>{error}</Alert>}
      {notice && <Alert kind="success" title="Saved" onDismiss={() => setNotice('')}>{notice}</Alert>}

      <section className="panel">
        <div className="panel-head toolbar">
          <SearchField value={query} onChange={setQuery} placeholder="Search username or name" />
          <SelectField
            label="Role"
            value={roleFilter}
            onChange={setRoleFilter}
            options={roleList.map((role) => ({ value: role.code, label: role.name }))}
          />
          <SelectField
            label="Status"
            value={activeFilter}
            onChange={setActiveFilter}
            placeholder={null}
            options={[
              { value: 'true', label: 'Active' },
              { value: 'false', label: 'Inactive' },
            ]}
          />
        </div>
        <div className="panel-body">
          {users.loading ? (
            <LoadingState label="Loading users…" />
          ) : rows.length === 0 ? (
            <EmptyState title="No users match this filter" icon="users" />
          ) : (
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(row) => row.id}
              footer={`${users.data?.total ?? rows.length} users`}
            />
          )}
        </div>
      </section>

      <Drawer
        open={createOpen}
        title="New user"
        subtitle="The account is created active and must change its password on first sign-in."
        onClose={() => setCreateOpen(false)}
        footer={
          <>
            <button type="button" className="btn subtle" onClick={() => setCreateOpen(false)}>
              Cancel
            </button>
            <button type="button" className="btn primary" disabled={createBusy} onClick={submitCreate}>
              {createBusy ? 'Creating…' : 'Create user'}
            </button>
          </>
        }
      >
        <form className="form-grid" onSubmit={submitCreate}>
          {createError && <Alert kind="error" title="Could not create the user">{createError}</Alert>}
          <label>
            Username
            <input
              value={createForm.username}
              onChange={(event) => setCreateForm({ ...createForm, username: event.target.value })}
              required
            />
          </label>
          <label>
            Display name
            <input
              value={createForm.displayName}
              onChange={(event) => setCreateForm({ ...createForm, displayName: event.target.value })}
              required
            />
          </label>
          <label>
            Email
            <input
              type="email"
              value={createForm.email}
              onChange={(event) => setCreateForm({ ...createForm, email: event.target.value })}
            />
          </label>
          <label>
            Employee code
            <input
              value={createForm.employeeCode}
              onChange={(event) => setCreateForm({ ...createForm, employeeCode: event.target.value })}
            />
          </label>
          <label>
            Temporary password
            <input
              type="password"
              value={createForm.password}
              onChange={(event) => setCreateForm({ ...createForm, password: event.target.value })}
              minLength={10}
              required
            />
          </label>
          <label>
            Role
            <select value={createForm.roleId} onChange={(event) => onRoleChange(event.target.value)} required>
              <option value="">Select a role</option>
              {roleOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          {selectedRole() && selectedRole().maxScope !== 'ORG' && (
            <label>
              {createForm.scopeKind === 'WING' ? 'Wing' : 'Division'}
              <select
                value={createForm.scopeId}
                onChange={(event) => setCreateForm({ ...createForm, scopeId: event.target.value })}
                required
              >
                <option value="">Select a {createForm.scopeKind.toLowerCase()}</option>
                {scopeOptionsFor(createForm.scopeKind).map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
          )}
        </form>
      </Drawer>

      <Drawer
        open={Boolean(editTarget)}
        title={`Edit ${editTarget?.username || ''}`}
        onClose={() => setEditTarget(null)}
        footer={
          <>
            <button type="button" className="btn subtle" onClick={() => setEditTarget(null)}>
              Cancel
            </button>
            <button type="button" className="btn primary" disabled={editBusy} onClick={submitEdit}>
              {editBusy ? 'Saving…' : 'Save'}
            </button>
          </>
        }
      >
        <form className="form-grid" onSubmit={submitEdit}>
          <label>
            Display name
            <input
              value={editForm.displayName}
              onChange={(event) => setEditForm({ ...editForm, displayName: event.target.value })}
              required
            />
          </label>
          <label>
            Email
            <input
              type="email"
              value={editForm.email}
              onChange={(event) => setEditForm({ ...editForm, email: event.target.value })}
            />
          </label>
          <label>
            Employee code
            <input
              value={editForm.employeeCode}
              onChange={(event) => setEditForm({ ...editForm, employeeCode: event.target.value })}
            />
          </label>
        </form>
      </Drawer>

      <Drawer
        open={Boolean(scopeTarget)}
        title={`Access for ${scopeTarget?.username || ''}`}
        subtitle="Each row is one role and the organizational scope it reaches. Saving replaces the current assignments."
        onClose={() => setScopeTarget(null)}
        footer={
          <>
            <button type="button" className="btn subtle" onClick={addScopeRow}>
              Add role
            </button>
            <button type="button" className="btn primary" disabled={scopeBusy} onClick={submitScopes}>
              {scopeBusy ? 'Saving…' : 'Save access'}
            </button>
          </>
        }
      >
        {scopeRows.length === 0 && <EmptyState title="No roles assigned" icon="shield" />}
        {scopeRows.map((row, index) => {
          const role = roleList.find((entry) => String(entry.id) === row.roleId)
          const maxScope = role?.maxScope || 'ORG'
          return (
            <div className="form-grid" key={index}>
              <label>
                Role
                <select
                  value={row.roleId}
                  onChange={(event) =>
                    updateScopeRow(index, { roleId: event.target.value, scopeId: '' })
                  }
                >
                  <option value="">Select a role</option>
                  {roleOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
              {maxScope !== 'ORG' && (
                <label>
                  {maxScope === 'WING' ? 'Wing' : 'Division'}
                  <select
                    value={row.scopeId}
                    onChange={(event) => updateScopeRow(index, { scopeId: event.target.value })}
                  >
                    <option value="">Select a {maxScope === 'WING' ? 'wing' : 'division'}</option>
                    {scopeOptionsFor(maxScope).map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              <button type="button" className="btn danger sm" onClick={() => removeScopeRow(index)}>
                Remove
              </button>
            </div>
          )
        })}
      </Drawer>

      <Drawer
        open={Boolean(passwordTarget)}
        title={`Reset password · ${passwordTarget?.username || ''}`}
        subtitle="The account is locked to a password change at next sign-in."
        onClose={() => setPasswordTarget(null)}
        footer={
          <>
            <button type="button" className="btn subtle" onClick={() => setPasswordTarget(null)}>
              Cancel
            </button>
            <button type="button" className="btn primary" disabled={passwordBusy} onClick={submitPassword}>
              {passwordBusy ? 'Saving…' : 'Reset password'}
            </button>
          </>
        }
      >
        {passwordError && <Alert kind="error" title="Could not reset the password">{passwordError}</Alert>}
        <form className="form-grid" onSubmit={(event) => { event.preventDefault(); submitPassword() }}>
          <label>
            New password
            <input
              type="password"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              minLength={10}
              required
            />
          </label>
          <p className="muted small">At least 10 characters. It is never written to the audit trail.</p>
        </form>
      </Drawer>

      <Drawer
        open={Boolean(overrideTarget)}
        title={`Permission overrides · ${overrideTarget?.username || ''}`}
        subtitle="A grant or a denial recorded here overrides the permission the roles would otherwise give."
        onClose={() => setOverrideTarget(null)}
        footer={
          <button type="button" className="btn subtle" onClick={() => setOverrideTarget(null)}>
            Close
          </button>
        }
      >
        {overrideError && <Alert kind="error" title="Could not apply the override">{overrideError}</Alert>}

        <label className="field">
          <span>Reason (required to deny)</span>
          <input
            value={overrideReason}
            onChange={(event) => setOverrideReason(event.target.value)}
            placeholder="Why this override is needed"
          />
        </label>

        {overrideTarget?.systemAdministrator && (
          <Alert kind="info" title="System Administrator">
            This account already holds every permission through the System Administrator role.
          </Alert>
        )}

        {catalogue.loading ? (
          <LoadingState label="Loading the permission catalogue…" />
        ) : (
          overrideModules.map((module) => (
            <section className="perm-module" key={module.module}>
              <header>
                <strong>{module.module}</strong>
              </header>
              <div className="check-list">
                {module.permissions.map((permission) => {
                  const override = overrideState.get(permission.id)
                  const effective = (overrideTarget?.effectivePermissions || []).includes(permission.code)

                  return (
                    <div className="check-row" key={permission.code}>
                      <div>
                        <span>{permission.code}</span>
                        <span className="muted small">{permission.description}</span>
                        {override && (
                          <Chip tone={override.granted ? 'success' : 'danger'}>
                            {override.granted ? 'Override: granted' : 'Override: denied'}
                            {override.reason ? ` · ${override.reason}` : ''}
                          </Chip>
                        )}
                      </div>
                      <div className="row-actions">
                        <button
                          type="button"
                          className={`btn sm ${override?.granted ? 'primary' : 'subtle'}`}
                          disabled={overrideBusy}
                          onClick={() => applyOverride(permission, true)}
                        >
                          Grant
                        </button>
                        <button
                          type="button"
                          className={`btn sm ${override && !override.granted ? 'danger' : 'subtle'}`}
                          disabled={overrideBusy}
                          onClick={() => applyOverride(permission, false)}
                        >
                          Deny
                        </button>
                      </div>
                      <span className="muted small">
                        {effective ? 'effective now' : 'not held'}
                      </span>
                    </div>
                  )
                })}
              </div>
            </section>
          ))
        )}
      </Drawer>

      <ConfirmDialog
        open={Boolean(deactivateTarget)}
        title={`Deactivate ${deactivateTarget?.username || ''}?`}
        description="The account stops signing in immediately. Role assignments are kept so it can be reactivated."
        confirmLabel="Deactivate"
        tone="danger"
        busy={deactivateBusy}
        onCancel={() => setDeactivateTarget(null)}
        onConfirm={confirmDeactivate}
      />
    </>
  )
}