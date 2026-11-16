import { useState } from 'react'
import { authApi } from '../../services/api.js'
import { Icon } from '../../components/ui.jsx'
import './login.css'

// Seeded accounts (see database/seed_demo_users.sql): exactly one per distinct
// spec role, so every role dashboard and permission set can be exercised.
// Passwords are supplied by the local/staging operator, never embedded here.
const DEMO_ACCOUNTS = [
  { username: 'sysadmin', role: 'System Administrator', scope: 'Organization-wide' },
  { username: 'orgadmin', role: 'Organization Administrator', scope: 'Organization-wide' },
  { username: 'ce_om', role: 'Chief Engineer (O&M)', scope: 'O&M Wing' },
  { username: 'ee_div1', role: 'Division Administrator', scope: 'O&M Division I' },
  { username: 'payroll_off1', role: 'Payroll Operator (Maker)', scope: 'O&M Division I' },
  { username: 'gm_md', role: 'Approving Authority (Checker)', scope: 'Organization-wide' },
  { username: 'fin_finalizer', role: 'Finance Finalizer', scope: 'Organization-wide' },
  { username: 'auditor', role: 'Auditor', scope: 'Organization-wide' },
  { username: 'report_user', role: 'Report User', scope: 'O&M Division I' },
  { username: 'emp001', role: 'Employee (self service)', scope: 'Own records' },
]

// Demo password pre-filled when a demo account is selected. Must match the
// `demo_password` variable supplied when running seed_demo_users.sql. Local /
// staging only - never for production use. Override with VITE_DEMO_PASSWORD.
const DEMO_PASSWORD =
  import.meta.env.VITE_DEMO_PASSWORD || 'Password@123'

const SHOW_DEMO_ACCOUNTS =
  import.meta.env.DEV || import.meta.env.VITE_SHOW_DEMO_ACCOUNTS === 'true'

