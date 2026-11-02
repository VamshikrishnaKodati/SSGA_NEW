/**
 * Pure data-import mapping logic, kept out of the JSX so it can be checked
 * with Node's built-in test runner. It mirrors the backend contract in
 * ImportJobSheetMappingService (allowed target fields, options, header rules).
 */

export const SHEET_TYPES = [
  { value: 'EMPLOYEE_MASTER', label: 'Employee master (review only — cannot commit)' },
  { value: 'EMPLOYEE_MASTER_AND_COMPONENTS', label: 'Employee master + salary components' },
  { value: 'SALARY_ENTITLEMENTS', label: 'Salary entitlements' },
  { value: 'PAYROLL_HISTORY', label: 'Payroll history (review only — cannot commit)' },
  { value: 'SALARY_REVISIONS', label: 'Salary revisions' },
  { value: 'REFERENCE_ONLY', label: 'Reference only (no import)' },
  { value: 'SKIP', label: 'Skip this sheet' },
]

export const TARGET_FIELDS = {
  EMPLOYEE_MASTER: [
    ['employeeCode', 'Employee code'], ['hrmsId', 'HRMS ID'], ['employeeName', 'Employee name'],
    ['designation', 'Designation'], ['wing', 'Wing'], ['division', 'Division'], ['divisionId', 'Division ID'],
    ['dateOfJoining', 'Date of joining'], ['dateOfBirth', 'Date of birth'], ['employmentType', 'Employment type'],
    ['payScale', 'Pay scale'], ['basicPay', 'Basic pay'], ['contact', 'Contact'], ['pan', 'PAN'],
    ['aadhaar', 'Aadhaar'], ['employeeStatus', 'Employee status'], ['bankAccount', 'Bank account'],
    ['ifscCode', 'IFSC code'], ['bankName', 'Bank name'], ['branch', 'Branch'],
  ],
  EMPLOYEE_MASTER_AND_COMPONENTS: [
    ['employeeCode', 'Employee code'], ['hrmsId', 'HRMS ID'], ['employeeName', 'Employee name'],
    ['designation', 'Designation'], ['wing', 'Wing'], ['division', 'Division'], ['divisionId', 'Division ID'],
    ['dateOfJoining', 'Date of joining'], ['dateOfBirth', 'Date of birth'], ['employmentType', 'Employment type'],
    ['payScale', 'Pay scale'], ['basicPay', 'Basic pay'], ['contact', 'Contact'], ['pan', 'PAN'],
    ['aadhaar', 'Aadhaar'], ['employeeStatus', 'Employee status'], ['bankAccount', 'Bank account'],
    ['ifscCode', 'IFSC code'], ['bankName', 'Bank name'], ['branch', 'Branch'], ['effectiveFrom', 'Effective from'],
    ['effectiveTo', 'Effective to'], ['componentCode', 'Component code'], ['componentName', 'Component name'],
    ['category', 'Category'], ['amount', 'Amount'],
  ],
  SALARY_ENTITLEMENTS: [
    ['employeeCode', 'Employee code'], ['hrmsId', 'HRMS ID'], ['employeeName', 'Employee name'],
    ['division', 'Division'], ['divisionId', 'Division ID'], ['effectiveFrom', 'Effective from'],
    ['effectiveTo', 'Effective to'], ['componentCode', 'Component code'], ['componentName', 'Component name'],
    ['category', 'Category'], ['amount', 'Amount'],
  ],
  PAYROLL_HISTORY: [
    ['employeeCode', 'Employee code'], ['hrmsId', 'HRMS ID'], ['employeeName', 'Employee name'],
    ['division', 'Division'], ['divisionId', 'Division ID'], ['period', 'Period'],
    ['payrollMonth', 'Payroll month'], ['payrollYear', 'Payroll year'], ['componentCode', 'Component code'],
    ['componentName', 'Component name'], ['category', 'Category'], ['amount', 'Amount'],
    ['gross', 'Gross'], ['totalDeductions', 'Total deductions'], ['net', 'Net'],
    ['transactionStatus', 'Transaction status'],
  ],
  SALARY_REVISIONS: [
    ['employeeCode', 'Employee code'], ['hrmsId', 'HRMS ID'], ['employeeName', 'Employee name'],
    ['effectiveFrom', 'Effective from'], ['componentCode', 'Component code'], ['componentName', 'Component name'],
    ['oldAmount', 'Old amount'], ['newAmount', 'New amount'], ['amount', 'Amount'], ['reason', 'Reason'],
  ],
}

