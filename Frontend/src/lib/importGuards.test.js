import test from 'node:test'
import assert from 'node:assert/strict'

import { MAX_UPLOAD_BYTES, uploadRejection, approveBlocked, finalizeBlocked } from './importGuards.js'

test('rejects unsupported extensions and oversize files', () => {
  assert.match(uploadRejection({ name: 'data.pdf', size: 10 }), /Only/)
  assert.match(uploadRejection({ name: 'data.xlsx', size: MAX_UPLOAD_BYTES + 1 }), /25 MB/)
  assert.equal(uploadRejection({ name: 'Data.CSV', size: MAX_UPLOAD_BYTES }), '')
})

test('maker cannot approve or finalize', () => {
  const identity = { uploadedBy: 7, submittedBy: null, approvedBy: null }
  assert.equal(approveBlocked(7, identity), true)
  assert.equal(finalizeBlocked(7, identity), true)
  assert.equal(approveBlocked(8, identity), false)
})

test('approver cannot finalize the same job', () => {
  const identity = { uploadedBy: 7, submittedBy: 7, approvedBy: 9 }
  assert.equal(finalizeBlocked(9, identity), true)
  assert.equal(finalizeBlocked(10, identity), false)
})
