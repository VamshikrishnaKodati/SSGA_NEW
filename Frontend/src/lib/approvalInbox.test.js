import test from 'node:test'
import assert from 'node:assert/strict'
import { createAbility } from './permissions.js'
import {
  canListApprovalBatches,
  filterApprovalBatches,
  isApprovalReadinessPassed,
} from './approvalInbox.js'

test('batch readers include workflow roles that are allowed by the API', () => {
  assert.equal(canListApprovalBatches(createAbility({
    authorities: ['payroll.approve'],
  })), true)
  assert.equal(canListApprovalBatches(createAbility({
    authorities: ['payroll.finalize'],
  })), true)
  assert.equal(canListApprovalBatches(createAbility({
    authorities: ['report.view'],
  })), false)
})

test('approval readiness fails closed unless all submitted transactions are verified', () => {
  assert.equal(isApprovalReadinessPassed({
    status: 'SUBMITTED',
    totalTransactions: 4,
    verifiedTransactions: 4,
    issues: [],
  }), true)
  assert.equal(isApprovalReadinessPassed({
    status: 'UNDER_REVIEW',
    totalTransactions: 4,
    verifiedTransactions: 4,
    issues: [],
  }), true)
  assert.equal(isApprovalReadinessPassed({
    status: 'APPROVED',
    totalTransactions: 4,
    verifiedTransactions: 4,
    issues: [],
  }), false)
  assert.equal(isApprovalReadinessPassed({
    status: 'SUBMITTED',
    totalTransactions: 4,
    verifiedTransactions: 3,
    issues: [],
  }), false)
  assert.equal(isApprovalReadinessPassed({
    status: 'SUBMITTED',
    totalTransactions: 4,
    verifiedTransactions: 4,
    issues: ['Net total mismatch'],
  }), false)
})

test('all open batches excludes finalized batches', () => {
  const batches = [
    { batchId: 1, status: 'SUBMITTED' },
    { batchId: 2, status: 'UNDER_REVIEW' },
    { batchId: 3, status: 'APPROVED' },
    { batchId: 4, status: 'FINALIZED' },
  ]

  assert.deepEqual(filterApprovalBatches(batches, 'all'), batches.slice(0, 3))
  assert.deepEqual(filterApprovalBatches(batches, 'pending'), batches.slice(0, 2))
  assert.deepEqual(filterApprovalBatches(batches, 'approved'), [batches[2]])
})
