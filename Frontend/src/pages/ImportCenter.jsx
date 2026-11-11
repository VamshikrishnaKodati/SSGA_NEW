import { useEffect, useState } from 'react'
import { importApi, mappingProfileApi, saveBlob } from '../services/api.js'
import { formatAmount, formatDateTime, friendlyError, humanizeStatus } from '../lib/format.js'
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
  { key: 'map', label: 'Map' },
  { key: 'validate', label: 'Validate' },
  { key: 'review', label: 'Review' },
  { key: 'import', label: 'Import' },
]

const SUBMITTED_STATUSES = new Set([
  'AWAITING_APPROVAL', 'APPROVED', 'IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED',
])

const IMPORT_IN_PROGRESS_STATUSES = new Set(['IMPORTING', 'IMPORTED', 'PARTIALLY_IMPORTED'])

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
  const [step, setStep] = useState(0)
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
  const [dragOver, setDragOver] = useState(false)
  const [mappingQuery, setMappingQuery] = useState('')
  const [issueFilter, setIssueFilter] = useState('ALL')
  const [workbookLayout, setWorkbookLayout] = useState('AUTO')

  const canPrepare = ability.hasAny('salary.manage', 'payroll.prepare', 'employee.create')
  const canAssign = ability.hasAny('user.manage', 'salary.manage', 'payroll.prepare', 'payroll.approve')
  const canSubmit = ability.hasAny('payroll.submit', 'payroll.prepare', 'salary.manage')
  const canDecide = ability.has('payroll.approve')
  const canFinalize = ability.has('payroll.finalize')

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

  function layoutOptionsFor(type) {
    return workbookLayout !== 'AUTO' && SUPPORTS_WIDE.has(type)
      ? { layout: workbookLayout }
      : {}
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
        options: layoutOptionsFor(suggested),
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
    setMessage(`Uploaded ${response.fileName}. Preparing sheet mapping.`)

    const analysisResponse = await importApi.analyze(response.jobId)
    const progressResponse = await importApi.progress(response.jobId)
    setJob((previous) => ({
      ...previous,
      status: progressResponse.status || analysisResponse.status,
      uploadedBy: analysisResponse.uploadedBy ?? previous?.uploadedBy,
      fileName: progressResponse.fileName || previous?.fileName,
      sourceType: progressResponse.sourceType || previous?.sourceType,
    }))
    setAnalysis(analysisResponse)
    setProgress(progressResponse)
    seed(analysisResponse.sheets)
    setStep(1)
    setMessage(`Uploaded and analysed ${response.fileName}. Review the suggested mapping.`)
  })

  const loadById = guard(async (id) => {
    setBusy('analyze')
    const response = await importApi.analyze(id)
    const progressResponse = await importApi.progress(id)
    const status = progressResponse.status || response.status
    setJob({
      jobId: id,
      status,
      uploadedBy: response.uploadedBy,
      fileName: progressResponse.fileName,
      sourceType: progressResponse.sourceType,
    })
    setAnalysis(response)
    setProgress(progressResponse)
    setWorkflow(null)
    seed(response.sheets)
    setStep(SUBMITTED_STATUSES.has(status) ? 4 : 1)
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

  useEffect(() => {
    if (step !== 1 || selectedSheet === null || !job?.jobId) return undefined
    if (previews[selectedSheet]) return undefined
    let active = true
    importApi.preview(job.jobId, selectedSheet)
      .then((response) => {
        if (active) {
          setPreviews((previous) => ({ ...previous, [selectedSheet]: response }))
        }
      })
      .catch(() => {})
    return () => { active = false }
  }, [step, selectedSheet, job?.jobId, previews])

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
    const progressResponse = await importApi.progress(job.jobId)
    setJob((previous) => ({
      ...previous,
      status: progressResponse.status || response.status,
    }))
    setAnalysis(response)
    setProgress(progressResponse)
    seed(response.sheets)
    setStep(SUBMITTED_STATUSES.has(progressResponse.status) ? 4 : 1)
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
      job.jobId, sheetIndex, buildMappingPayload(draft),
    )
    updateDraft(sheetIndex, { mappingResult: mappingResponse, validation: null })
    const response = await importApi.validateSheet(job.jobId, sheetIndex)
    updateDraft(sheetIndex, { validation: response })
    setMessage(`Validation complete: ${response.validRows} valid · ${response.errorRows} error · ${response.warningRows} warning.`)
  })

  const saveAllMappings = guard(async (nextStep = null) => {
    if (!job || !analysis) {
      throw new Error('Upload and analyse a workbook first.')
    }
    const sheets = analysis.sheets || []
    if (sheets.length === 0) {
      throw new Error('No readable worksheets were detected in this workbook.')
    }
    let saved = 0
    for (const sheet of sheets) {
      const draft = drafts[sheet.sheetIndex]
      if (!draft?.type) {
        setSelectedSheet(sheet.sheetIndex)
        throw new Error(`Choose an import purpose for sheet "${sheet.sheetName}".`)
      }
      if (draft.mappingResult) continue
      if (!['SKIP', 'REFERENCE_ONLY'].includes(draft.type)) {
        const blocker = validationBlocker(draft)
        if (blocker) {
          setSelectedSheet(sheet.sheetIndex)
          throw new Error(`${sheet.sheetName}: ${blocker}`)
        }
      }
      setBusy(`map-${sheet.sheetIndex}`)
      const response = await importApi.confirmMapping(
        job.jobId, sheet.sheetIndex, buildMappingPayload(draft),
      )
      updateDraft(sheet.sheetIndex, { mappingResult: response, validation: null })
      saved += 1
    }
    setMessage(saved > 0
      ? `Saved mapping for ${saved} sheet${saved === 1 ? '' : 's'}.`
      : 'All sheet mappings are already saved.')
    if (nextStep !== null) setStep(nextStep)
  })

  const validateAllSheets = guard(async () => {
    if (!job || !analysis) {
      throw new Error('Upload and analyse a workbook first.')
    }
    const sheets = (analysis.sheets || []).filter((sheet) => {
      const draft = drafts[sheet.sheetIndex]
      return draft?.type && !['SKIP', 'REFERENCE_ONLY'].includes(draft.type)
    })
    if (sheets.length === 0) {
      throw new Error('There are no importable sheets to validate.')
    }
    let firstSheet = null
    let validRows = 0
    let errorRows = 0
    let warningRows = 0
    for (const sheet of sheets) {
      const draft = drafts[sheet.sheetIndex]
      const blocker = validationBlocker(draft)
      if (blocker) {
        setSelectedSheet(sheet.sheetIndex)
        throw new Error(`${sheet.sheetName}: ${blocker}`)
      }
      setBusy(`validate-${sheet.sheetIndex}`)
      const mappingResponse = await importApi.confirmMapping(
        job.jobId, sheet.sheetIndex, buildMappingPayload(draft),
      )
      updateDraft(sheet.sheetIndex, { mappingResult: mappingResponse, validation: null })
      const response = await importApi.validateSheet(job.jobId, sheet.sheetIndex)
      updateDraft(sheet.sheetIndex, { validation: response })
      validRows += response.validRows || 0
      errorRows += response.errorRows || 0
      warningRows += response.warningRows || 0
      if (firstSheet === null) firstSheet = sheet.sheetIndex
    }
    setSelectedSheet(firstSheet)
    setMessage(
      `Validation complete: ${validRows} valid · ${errorRows} error · ${warningRows} warning.`,
    )
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
      if (label === 'Submit') setStep(4)
      if (label === 'Reject') setStep(3)
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
  const previewSamples = previews[selectedSheet]?.rows?.[0]?.values || {}
  const activeDraft = selectedSheet === null ? null : drafts[selectedSheet]
  const activeSheet = analysis?.sheets?.find((sheet) => sheet.sheetIndex === selectedSheet)

  function sampleForHeader(header) {
    if (!header) return '—'
    const previewMatch = Object.values(previewSamples).find((value, index) => {
      const columns = previews[selectedSheet]?.columns || []
      return columns[index]?.sourceHeader === header && value
    })
    return previewMatch || '—'
  }

  function isRequiredField(type, field) {
    if (type === 'EMPLOYEE_MASTER_AND_COMPONENTS') return ['employeeCode', 'hrmsId', 'employeeName', 'division', 'divisionId', 'effectiveFrom', 'employeeCode', 'hrmsId'].includes(field)
    if (type === 'SALARY_ENTITLEMENTS') return ['employeeCode', 'hrmsId', 'componentCode', 'amount', 'effectiveFrom'].includes(field)
    if (type === 'SALARY_REVISIONS') return ['employeeCode', 'hrmsId', 'componentCode', 'effectiveFrom', 'newAmount', 'amount'].includes(field)
    if (type === 'EMPLOYEE_MASTER') return ['employeeCode', 'hrmsId', 'employeeName', 'division', 'divisionId'].includes(field)
    if (type === 'PAYROLL_HISTORY') return ['employeeCode', 'hrmsId', 'componentCode', 'amount', 'period'].includes(field)
    return false
  }

  function issueGuidance(issue) {
    const map = {
      EMPLOYEE_IDENTIFIER_REQUIRED: 'Map HRMS ID or Employee code and provide a value in each row.',
      EMPLOYEE_NOT_FOUND_OR_OUT_OF_SCOPE: 'Verify the employee exists and is in your authorized division/scope.',
      EMPLOYEE_IDENTITY_MISMATCH: 'Use one employee reference per row; correct code/HRMS ID mismatch.',
      COMPONENT_CODE_REQUIRED: 'Map a component code column or provide a valid code.',
      UNKNOWN_COMPONENT: 'Use an active salary component code from the master list.',
      UNKNOWN_OR_INACTIVE_COMPONENT: 'Use an active salary component code or activate the component first.',
      AMOUNT_REQUIRED: 'Provide a numeric amount with up to 2 decimal places.',
      INVALID_AMOUNT: 'Remove text/currency symbols; use a decimal number.',
      NEGATIVE_AMOUNT: 'Amounts must be zero or positive unless the business rule allows negatives.',
      EFFECTIVE_DATE_REQUIRED: 'Map Effective from or set a default effective date (YYYY-MM-DD).',
      INVALID_EFFECTIVE_DATE: 'Use YYYY-MM-DD or a supported date format.',
      INVALID_DATE: 'Use YYYY-MM-DD or a supported date format.',
      DUPLICATE_EMPLOYEE_ROW: 'Keep one row per employee (or employee+component for combined layouts).',
      DUPLICATE_ROW: 'Remove duplicate employee/component/effective-date combinations.',
      DIVISION_REQUIRED: 'Provide division name or division ID for new employees.',
      DIVISION_MAPPING_REQUIRED: 'Map Division (name or ID) so new employees can be assigned.',
      DIVISION_AMBIGUOUS: 'The division name matches more than one division; use the division code or ID.',
      DIVISION_NOT_FOUND_OR_OUT_OF_SCOPE: 'Use an existing authorized division.',
      EMPLOYEE_NAME_REQUIRED: 'Provide the employee name for new employees.',
      EMPLOYEE_NAME_MISMATCH: 'The name does not match the existing employee; confirm the identifier is correct.',
      EMPLOYEE_EXISTS_MASTER_NOT_UPDATED: 'This employee already exists; master fields are not overwritten. Use existing salary update for pay changes.',
      INVALID_HRMS_ID: 'Use a 4-digit HRMS ID or leave it blank for a temporary T-#### ID.',
      IMPORT_WILL_CREATE_EMPLOYEES: 'New employees will be created on approval; existing employees are matched and left unchanged.',
      IMPORT_WILL_CREATE_EMPLOYEES_WITH_SALARY: 'New employees and their salary components will be created on approval; existing components are not overwritten with blanks.',
      COMPONENT_AMOUNT_SKIPPED: 'Blank amounts are skipped; matched components are left unchanged.',
      COMPONENT_VALUE_UNCHANGED: 'The amount already matches; this row will be skipped.',
      COMPONENT_VALUE_CHANGED: 'The amount differs from the existing value; review before approving.',
      COMPONENT_EFFECTIVE_DATE_OVERLAP: 'An existing salary value overlaps this effective date; review before approving.',
      EFFECTIVE_TO_BEFORE_FROM: 'Effective To must be on or after Effective From.',
      AMOUNT_PRECISION: 'Use at most 2 decimal places.',
      NO_COMPONENT_AMOUNTS: 'Map at least one component column with a value for wide layouts.',
      TEMP_ID_ASSIGNED: 'A temporary ID (T-####) will be assigned at import; add employee code or HRMS ID to keep the real identifier.',
      COMPONENT_CODE_INTERPRETED: 'The source code was matched to a master component code; confirm the mapping is correct.',
      VALIDATION_FAILED: 'Review the row values and mapping, then re-validate.',
    }
    return map[issue?.errorCode] || issue?.message || 'Correct the value and re-validate.'
  }

  const sheets = analysis?.sheets || []
  const importableSheets = sheets.filter((sheet) => {
    const draft = drafts[sheet.sheetIndex]
    return draft?.type && !['SKIP', 'REFERENCE_ONLY'].includes(draft.type)
  })
  const mappingComplete = sheets.length > 0
    && sheets.every((sheet) => Boolean(drafts[sheet.sheetIndex]?.mappingResult))
  const validationRun = importableSheets.length > 0
    && importableSheets.every((sheet) => Boolean(drafts[sheet.sheetIndex]?.validation))
  const validationValidRows = importableSheets.reduce(
    (total, sheet) => total + (drafts[sheet.sheetIndex]?.validation?.validRows || 0),
    0,
  )
  const validationErrorRows = importableSheets.reduce(
    (total, sheet) => total + (drafts[sheet.sheetIndex]?.validation?.errorRows || 0),
    0,
  )
  const validationWarningRows = importableSheets.reduce(
    (total, sheet) => total + (drafts[sheet.sheetIndex]?.validation?.warningRows || 0),
    0,
  )
  const validationTotalRows = importableSheets.reduce(
    (total, sheet) => total + (drafts[sheet.sheetIndex]?.validation?.totalRows || 0),
    0,
  )
  const validationTempIdRows = importableSheets.reduce(
    (total, sheet) => total + (drafts[sheet.sheetIndex]?.validation?.tempIdRows || 0),
    0,
  )
  const validationReady = validationRun && validationErrorRows === 0
  const submitted = SUBMITTED_STATUSES.has(job?.status)
  const finalized = IMPORT_IN_PROGRESS_STATUSES.has(job?.status)
  const completedSteps = {
    upload: Boolean(job && analysis),
    map: mappingComplete,
    validate: validationReady,
    review: submitted,
    import: finalized,
  }
  const furthestStep = finalized || submitted
    ? 4
    : validationReady
      ? 3
      : mappingComplete
        ? 2
        : job && analysis
          ? 1
          : 0

  function goToStep(index) {
    if (!canVisit(index)) return
    setStep(index)
  }

  function canVisit(index) {
    if (index === step) return true
    if (submitted || finalized) return index >= 3 && index <= 4
    return index <= furthestStep
  }

  function stepTone(index) {
    if (index === step) return 'active'
    if (index < step || completedSteps[STEPS[index].key]) return 'done'
    return ''
  }

  function nextHint() {
    if (step === 0) {
      return !job ? 'Upload a workbook or open an existing job.' : 'Analyse the workbook to continue.'
    }
    if (step === 1) return 'Classify every sheet and map the required columns.'
    if (step === 2) {
      if (!validationRun) return 'Run validation before reviewing.'
      if (validationErrorRows > 0) {
        return `${validationErrorRows} error row${validationErrorRows === 1 ? '' : 's'} must be resolved first.`
      }
      return 'All mapped sheets passed validation.'
    }
    if (step === 3) return 'Submit for maker–checker approval to unlock the import.'
    return ''
  }

  function nextStep() {
    if (step === 0) {
      if (job && analysis) goToStep(1)
      return
    }
    if (step === 1) {
      saveAllMappings(2)
      return
    }
    if (step === 2) {
      if (validationReady) goToStep(3)
      return
    }
    if (step === 3 && submitted) goToStep(4)
  }

  function canContinue() {
    if (busy) return false
    if (step === 0) return Boolean(job && analysis)
    if (step === 1) return canPrepare && sheets.length > 0
    if (step === 2) return validationReady
    if (step === 3) return submitted
    return false
  }

  function nextLabel() {
    if (step === 1) return 'Save all mappings'
    if (step === 2) return 'Continue to review'
    if (step === 3) return 'Continue to import'
    return 'Continue'
  }

  function onFileSelected(file) {
    if (file) upload(file)
  }

  function onDrop(event) {
    event.preventDefault()
    setDragOver(false)
    const file = event.dataTransfer?.files?.[0]
    onFileSelected(file)
  }

  // Compact header bar - always visible, limited scrolling
  const headerBar = (
    <header className="import-compact-header">
      <div className="import-header-left">
        <PageHeader
          title="Data import"
          subtitle="Upload salary, employee or payroll workbooks, map sheets, validate, then route through maker–checker approval"
          breadcrumbs={['Operations', 'Data import']}
        />
      </div>
      <div className="import-header-right">
        {job ? (
          <div className="import-job-badge">
            <Chip>Job #{job.jobId}</Chip>
            <StatusChip value={job.status} />
          </div>
        ) : null}
      </div>
    </header>
  )

  // Compact stepper - horizontal, minimal height
  const stepper = (
    <section className="import-stepper-bar" aria-label="Import steps">
      <ol className="import-stepper-list">
        {STEPS.map((item, index) => (
          <li key={item.key}>
            <button
              type="button"
              className={`import-step ${stepTone(index)}`.trim()}
              onClick={() => goToStep(index)}
              disabled={!canVisit(index)}
              aria-current={index === step ? 'step' : undefined}
            >
              <span className="import-step-index">
                {completedSteps[item.key] && index !== step ? <Icon name="check" size={12} /> : index + 1}
              </span>
              {item.label}
            </button>
          </li>
        ))}
      </ol>
      <div className="import-step-hint">{nextHint()}</div>
    </section>
  )

  // === Step 0: Upload ===
  const stepUpload = (
    <section className="import-panel import-panel-compact" aria-label="Step 1 · Upload">
      <div className="import-panel-head">
        <div>
          <h3>Upload a workbook</h3>
          <p>Excel workbooks (.xlsx / .xlsm / .xls) or .csv files</p>
        </div>
      </div>
      <div className="import-panel-body">
        {canPrepare ? (
          <label
            className={`dropzone${dragOver ? ' dragover' : ''}`}
            onDragOver={(event) => { event.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
          >
            <Icon name="upload" size={22} />
            <strong>{busy === 'upload' ? 'Uploading…' : 'Drag and drop a workbook, or choose a file'}</strong>
            <span>Accepted formats: .xlsx, .xlsm, .xls, .csv (max 25 MB). Files are checked for integrity.</span>
            <input
              type="file"
              accept=".xlsx,.xlsm,.xls,.csv"
              disabled={busy === 'upload'}
              onChange={(event) => {
                onFileSelected(event.target.files?.[0])
                event.target.value = ''
              }}
            />
          </label>
        ) : (
            <Alert kind="info">You do not have permission to upload workbooks.</Alert>
        )}

        <div className="import-toolbar compact">
          <label className="field">
            <span>Workbook layout</span>
            <select
              value={workbookLayout}
              onChange={(event) => setWorkbookLayout(event.target.value)}
            >
              <option value="AUTO">Auto-detect (recommended)</option>
              <option value="LONG">Long · one row per component</option>
              <option value="WIDE">Wide · one column per component</option>
            </select>
          </label>
        </div>

        <form
          className="inline-form compact"
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
              { key: 'sourceType', label: 'Source', render: (row) => row.sourceType ? humanizeStatus(row.sourceType) : '—' },
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
            emptyDescription="Upload a workbook or open a job by ID above"
          />
        </div>

        {job && (
          <DefinitionList
            items={[
              { term: 'Job ID', value: job.jobId },
              { term: 'File name', value: job.fileName || '—' },
              { term: 'Source type', value: job.sourceType ? humanizeStatus(job.sourceType) : '—' },
              { term: 'Status', value: <StatusChip value={job.status} /> },
            ]}
          />
        )}
      </div>
    </section>
  )

  // === Step 1: Map ===
  const stepMap = (
    <section className="import-panel import-panel-compact" aria-label="Step 2 · Map sheet columns">
      <div className="import-panel-head">
        <div>
          <h3>Map sheet columns</h3>
          <p>Classify each sheet, auto-map or choose source columns, then save every mapping</p>
        </div>
        <button type="button" className="btn" onClick={analyze} disabled={busy === 'analyze'}>
          <Icon name="refresh" size={14} /> {busy === 'analyze' ? 'Analysing…' : analysis ? 'Re-analyse' : 'Analyse'}
        </button>
      </div>
      <div className="import-panel-body">
        {!analysis ? (
          <p className="muted">Run the analysis to see sheet structure.</p>
        ) : (
          <>
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
              emptyDescription="The workbook did not contain readable sheets"
            />

            <div className="sheet-tabs">
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
                    {badge && <span className="sheet-tab-dot" title="Draft has data" />}
                  </button>
                )
              })}
            </div>

            {activeDraft && activeSheet && (
              <div className="import-sheet compact">
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

                <div className="import-toolbar compact">
                  <label className="field">
                    <span>Sheet type</span>
                    <select
                      value={activeDraft.type}
                      onChange={(event) => updateDraft(selectedSheet, {
                        type: event.target.value,
                        columns: {},
                        options: layoutOptionsFor(event.target.value),
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
                    className="btn"
                    onClick={() => saveMapping(selectedSheet)}
                    disabled={!canPrepare || busy === `map-${selectedSheet}` || !activeDraft.type}
                  >
                    {busy === `map-${selectedSheet}` ? 'Saving…' : 'Save mapping'}
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
                    <div className="import-map-toolbar">
                      <label className="field" style={{ minWidth: 240 }}>
                        <span>Search mappings</span>
                        <input
                          type="search"
                          value={mappingQuery}
                          onChange={(event) => setMappingQuery(event.target.value)}
                          placeholder="Target field or source column"
                          disabled={!canPrepare}
                        />
                      </label>
                      <span className="inline-meta">
                        {Object.values(activeDraft.columns || {}).filter(Boolean).length} mapped · {validationBlocker(activeDraft) ? 'required mapping incomplete' : 'ready to validate'}
                      </span>
                    </div>
                    <table className="import-map-table compact">
                      <thead>
                        <tr>
                          <th style={{ width: '30%' }}>Target field</th>
                          <th style={{ width: '28%' }}>Source column</th>
                          <th style={{ width: '24%' }}>Sample value</th>
                          <th style={{ width: '18%' }}>Status</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(TARGET_FIELDS[activeDraft.type] || []).filter(([field, label]) => {
                          if (!mappingQuery.trim()) return true
                          const q = mappingQuery.trim().toLowerCase()
                          const header = activeDraft.columns[field] || ''
                          return label.toLowerCase().includes(q) || field.toLowerCase().includes(q) || header.toLowerCase().includes(q)
                        }).map(([field, label]) => {
                          const used = usedHeadersFor(activeDraft, field, -1)
                          const suggestion = suggestionFor(selectedSheet, field)
                          const required = isRequiredField(activeDraft.type, field)
                          const mappedHeader = activeDraft.columns[field] || ''
                          return (
                            <tr key={field}>
                              <td>
                                {label}
                                <span className={required ? 'req-badge' : 'opt-badge'}>{required ? 'Required' : 'Optional'}</span>
                                {suggestion?.sourceHeader && (
                                  <ConfidenceChip confidence={suggestion.confidence} reason={suggestion.reason} />
                                )}
                              </td>
                              <td>
                                <select
                                  value={mappedHeader}
                                  onChange={(event) => patchDraft(selectedSheet, 'columns', { [field]: event.target.value })}
                                  disabled={!canPrepare}
                                  aria-label={`Map ${label}`}
                                >
                                  <option value="">— not mapped —</option>
                                  {headers.map((header) => (
                                    <option key={header} value={header} disabled={used.has(header)}>{header}</option>
                                  ))}
                                </select>
                              </td>
                              <td className="import-issue-value">{sampleForHeader(mappedHeader)}</td>
                              <td>{mappedHeader ? <Chip tone="approved">Mapped</Chip> : <Chip tone={required ? 'warning' : 'muted'}>{required ? 'Missing' : '—'}</Chip>}</td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>

                    {previews[selectedSheet]?.rows?.length > 0 && (
                      <div className="import-subsection">
                        <div className="import-subhead">
                          <strong>Live sample · first {Math.min(5, previews[selectedSheet].rows.length)} rows</strong>
                        </div>
                        {previews[selectedSheet].message && (
                          <p className="inline-meta">{previews[selectedSheet].message}</p>
                        )}
                        <div className="preview-scroll">
                          <table className="map-table">
                            <thead>
                              <tr>
                                <th>Row</th>
                                {(previews[selectedSheet].columns || []).map((column) => (
                                  <th key={column.field}>{column.label}</th>
                                ))}
                              </tr>
                            </thead>
                            <tbody>
                              {previews[selectedSheet].rows.slice(0, 5).map((row) => (
                                <tr key={row.sourceRowNumber}>
                                  <td>{row.sourceRowNumber}</td>
                                  {(previews[selectedSheet].columns || []).map((column) => (
                                    <td key={column.field}>{row.values?.[column.field] ?? '—'}</td>
                                  ))}
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}

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
                        Profiles are matched to the sheet's structure signature. Run Auto-map first so a changed
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
                        <p className="inline-meta">Use only for wide sheets where each salary component has its own column. Leave empty for long-layout sheets.</p>
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
              </div>
            )}
          </>
        )}
      </div>
    </section>
  )

  // === Step 2: Validate ===
  const stepValidate = (
    <section className="import-panel import-panel-compact" aria-label="Step 3 · Validate mapped data">
      <div className="import-panel-head">
        <div>
          <h3>Validate mapped data</h3>
          <p>Validation checks every importable sheet. Error rows block review and import.</p>
        </div>
        <button
          type="button"
          className="btn"
          onClick={validateAllSheets}
          disabled={!canPrepare || !mappingComplete || busy.startsWith('validate-')}
        >
          <Icon name="check" size={14} /> {validationRun ? 'Re-validate all sheets' : 'Validate all sheets'}
        </button>
      </div>
      <div className="import-panel-body">
        {!mappingComplete ? (
          <Alert kind="info">Save a mapping for every sheet before validating.</Alert>
        ) : (
          <>
            <div className="import-job-details compact">
              <div><span>Total rows</span><strong>{validationTotalRows}</strong></div>
              <div><span>Valid rows</span><strong>{validationValidRows}</strong></div>
              <div><span>Error rows</span><strong>{validationErrorRows}</strong></div>
              <div><span>Warning rows</span><strong>{validationWarningRows}</strong></div>
              <div><span>Temp IDs</span><strong>{validationTempIdRows}</strong></div>
            </div>

            {!validationRun && (
              <p className="inline-meta" role="status">Validation has not run yet.</p>
            )}
            {validationRun && validationErrorRows === 0 && (
              <p className="inline-meta" role="status">All importable sheets passed validation.</p>
            )}
            {validationErrorRows > 0 && (
              <p className="inline-meta" role="status">
                {validationErrorRows} error row{validationErrorRows === 1 ? '' : 's'} must be corrected in the source workbook, then re-uploaded.
              </p>
            )}

            <DataTable
              columns={[
                { key: 'sheetName', label: 'Sheet', render: (row) => <span className="cell-primary">{row.sheetName}</span> },
                { key: 'type', label: 'Purpose', render: (row) => drafts[row.sheetIndex]?.type || '—' },
                { key: 'validRows', label: 'Valid', align: 'right', render: (row) => drafts[row.sheetIndex]?.validation?.validRows ?? '—' },
                { key: 'errorRows', label: 'Errors', align: 'right', render: (row) => drafts[row.sheetIndex]?.validation?.errorRows ?? '—' },
                { key: 'warningRows', label: 'Warnings', align: 'right', render: (row) => drafts[row.sheetIndex]?.validation?.warningRows ?? '—' },
                {
                  key: 'status',
                  label: 'Status',
                  render: (row) => {
                    const validation = drafts[row.sheetIndex]?.validation
                    if (!validation) return <Chip tone="muted">Not validated</Chip>
                    return (
                      <Chip tone={validation.errorRows > 0 ? 'rejected' : 'approved'}>
                        {validation.errorRows > 0 ? 'Errors' : 'Validated'}
                      </Chip>
                    )
                  },
                },
                {
                  key: 'actions',
                  label: '',
                  render: (row) => {
                    const draft = drafts[row.sheetIndex]
                    return (
                      <div className="detail-actions compact">
                        <button type="button" className="btn ghost sm" onClick={() => setSelectedSheet(row.sheetIndex)}>
                          Inspect
                        </button>
                        <button
                          type="button"
                          className="btn ghost sm"
                          onClick={() => validateSheet(row.sheetIndex)}
                          disabled={!canPrepare || busy === `validate-${row.sheetIndex}` || !canValidateSheet(draft)}
                        >
                          {busy === `validate-${row.sheetIndex}` ? 'Validating…' : 'Validate'}
                        </button>
                        <button
                          type="button"
                          className="btn ghost sm"
                          onClick={() => showPreview(row.sheetIndex)}
                          disabled={busy === `preview-${row.sheetIndex}`}
                        >
                          {busy === `preview-${row.sheetIndex}` ? 'Loading…' : 'Preview'}
                        </button>
                      </div>
                    )
                  },
                },
              ]}
              rows={importableSheets}
              rowKey={(row) => row.sheetIndex}
              emptyTitle="No importable sheets"
              emptyDescription="Every sheet is classified as skip or reference only"
            />

            {activeDraft && activeSheet && importableSheets.some((sheet) => sheet.sheetIndex === selectedSheet) && (
              <div className="import-sheet compact">
                <div className="import-sheet-head">
                  <div>
                    <strong>{activeSheet.sheetName}</strong>
                    <span className="inline-meta"> · sheet {activeSheet.sheetIndex} · {activeSheet.dataRowsEstimate} rows</span>
                  </div>
                  {activeDraft.validation ? (
                    <Chip tone={activeDraft.validation.errorRows > 0 ? 'rejected' : 'approved'}>
                      {activeDraft.validation.validRows} valid · {activeDraft.validation.errorRows} error · {activeDraft.validation.warningRows} warning
                    </Chip>
                  ) : (
                    <Chip tone="muted">Not validated</Chip>
                  )}
                </div>

                {canPrepare && validationBlocker(activeDraft) && (
                  <p className="inline-meta" role="status">Cannot validate: {validationBlocker(activeDraft)}</p>
                )}

                {activeDraft.type === VALIDATABLE_TYPE && activeDraft.options.layout !== 'LONG' && (
                  <Alert kind="info">
                    Validation currently supports the <strong>LONG</strong> salary-entitlement layout only.
                    Select layout <strong>LONG</strong> and map componentCode + amount + effectiveFrom (or set a
                    default effective date) before validating.
                  </Alert>
                )}

                {activeDraft.validation?.issues?.length > 0 ? (
                  <details className="import-issues-drawer">
                    <summary>
                      Validation issues ({activeDraft.validation.issues.length})
                      {activeDraft.validation.errorRows > 0 ? ' · errors present' : ''}
                    </summary>
                    <div className="import-subhead">
                      <div className="import-filter-bar">
                        <label className="field" style={{ minWidth: 160 }}>
                          <span>Status</span>
                          <select value={issueFilter} onChange={(event) => setIssueFilter(event.target.value)}>
                            <option value="ALL">All</option>
                            <option value="ERROR">Errors</option>
                            <option value="WARNING">Warnings</option>
                          </select>
                        </label>
                        <button
                          type="button"
                          className="btn ghost sm"
                          onClick={() => downloadIssues(selectedSheet)}
                          disabled={busy === `issues-${selectedSheet}`}
                        >
                          <Icon name="down" size={13} /> Download validation errors
                        </button>
                      </div>
                    </div>
                    <DataTable
                      columns={[
                        { key: 'sourceRowNumber', label: 'Row', align: 'right' },
                        { key: 'severity', label: 'Severity', render: (row) => <StatusChip value={row.severity} /> },
                        { key: 'fieldName', label: 'Field', render: (row) => row.fieldName || '—' },
                        { key: 'errorCode', label: 'Issue', render: (row) => <code>{row.errorCode}</code> },
                        { key: 'message', label: 'Current issue' },
                        { key: 'guidance', label: 'Correction guidance', render: (row) => <span className="import-issue-guidance">{issueGuidance(row)}</span> },
                      ]}
                      rows={(activeDraft.validation.issues || []).filter((issue) => issueFilter === 'ALL' || issue.severity === issueFilter)}
                      rowKey={(row, index) => `${row.sourceRowNumber}-${row.errorCode}-${index}`}
                    />
                  </details>
                ) : (
                  <p className="muted">
                    {activeDraft.validation
                      ? 'No row-level issues were returned for this sheet.'
                      : 'Validate this sheet to inspect row-level issues.'}
                  </p>
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
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </section>
  )

  // === Step 3: Review ===
  const stepReview = (
    <section className="import-panel import-panel-compact" aria-label="Step 4 · Review & assign">
      <div className="import-panel-head">
        <div>
          <h3>Review &amp; assign</h3>
          <p>Confirm validation totals and assignment, then submit the job for maker–checker approval.</p>
        </div>
        <button
          type="button"
          className="btn"
          disabled={!canSubmit || busy === 'Submit' || submitted}
          onClick={() => setDialog({ kind: 'submit' })}
        >
          <Icon name="check" size={14} /> {submitted ? 'Submitted' : busy === 'Submit' ? 'Submitting…' : 'Submit for approval'}
        </button>
      </div>
      <div className="import-panel-body">
        <div className="import-job-details compact">
          <div><span>File</span><strong>{job?.fileName || '—'}</strong></div>
          <div><span>Importable sheets</span><strong>{importableSheets.length}</strong></div>
          <div><span>Valid rows</span><strong>{validationValidRows}</strong></div>
          <div><span>Error rows</span><strong>{validationErrorRows}</strong></div>
          <div><span>Warning rows</span><strong>{validationWarningRows}</strong></div>
          <div><span>Temp IDs</span><strong>{validationTempIdRows}</strong></div>
          <div><span>Status</span><strong><StatusChip value={job?.status} /></strong></div>
        </div>

        {validationTempIdRows > 0 && (
          <p className="inline-meta" role="status">
            {validationTempIdRows} row{validationTempIdRows === 1 ? '' : 's'} without an employee code or HRMS ID will receive a temporary T-#### ID.
          </p>
        )}

        {validationErrorRows > 0 && (
          <Alert kind="error">
            Validation has unresolved error rows. Return to the Validate step, fix the workbook, and upload it again.
          </Alert>
        )}
        {!canSubmit && (
              <Alert kind="info">You do not have permission to submit this job.</Alert>
        )}
        {submitted && (
          <Alert kind="success">
            This job has been submitted. Approval and import are handled in the Import step.
          </Alert>
        )}

        <Alert kind="info">
          Review impact: employee-master imports create only missing employees; existing employees are matched by Employee code, HRMS ID or identity and left unchanged. Employee+components and salary-update imports skip identical amounts, update changed amounts and insert missing components; blank amounts never overwrite. No database writes occur during preview or validation.
        </Alert>

        <div className="import-subsection">
          <div className="import-subhead"><strong>Assignment (optional)</strong></div>
          {!canAssign ? (
              <Alert kind="info">You do not have permission to assign this job.</Alert>
          ) : (
            <form className="inline-form compact" onSubmit={assign}>
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
      </div>
    </section>
  )

  // === Step 4: Import ===
  const stepImport = (
    <section className="import-panel import-panel-compact" aria-label="Step 5 · Submit, approve & import">
      <div className="import-panel-head">
        <div>
          <h3>Submit, approve &amp; import</h3>
          <p>Approve or reject the submitted job, then import the approved data</p>
        </div>
      </div>
      <div className="import-panel-body">
        <div className="detail-actions compact">
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
              { term: 'Job ID', value: workflow.jobId ?? job.jobId },
              { term: 'Status', value: <StatusChip value={workflow.status} /> },
              { term: 'Uploaded by', value: workflow.uploadedBy ?? '—' },
              { term: 'Submitted by', value: workflow.submittedBy ?? '—' },
              { term: 'Approved by', value: workflow.approvedBy ?? '—' },
              { term: 'Finalized by', value: workflow.finalizedBy ?? '—' },
              { term: 'Inserted/imported rows', value: workflow.importedRows ?? '—' },
              { term: 'Skipped rows', value: workflow.skippedRows ?? '—' },
              { term: 'Failed rows', value: workflow.errorRows ?? '—' },
              { term: 'Imported amount', value: formatAmount(workflow.importedAmountTotal) },
              { term: 'Imported earnings', value: formatAmount(workflow.importedEarningsTotal) },
              { term: 'Imported deductions', value: formatAmount(workflow.importedDeductionsTotal) },
              { term: 'Rejection reason', value: workflow.rejectionReason || '—' },
            ]}
          />
        )}

        {progress && (
          progress.startedAt
          || progress.status === 'IMPORTING'
          || ['IMPORTED', 'PARTIALLY_IMPORTED', 'FAILED'].includes(progress.status)
        ) && (
          <div className="import-progress-panel compact">
            <div className="import-progress-heading">
              <div>
                <h3>Import progress · Job #{progress.jobId}</h3>
                <p>
                  Status: <StatusChip value={progress.status} />
                  {' '}· Importing records does not generate or finalize monthly payroll.
                </p>
              </div>
              <strong className="import-progress-percent">
                {progress.progressAvailable ? `${progress.percentage}%` : '—'}
              </strong>
            </div>
            <div className="panel-body stack">
              <div className="import-job-details compact">
                <div><span>File</span><strong>{progress.fileName || '—'}</strong></div>
                <div>
                  <span>Period</span>
                  <strong>
                    {progress.periodStart || progress.periodEnd
                      ? `${progress.periodStart || '—'} – ${progress.periodEnd || '—'}`
                      : 'Not recorded for this job'}
                  </strong>
                </div>
                <div><span>Uploader ID</span><strong>{progress.uploadedBy ?? '—'}</strong></div>
                <div><span>Started</span><strong>{progress.startedAt ? formatDateTime(progress.startedAt) : '—'}</strong></div>
              </div>

              <div className="import-progress-summary compact">
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
                      ? 'Counts update as processing events arrive. Imported rows are not committed until the import succeeds.'
                      : progress.status === 'FAILED'
                        ? 'The import transaction failed. No partial data was committed.'
                        : 'Review the import outcome below.'}
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
                    <p className="muted">Progress updates are not available for this job.</p>
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

              {progress.events.length > 0 && (
                <div className="import-live-log compact">
                  <h4>Import log</h4>
                  <div className="import-live-log-scroll">
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
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </section>
  )

  const panels = [stepUpload, stepMap, stepValidate, stepReview, stepImport]

  return (
    <div className="import-wizard-shell">
      {headerBar}
      {error && <Alert kind="error">{error}</Alert>}
      {message && <Alert kind="success" onDismiss={() => setMessage('')}>{message}</Alert>}
      {stepper}
      <main className="import-main">
        {panels[step]}
      </main>

      {job && analysis && (
        <div className="wizard-nav compact">
          <button type="button" className="btn ghost" onClick={() => goToStep(step - 1)} disabled={step === 0}>
            Back
          </button>
          <div className="wizard-nav-copy">
            <strong>{nextHint()}</strong>
          </div>
          {step < 4 && (
            <button type="button" className="btn" onClick={nextStep} disabled={!canContinue()}>
              {nextLabel()}
            </button>
          )}
        </div>
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
        description="The rejection remarks are recorded against the job and returned to the preparer"
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
        description="Save the current column mapping so it can be reused on future workbooks with the same structure"
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
    </div>
  )
}
