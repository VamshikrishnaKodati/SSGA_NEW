import { useMemo, useState } from 'react'
import { useLoader } from '../lib/useLoader.js'
import { divisionApi } from '../services/api.js'
import { PERMISSIONS, ROLE_LABELS } from '../lib/permissions.js'
import {
  Alert,
  DataTable,
  DefinitionList,
  LoadingState,
  PageHeader,
  Unavailable,
} from '../components/ui.jsx'

const ROLE_ROWS = Object.entries(ROLE_LABELS).map(([code, label]) => ({ code, label }))

export default function AccessConsole({ user, ability }) {
  const [tab, setTab] = useState('effective')

  const divisions = useLoader(
    () => divisionApi.myScope(),
    [],
    { enabled: ability.has('division.view') },
  )

  const granted = useMemo(
    () => new Set(ability.authorities.filter((entry) => !entry.startsWith('ROLE_'))),
    [ability],
  )

  return (
    <>
      <PageHeader
        title="User management"
        subtitle="Who this account is, what it can do, and the organizational scope it can reach"
        breadcrumbs={['Administration', 'User management']}
      />

      <section className="panel">
        <div className="panel-head">
          <div>
            <h3>Account & effective access</h3>
          </div>
        </div>
        <div className="panel-body">
          <DefinitionList
            items={[
              { term: 'Username', value: user.username },
              { term: 'User ID', value: user.userId },
              {
                term: 'Roles',
                value: ability.roles.length
                  ? ability.roles.map((role) => ROLE_LABELS[role]).join(', ')
                  : 'No roles assigned',
              },
              { term: 'Permissions', value: `${granted.size} granted` },
            ]}
          />
        </div>
      </section>

      <section className="panel section-gap">
        <div className="tabs">
          <button type="button" className={`tab${tab === 'effective' ? ' active' : ''}`} onClick={() => setTab('effective')}>Effective permissions</button>
          <button type="button" className={`tab${tab === 'roles' ? ' active' : ''}`} onClick={() => setTab('roles')}>Roles</button>
          <button type="button" className={`tab${tab === 'scope' ? ' active' : ''}`} onClick={() => setTab('scope')}>Organizational scope</button>
          <button type="button" className={`tab${tab === 'manage' ? ' active' : ''}`} onClick={() => setTab('manage')}>User administration</button>
        </div>

        <div className="panel-body">
          {tab === 'effective' && (
            <>
              <p className="muted" style={{ marginTop: 0 }}>
              Permission codes for this account. Access follows your role and
              cannot be changed here.
              </p>
              <div className="tag-list" style={{ marginTop: 12 }}>
                {PERMISSIONS.map((code) => (
                  <span key={code} className={`chip plain ${granted.has(code) ? 'approved' : ''}`} title={granted.has(code) ? 'Granted' : 'Not granted'}>
                    {code}
                  </span>
                ))}
              </div>
              {ability.isAdmin && (
                <Alert kind="info">
                  System Administrator has full access by role.
                </Alert>
              )}
            </>
          )}

          {tab === 'roles' && (
            <DataTable
              columns={[
                { key: 'label', label: 'Role', render: (row) => <span className="cell-primary">{row.label}</span> },
                { key: 'code', label: 'Role code', render: (row) => <code>{row.code.replace('ROLE_', '')}</code> },
                { key: 'assigned', label: 'Assigned to this account', render: (row) => (ability.hasRole(row.code) ? 'Yes' : '—') },
              ]}
              rows={ROLE_ROWS}
              rowKey={(row) => row.code}
              emptyTitle="No roles defined"
            />
          )}

          {tab === 'scope' && (
            <>
              {!ability.has('division.view') ? (
                <Alert kind="info">Organizational scope is not available for your role.</Alert>
              ) : divisions.loading ? (
                <LoadingState rows={3} />
              ) : divisions.error ? (
                <Alert kind="error">{divisions.error}</Alert>
              ) : (
                <>
                  <p className="muted" style={{ marginTop: 0 }}>
                    Wings and divisions available to this account. Access is limited
                    to these units on every request.
                  </p>
                  <DataTable
                    columns={[
                      { key: 'wingName', label: 'Wing', render: (row) => row.wingName || '—' },
                      { key: 'name', label: 'Division', render: (row) => <span className="cell-primary">{row.name}</span> },
                      { key: 'code', label: 'Code' },
                    ]}
                    rows={divisions.data || []}
                    rowKey={(row) => row.id}
                    emptyTitle="No scope assignments"
                    emptyDescription="No division scope rows were returned for this account"
                  />
                </>
              )}
            </>
          )}

          {tab === 'manage' && (
            <Unavailable
              title="User administration is not available here"
              description="User, role, and scope changes are managed outside this console. This screen is read-only."
            />
          )}
        </div>
      </section>
    </>
  )
}