export const OPTION_FIELDS = {
  EMPLOYEE_MASTER_AND_COMPONENTS: [
    ['layout', 'Layout', 'select', ['LONG', 'WIDE']],
    ['dateFormat', 'Date format', 'text'],
    ['effectiveFromDefault', 'Default effective from (YYYY-MM-DD)', 'date'],
  ],
  SALARY_ENTITLEMENTS: [
    ['layout', 'Layout', 'select', ['LONG', 'WIDE']],
    ['dateFormat', 'Date format', 'text'],
    ['effectiveFromDefault', 'Default effective from (YYYY-MM-DD)', 'date'],
  ],
  PAYROLL_HISTORY: [
    ['layout', 'Layout', 'select', ['LONG', 'WIDE']],
    ['dateFormat', 'Date format', 'text'],
    ['periodYearDefault', 'Default period year', 'number'],
    ['periodMonthDefault', 'Default period month', 'number'],
  ],
  EMPLOYEE_MASTER: [['dateFormat', 'Date format', 'text']],
  SALARY_REVISIONS: [
    ['dateFormat', 'Date format', 'text'],
    ['effectiveFromDefault', 'Default effective from (YYYY-MM-DD)', 'date'],
  ],
}

export const SUPPORTS_WIDE = new Set(['SALARY_ENTITLEMENTS', 'PAYROLL_HISTORY', 'EMPLOYEE_MASTER_AND_COMPONENTS'])
export const VALIDATABLE_TYPE = 'SALARY_ENTITLEMENTS'
export const VALIDATABLE_TYPES = new Set(['SALARY_ENTITLEMENTS', 'SALARY_REVISIONS', 'EMPLOYEE_MASTER_AND_COMPONENTS'])

export const CONFIDENCE = {
  HIGH: 'HIGH',
  REVIEW: 'REVIEW',
  UNMATCHED: 'UNMATCHED',
}

export function validationBlocker(draft) {
  if (!draft?.type) return 'Choose an import purpose.'
  if (!VALIDATABLE_TYPES.has(draft.type)) {
    return 'The selected sheet type is read-only and cannot be validated for import.'
  }

  const columns = draft.columns || {}
  const hasEmployeeIdentity = columns.employeeCode || columns.hrmsId
  const hasEffectiveDate = columns.effectiveFrom || draft.options?.effectiveFromDefault
  const hasWideComponents = (draft.components || []).some(
    (component) => component.code?.trim() && component.header,
  )

  if (draft.type === 'EMPLOYEE_MASTER_AND_COMPONENTS') {
    const layout = draft.options?.layout
    if (layout !== 'LONG' && layout !== 'WIDE') {
      return 'Select LONG or WIDE layout.'
    }
    if (!hasEmployeeIdentity) return 'Map HRMS ID or Employee code.'
    if (!columns.employeeName) return 'Map Employee name.'
    if (layout === 'LONG' && (!columns.componentCode || !columns.amount)) {
      return 'Map Component code and Amount for LONG layout.'
    }
    if (layout === 'WIDE' && !hasWideComponents) {
      return 'Map at least one recognized salary component column for WIDE layout.'
    }
    if (!hasEffectiveDate) {
      return 'Map Effective from or set a default effective date.'
    }
    return ''
  }

  if (draft.type === 'SALARY_ENTITLEMENTS') {
    if (draft.options?.layout !== 'LONG') {
      return 'Salary entitlements can currently be validated only in LONG layout.'
    }
    if (!hasEmployeeIdentity) return 'Map HRMS ID or Employee code.'
    if (!columns.componentCode) return 'Map Component code.'
    if (!columns.amount) return 'Map Amount.'
    if (!hasEffectiveDate) {
      return 'Map Effective from or set a default effective date.'
    }
    return ''
  }

  if (!hasEmployeeIdentity) return 'Map HRMS ID or Employee code.'
  if (!columns.componentCode) return 'Map Component code.'
  if (!columns.newAmount && !columns.amount) return 'Map New amount or Amount.'
  if (!hasEffectiveDate) {
    return 'Map Effective from or set a default effective date.'
  }
  return ''
}