export default function Login({ onLogin, sessionError }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const [forgotMode, setForgotMode] = useState(false)
  const [forgotUsername, setForgotUsername] = useState('')
  const [forgotSubmitting, setForgotSubmitting] = useState(false)
  const [forgotMessage, setForgotMessage] = useState('')
  const [forgotError, setForgotError] = useState('')

  const [resetMode, setResetMode] = useState(false)
  const [resetToken, setResetToken] = useState('')
  const [resetPassword, setResetPassword] = useState('')
  const [resetSubmitting, setResetSubmitting] = useState(false)
  const [resetError, setResetError] = useState('')

  function fillDemo(account) {
    setUsername(account.username)
    setPassword(DEMO_PASSWORD)
    setError('')
  }

  function backToLogin() {
    setForgotMode(false)
    setResetMode(false)
    setForgotMessage('')
    setForgotError('')
    setResetError('')
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

  async function handleForgotPassword(event) {
    event.preventDefault()
    setForgotError('')
    setForgotMessage('')

    if (!forgotUsername.trim()) {
      setForgotError('Enter your username.')
      return
    }

    setForgotSubmitting(true)

    try {
      const result = await authApi.forgotPassword(forgotUsername.trim())
      setForgotMessage(result.message || 'Reset instructions sent.')

      // Dev mode returns the reset token directly so the flow can be tested
      // without an email provider.
      if (result.resetToken) {
        setResetToken(result.resetToken)
        setForgotMode(false)
        setResetMode(true)
      }
    } catch (requestError) {
      setForgotError(
        requestError.message || 'Unable to process the request. Please try again.',
      )
    } finally {
      setForgotSubmitting(false)
    }
  }

  async function handleResetPassword(event) {
    event.preventDefault()
    setResetError('')

    if (!resetToken || !resetPassword) {
      setResetError('Enter a new password.')
      return
    }

    setResetSubmitting(true)

    try {
      await authApi.resetPassword(resetToken, resetPassword)
      setResetMode(false)
      setForgotMessage(
        'Your password has been reset. You can now sign in with the new password.'
      )
      setForgotMode(true)
    } catch (requestError) {
      setResetError(
        requestError.message || 'Unable to reset the password. Please try again.',
      )
    } finally {
      setResetSubmitting(false)
    }
  }

  if (forgotMode) {
    return (
      <main className="login-page">
        <LoginBrandPanel />

        <section className="login-form-panel">
          <div className="login-form-container">
            <div className="login-mobile-brand">HMWSSB · SSGA</div>

            <button
              className="login-back"
              type="button"
              onClick={backToLogin}
              disabled={forgotSubmitting}
            >
              Back to sign in
            </button>

            <h2>Reset your password</h2>
            <p className="login-subtitle">
              Enter your username and we will issue a password reset token
            </p>

            {forgotMessage && (
              <div className="login-message success" role="status">
                {forgotMessage}
              </div>
            )}

            {forgotError && (
              <div className="login-message error" role="alert">
                {forgotError}
              </div>
            )}

            <form className="login-form" onSubmit={handleForgotPassword}>
              <label htmlFor="forgot-username">Username</label>
              <input
                id="forgot-username"
                name="forgot-username"
                type="text"
                autoComplete="username"
                value={forgotUsername}
                onChange={(event) => setForgotUsername(event.target.value)}
                disabled={forgotSubmitting}
                required
              />

              <button
                className="login-submit"
                type="submit"
                disabled={forgotSubmitting}
              >
                {forgotSubmitting ? 'Sending…' : 'Send reset token'}
              </button>
            </form>

            <div className="login-security-note">
              <span className="login-security-dot" aria-hidden="true" />
              Your session is managed securely
            </div>
          </div>

          <footer className="login-copyright">
            SSGA · HMWSSB
          </footer>
        </section>
      </main>
    )
  }

  if (resetMode) {
    return (
      <main className="login-page">
        <LoginBrandPanel />

        <section className="login-form-panel">
          <div className="login-form-container">
            <div className="login-mobile-brand">HMWSSB · SSGA</div>

            <button
              className="login-back"
              type="button"
              onClick={backToLogin}
              disabled={resetSubmitting}
            >
              Back to sign in
            </button>

            <h2>Set a new password</h2>
            <p className="login-subtitle">
              Enter a new password for your account
            </p>

            {resetError && (
              <div className="login-message error" role="alert">
                {resetError}
              </div>
            )}

            <form className="login-form" onSubmit={handleResetPassword}>
              <label htmlFor="reset-password">New password</label>
              <div className="password-field">
                <input
                  id="reset-password"
                  name="reset-password"
                  type="password"
                  autoComplete="new-password"
                  value={resetPassword}
                  onChange={(event) => setResetPassword(event.target.value)}
                  disabled={resetSubmitting}
                  required
                />
              </div>

              <button
                className="login-submit"
                type="submit"
                disabled={resetSubmitting}
              >
                {resetSubmitting ? 'Resetting…' : 'Reset password'}
              </button>
            </form>

            <div className="login-security-note">
              <span className="login-security-dot" aria-hidden="true" />
              Your session is managed securely
            </div>
          </div>

          <footer className="login-copyright">
            SSGA · HMWSSB
          </footer>
        </section>
      </main>
    )
  }

  return (
    <main className="login-page">
      <LoginBrandPanel />

      <section className="login-form-panel">
        <div className="login-form-container">
          <div className="login-mobile-brand">HMWSSB · SSGA</div>

          <h2>Sign in</h2>
          <p className="login-subtitle">
            Use your authorized account credentials
          </p>

          {sessionError && (
            <div className="login-message error" role="status">
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

            <div className="login-actions">
              <button
                type="button"
                className="login-forgot"
                onClick={() => setForgotMode(true)}
                disabled={submitting}
              >
                Forgot password
              </button>
            </div>

            {error && (
              <div className="login-message error" role="alert">
                {error}
              </div>
            )}

            <button
              className="login-submit"
              type="submit"
              disabled={submitting}
            >
              {submitting ? 'Signing in…' : 'Sign in'}
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
                Select an account to fill its credentials. For local and staging
                use only. Password: <strong>{DEMO_PASSWORD}</strong>
              </p>
            </div>
          )}

          <div className="login-security-note">
            <span className="login-security-dot" aria-hidden="true" />
            Sessions are managed securely
          </div>
        </div>

        <footer className="login-copyright">
          SSGA · HMWSSB
        </footer>
      </section>
    </main>
  )
}

function LoginBrandPanel() {
  return (
    <section className="login-brand-panel">
      <div className="login-brand">
        <img className="login-brand-logo" src="/hmwssb-logo.png" alt="HMWSSB" />
        <img className="login-brand-logo" src="/organisation-logo.png" alt="Organisation" />
        <div className="login-brand-text">
          <strong>SSGA</strong>
          <span>HMWSSB salary management</span>
        </div>
      </div>

      <div className="login-brand-body">
        <div className="login-introduction">
          <h1>Monthly salary processing for HMWSSB staff</h1>
          <p>
            One scoped workspace for employee records, payroll preparation, approvals and salary statements
          </p>
        </div>

        <aside className="login-proof" aria-label="Sample salary statement">
          <div className="login-proof-head">
            <span className="login-proof-kicker">Salary statement</span>
            <span className="login-proof-status">Issued</span>
          </div>
          <div className="login-proof-employee">
            <strong>K. Srinivas</strong>
            <span>EE/1042 · O&amp;M Division I</span>
          </div>
          <dl className="login-proof-figures">
            <div><dt>Gross</dt><dd>₹84,200</dd></div>
            <div><dt>Deductions</dt><dd>−₹9,480</dd></div>
            <div className="net"><dt>Net pay</dt><dd>₹74,720</dd></div>
          </dl>
          <div className="login-proof-foot">
            <span>September 2026</span>
            <span className="login-proof-ref">PAY/2026/09/0417</span>
          </div>
        </aside>
      </div>

      <div className="login-panel-footer">
        <Icon name="check" size={14} />
        <span>Role-based access</span>
      </div>
    </section>
  )
}
