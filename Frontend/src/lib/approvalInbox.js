const BATCH_READ_AUTHORITIES = [
  'payroll.view',
  'payroll.prepare',
  'payroll.verify',
  'payroll.submit',
  'payroll.approve',
  'payroll.finalize',
  'payroll.reopen',
]

export function canListApprovalBatches(ability) {
  return ability.hasAny(...BATCH_READ_AUTHORITIES)
}

export function isAwaitingApproval(status) {
  return ['SUBMITTED', 'UNDER_REVIEW'].includes(status)
}

export function isApprovalReadinessPassed(readiness) {
  return Boolean(
    readiness
      && isAwaitingApproval(readiness.status)
      && Array.isArray(readiness.issues)
      && readiness.issues.length === 0
      && readiness.totalTransactions > 0
      && readiness.verifiedTransactions === readiness.totalTransactions,
  )
}

export function filterApprovalBatches(batches, tab) {
  if (tab === 'pending') {
    return batches.filter((batch) => isAwaitingApproval(batch.status))
  }

  if (tab === 'approved') {
    return batches.filter((batch) => batch.status === 'APPROVED')
  }

  return batches.filter((batch) => batch.status !== 'FINALIZED')
}
