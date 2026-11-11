import { EmptyState, PageHeader } from '../components/ui.jsx'

export default function Notifications() {
  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle="Approval requests, returns, statement issuance and workflow alerts"
        breadcrumbs={['Administration', 'Notifications']}
      />

      <section className="panel">
        <div className="panel-body">
          <EmptyState
            title="No notifications yet"
            description="New approvals, returns and statement alerts will appear here"
            icon="bell"
          />
        </div>
      </section>
    </>
  )
}
