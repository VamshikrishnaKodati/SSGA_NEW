import { useCallback, useEffect, useState } from 'react'
import Login from './pages/admin/Login.jsx'
import AppShell from './components/AppShell.jsx'
import { authApi } from './services/api.js'
import { Alert } from './components/ui.jsx'
import './App.css'
import './styles/theme.css'

export default function App() {
  const [user, setUser] = useState(null)
  const [checkingSession, setCheckingSession] = useState(true)
  const [sessionError, setSessionError] = useState('')

  const acceptLogin = useCallback(async (authenticatedUser) => {
    if (authenticatedUser.assignments?.length === 1) {
      const activeUser = await authApi.activateAssignment(
        authenticatedUser.assignments[0].assignmentId,
      )
      setUser(activeUser)
    } else {
      setUser(authenticatedUser)
    }

    setSessionError('')
    window.location.hash = '#/dashboard'
  }, [])

  const handleSessionExpired = useCallback(() => {
    setUser(null)
    setSessionError('Your session has expired. Please sign in again.')
  }, [])

  useEffect(() => {
    let active = true

    authApi
      .me()
      .then((data) => {
        if (active) {
          setUser(data)
          setSessionError('')
        }
      })
      .catch(() => {
        if (active) setUser(null)
      })
      .finally(() => {
        if (active) setCheckingSession(false)
      })

    return () => {
      active = false
    }
  }, [])

  if (checkingSession) {
    return (
      <main className="app-loading">
        <div className="loading-indicator" />
        <p>Connecting to SSGA…</p>
      </main>
    )
  }

  if (!user) {
    return (
      <Login
        onLogin={acceptLogin}
        sessionError={sessionError}
      />
    )
  }

  if (!user.activeAssignmentId) {
    return (
      <RoleAssignmentSelection
        user={user}
        onActivated={(activeUser) => {
          setUser(activeUser)
          window.location.hash = '#/dashboard'
        }}
        onSignOut={() => setUser(null)}
      />
    )
  }

  return (
    <AppShell
      user={user}
      onRoleChanged={setUser}
      onSignOut={() => {
        setUser(null)
        setSessionError('')
      }}
      onSessionExpired={handleSessionExpired}
    />
  )
}

function RoleAssignmentSelection({ user, onActivated, onSignOut }) {
  const [assignmentId, setAssignmentId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const assignments = user.assignments || []

  async function activate(event) {
    event.preventDefault()
    if (!assignmentId) return

    setBusy(true)
    setError('')
    try {
      onActivated(await authApi.activateAssignment(Number(assignmentId)))
    } catch (requestError) {
      setError(requestError.message || 'Unable to activate this role and scope.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="app-loading role-assignment-page">
      <section className="panel role-assignment-panel">
        <div className="panel-head">
          <div>
            <h1>Select your active workspace</h1>
            <p>Choose one authorized role and scope. Permissions will not be combined across assignments.</p>
          </div>
        </div>
        <div className="panel-body">
          {assignments.length === 0 ? (
            <Alert kind="error">
              No active role and scope assignment is configured for this account. Contact an administrator.
            </Alert>
          ) : (
            <form className="stack" onSubmit={activate}>
              <label className="field">
                <span>Role and assigned scope</span>
                <select
                  required
                  value={assignmentId}
                  onChange={(event) => setAssignmentId(event.target.value)}
                  disabled={busy}
                >
                  <option value="">Select a workspace</option>
                  {assignments.map((assignment) => (
                    <option key={assignment.assignmentId} value={assignment.assignmentId}>
                      {assignment.roleName} · {assignment.scopeLabel}
                    </option>
                  ))}
                </select>
              </label>
              {error && <Alert kind="error">{error}</Alert>}
              <div className="detail-actions">
                <button className="btn" type="submit" disabled={!assignmentId || busy}>
                  {busy ? 'Activating…' : 'Continue'}
                </button>
                <button className="btn ghost" type="button" onClick={onSignOut} disabled={busy}>
                  Sign out
                </button>
              </div>
            </form>
          )}
        </div>
      </section>
    </main>
  )
}
