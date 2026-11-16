import { useMemo } from 'react'
import { meApi } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { ROLE_LABELS } from '../lib/permissions.js'
import {
  Alert,
  Chip,
  DefinitionList,
  EmptyState,
  LoadingState,
  PageHeader,
} from '../components/ui.jsx'

function scopeText(assignment) {
  if (assignment.divisionName) return assignment.divisionName
  if (assignment.wingName) return assignment.wingName
  return assignment.scopeLabel || 'Organization'
}

export default function MyAccess({ user, ability, onSessionExpired }) {
  const access = useLoader(() => meApi.access(), [], { enabled: true })

  const assignments = access.data?.assignments || []
  const permissions = access.data?.permissions || []

  const roleChips = useMemo(
    () => Array.from(new Set((access.data?.assignments || []).map((assignment) => assignment.roleCode))),
    [access.data],
  )

  return (
    <>
      <PageHeader
        title="My access"
        subtitle="The roles assigned to your account, the scope each one reaches, and what you can do"
        breadcrumbs={['Records', 'My access']}
      />

      {access.error && (
        <Alert kind="error" title="Could not load your access">
          {access.error}
          {access.error.toLowerCase().includes('sign in') && onSessionExpired ? ' ' : ''}
        </Alert>
      )}

      <section className="panel">
        <div className="panel-head">
          <div>
            <h3>Account</h3>
          </div>
        </div>
        <div className="panel-body">
          <DefinitionList
            items={[
              { term: 'Username', value: user.username },
              { term: 'User ID', value: user.userId },
              {
                term: 'Roles',
                value: roleChips.length
                  ? roleChips.map((code) => ROLE_LABELS[code] || code).join(', ')
                  : 'No roles assigned',
              },
              { term: 'Permissions', value: `${permissions.length} effective` },
            ]}
          />
        </div>
      </section>

      <section className="panel section-gap">
        <div className="panel-head">
          <div>
            <h3>Roles and scope</h3>
            <p>Each assignment reaches only the organization unit shown here</p>
          </div>
        </div>
        <div className="panel-body">
          {access.loading ? (
            <LoadingState rows={3} label="Loading your access…" />
          ) : assignments.length === 0 ? (
            <EmptyState
              title="No roles assigned"
              description="Your account has no active role assignment, so you cannot reach any payroll data yet. Ask an administrator."
              icon="shield"
            />
          ) : (
            <div className="table-scroll">
              <table className="ui-table">
                <thead>
                  <tr>
                    <th scope="col">Role</th>
                    <th scope="col">Reaches</th>
                    <th scope="col">Maximum scope</th>
                  </tr>
                </thead>
                <tbody>
                  {assignments.map((assignment) => (
                    <tr key={assignment.assignmentId}>
                      <td>{assignment.roleName || ROLE_LABELS[assignment.roleCode] || assignment.roleCode}</td>
                      <td>{scopeText(assignment)}</td>
                      <td><Chip tone="neutral">{assignment.maxScope}</Chip></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>

      <section className="panel section-gap">
        <div className="panel-head">
          <div>
            <h3>Effective permissions</h3>
            <p>What these roles allow you to do, after any administrator override</p>
          </div>
        </div>
        <div className="panel-body">
          {access.loading ? (
            <LoadingState rows={4} label="Loading permissions…" />
          ) : permissions.length === 0 ? (
            <EmptyState
              title="No permissions"
              description="None of your roles currently grants a permission."
              icon="lock"
            />
          ) : (
            <ul className="chip-list">
              {permissions.map((code) => (
                <li key={code}><Chip tone="info">{code}</Chip></li>
              ))}
            </ul>
          )}
        </div>
      </section>

      {ability.isAdmin && (
        <Alert kind="info" title="You administer this system">
          Your account also carries System Administrator rights. Use User management to
          review or change other accounts.
        </Alert>
      )}
    </>
  )
}