/**
 * Invert the UI's field-oriented model into the backend's
 * { targetField: sourceHeader } shape. Blank mappings are dropped so the
 * server only receives fields the operator actually mapped.
 */
export function buildMappingPayload(draft) {
  const columnMapping = {}

  Object.entries(draft.columns).forEach(([field, header]) => {
    if (header) columnMapping[field] = header
  })

  if (SUPPORTS_WIDE.has(draft.type)) {
    const components = {}
    ;(draft.components || []).forEach(({ code, header }) => {
      if (code.trim() && header) components[code.trim().toUpperCase()] = header
    })
    if (Object.keys(components).length > 0) columnMapping.componentColumns = components
  }

  const options = {}
  Object.entries(draft.options || {}).forEach(([key, value]) => {
    if (value !== '' && value !== null && value !== undefined) options[key] = value
  })

  return { confirmedType: draft.type, columnMapping, options, notes: draft.notes || null }
}

/** Headers already consumed by other mappings, so a column is used at most once. */
export function usedHeadersFor(draft, excludeField, excludeComponentIndex) {
  const used = new Set()

  Object.entries(draft.columns).forEach(([field, header]) => {
    if (field !== excludeField && header) used.add(header)
  })

  ;(draft.components || []).forEach((component, index) => {
    if (index !== excludeComponentIndex && component.header) used.add(component.header)
  })

  return used
}

export function canValidateSheet(draft) {
  return !validationBlocker(draft)
}

export function stepStates({ job, analysis, drafts, workflow }) {
  const mapped = Object.values(drafts).some((draft) => draft.mappingResult)
  const validated = Object.values(drafts).some((draft) => draft.validation)

  return {
    upload: job ? 'done' : 'active',
    analyse: analysis ? 'done' : job ? 'active' : '',
    map: validated ? 'done' : analysis ? 'active' : '',
    assign: workflow ? 'done' : mapped ? 'active' : '',
    submit: workflow?.submittedBy ? 'done' : validated ? 'active' : '',
    approve: workflow?.approvedBy ? 'done' : workflow?.submittedBy ? 'active' : '',
    finalize: workflow?.finalizedBy ? 'done' : workflow?.approvedBy ? 'active' : '',
  }
}

export function applyAutoMap(draft, autoMap) {
  const fields = autoMap?.fields || []
  const components = autoMap?.components || []

  const columns = { ...(draft.columns || {}) }

  fields.forEach((field) => {
    if (field?.sourceHeader && field?.field) {
      columns[field.field] = field.sourceHeader
    }
  })

  const nextComponents = (draft.components || []).map((component) => ({ ...component }))

  components.forEach((component) => {
    if (component?.sourceHeader && component?.componentCode) {
      const code = component.componentCode.toUpperCase()
      const existing = nextComponents.find((item) => item.code?.trim().toUpperCase() === code)
      if (existing) {
        existing.header = component.sourceHeader
      } else {
        nextComponents.push({ code, header: component.sourceHeader })
      }
    }
  })

  return {
    ...draft,
    columns,
    components: nextComponents,
    autoMapResult: autoMap || null,
  }
}

export function profileFromDraft(draft) {
  return {
    columnMapping: {
      fields: { ...(draft.columns || {}) },
      components: (draft.components || [])
        .filter((component) => component.code?.trim() && component.header)
        .map((component) => ({ code: component.code.trim(), header: component.header })),
    },
    options: { ...(draft.options || {}) },
  }
}

export function applyProfile(draft, profile) {
  const mapping = profile?.columnMapping || {}
  const fields = mapping.fields || {}
  const components = Array.isArray(mapping.components) ? mapping.components : []

  return {
    ...draft,
    columns: { ...(draft.columns || {}), ...fields },
    components: components.length
      ? components.map((component) => ({ code: component.code || '', header: component.header || '' }))
      : draft.components,
    options: { ...(draft.options || {}), ...(profile?.options || {}) },
  }
}
