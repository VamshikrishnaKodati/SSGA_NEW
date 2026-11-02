import { useEffect, useState } from 'react'
import { importApi, mappingProfileApi, saveBlob } from '../services/api.js'
import { formatAmount, formatDateTime, friendlyError } from '../lib/format.js'
import { approveBlocked, finalizeBlocked, uploadRejection } from '../lib/importGuards.js'
import {
  SHEET_TYPES,
  TARGET_FIELDS,
  OPTION_FIELDS,
  SUPPORTS_WIDE,
  VALIDATABLE_TYPE,
  CONFIDENCE,
  buildMappingPayload,
  usedHeadersFor,
  canValidateSheet,
  stepStates,
  applyAutoMap,
  profileFromDraft,
  applyProfile,
  validationBlocker,
} from '../lib/importMapping.js'
import {
  Alert,
  Chip,
  ConfirmDialog,
  DataTable,
  DefinitionList,
  Icon,
  PageHeader,
  ReasonDialog,
  StatusChip,
} from '../components/ui.jsx'

const STEPS = [
  { key: 'upload', label: 'Upload' },
  { key: 'analyse', label: 'Analyse' },
  { key: 'map', label: 'Map & validate' },
  { key: 'assign', label: 'Assign' },
  { key: 'submit', label: 'Submit' },
  { key: 'approve', label: 'Approve' },
  { key: 'finalize', label: 'Import' },
]

function formatElapsed(seconds) {
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  if (minutes === 0) return `${remainder}s`
  return `${minutes}m ${remainder}s`
}

function profileOptionsFor(draft, profiles) {
  const type = draft?.autoMapResult?.importType || draft?.type
  if (!type) return []
  return (profiles || []).filter((profile) => profile.importType === type)
}

function ConfidenceChip({ confidence, reason }) {
  const tone = confidence === CONFIDENCE.HIGH ? 'approved' : confidence === CONFIDENCE.REVIEW ? 'warning' : 'muted'
  return (
    <span className="confidence-chip" title={reason || confidence}>
      <Chip tone={tone}>{confidence}</Chip>
    </span>
  )
}

