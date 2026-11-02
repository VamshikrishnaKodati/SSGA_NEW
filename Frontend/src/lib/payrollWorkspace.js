export const PAYROLL_WORKFLOW_STEPS = [
  'Period & Scope',
  'Readiness Check',
  'Generate Salary',
  'Verify & Review',
  'Submit',
  'Approval',
  'Finalize & Lock',
  'Statements',
]

const BATCH_STATUS_LABELS = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  UNDER_REVIEW: 'Under review',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  FINALIZED: 'Finalized',
  REOPENED: 'Reopened',
  OPEN: 'Open',
  LOCKED: 'Locked',
  CLOSED: 'Closed',
  UNSELECTED: 'Unselected',
  DUPLICATE: 'Existing batch',
}

export function payrollBatchStatusLabel(status) {
  return BATCH_STATUS_LABELS[status] || status || 'Unknown'
}

export function canFinalizePayrollBatch({
  batch,
  eligibility,
  canFinalize,
  userId,
}) {
  return Boolean(
    canFinalize
      && batch?.status === 'APPROVED'
      && eligibility?.eligible === true
      && String(batch.version) === String(eligibility.version)
      && (batch.makerId == null || String(batch.makerId) !== String(userId))
      && (batch.checkerId == null || String(batch.checkerId) !== String(userId)),
  )
}

export function filterScopedDivisions(divisions, organizationId, wingId = '') {
  return divisions.filter((division) => {
    if (organizationId !== null && organizationId !== undefined
      && String(division.organizationId) !== String(organizationId)) {
      return false
    }

    return !wingId || String(division.wingId) === String(wingId)
  })
}

export function hasMonthlyBatch(batches, periodId, divisionId) {
  return batches.some((batch) =>
    String(batch.periodId) === String(periodId)
      && String(batch.divisionId) === String(divisionId)
      && String(batch.runType || 'MONTHLY').toUpperCase() === 'MONTHLY',
  )
}

export function payrollScopeIsProcessable(period, division, visibleDivisions) {
  return !!period
    && period.status === 'OPEN'
    && !!division
    && division.status === 'ACTIVE'
    && String(division.organizationId) === String(period.organizationId)
    && visibleDivisions.some((visible) =>
      String(visible.id) === String(division.id)
        && String(visible.organizationId) === String(period.organizationId),
    )
}

export function payrollWorkflowSteps({
  period,
  division,
  batch,
  readiness,
  readinessLoading = false,
  readinessError = '',
  statementsGenerated = false,
}) {
  const scopeSelected = !!period && !!division
  const batchStatus = batch?.status
  const batchExists = !!batch
  const transactionsVerified = !!readiness
    && readiness.totalTransactions > 0
    && readiness.verifiedTransactions >= readiness.totalTransactions
  const readinessBlocked = !!readinessError
    || (readiness?.issues?.length || 0) > 0
  const submitted = ['SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'FINALIZED'].includes(batchStatus)

  const states = [
    scopeSelected ? 'complete' : 'current',
    !batchExists
      ? 'pending'
      : readinessLoading
        ? 'current'
        : readinessBlocked
          ? 'blocked'
          : readiness
            ? 'complete'
            : 'pending',
    batchExists
      ? 'complete'
      : period?.status === 'OPEN' && division?.status === 'ACTIVE'
        ? 'current'
        : 'blocked',
    !batchExists
      ? 'pending'
      : readinessBlocked
        ? 'blocked'
        : transactionsVerified
          ? 'complete'
          : 'current',
    submitted
      ? 'complete'
      : transactionsVerified
        ? 'current'
        : readinessBlocked
          ? 'blocked'
          : 'pending',
    ['APPROVED', 'FINALIZED'].includes(batchStatus)
      ? 'complete'
      : ['SUBMITTED', 'UNDER_REVIEW'].includes(batchStatus)
        ? 'current'
        : 'pending',
    batchStatus === 'FINALIZED'
      ? 'complete'
      : batchStatus === 'APPROVED'
        ? 'current'
        : 'pending',
    statementsGenerated
      ? 'complete'
      : batchStatus === 'FINALIZED'
        ? 'current'
        : 'pending',
  ]

  const currentIndex = states.findIndex((state) => state === 'current')

  return PAYROLL_WORKFLOW_STEPS.map((title, index) => ({
    number: index + 1,
    title,
    state: states[index],
    current: index === currentIndex,
  }))
}
