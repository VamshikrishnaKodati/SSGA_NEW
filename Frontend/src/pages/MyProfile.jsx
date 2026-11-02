import { useCallback, useState } from 'react'
import { employeeApi, saveBlob, statementApi } from '../services/api.js'
import { useLoader } from '../lib/useLoader.js'
import { formatDateTime, friendlyError } from '../lib/format.js'
import { ROLE_LABELS } from '../lib/permissions.js'
import {
  Alert,
  DataTable,
  DefinitionList,
  EmptyState,
  Icon,
  LoadingState,
  PageHeader,
  StatusChip,
} from '../components/ui.jsx'

export default function MyProfile({ user, ability, onSessionExpired }) {
  const [downloadError, setDownloadError] = useState('')

  const isSelfService = ability.hasRole('ROLE_EMPLOYEE_SELF')
  const employees = useLoader(
    () => employeeApi.list(),
    [],
    { enabled: ability.has('employee.view') },
  )
  const statements = useLoader(
    () => statementApi.list(),
    [],
    { enabled: ability.hasAny('statement.view', 'statement.download') },
  )

  const myRecord = isSelfService && Array.isArray(employees.data) ? employees.data[0] : null

  const download = useCallback(async (statement) => {
    setDownloadError('')

    try {
      const blob = await statementApi.download(statement.id)
      saveBlob(blob, `${statement.statementNo}.pdf`)
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
      setDownloadError(friendlyError(requestError))
    }
  }, [onSessionExpired])

  return (
    <>
      <PageHeader
        title="My profile"
        subtitle={isSelfService
          ? 'Your own employee record and issued salary statements.'
          : 'Your account details, employee record and issued statements.'}
        breadcrumbs={['Records', 'My profile']}
      />

      <section className="panel">
        <div className="panel-head">
          <div>
            <h3>Account</h3>
            <p>From the authenticated server session</p>
          </div>
        </div>
        <div className="panel-body">
          <DefinitionList
            items={[
              { term: 'Username', value: user.username },
              { term: 'User ID', value: user.userId },
              {
                term: 'Role',
                value: ability.roles.length
                  ? ability.roles.map((role) => ROLE_LABELS[role]).join(', ')
                  : 'No role authority returned',
              },
            ]}
          />
        </div>
      </section>

      {ability.has('employee.view') && (
        <section className="panel section-gap">
          <div className="panel-head">
            <div>
              <h3>My employee record</h3>
              <p>Only records your grants permit are returned</p>
            </div>
          </div>
          <div className="panel-body">
            {employees.loading ? (
              <LoadingState rows={3} />
            ) : employees.error ? (
              <Alert kind="error">{employees.error}</Alert>
            ) : isSelfService ? (
              myRecord ? (
                <DefinitionList
                  items={[
                    { term: 'Employee code', value: myRecord.employeeCode },
                    { term: 'HRMS id', value: myRecord.hrmsId || '—' },
                    { term: 'Name', value: myRecord.name },
                    { term: 'Division id', value: myRecord.divisionId },
                    { term: 'Status', value: <StatusChip value={myRecord.status} /> },
                  ]}
                />
              ) : (
                <EmptyState
                  title="No employee record linked"
                  description="No employee record is associated with your account yet. Contact the HR administrator."
                  icon="badge"
                />
              )
            ) : (
              <p className="muted">
                Your account has employee visibility across its scope; use the Employee master to
                browse records.
              </p>
            )}
          </div>
        </section>
      )}

      {ability.hasAny('statement.view', 'statement.download') && (
        <section className="panel section-gap">
          <div className="panel-head">
            <div>
              <h3>Salary statements &amp; payslips</h3>
              <p>Issued statements you are permitted to view and download</p>
            </div>
            <button type="button" className="btn ghost sm" onClick={statements.reload}>
              <Icon name="refresh" size={13} /> Refresh
            </button>
          </div>

          {downloadError && <div className="panel-body"><Alert kind="error">{downloadError}</Alert></div>}

          {statements.loading ? (
            <LoadingState rows={4} />
          ) : statements.error ? (
            <div className="panel-body"><Alert kind="error">{statements.error}</Alert></div>
          ) : (
            <DataTable
              columns={[
                { key: 'statementNo', label: 'Statement', render: (row) => <span className="cell-primary">{row.statementNo}</span> },
                { key: 'periodLabel', label: 'Period' },
                { key: 'version', label: 'Version', render: (row) => `v${row.version}` },
                { key: 'status', label: 'Status', render: (row) => <StatusChip value={row.status} /> },
                { key: 'generatedAt', label: 'Generated', render: (row) => formatDateTime(row.generatedAt) },
                {
                  key: 'actions',
                  label: '',
                  render: (row) => (
                    <button
                      type="button"
                      className="btn ghost sm"
                      disabled={!ability.has('statement.download')}
                      title={ability.has('statement.download') ? 'Download PDF' : 'Requires statement.download'}
                      onClick={() => download(row)}
                    >
                      <Icon name="down" size={13} /> PDF
                    </button>
                  ),
                },
              ]}
              rows={statements.data || []}
              rowKey={(row) => row.id}
              emptyTitle="No statements issued"
              emptyDescription="Your salary statements will appear here once payroll for a period is finalized and statements are generated."
            />
          )}
        </section>
      )}
    </>
  )
}
