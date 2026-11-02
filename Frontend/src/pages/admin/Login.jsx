import { useState } from 'react'
import { authApi } from '../../services/api.js'
import './login.css'

// Seeded accounts (see database/seed_demo_users.sql and the bootstrap admin).
// Passwords are supplied by the local/staging operator, never embedded here.
const DEMO_ACCOUNTS = [
  { username: 'admin', role: 'System Administrator', scope: 'Organization-wide' },
  { username: 'org_admin', role: 'Organization Administrator', scope: 'Organization-wide' },
  { username: 'wing_admin_eng', role: 'Wing Administrator', scope: 'Engineering wing' },
  { username: 'division_admin_a1', role: 'Division Administrator', scope: 'Division A1' },
  { username: 'payroll_maker', role: 'Salary / Payroll Operator', scope: 'Division A1' },
  { username: 'payroll_checker', role: 'Approving Authority', scope: 'Division A1' },
  { username: 'payroll_finalizer', role: 'Payroll Finalizer', scope: 'Division A1' },
  { username: 'report_user', role: 'Report User', scope: 'Division A1' },
  { username: 'auditor', role: 'Auditor', scope: 'Organization-wide' },
  { username: 'employee_ravi', role: 'Employee (self-service)', scope: 'Own records' },
]
const SHOW_DEMO_ACCOUNTS =
  import.meta.env.DEV || import.meta.env.VITE_SHOW_DEMO_ACCOUNTS === 'true'

export default function Login({ onLogin, sessionError }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  function fillDemo(account) {
    setUsername(account.username)
    setPassword('')
    setError('')
  }

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')

    if (!username.trim() || !password) {
      setError('Enter both your username and password.')
      return
    }

    setSubmitting(true)

    try {
      const user = await authApi.login(username.trim(), password)
      await onLogin(user)
    } catch (requestError) {
      setError(
        requestError.message || 'Unable to sign in. Please try again.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-brand-panel">
        <div className="login-brand">
          <img src="/hmwssb-logo.png" alt="HMWSSB" style={{ width: 44, height: 44, objectFit: 'contain', background: '#fff', borderRadius: 8, padding: 2 }} />
          <img src="/organisation-logo.png" alt="Organisation" style={{ width: 44, height: 44, objectFit: 'contain', background: '#fff', borderRadius: 8, padding: 2 }} />
          <div>
            <strong>HMWSSB - SSGA</strong>
            <span>Hyderabad Metropolitan Water Supply</span>
          </div>
        </div>

        <div className="login-introduction">
          <span className="login-eyebrow">ENTERPRISE PAYROLL SYSTEM</span>
          <h1>Salary management, built around your workflow.</h1>
          <p>
            Access authorized employee information, salary processing,
            approvals and statement generation through one workspace.
          </p>
        </div>

        <div className="login-panel-footer">
          <span className="security-symbol">✓</span>
          <span>Secure, role-based application access</span>
        </div>
      </section>

      <section className="login-form-panel">
        <div className="login-form-container">
          <div className="login-mobile-brand">HMWSSB · SSGA</div>

          <span className="login-eyebrow">WELCOME BACK</span>
          <h2>Sign in to SSGA</h2>
          <p className="login-subtitle">
            Enter your authorized account credentials to continue.
          </p>

          {sessionError && (
            <div className="login-error" role="status" style={{ marginTop: 18 }}>
              {sessionError}
            </div>
          )}

          <form className="login-form" onSubmit={handleSubmit}>
            <label htmlFor="username">Username</label>
            <input
              id="username"
              name="username"
              type="text"
              autoComplete="username"
              placeholder="Enter your username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              disabled={submitting}
              required
            />

            <label htmlFor="password">Password</label>
            <div className="password-field">
              <input
                id="password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                placeholder="Enter your password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                disabled={submitting}
                required
              />
              <button
                type="button"
                className="password-toggle"
                onClick={() => setShowPassword((visible) => !visible)}
                disabled={submitting}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>

            {error && (
              <div className="login-error" role="alert">
                {error}
              </div>
            )}

            <button
              className="login-submit"
              type="submit"
              disabled={submitting}
            >
              {submitting ? 'Signing in...' : 'Sign in securely'}
              {!submitting && <span aria-hidden="true">→</span>}
            </button>
          </form>

          {SHOW_DEMO_ACCOUNTS && (
            <div className="login-demo">
              <div className="login-demo-head">
                <span>Demo accounts</span>
                <span className="login-demo-password">Local / staging only</span>
              </div>
              <ul className="login-demo-list">
                {DEMO_ACCOUNTS.map((account) => (
                  <li key={account.username}>
                    <button
                      type="button"
                      onClick={() => fillDemo(account)}
                      disabled={submitting}
                    >
                      <strong>{account.username}</strong>
                      <span>{account.role} · {account.scope}</span>
                    </button>
                  </li>
                ))}
              </ul>
              <p className="login-demo-note">
                Select an account to fill its username, then enter the password configured by
                your local or staging administrator. Demo accounts are not for production use.
              </p>
            </div>
          )}

          <div className="login-security-note">
            <span>●</span>
            Your session is managed by the application server.
          </div>
        </div>

        <footer className="login-copyright">
          SSGA · HMWSSB
        </footer>
      </section>
    </main>
  )
}