export default function ImportCenter({ user, ability, onSessionExpired }) {
  const [job, setJob] = useState(null)
  const [analysis, setAnalysis] = useState(null)
  const [drafts, setDrafts] = useState({})
  const [selectedSheet, setSelectedSheet] = useState(null)
  const [assignment, setAssignment] = useState({ userId: '', assignmentRole: '', divisionId: '', notes: '' })
  const [assignmentResult, setAssignmentResult] = useState(null)
  const [workflow, setWorkflow] = useState(null)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [dialog, setDialog] = useState(null)
  const [lookupId, setLookupId] = useState('')
  const [jobs, setJobs] = useState([])
  const [previews, setPreviews] = useState({})
  const [profiles, setProfiles] = useState([])
  const [progress, setProgress] = useState(null)

  const canPrepare = ability.hasAny('salary.manage', 'payroll.prepare', 'employee.create')
  const canAssign = ability.hasAny('user.manage', 'salary.manage', 'payroll.prepare', 'payroll.approve')
  const canSubmit = ability.hasAny('payroll.submit', 'payroll.prepare', 'salary.manage')
  const canDecide = ability.has('payroll.approve')
  const canFinalize = ability.has('payroll.finalize')

  const states = stepStates({ job, analysis, drafts, workflow })
  const actorId = user?.userId
  const identity = {
    uploadedBy: job?.uploadedBy ?? analysis?.uploadedBy ?? workflow?.uploadedBy,
    submittedBy: workflow?.submittedBy,
    approvedBy: workflow?.approvedBy,
  }
  const isApproverBlocked = approveBlocked(actorId, identity)
  const isFinalizerBlocked = finalizeBlocked(actorId, identity)

  function guard(fn) {
    return async (...args) => {
      setError('')
      setMessage('')

      try {
        return await fn(...args)
      } catch (requestError) {
        if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
        setError(friendlyError(requestError))
        return null
      } finally {
        setBusy('')
      }
    }
  }

  function seed(authoredSheets) {
    const next = {}
    ;(authoredSheets || []).forEach((sheet) => {
      const suggested = SHEET_TYPES.some((type) => type.value === sheet.suggestedType)
        ? sheet.suggestedType
        : ''
      next[sheet.sheetIndex] = {
        type: suggested,
        columns: {},
        options: {},
        components: [{ code: '', header: '' }],
        notes: '',
        mappingResult: null,
        validation: null,
      }
    })
    setDrafts(next)
    setSelectedSheet(authoredSheets?.[0]?.sheetIndex ?? null)
  }

  const upload = guard(async (file) => {
    const rejection = uploadRejection(file)
    if (rejection) {
      setError(rejection)
      return
    }
    setBusy('upload')
    const response = await importApi.upload(file)
    setJob({
      jobId: response.jobId,
      status: response.status,
      fileName: response.fileName,
      sourceType: response.sourceType,
      uploadedBy: user?.userId,
    })
    setAnalysis(null)
    setDrafts({})
    setSelectedSheet(null)
    setWorkflow(null)
    setProgress(null)
    setMessage(`Uploaded ${response.fileName}. Job #${response.jobId} created.`)
  })

  const loadById = guard(async (id) => {
    setBusy('analyze')
    const response = await importApi.analyze(id)
    const progressResponse = await importApi.progress(id)
    setJob({
      jobId: id,
      status: progressResponse.status || response.status,
      uploadedBy: response.uploadedBy,
      fileName: progressResponse.fileName,
      sourceType: progressResponse.sourceType,
    })
    setAnalysis(response)
    setProgress(progressResponse)
    setWorkflow(null)
    seed(response.sheets)
    setMessage(`Loaded import job #${id}.`)
  })

  useEffect(() => {
    if (!job?.jobId || (busy !== 'Finalize' && job.status !== 'IMPORTING')) return undefined

    let active = true
    let requestInFlight = false
    const refreshProgress = async () => {
      if (requestInFlight) return
      requestInFlight = true
      try {
        const response = await importApi.progress(job.jobId)
        if (active) {
          setProgress(response)
          setJob((previous) => (
            previous ? { ...previous, status: response.status } : previous
          ))
        }
      } catch (requestError) {
        if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
        if (active) setError(friendlyError(requestError))
      } finally {
        requestInFlight = false
      }
    }

    refreshProgress()
    const timerId = window.setInterval(refreshProgress, 1500)
    return () => {
      active = false
      window.clearInterval(timerId)
    }
  }, [busy, job?.jobId, job?.status, onSessionExpired])

  async function refreshJobs() {
    try {
      const response = await importApi.listJobs()
      setJobs(Array.isArray(response) ? response : [])
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
    }
  }

  async function loadProfiles(importType) {
    try {
      const response = await mappingProfileApi.list(importType)
      setProfiles(Array.isArray(response) ? response : [])
    } catch (requestError) {
      if (requestError?.status === 401 && onSessionExpired) onSessionExpired()
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refreshJobs()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const autoMapSheet = guard(async (sheetIndex) => {
    setBusy(`automap-${sheetIndex}`)
    const response = await importApi.autoMap(job.jobId, sheetIndex)
    setDrafts((previous) => ({
      ...previous,
      [sheetIndex]: {
        ...applyAutoMap(previous[sheetIndex], response),
        mappingResult: null,
        validation: null,
      },
    }))
    loadProfiles(response?.importType)
    const matched = (response?.fields || []).filter((field) => field.sourceHeader).length
    setMessage(`Auto-mapped ${matched} field(s) for sheet ${sheetIndex}. Review anything flagged for review.`)
  })

  const showPreview = guard(async (sheetIndex) => {
    setBusy(`preview-${sheetIndex}`)
    const response = await importApi.preview(job.jobId, sheetIndex)
    setPreviews((previous) => ({ ...previous, [sheetIndex]: response }))
    setMessage(`Preview loaded for sheet ${sheetIndex}.`)
  })

  const downloadIssues = guard(async (sheetIndex) => {
    setBusy(`issues-${sheetIndex}`)
    const blob = await importApi.downloadIssues(job.jobId, sheetIndex)
    saveBlob(blob, `import-job-${job.jobId}-sheet-${sheetIndex}-errors.csv`)
    setMessage('Error report downloaded.')
  })

  const saveProfile = guard(async (sheetIndex, name) => {
    const draft = drafts[sheetIndex]
    const autoMap = draft.autoMapResult
    if (!autoMap?.importType || !autoMap?.structureSignature) {
      throw new Error('Run Auto-map first so the structure signature is known.')
    }
    setBusy(`saveprofile-${sheetIndex}`)
    const profile = profileFromDraft(draft)
    await mappingProfileApi.create({
      name,
      importType: autoMap.importType,
      structureSignature: autoMap.structureSignature,
      schemaVersion: 1,
      columnMapping: profile.columnMapping,
      options: profile.options,
    })
    loadProfiles(autoMap.importType)
    setMessage(`Mapping profile "${name}" saved.`)
  })

  const applySavedProfile = (sheetIndex, profile) => {
    setDrafts((previous) => ({
      ...previous,
      [sheetIndex]: {
        ...applyProfile(previous[sheetIndex], profile),
        mappingResult: null,
        validation: null,
      },
    }))
    setMessage(`Applied mapping profile "${profile.name}".`)
  }

  const analyze = guard(async () => {
    setBusy('analyze')
    const response = await importApi.analyze(job.jobId)
    setJob((previous) => ({ ...previous, status: response.status }))
    setAnalysis(response)
    seed(response.sheets)
    setMessage('Workbook analysed. Classify each sheet and map its columns.')
  })

  const saveMapping = guard(async (sheetIndex) => {
    const draft = drafts[sheetIndex]
    setBusy(`map-${sheetIndex}`)
    const response = await importApi.confirmMapping(job.jobId, sheetIndex, buildMappingPayload(draft))
    updateDraft(sheetIndex, { mappingResult: response, validation: null })
    setMessage(`Mapping saved for sheet ${sheetIndex}.`)
  })

  const validateSheet = guard(async (sheetIndex) => {
    const draft = drafts[sheetIndex]
    const blocker = validationBlocker(draft)
    if (blocker) {
      setError(blocker)
      return
    }

    setBusy(`validate-${sheetIndex}`)
    const mappingResponse = await importApi.confirmMapping(
      job.jobId,
      sheetIndex,
      buildMappingPayload(draft),
    )
    updateDraft(sheetIndex, { mappingResult: mappingResponse, validation: null })
    const response = await importApi.validateSheet(job.jobId, sheetIndex)
    updateDraft(sheetIndex, { validation: response })
    setMessage(`Validation complete: ${response.validRows} valid · ${response.errorRows} error · ${response.warningRows} warning.`)
  })

  const assign = guard(async (event) => {
    event.preventDefault()
    setBusy('assign')
    const response = await importApi.assign(job.jobId, {
      userId: assignment.userId ? Number(assignment.userId) : null,
      assignmentRole: assignment.assignmentRole || null,
      divisionId: assignment.divisionId ? Number(assignment.divisionId) : null,
      notes: assignment.notes || null,
    })
    setAssignmentResult(response)
    setMessage('Assignment recorded.')
  })

  const runWorkflow = (label, fn, remarks) => guard(async () => {
    setBusy(label)
    try {
      const response = await fn(remarks)
      setWorkflow(response)
      setJob((previous) => (previous ? { ...previous, status: response.status } : previous))
      if (label === 'Finalize') {
        setProgress(await importApi.progress(job.jobId))
      }
      setMessage(response.message || `${label} completed.`)
    } catch (requestError) {
      if (label === 'Finalize') {
        const latestProgress = await importApi.progress(job.jobId)
        setProgress(latestProgress)
        setJob((previous) => (
          previous ? { ...previous, status: latestProgress.status } : previous
        ))
      }
      throw requestError
    }
  })

  function updateDraft(sheetIndex, patch) {
    setDrafts((previous) => ({ ...previous, [sheetIndex]: { ...previous[sheetIndex], ...patch } }))
  }

  function patchDraft(sheetIndex, nestedKey, nestedPatch) {
    setDrafts((previous) => ({
      ...previous,
      [sheetIndex]: {
        ...previous[sheetIndex],
        [nestedKey]: { ...previous[sheetIndex][nestedKey], ...nestedPatch },
        mappingResult: null,
        validation: null,
      },
    }))
  }

  function patchComponent(sheetIndex, index, patch) {
    setDrafts((previous) => {
      const draft = previous[sheetIndex]
      const components = draft.components.map((component, position) => (
        position === index ? { ...component, ...patch } : component
      ))
      return {
        ...previous,
        [sheetIndex]: { ...draft, components, mappingResult: null, validation: null },
      }
    })
  }

  function addComponent(sheetIndex) {
    setDrafts((previous) => {
      const draft = previous[sheetIndex]
      return {
        ...previous,
        [sheetIndex]: {
          ...draft,
          components: [...draft.components, { code: '', header: '' }],
          mappingResult: null,
          validation: null,
        },
      }
    })
  }

  function removeComponent(sheetIndex, index) {
    setDrafts((previous) => {
      const draft = previous[sheetIndex]
      return {
        ...previous,
        [sheetIndex]: {
          ...draft,
          components: draft.components.filter((_, position) => position !== index),
          mappingResult: null,
          validation: null,
        },
      }
    })
  }

  function suggestionFor(sheetIndex, field) {
    const result = drafts[sheetIndex]?.autoMapResult
    return (result?.fields || []).find((item) => item.field === field) || null
  }

  function dismissPreview(sheetIndex) {
    setPreviews((previous) => {
      const next = { ...previous }
      delete next[sheetIndex]
      return next
    })
  }

  const headers = analysis?.sheets?.find((sheet) => sheet.sheetIndex === selectedSheet)?.headers || []
  const activeDraft = selectedSheet === null ? null : drafts[selectedSheet]
  const activeSheet = analysis?.sheets?.find((sheet) => sheet.sheetIndex === selectedSheet)

  return (
    <>
      <PageHeader
        title="Data import"
        subtitle="Upload salary, employee or payroll workbooks, map their sheets, validate, then route through maker–checker approval."
        breadcrumbs={['Operations', 'Data import']}
        actions={job ? (
          <div className="import-head">
            <Chip>Job #{job.jobId}</Chip>
            <StatusChip value={job.status} />
          </div>
        ) : null}
      />

      {error && <Alert kind="error">{error}</Alert>}
      {message && <Alert kind="success" onDismiss={() => setMessage('')}>{message}</Alert>}

      {progress && (
        progress.startedAt
        || progress.status === 'IMPORTING'
        || ['IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED'].includes(progress.status)
      ) && (
        <section className="panel import-progress-panel">
          <div className="panel-head import-progress-heading">
            <div>
              <h3>Import progress · Job #{progress.jobId}</h3>
              <p>
                Backend status: <StatusChip value={progress.status} />
                {' '}· Importing records does not generate or finalize monthly payroll.
              </p>
            </div>
            <strong className="import-progress-percent">
              {progress.progressAvailable ? `${progress.percentage}%` : '—'}
            </strong>
          </div>
          <div className="panel-body stack">
            <div className="import-job-details">
              <div><span>File</span><strong>{progress.fileName || '—'}</strong></div>
              <div>
                <span>Period</span>
                <strong>
                  {progress.periodStart || progress.periodEnd
                    ? `${progress.periodStart || '—'} – ${progress.periodEnd || '—'}`
                    : 'Not recorded for this job'}
                </strong>
              </div>
              <div><span>Wing</span><strong>Not recorded for this job</strong></div>
              <div><span>Division</span><strong>Not recorded for this job</strong></div>
              <div><span>Uploader ID</span><strong>{progress.uploadedBy ?? '—'}</strong></div>
              <div><span>Started</span><strong>{progress.startedAt ? formatDateTime(progress.startedAt) : '—'}</strong></div>
            </div>

            <div className="import-progress-main">
              <div className="import-progress-summary">
                <div className="import-progress-copy">
                  <h4>
                    {progress.status === 'IMPORTING'
                      ? 'Importing approved data…'
                      : progress.status === 'FAILED'
                        ? 'Import failed'
                        : progress.status === 'IMPORTED' || progress.status === 'PARTIALLY_IMPORTED'
                          ? 'Import processing complete'
                          : 'Import status'}
                  </h4>
                  <p>
                    {progress.status === 'IMPORTING'
                      ? 'Counts reflect backend processing events. Imported rows remain uncommitted until this operation succeeds.'
                      : progress.status === 'FAILED'
                        ? 'The import transaction failed. No partial data was committed.'
                        : 'Review the server-reported import outcome below.'}
                  </p>
                  {progress.progressAvailable ? (
                    <div
                      className="import-progress-track"
                      role="progressbar"
                      aria-label="Import progress"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={progress.percentage}
                    >
                      <span style={{ width: `${progress.percentage}%` }} />
                    </div>
                  ) : (
                    <p className="muted">Backend progress events are not available for this job.</p>
                  )}
                  <div className="import-progress-count">
                    {progress.progressAvailable
                      ? `${progress.processedRecords.toLocaleString()} of ${progress.totalRecords.toLocaleString()} records processed`
                      : `— of ${progress.totalRecords.toLocaleString()} records processed`}
                  </div>
                </div>
                <div className="import-progress-counts">
                  <div><strong>{progress.progressAvailable ? progress.importedRows.toLocaleString() : '—'}</strong><span>Imported</span></div>
                  <div><strong>{progress.progressAvailable ? progress.pendingRows.toLocaleString() : '—'}</strong><span>Pending</span></div>
                  <div><strong>{progress.progressAvailable ? progress.failedRows.toLocaleString() : '—'}</strong><span>Failed</span></div>
                  <div><strong>{progress.progressAvailable ? progress.skippedRows.toLocaleString() : '—'}</strong><span>Skipped</span></div>
                </div>
              </div>

              <aside className="import-progress-details">
                <h4>Progress details</h4>
                <dl>
                  <div><dt>Total records</dt><dd>{progress.totalRecords.toLocaleString()}</dd></div>
                  <div><dt>Processed</dt><dd>{progress.progressAvailable ? `${progress.processedRecords.toLocaleString()} (${progress.percentage}%)` : '—'}</dd></div>
                  <div><dt>Started at</dt><dd>{progress.startedAt ? formatDateTime(progress.startedAt) : '—'}</dd></div>
                  <div><dt>Elapsed time</dt><dd>{progress.progressAvailable ? formatElapsed(progress.elapsedSeconds) : '—'}</dd></div>
                  <div>
                    <dt>Estimated time left</dt>
                    <dd>
                      {progress.estimatedSecondsRemaining === null
                        || progress.estimatedSecondsRemaining === undefined
                        ? '—'
                        : `~ ${formatElapsed(progress.estimatedSecondsRemaining)}`}
                    </dd>
                  </div>
                </dl>
              </aside>
            </div>

            <div className="import-live-log">
              <h4>Import log · Backend events</h4>
              <div className="import-live-log-scroll">
                {progress.events.length === 0 ? (
                  <p className="muted">No processing events have been recorded for this job.</p>
                ) : (
                  <table>
                    <thead>
                      <tr><th>Time</th><th>Level</th><th>Message</th></tr>
                    </thead>
                    <tbody>
                      {progress.events.map((event) => (
                        <tr key={event.id}>
                          <td>{formatDateTime(event.timestamp)}</td>
                          <td><span className={`import-log-level ${event.level.toLowerCase()}`}>{event.level}</span></td>
                          <td>{event.message}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </div>

            {progress.status === 'IMPORTING' && (
              <Alert kind="info">
                Import runs after approval in this workflow. When processing completes, review the backend result before moving to any monthly payroll workflow.
              </Alert>
            )}
            {['IMPORTED', 'PARTIALLY_IMPORTED'].includes(progress.status) && (
              <Alert kind="success">
                Next stage: review the completed import results and any row errors. Monthly payroll generation and finalization remain separate workflows.
              </Alert>
            )}
          </div>
        </section>
      )}

      <section className="panel">
        <div className="panel-body">
          <ol className="import-stepper">
            {STEPS.map((step, index) => (
              <li key={step.key} className={`import-step ${states[step.key] || ''}`.trim()}>
                <span className="import-step-index">{index + 1}</span>
                {step.label}
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h3>1 · Upload or open a job</h3>
            <p>Excel workbooks (.xlsx / .xlsm / .xls) or .csv files</p>
          </div>
        </div>
        <div className="panel-body stack">
          {canPrepare ? (
            <label className="dropzone">
              <Icon name="upload" size={22} />
              <strong>{busy === 'upload' ? 'Uploading…' : 'Choose a workbook or CSV'}</strong>
              <span>Accepted formats: .xlsx, .xlsm, .xls, .csv — the file is hashed (SHA-256) for integrity tracking.</span>
              <input
                type="file"
                accept=".xlsx,.xlsm,.xls,.csv"
                disabled={busy === 'upload'}
                onChange={(event) => {
                  const file = event.target.files?.[0]
                  if (file) upload(file)
                  event.target.value = ''
                }}
              />
            </label>
          ) : (
            <Alert kind="info">Upload requires salary.manage, payroll.prepare or employee.create.</Alert>
          )}

          <form
            className="inline-form"
            onSubmit={(event) => {
              event.preventDefault()
              if (lookupId) loadById(Number(lookupId))
            }}
          >
            <label className="field">
              <span>Open an existing job by ID</span>
              <input type="number" min="1" value={lookupId} onChange={(event) => setLookupId(event.target.value)} placeholder="Job ID" />
            </label>
            <button type="submit" className="btn ghost" disabled={!lookupId || busy === 'analyze'}>
              <Icon name="search" size={14} /> Open job
            </button>
          </form>

          <div className="import-subsection">
            <div className="import-subhead">
              <strong>Recent jobs</strong>
              <button type="button" className="btn ghost sm" onClick={refreshJobs}>
                <Icon name="refresh" size={13} /> Refresh
              </button>
            </div>
            <DataTable
              columns={[
                { key: 'jobId', label: 'Job', align: 'right' },
                { key: 'fileName', label: 'File', render: (row) => row.fileName || '—' },
                { key: 'sourceType', label: 'Source', render: (row) => row.sourceType || '—' },
                { key: 'status', label: 'Status', render: (row) => <StatusChip value={row.status} /> },
                { key: 'validRows', label: 'Valid rows', align: 'right', render: (row) => row.validRows ?? '—' },
                {
                  key: 'actions',
                  label: '',
                  render: (row) => (
                    <button type="button" className="btn ghost sm" onClick={() => loadById(row.jobId)}>
                      Open
                    </button>
                  ),
                },
              ]}
              rows={jobs}
              rowKey={(row) => row.jobId}
              emptyTitle="No import jobs yet"
              emptyDescription="Upload a workbook or open a job by ID above."
            />
          </div>

          {job && (
            <DefinitionList
              items={[
                { term: 'Job ID', value: job.jobId },
                { term: 'File name', value: job.fileName || '—' },
                { term: 'Source type', value: job.sourceType || '—' },
                { term: 'Status', value: <StatusChip value={job.status} /> },
              ]}
            />
          )}
        </div>
      </section>

      {job && (
        <section className="panel section-gap">
          <div className="panel-head">
            <div>
              <h3>2 · Analyse workbook</h3>
              <p>Detect sheets, headers and a suggested data type for each sheet</p>
            </div>
            <button type="button" className="btn" onClick={analyze} disabled={busy === 'analyze'}>
              <Icon name="refresh" size={14} /> {busy === 'analyze' ? 'Analysing…' : analysis ? 'Re-analyse' : 'Analyse'}
            </button>
          </div>
          <div className="panel-body">
            {!analysis ? (
              <p className="muted">Run the analysis to see sheet structure.</p>
            ) : (
              <DataTable
                columns={[
                  { key: 'sheetIndex', label: '#', align: 'right' },
                  { key: 'sheetName', label: 'Sheet', render: (row) => <span className="cell-primary">{row.sheetName}</span> },
                  { key: 'dataRowsEstimate', label: 'Rows (est.)', align: 'right' },
                  { key: 'columnCount', label: 'Columns', align: 'right' },
                  { key: 'headerRowNumber', label: 'Header row', render: (row) => row.headerRowNumber ?? '—' },
                  { key: 'suggestedType', label: 'Suggested type', render: (row) => row.suggestedType ? <StatusChip value={row.suggestedType} /> : '—' },
                  { key: 'note', label: 'Note', render: (row) => row.note || '—' },
                ]}
                rows={analysis.sheets || []}
                rowKey={(row) => row.sheetIndex}
                emptyTitle="No sheets detected"
                emptyDescription="The workbook did not contain readable sheets."
              />
            )}
          </div>
        </section>
      )}

      {analysis && (
        <section className="panel section-gap">
          <div className="panel-head">
            <div>
              <h3>3 · Map columns &amp; validate</h3>
              <p>Classify each sheet, map each target field to a source column, then validate</p>
            </div>
          </div>
          <div className="panel-body">
            <div className="tabs" role="tablist">
              {(analysis.sheets || []).map((sheet) => {
                const draft = drafts[sheet.sheetIndex]
                const badge = draft?.validation?.errorRows > 0 || draft?.mappingResult
                return (
                  <button
                    key={sheet.sheetIndex}
                    type="button"
                    role="tab"
                    className={`tab ${sheet.sheetIndex === selectedSheet ? 'active' : ''}`.trim()}
                    aria-selected={sheet.sheetIndex === selectedSheet}
                    onClick={() => setSelectedSheet(sheet.sheetIndex)}
                  >
                    {sheet.sheetName}
                    {badge ? ' ●' : ''}
                  </button>
                )
              })}
            </div>

            {activeDraft && activeSheet && (
              <div className="import-sheet">
                <div className="import-sheet-head">
                  <div>
                    <strong>{activeSheet.sheetName}</strong>
                    <span className="inline-meta"> · sheet {activeSheet.sheetIndex} · {activeSheet.dataRowsEstimate} rows · header row {activeSheet.headerRowNumber ?? '—'}</span>
                  </div>
                  {activeDraft.validation && (
                    <Chip tone={activeDraft.validation.errorRows > 0 ? 'rejected' : 'approved'}>
                      {activeDraft.validation.validRows} valid · {activeDraft.validation.errorRows} error · {activeDraft.validation.warningRows} warning
                    </Chip>
                  )}
                </div>

                <div className="toolbar">
                  <label className="field">
                    <span>Sheet type</span>
                    <select
                      value={activeDraft.type}
                      onChange={(event) => updateDraft(selectedSheet, {
                        type: event.target.value,
                        columns: {},
                        options: {},
                        components: [{ code: '', header: '' }],
                        autoMapResult: null,
                        mappingResult: null,
                        validation: null,
                      })}
                      disabled={!canPrepare}
                    >
                      <option value="">— select type —</option>
                      {SHEET_TYPES.map((type) => (
                        <option key={type.value} value={type.value}>{type.label}</option>
                      ))}
                    </select>
                  </label>
                  <button
                    type="button"
                    className="btn ghost"
                    onClick={() => autoMapSheet(selectedSheet)}
                    disabled={!canPrepare || busy === `automap-${selectedSheet}` || !activeDraft.type}
                  >
                    <Icon name="layers" size={14} /> {busy === `automap-${selectedSheet}` ? 'Auto-mapping…' : 'Auto-map'}
                  </button>
                  <button
                    type="button"
                    className="btn ghost"
                    onClick={() => saveMapping(selectedSheet)}
                    disabled={!canPrepare || busy === `map-${selectedSheet}` || !activeDraft.type}
                  >
                    {busy === `map-${selectedSheet}` ? 'Saving…' : 'Save mapping'}
                  </button>
                  <button
                    type="button"
                    className="btn"
                    onClick={() => validateSheet(selectedSheet)}
                    disabled={!canPrepare || busy === `validate-${selectedSheet}` || !canValidateSheet(activeDraft)}
                  >
                    {busy === `validate-${selectedSheet}` ? 'Validating…' : 'Validate sheet'}
                  </button>
                  <button
                    type="button"
                    className="btn ghost"
                    onClick={() => showPreview(selectedSheet)}
                    disabled={busy === `preview-${selectedSheet}`}
                  >
                    {busy === `preview-${selectedSheet}` ? 'Loading…' : 'Preview rows'}
                  </button>
                </div>

                {canPrepare && validationBlocker(activeDraft) && (
                  <p className="inline-meta" role="status">
                    Validation blocked: {validationBlocker(activeDraft)}
                  </p>
                )}

                {activeDraft.type === 'REFERENCE_ONLY' || activeDraft.type === 'SKIP' ? (
                  <p className="muted">This sheet will not be imported. No column mapping is required — just save the classification.</p>
                ) : !activeDraft.type ? (
                  <p className="muted">Choose a sheet type to reveal the target fields.</p>
                ) : (
                  <>
                    <table className="map-table">
                      <thead>
                        <tr>
                          <th style={{ width: '40%' }}>Target field</th>
                          <th>Source column</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(TARGET_FIELDS[activeDraft.type] || []).map(([field, label]) => {
                          const used = usedHeadersFor(activeDraft, field, -1)
                          const suggestion = suggestionFor(selectedSheet, field)
                          return (
                            <tr key={field}>
                              <td>
                                {label}
                                {suggestion?.sourceHeader && (
                                  <ConfidenceChip confidence={suggestion.confidence} reason={suggestion.reason} />
                                )}
                              </td>
                              <td>
                                <select
                                  value={activeDraft.columns[field] || ''}
                                  onChange={(event) => patchDraft(selectedSheet, 'columns', { [field]: event.target.value })}
                                  disabled={!canPrepare}
                                >
                                  <option value="">— not mapped —</option>
                                  {headers.map((header) => (
                                    <option key={header} value={header} disabled={used.has(header)}>{header}</option>
                                  ))}
                                </select>
                              </td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>

                    <div className="import-subsection">
                      <div className="import-subhead">
                        <strong>Reusable mapping profiles</strong>
                        <button
                          type="button"
                          className="btn ghost sm"
                          onClick={() => setDialog({ kind: 'saveProfile', sheetIndex: selectedSheet })}
                          disabled={!canPrepare || !activeDraft.autoMapResult?.structureSignature}
                        >
                          <Icon name="plus" size={13} /> Save current mapping
                        </button>
                      </div>
                      <p className="inline-meta">
                        Profiles are matched to the sheet&rsquo;s structure signature. Run Auto-map first so a changed
                        template can be detected before a saved profile is applied.
                      </p>
                      {profileOptionsFor(activeDraft, profiles).length === 0 ? (
                        <p className="muted">No saved profiles for this import type yet.</p>
                      ) : (
                        <div className="option-grid">
                          {profileOptionsFor(activeDraft, profiles).map((profile) => {
                            const stale = activeDraft.autoMapResult?.structureSignature
                              && profile.structureSignature !== activeDraft.autoMapResult.structureSignature
                            return (
                              <button
                                key={profile.id}
                                type="button"
                                className="btn ghost"
                                onClick={() => applySavedProfile(selectedSheet, profile)}
                                disabled={busy === `saveprofile-${selectedSheet}`}
                                title={stale ? 'Template structure differs from when this profile was saved' : profile.name}
                              >
                                {profile.name}
                                {stale ? ' ⚠' : ''}
                              </button>
                            )
                          })}
                        </div>
                      )}
                    </div>

                    {SUPPORTS_WIDE.has(activeDraft.type) && (
                      <div className="import-subsection">
                        <div className="import-subhead">
                          <strong>Wide-layout component columns</strong>
                          <button type="button" className="btn ghost sm" onClick={() => addComponent(selectedSheet)} disabled={!canPrepare}>
                            <Icon name="plus" size={13} /> Add component
                          </button>
                        </div>
                        <p className="inline-meta">Use this only for wide sheets where each salary component is its own column (maps component code → column). Leave empty for long-layout sheets.</p>
                        {activeDraft.components.map((component, index) => {
                          const used = usedHeadersFor(activeDraft, null, index)
                          return (
                            <div className="component-row" key={index}>
                              <input
                                value={component.code}
                                onChange={(event) => patchComponent(selectedSheet, index, { code: event.target.value })}
                                placeholder="Component code (e.g. BASIC)"
                                disabled={!canPrepare}
                                aria-label="Component code"
                              />
                              <select
                                value={component.header}
                                onChange={(event) => patchComponent(selectedSheet, index, { header: event.target.value })}
                                disabled={!canPrepare}
                                aria-label="Component source column"
                              >
                                <option value="">— source column —</option>
                                {headers.map((header) => (
                                  <option key={header} value={header} disabled={used.has(header)}>{header}</option>
                                ))}
                              </select>
                              <button type="button" className="icon-button" onClick={() => removeComponent(selectedSheet, index)} disabled={!canPrepare} aria-label="Remove component">
                                <Icon name="close" size={14} />
                              </button>
                            </div>
                          )
                        })}
                      </div>
                    )}

                    {(OPTION_FIELDS[activeDraft.type] || []).length > 0 && (
                      <div className="import-subsection">
                        <div className="import-subhead"><strong>Import options</strong></div>
                        <div className="option-grid">
                          {(OPTION_FIELDS[activeDraft.type] || []).map(([key, label, kind, choices]) => (
                            <label className="field" key={key}>
                              <span>{label}</span>
                              {kind === 'select' ? (
                                <select
                                  value={activeDraft.options[key] || ''}
                                  onChange={(event) => patchDraft(selectedSheet, 'options', { [key]: event.target.value })}
                                  disabled={!canPrepare}
                                >
                                  <option value="">— none —</option>
                                  {choices.map((choice) => <option key={choice} value={choice}>{choice}</option>)}
                                </select>
                              ) : (
                                <input
                                  type={kind}
                                  value={activeDraft.options[key] || ''}
                                  onChange={(event) => patchDraft(selectedSheet, 'options', { [key]: event.target.value })}
                                  disabled={!canPrepare}
                                />
                              )}
                            </label>
                          ))}
                        </div>
                      </div>
                    )}
                  </>
                )}

                {activeDraft.type === VALIDATABLE_TYPE && activeDraft.options.layout !== 'LONG' && (
                  <Alert kind="info">
                    Validation currently supports the <strong>LONG</strong> salary-entitlement layout only.
                    Select layout <strong>LONG</strong> and map componentCode + amount + effectiveFrom (or set a
                    default effective date) before validating.
                  </Alert>
                )}

                {activeDraft.validation?.issues?.length > 0 && (
                  <>
                    <div className="import-subhead">
                      <strong>Validation issues</strong>
                      <button
                        type="button"
                        className="btn ghost sm"
                        onClick={() => downloadIssues(selectedSheet)}
                        disabled={busy === `issues-${selectedSheet}`}
                      >
                        <Icon name="down" size={13} /> Download errors (CSV)
                      </button>
                    </div>
                    <DataTable
                      columns={[
                        { key: 'sourceRowNumber', label: 'Row', align: 'right' },
                        { key: 'severity', label: 'Severity', render: (row) => <StatusChip value={row.severity} /> },
                        { key: 'fieldName', label: 'Field', render: (row) => row.fieldName || '—' },
                        { key: 'errorCode', label: 'Code', render: (row) => <code>{row.errorCode}</code> },
                        { key: 'message', label: 'Message' },
                      ]}
                      rows={activeDraft.validation.issues.slice(0, 50)}
                      rowKey={(row, index) => `${row.sourceRowNumber}-${row.errorCode}-${index}`}
                    />
                    {activeDraft.validation.issues.length > 50 && (
                      <p className="inline-meta">Showing the first 50 issues of {activeDraft.validation.issues.length}.</p>
                    )}
                  </>
                )}

                {previews[selectedSheet] && (
                  <div className="import-subsection">
                    <div className="import-subhead">
                      <strong>Data preview</strong>
                      <button type="button" className="btn ghost sm" onClick={() => dismissPreview(selectedSheet)}>
                        Hide
                      </button>
                    </div>
                    {previews[selectedSheet].message && (
                      <p className="inline-meta">{previews[selectedSheet].message}</p>
                    )}
                    <div className="preview-scroll">
                      <table className="map-table">
                        <thead>
                          <tr>
                            <th>Row</th>
                            <th>Status</th>
                            {(previews[selectedSheet].columns || []).map((column) => (
                              <th key={column.field}>
                                {column.label}
                                {column.masked ? ' 🔒' : ''}
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {(previews[selectedSheet].rows || []).map((row) => (
                            <tr key={row.sourceRowNumber}>
                              <td>{row.sourceRowNumber}</td>
                              <td><StatusChip value={row.severity} /></td>
                              {(previews[selectedSheet].columns || []).map((column) => (
                                <td key={column.field}>{row.values?.[column.field] ?? '—'}</td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {(previews[selectedSheet].rows || []).length === 0 && (
                      <p className="muted">No preview rows available.</p>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        </section>
      )}

      {job && (
        <section className="panel section-gap">
          <div className="panel-head">
            <div>
              <h3>4 · Assignment</h3>
              <p>Assign the job to a division/user for controlled loading</p>
            </div>
          </div>
          <div className="panel-body stack">
            {!canAssign ? (
              <Alert kind="info">Assignment requires user.manage, salary.manage, payroll.prepare or payroll.approve.</Alert>
            ) : (
              <form className="inline-form" onSubmit={assign}>
                <label className="field">
                  <span>User ID</span>
                  <input type="number" min="1" value={assignment.userId} onChange={(event) => setAssignment((previous) => ({ ...previous, userId: event.target.value }))} />
                </label>
                <label className="field">
                  <span>Assignment role</span>
                  <select value={assignment.assignmentRole} onChange={(event) => setAssignment((previous) => ({ ...previous, assignmentRole: event.target.value }))}>
                    <option value="">Select role…</option>
                    <option value="RESOLVER">Resolver</option>
                    <option value="APPROVER">Approver</option>
                    <option value="FINALIZER">Finalizer</option>
                    <option value="VIEWER">Viewer</option>
                  </select>
                </label>
                <label className="field">
                  <span>Division ID</span>
                  <input type="number" min="1" value={assignment.divisionId} onChange={(event) => setAssignment((previous) => ({ ...previous, divisionId: event.target.value }))} />
                </label>
                <label className="field">
                  <span>Notes</span>
                  <input value={assignment.notes} onChange={(event) => setAssignment((previous) => ({ ...previous, notes: event.target.value }))} />
                </label>
                <button type="submit" className="btn" disabled={busy === 'assign'}>
                  {busy === 'assign' ? 'Saving…' : 'Save assignment'}
                </button>
              </form>
            )}

            {assignmentResult && (
              <DefinitionList
                items={[
                  { term: 'Assignment ID', value: assignmentResult.id },
                  { term: 'User', value: assignmentResult.userId },
                  { term: 'Role', value: assignmentResult.assignmentRole },
                  { term: 'Division', value: assignmentResult.divisionId ?? '—' },
                  { term: 'Active', value: assignmentResult.active ? 'Yes' : 'No' },
                  { term: 'Assigned at', value: formatDateTime(assignmentResult.assignedAt) },
                ]}
              />
            )}
          </div>
        </section>
      )}

      {job && (
        <section className="panel section-gap">
          <div className="panel-head">
            <div>
              <h3>5–7 · Workflow</h3>
              <p>Submit for approval, approve, then import the approved data</p>
            </div>
          </div>
          <div className="panel-body stack">
            <div className="detail-actions">
              <button
                type="button"
                className="btn"
                disabled={!canSubmit || busy === 'Submit'}
                onClick={() => setDialog({ kind: 'submit' })}
              >
                {busy === 'Submit' ? 'Submitting…' : 'Submit for approval'}
              </button>
              <button type="button" className="btn" disabled={!canDecide || isApproverBlocked || busy === 'Approve'} onClick={() => setDialog({ kind: 'approve' })}>
                {busy === 'Approve' ? 'Approving…' : 'Approve'}
              </button>
              <button type="button" className="btn danger" disabled={!canDecide || isApproverBlocked || busy === 'Reject'} onClick={() => setDialog({ kind: 'reject' })}>
                Reject
              </button>
              <button type="button" className="btn" disabled={!canFinalize || isFinalizerBlocked || busy === 'Finalize'} onClick={() => setDialog({ kind: 'finalize' })}>
                {busy === 'Finalize' ? 'Importing…' : 'Import approved data'}
              </button>
            </div>

            {isApproverBlocked && canDecide && (
              <Alert kind="warning">
                Maker–checker: you uploaded or submitted this job, so a different approver must approve it.
              </Alert>
            )}

            {!isApproverBlocked && isFinalizerBlocked && canFinalize && (
              <Alert kind="warning">
                Maker–checker: you already approved this job, so a different user must finalize it.
              </Alert>
            )}

            {workflow && (
              <DefinitionList
                items={[
                  { term: 'Status', value: <StatusChip value={workflow.status} /> },
                  { term: 'Uploaded by', value: workflow.uploadedBy ?? '—' },
                  { term: 'Submitted by', value: workflow.submittedBy ?? '—' },
                  { term: 'Approved by', value: workflow.approvedBy ?? '—' },
                  { term: 'Finalized by', value: workflow.finalizedBy ?? '—' },
                  { term: 'Imported rows', value: workflow.importedRows ?? '—' },
                  { term: 'Skipped rows', value: workflow.skippedRows ?? '—' },
                  { term: 'Error rows', value: workflow.errorRows ?? '—' },
                  { term: 'Imported amount', value: formatAmount(workflow.importedAmountTotal) },
                  { term: 'Imported earnings', value: formatAmount(workflow.importedEarningsTotal) },
                  { term: 'Imported deductions', value: formatAmount(workflow.importedDeductionsTotal) },
                  { term: 'Rejection reason', value: workflow.rejectionReason || '—' },
                ]}
              />
            )}
          </div>
        </section>
      )}

      <ConfirmDialog
        open={dialog?.kind === 'submit'}
        title="Submit for approval?"
        description="The job is locked for maker–checker review. A different user must approve it before the approved data can be imported."
        confirmLabel="Submit for approval"
        busy={busy === 'Submit'}
        onCancel={() => setDialog(null)}
        onConfirm={() => {
          const action = runWorkflow('Submit', () => importApi.submit(job.jobId))
          setDialog(null)
          action()
        }}
      />

      <ConfirmDialog
        open={dialog?.kind === 'finalize'}
        title="Import approved data?"
        description="Validated salary entitlement rows will be written to the employee salary values. This is the point of no return for this job."
        confirmLabel="Import approved data"
        busy={busy === 'Finalize'}
        onCancel={() => setDialog(null)}
        onConfirm={() => {
          const action = runWorkflow('Finalize', () => importApi.finalize(job.jobId))
          setDialog(null)
          action()
        }}
      />

      <ReasonDialog
        open={dialog?.kind === 'approve'}
        title="Approve import job"
        description="Approval is recorded against the job. Finalization requires a further, separate user."
        label="Approval remarks"
        required={false}
        confirmLabel="Approve job"
        busy={busy === 'Approve'}
        onCancel={() => setDialog(null)}
        onConfirm={(remarks) => {
          const action = runWorkflow('Approve', (text) => importApi.approve(job.jobId, text), remarks)
          setDialog(null)
          action()
        }}
      />

      <ReasonDialog
        open={dialog?.kind === 'reject'}
        title="Reject import job"
        description="The rejection remarks are recorded against the job and returned to the preparer."
        label="Rejection remarks"
        confirmLabel="Reject job"
        busy={busy === 'Reject'}
        onCancel={() => setDialog(null)}
        onConfirm={(remarks) => {
          const action = runWorkflow('Reject', (text) => importApi.reject(job.jobId, text), remarks)
          setDialog(null)
          action()
        }}
      />

      <ReasonDialog
        open={dialog?.kind === 'saveProfile'}
        title="Save mapping profile"
        description="Save the current column mapping so it can be reused on future workbooks with the same structure."
        label="Profile name"
        confirmLabel="Save profile"
        required
        busy={busy === `saveprofile-${dialog?.sheetIndex}`}
        onCancel={() => setDialog(null)}
        onConfirm={(name) => {
          const sheetIndex = dialog.sheetIndex
          const action = saveProfile(sheetIndex, name)
          setDialog(null)
          action()
        }}
      />
    </>
  )
}
