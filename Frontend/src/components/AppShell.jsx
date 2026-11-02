import { useEffect, useMemo, useState } from 'react'
import { createAbility, primaryRoleLabel, visibleNav } from '../lib/permissions.js'
import { authApi } from '../services/api.js'
import { ConfirmDialog, Icon } from './ui.jsx'
import RoleDashboard from '../pages/RoleDashboards.jsx'
import EmployeeMaster from '../pages/EmployeeMaster.jsx'
import OrganizationMaster from '../pages/OrganizationMaster.jsx'
import SalaryComponents from '../pages/SalaryComponents.jsx'
import ApprovalInbox from '../pages/ApprovalInbox.jsx'
import Reports from '../pages/Reports.jsx'
import ImportCenter from '../pages/ImportCenter.jsx'
import AccessConsole from '../pages/AccessConsole.jsx'
import AuditTrail from '../pages/AuditTrail.jsx'
import Notifications from '../pages/Notifications.jsx'
import SystemMonitor from '../pages/SystemMonitor.jsx'
import MyProfile from '../pages/MyProfile.jsx'
import PayrollWorkspace from '../pages/payroll/PayrollWorkspace.jsx'
import StatementWorkspace from '../pages/payroll/StatementWorkspace.jsx'

const PAGES = {
  dashboard: RoleDashboard,
  payroll: PayrollWorkspace,
  statements: StatementWorkspace,
  approvals: ApprovalInbox,
  imports: ImportCenter,
  employees: EmployeeMaster,
  profile: MyProfile,
  reports: Reports,
  organization: OrganizationMaster,
  'salary-components': SalaryComponents,
  access: AccessConsole,
  audit: AuditTrail,
  notifications: Notifications,
  system: SystemMonitor,
}

function readRoute() {
  const hash = window.location.hash.replace(/^#\/?/, '')
  return hash || 'dashboard'
}

function navigateTo(id) {
  window.location.hash = `#/${id}`
}

export default function AppShell({ user, onSignOut, onSessionExpired, onRoleChanged }) {
  const ability = useMemo(() => createAbility(user), [user])
  const groups = useMemo(() => visibleNav(ability), [ability])
  const allowedIds = useMemo(
    () => groups.flatMap((group) => group.items.map((item) => item.id)),
    [groups],
  )

  const [route, setRoute] = useState(() => readRoute())
  const [navOpen, setNavOpen] = useState(false)
  const [confirmSignOut, setConfirmSignOut] = useState(false)
  const [switchingRole, setSwitchingRole] = useState(false)
  const [roleSwitchError, setRoleSwitchError] = useState('')

  useEffect(() => {
    function onHashChange() {
      setRoute(readRoute())
    }

    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  const activeRoute = allowedIds.includes(route) ? route : 'dashboard'

  useEffect(() => {
    if (route !== activeRoute) {
      navigateTo(activeRoute)
    }
  }, [route, activeRoute])

  const activeItem = groups
    .flatMap((group) => group.items)
    .find((item) => item.id === activeRoute)

  const PageComponent = PAGES[activeRoute] || RoleDashboard

  function navigate(id) {
    navigateTo(id)
    setNavOpen(false)
  }

  async function switchRole(assignmentId) {
    if (!assignmentId || String(assignmentId) === String(user.activeAssignmentId) || switchingRole) return

    setSwitchingRole(true)
    setRoleSwitchError('')
    try {
      const activeUser = await authApi.activateAssignment(Number(assignmentId))
      onRoleChanged(activeUser)
      navigateTo('dashboard')
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setRoleSwitchError(requestError.message || 'Unable to switch workspace.')
    } finally {
      setSwitchingRole(false)
    }
  }

  const pageProps = { user, ability, onSessionExpired, onNavigate: navigate }

  return (
    <div className="shell">
      <aside className={`shell-sidebar${navOpen ? ' open' : ''}`} aria-label="Primary navigation">
        <div className="shell-brand">
          <img src="/hmwssb-logo.png" alt="HMWSSB" className="brand-logo" />
          <div className="shell-brand-text">
            <strong>SSGA</strong>
            <span>HMWSSB · Salary Management</span>
          </div>
        </div>

        <nav className="shell-nav" aria-label="Application modules">
          {groups.map((group) => (
            <div className="shell-nav-group" key={group.id}>
              <div className="shell-nav-heading">{group.label}</div>
              {group.items.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className={`shell-nav-item${activeRoute === item.id ? ' active' : ''}`}
                  onClick={() => navigate(item.id)}
                  aria-current={activeRoute === item.id ? 'page' : undefined}
                >
                  <span className="shell-nav-icon"><Icon name={item.icon} size={16} /></span>
                  <span className="shell-nav-label">{item.label}</span>
                </button>
              ))}
            </div>
          ))}
        </nav>

        <div className="shell-sidebar-footer">
          <p>SSGA · HMWSSB<br />Authorized access only</p>
        </div>
      </aside>

      {navOpen && (
        <div
          role="presentation"
          onClick={() => setNavOpen(false)}
          style={{ position: 'fixed', inset: 0, zIndex: 35, background: 'rgba(8,24,44,0.35)' }}
        />
      )}

      <div className="shell-main">
        <div className="shell-mobile-bar">
          <button type="button" className="icon-button shell-menu-button" onClick={() => setNavOpen(true)} aria-label="Open navigation">
            <Icon name="menu" size={18} />
          </button>
          <div className="shell-mobile-brand">
            <strong>SSGA</strong>
            <span>HMWSSB</span>
          </div>
        </div>

        <header className="shell-topbar">
          <div className="shell-topbar-context">
            <span className="shell-topbar-eyebrow">SSGA Workspace</span>
            <strong>{activeItem?.label || 'Dashboard'}</strong>
          </div>

          <div className="shell-topbar-spacer" />

          <button
            type="button"
            className="icon-button shell-notifications-button"
            onClick={() => navigate('notifications')}
            aria-label="Open notifications"
            title="Open notifications"
          >
            <Icon name="bell" size={16} />
          </button>

          {user.assignments?.length > 1 ? (
            <label className="shell-role-switch">
              <span className="sr-only">Active role and scope</span>
              <select
                aria-label="Active role and scope"
                value={user.activeAssignmentId || ''}
                onChange={(event) => switchRole(event.target.value)}
                disabled={switchingRole}
              >
                {user.assignments.map((assignment) => (
                  <option key={assignment.assignmentId} value={assignment.assignmentId}>
                    {assignment.roleName} · {assignment.scopeLabel}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <span className="shell-role-pill">
              <Icon name="shield" size={13} />
              {primaryRoleLabel(user)} · {user.activeAssignment?.scopeLabel || 'Assigned scope'}
            </span>
          )}

          <button type="button" className="shell-signout" onClick={() => setConfirmSignOut(true)}>
            <Icon name="logout" size={14} /> Sign out
          </button>
        </header>

        <main className="shell-content">
          {roleSwitchError && (
            <div className="shell-role-error" role="alert">{roleSwitchError}</div>
          )}
          <PageComponent {...pageProps} />
        </main>
      </div>

      <ConfirmDialog
        open={confirmSignOut}
        title="Sign out of SSGA?"
        description="Your session is managed by the application server. Signing out clears this browser session."
        confirmLabel="Sign out"
        onCancel={() => setConfirmSignOut(false)}
        onConfirm={() => {
          setConfirmSignOut(false)
          onSignOut()
        }}
      />
    </div>
  )
}
