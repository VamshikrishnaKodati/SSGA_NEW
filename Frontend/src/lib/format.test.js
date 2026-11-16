import test from 'node:test'
import assert from 'node:assert/strict'

import { friendlyError } from './format.js'

test('500 without an action keeps the bare message callers already render', () => {
  assert.equal(
    friendlyError({ status: 500 }),
    'The server encountered an error. Please try again.',
  )
})

test('401 is unchanged for the session-expiry banner', () => {
  assert.equal(friendlyError({ status: 401 }), 'Your session has expired. Please sign in again.')
})

test('500 with an action names the action and the status', () => {
  const message = friendlyError({ status: 500 }, 'Submit for approval')
  assert.match(message, /The server encountered an error/)
  assert.match(message, /Submit for approval failed/)
  assert.match(message, /HTTP 500/)
})

test('a backend message is preserved and still gets context', () => {
  const message = friendlyError(
    { status: 409, message: 'This active assignment already exists.' },
    'Save assignment',
  )
  assert.match(message, /This active assignment already exists\./)
  assert.match(message, /Save assignment failed · HTTP 409/)
})

test('errors without a status fall back to the raw message', () => {
  assert.equal(friendlyError(new Error('boom')), 'boom')
  assert.equal(friendlyError(new Error('boom'), 'Analyse workbook'), 'boom (Analyse workbook failed)')
})