import { EmptyState, PageHeader } from '../components/ui.jsx'

export default function Notifications() {
  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle="Approval requests, returns, statement issuance and workflow alerts."
        breadcrumbs={['Administration', 'Notifications']}
      />

      <section className="panel">
        <div className="panel-head">
          <div>
            <h3>Your notifications</h3>
            <p>Alerts relevant to your role and organizational scope</p>
          </div>
        </div>
        <div className="panel-body">
          <EmptyState
            title="No notifications yet"
            description="Workflow actions currently return their outcome in the action response. Alerts for approvals, returns and statement issuance will appear here when new items are available."
            icon="bell"
          />
        </div>
      </section>
    </>
  )
}
