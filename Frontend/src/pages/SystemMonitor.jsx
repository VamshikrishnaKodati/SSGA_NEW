import { useState } from 'react'
import { systemApi } from '../services/api.js'
import { formatDateTime, friendlyError } from '../lib/format.js'
import { Alert, Icon, PageHeader, Unavailable } from '../components/ui.jsx'

export default function SystemMonitor() {
  const [state, setState] = useState({ checking: false, status: 'unknown', checkedAt: '', error: '' })

  async function check() {
    setState((previous) => ({ ...previous, checking: true, error: '' }))

    try {
      const body = await systemApi.health()
      setState({
        checking: false,
        status: 'healthy',
        checkedAt: new Date().toISOString(),
        error: '',
        body: String(body),
      })
    } catch (requestError) {
      setState({
        checking: false,
        status: 'unreachable',
        checkedAt: new Date().toISOString(),
        error: friendlyError(requestError),
      })
    }
  }

  return (
    <>
      <PageHeader
        title="System monitor"
        subtitle="Service availability for the SSGA application programming interface."
        breadcrumbs={['Administration', 'System monitor']}
        actions={(
          <button type="button" className="btn" onClick={check} disabled={state.checking}>
            <Icon name="refresh" size={14} /> {state.checking ? 'Checking…' : 'Check API health'}
          </button>
        )}
      />

      {state.error && <Alert kind="error">{state.error}</Alert>}

      <section className="panel">
        <div className="panel-head">
          <div>
            <h3>API availability</h3>
            <p>GET /api/health (public, unauthenticated)</p>
          </div>
          {state.checkedAt && (
            <span className={`chip plain ${state.status === 'healthy' ? 'approved' : 'rejected'}`}>
              {state.status === 'healthy' ? 'Reachable' : 'Unreachable'}
            </span>
          )}
        </div>
        <div className="panel-body">
          {state.checkedAt ? (
            <div className="definition-list">
              <div className="dl-row"><dt>Endpoint</dt><dd><code>/api/health</code></dd></div>
              <div className="dl-row"><dt>Checked at</dt><dd>{formatDateTime(state.checkedAt)}</dd></div>
              <div className="dl-row"><dt>Response</dt><dd>{state.body || '—'}</dd></div>
            </div>
          ) : (
            <p className="muted">Run a health check to confirm the API is reachable.</p>
          )}
        </div>
      </section>

      <section className="panel section-gap">
        <div className="panel-head">
          <div>
            <h3>Settings &amp; monitoring</h3>
            <p>Configuration, integrations and operational telemetry</p>
          </div>
          <span className="chip warning plain">Not available</span>
        </div>
        <div className="panel-body">
          <Unavailable
            title="No settings or metrics endpoints are exposed"
            description="The backend does not currently expose configuration management, scheduled-task status, metrics or integration monitoring. Only the health probe above is available."
            missingEndpoint="GET /api/settings · GET /api/metrics · GET /api/system/jobs"
          />
        </div>
      </section>
    </>
  )
}
