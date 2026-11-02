import test from 'node:test'
import assert from 'node:assert/strict'
import {
  canFinalizePayrollBatch,
  filterScopedDivisions,
  hasMonthlyBatch,
  payrollBatchStatusLabel,
  payrollScopeIsProcessable,
  payrollWorkflowSteps,
} from './payrollWorkspace.js'

test('division choices respect selected organization and wing', () => {
  const divisions = [
    { id: 1, organizationId: 10, wingId: 4 },
    { id: 2, organizationId: 10, wingId: 5 },
    { id: 3, organizationId: 11, wingId: 4 },
  ]

  assert.deepEqual(filterScopedDivisions(divisions, 10, 4), [divisions[0]])
})

test('processing requires an open period and active authorized division', () => {
  const period = { id: 7, organizationId: 10, status: 'OPEN' }
  const division = { id: 1, organizationId: 10, status: 'ACTIVE' }

  assert.equal(payrollScopeIsProcessable(period, division, [division]), true)
  assert.equal(
    payrollScopeIsProcessable({ ...period, status: 'LOCKED' }, division, [division]),
    false,
  )
  assert.equal(
    payrollScopeIsProcessable(period, { ...division, organizationId: 11 }, [division]),
    false,
  )
})

test('duplicate monthly generation is detected from loaded batches', () => {
  const batches = [{ periodId: 7, divisionId: 1, runType: 'MONTHLY' }]

  assert.equal(hasMonthlyBatch(batches, 7, 1), true)
  assert.equal(hasMonthlyBatch(batches, 7, 2), false)
})

test('backend batch states receive readable labels without invented state values', () => {
  assert.equal(payrollBatchStatusLabel('UNDER_REVIEW'), 'Under review')
  assert.equal(payrollBatchStatusLabel('SUBMITTED'), 'Submitted')
  assert.equal(payrollBatchStatusLabel('REOPENED'), 'Reopened')
  assert.equal(payrollBatchStatusLabel('UNEXPECTED'), 'UNEXPECTED')
})

test('workflow progress reflects backend batch and readiness state', () => {
  const steps = payrollWorkflowSteps({
    period: { status: 'OPEN' },
    division: { status: 'ACTIVE' },
    batch: { status: 'SUBMITTED' },
    readiness: { totalTransactions: 2, verifiedTransactions: 2, issues: [] },
  })

  assert.equal(steps[0].state, 'complete')
  assert.equal(steps[2].state, 'complete')
  assert.equal(steps[3].state, 'complete')
  assert.equal(steps[4].state, 'complete')
  assert.equal(steps[5].state, 'current')
})

test('workflow status progression covers draft through finalization', () => {
  const expectedCurrentStep = {
    DRAFT: 5,
    SUBMITTED: 6,
    UNDER_REVIEW: 6,
    REJECTED: 5,
    APPROVED: 7,
    FINALIZED: 8,
  }

  Object.entries(expectedCurrentStep).forEach(([status, currentStep]) => {
    const steps = payrollWorkflowSteps({
      period: { status: 'OPEN' },
      division: { status: 'ACTIVE' },
      batch: { status },
      readiness: { totalTransactions: 2, verifiedTransactions: 2, issues: [] },
    })

    assert.equal(steps.find((step) => step.current)?.number, currentStep, status)
    if (status !== 'APPROVED' && status !== 'FINALIZED') {
      assert.notEqual(steps[6].state, 'current', status)
    }
  })
})

test('finalization requires approved backend eligibility, matching version and separation', () => {
  const batch = { status: 'APPROVED', version: 8, makerId: 7, checkerId: 42 }
  const eligibility = { eligible: true, version: 8 }
  const canFinalize = (overrides = {}) => canFinalizePayrollBatch({
    batch,
    eligibility,
    canFinalize: true,
    userId: 99,
    ...overrides,
  })

  assert.equal(canFinalize(), true)
  assert.equal(canFinalize({ canFinalize: false }), false)
  assert.equal(canFinalize({ eligibility: { eligible: true, version: 7 } }), false)
  assert.equal(canFinalize({ batch: { ...batch, status: 'SUBMITTED' } }), false)
  assert.equal(canFinalize({ userId: batch.makerId }), false)
  assert.equal(canFinalize({ userId: batch.checkerId }), false)
})
