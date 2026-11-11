import { salaryComponentApi } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { formatAmount, humanizeStatus } from '../lib/format.js'
import {
  Alert,
  Chip,
  DataTable,
  Icon,
  Kpi,
  LoadingState,
  PageHeader,
} from '../components/ui.jsx'

export default function SalaryComponents({ ability }) {
  const canView = ability.has('salary.view')

  const components = useLoader(
    () => salaryComponentApi.list(),
    [],
    { enabled: canView },
  )

  const rows = components.data || []
  const earningsCount = rows.filter((row) => row.category === 'EARNING').length
  const deductionsCount = rows.length - earningsCount

  return (
    <>
      <PageHeader
        title="Salary components"
        subtitle="Earnings, deductions and the salary structure used when payroll lines are calculated"
        breadcrumbs={['Administration', 'Salary components']}
      />

      {!canView ? (
        <section className="panel">
          <div className="panel-body">
            <Alert kind="info">You do not have access to salary components.</Alert>
          </div>
        </section>
      ) : (
        <>
          <div className="kpi-strip">
            <Kpi label="Components" value={rows.length} icon="layers" />
            <Kpi label="Earnings" value={earningsCount} icon="calculator" />
            <Kpi label="Deductions" value={deductionsCount} icon="calculator" />
          </div>

          <section className="panel">
            <div className="panel-head">
              <div>
                <h3>Component list</h3>
                <p>Active salary components</p>
              </div>
            </div>

            {components.loading ? (
              <LoadingState rows={6} />
            ) : components.error ? (
              <div className="panel-body"><Alert kind="error">{components.error}</Alert></div>
            ) : (
              <DataTable
                columns={[
                  { key: 'componentCode', label: 'Code', nowrap: true, render: (row) => <span className="cell-primary">{row.componentCode}</span> },
                  { key: 'componentName', label: 'Component' },
                  { key: 'category', label: 'Category', render: (row) => <Chip tone={row.category === 'EARNING' ? 'success' : undefined}>{humanizeStatus(row.category)}</Chip> },
                  { key: 'calculationType', label: 'Calculation', render: (row) => humanizeStatus(row.calculationType) },
                  { key: 'rate', label: 'Rate', align: 'right', nowrap: true, render: (row) => formatAmount(row.rate) },
                  { key: 'displayOrder', label: 'Order', align: 'right', render: (row) => row.displayOrder ?? '—' },
                ]}
                rows={rows}
                rowKey={(row) => row.id}
                emptyTitle="No salary components"
                emptyDescription="No active salary components are configured"
              />
            )}

            <div className="panel-body">
              <div className="availability-note">
                <Icon name="info" size={17} />
                <div>
                  <strong>Read-only</strong>
                  <p>
                    Components are managed through controlled imports.
                  </p>
                </div>
              </div>
            </div>
          </section>
        </>
      )}
    </>
  )
}
