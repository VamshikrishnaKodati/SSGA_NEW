import test from 'node:test'
import assert from 'node:assert/strict'

import { validateEmployeeForm } from './employeeValidation.js'

const base = { employeeCode: 'E1', name: 'Asha', divisionId: '3' }

test('required fields', () => {
  assert.equal(validateEmployeeForm({ name: 'Asha', divisionId: '3' }, false), 'Employee code is required.')
  assert.equal(validateEmployeeForm({ employeeCode: 'E1', divisionId: '3' }, false), 'Employee name is required.')
  assert.equal(validateEmployeeForm({ employeeCode: 'E1', name: 'Asha' }, false), 'Select a division.')
  assert.equal(validateEmployeeForm(base, false), '')
})

test('pan is upper-cased before matching', () => {
  assert.equal(validateEmployeeForm({ ...base, pan: 'abcde1234f' }, false), '')
  assert.equal(validateEmployeeForm({ ...base, pan: 'ABC123' }, false), 'PAN must look like ABCDE1234F.')
})

test('aadhaar and email and ifsc and account formats', () => {
  assert.equal(validateEmployeeForm({ ...base, aadhaar: '123456789012' }, false), '')
  assert.equal(validateEmployeeForm({ ...base, aadhaar: '1234' }, false), 'Aadhaar must be 12 digits.')
  assert.equal(validateEmployeeForm({ ...base, email: 'a@b.co' }, false), '')
  assert.equal(validateEmployeeForm({ ...base, email: 'nope' }, false), 'Enter a valid email address.')
  assert.equal(validateEmployeeForm({ ...base, ifscCode: 'sbin0001234' }, false), '')
  assert.equal(validateEmployeeForm({ ...base, ifscCode: 'SBIN123' }, false), 'IFSC must look like SBIN0001234.')
  assert.equal(validateEmployeeForm({ ...base, bankName: 'SBI', bankAccountNumber: '123456789' }, false), '')
  assert.equal(validateEmployeeForm({ ...base, bankName: 'SBI', bankAccountNumber: '12' }, false), 'Account number must be 9 to 18 digits.')
})

test('bank name and account number must come together', () => {
  const message = 'Bank name and account number must be provided together.'
  assert.equal(validateEmployeeForm({ ...base, bankName: 'SBI' }, false), message)
  assert.equal(validateEmployeeForm({ ...base, bankAccountNumber: '123456789' }, false), message)
  assert.equal(validateEmployeeForm({ ...base, bankName: 'SBI', bankAccountNumber: '123456789' }, false), '')
})

test('edit mode does not require code or division', () => {
  assert.equal(validateEmployeeForm({ name: 'Asha' }, true), '')
})
