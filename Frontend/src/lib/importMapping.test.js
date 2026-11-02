import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildMappingPayload,
  usedHeadersFor,
  canValidateSheet,
  stepStates,
  applyAutoMap,
  profileFromDraft,
  applyProfile,
  validationBlocker,
} from './importMapping.js'

test('buildMappingPayload inverts fields→headers and drops blanks', () => {
  const payload = buildMappingPayload({
    type: 'SALARY_ENTITLEMENTS',
    columns: { employeeCode: 'Emp Code', amount: 'Gross', effectiveFrom: '' },
    options: { layout: 'LONG', dateFormat: '' },
    components: [],
  })

  assert.equal(payload.confirmedType, 'SALARY_ENTITLEMENTS')
  assert.deepEqual(payload.columnMapping, { employeeCode: 'Emp Code', amount: 'Gross' })
  assert.deepEqual(payload.options, { layout: 'LONG' })
  assert.equal(payload.notes, null)
})

test('componentColumns is only emitted for wide-layout types', () => {
  const wide = buildMappingPayload({
    type: 'PAYROLL_HISTORY',
    columns: { employeeCode: 'Code' },
    options: {},
    components: [{ code: 'basic', header: 'Basic' }, { code: '', header: 'Ignored' }],
  })
  assert.deepEqual(wide.columnMapping.componentColumns, { BASIC: 'Basic' })

  const narrow = buildMappingPayload({
    type: 'EMPLOYEE_MASTER',
    columns: { employeeCode: 'Code' },
    options: {},
    components: [{ code: 'BASIC', header: 'Basic' }],
  })
  assert.equal('componentColumns' in narrow.columnMapping, false)
})

test('usedHeadersFor excludes the field and component under edit', () => {
  const draft = {
    columns: { employeeCode: 'Code', amount: 'Gross' },
    components: [{ code: 'BASIC', header: 'Basic' }],
  }

  const forAmount = usedHeadersFor(draft, 'amount', -1)
  assert.equal(forAmount.has('Gross'), false)
  assert.equal(forAmount.has('Code'), true)
  assert.equal(forAmount.has('Basic'), true)

  const forComponent = usedHeadersFor(draft, null, 0)
  assert.equal(forComponent.has('Basic'), false)
  assert.equal(forComponent.has('Code'), true)
})

test('validation gating reports missing mappings and accepts complete supported sheet types', () => {
  const base = {
    type: 'SALARY_ENTITLEMENTS',
    options: { layout: 'LONG' },
    columns: {
      hrmsId: 'HRMS ID',
      componentCode: 'Component code',
      amount: 'Amount',
      effectiveFrom: 'Effective from',
    },
  }
  assert.equal(canValidateSheet(base), true)
  assert.equal(canValidateSheet({ ...base, options: { layout: 'WIDE' } }), false)
  assert.equal(validationBlocker({ ...base, type: 'EMPLOYEE_MASTER' }), 'The selected sheet type is read-only and cannot be validated for import.')

  const revision = {
    type: 'SALARY_REVISIONS',
    columns: { employeeCode: 'Employee code', componentCode: 'Component', amount: 'Amount', effectiveFrom: 'Effective from' },
  }
  assert.equal(canValidateSheet(revision), true)

  const wideCombined = {
    type: 'EMPLOYEE_MASTER_AND_COMPONENTS',
    options: { layout: 'WIDE' },
    columns: { hrmsId: 'HRMS ID', employeeName: 'Employee name', effectiveFrom: 'Effective from' },
    components: [{ code: 'BASIC_PAY', header: 'Basic pay' }],
  }
  assert.equal(canValidateSheet(wideCombined), true)
  assert.equal(canValidateSheet({
    ...wideCombined,
    components: [],
  }), false)
  assert.equal(validationBlocker({
    ...wideCombined,
    columns: { employeeName: 'Employee name' },
  }), 'Map HRMS ID or Employee code.')
  assert.equal(canValidateSheet({ ...base, columns: { hrmsId: 'HRMS ID' } }), false)
})

test('stepStates marks upload active before a job exists', () => {
  const states = stepStates({ job: null, analysis: null, drafts: {}, workflow: null })
  assert.equal(states.upload, 'active')
  assert.equal(states.analyse, '')
})

test('applyAutoMap fills columns and merges component suggestions', () => {
  const draft = {
    columns: {},
    components: [{ code: 'BASIC', header: '' }],
    options: {},
  }
  const autoMap = {
    importType: 'SALARY_ENTITLEMENTS',
    structureSignature: 'a'.repeat(64),
    fields: [
      { field: 'employeeCode', sourceHeader: 'Emp Code', confidence: 'REVIEW' },
      { field: 'amount', sourceHeader: null, confidence: 'UNMATCHED' },
    ],
    components: [{ componentCode: 'BASIC', sourceHeader: 'Basic Pay' }],
  }

  const result = applyAutoMap(draft, autoMap)

  assert.deepEqual(result.columns, { employeeCode: 'Emp Code' })
  assert.deepEqual(result.components, [{ code: 'BASIC', header: 'Basic Pay' }])
  assert.equal(result.autoMapResult.structureSignature, 'a'.repeat(64))
})

test('profileFromDraft drops blank components and round-trips through applyProfile', () => {
  const draft = {
    columns: { employeeCode: 'Emp Code' },
    components: [{ code: 'BASIC', header: 'Basic' }, { code: '', header: 'Ignored' }],
    options: { layout: 'LONG' },
  }

  const profile = profileFromDraft(draft)
  assert.deepEqual(profile.columnMapping.fields, { employeeCode: 'Emp Code' })
  assert.deepEqual(profile.columnMapping.components, [{ code: 'BASIC', header: 'Basic' }])
  assert.deepEqual(profile.options, { layout: 'LONG' })

  const restored = applyProfile({ columns: {}, components: [{ code: '', header: '' }], options: {} }, profile)
  assert.equal(restored.columns.employeeCode, 'Emp Code')
  assert.deepEqual(restored.components, [{ code: 'BASIC', header: 'Basic' }])
  assert.equal(restored.options.layout, 'LONG')
})
