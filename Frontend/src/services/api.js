const API_BASE = '/api'

async function parseResponse(response) {
  const contentType = response.headers.get('content-type') || ''

  if (contentType.includes('application/json')) {
    try {
      return await response.json()
    } catch {
      return null
    }
  }

  const text = await response.text()

  return text || null
}

function toError(data, response) {
  const message =
    typeof data === 'string'
      ? data
      : data?.message || data?.detail || data?.error || `Request failed (${response.status})`

  const error = new Error(message)
  error.status = response.status
  return error
}

async function request(url, options = {}) {
  const isForm = typeof FormData !== 'undefined' && options.body instanceof FormData
  const headers = { ...(options.headers || {}) }

  if (!isForm && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(`${API_BASE}${url}`, {
    ...options,
    credentials: 'include',
    headers,
  })

  const data = await parseResponse(response)

  if (!response.ok) throw toError(data, response)

  return data
}

// Multipart uploads must not carry a JSON Content-Type header.
function requestForm(url, formData) {
  return request(url, { method: 'POST', body: formData })
}

async function downloadBlob(url) {
  const response = await fetch(`${API_BASE}${url}`, { credentials: 'include' })

  if (!response.ok) {
    const data = await parseResponse(response)
    throw toError(data, response)
  }

  return response.blob()
}

export function saveBlob(blob, filename) {
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')

  anchor.href = objectUrl
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(objectUrl)
}

function query(params) {
  const search = new URLSearchParams()

  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, value)
    }
  })

  const serialized = search.toString()
  return serialized ? `?${serialized}` : ''
}

export const authApi = {
  login(username, password) {
    return request('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
  },

  forgotPassword(username) {
    return request('/auth/forgot-password', {
      method: 'POST',
      body: JSON.stringify({ username }),
    })
  },

  resetPassword(token, newPassword) {
    return request('/auth/reset-password', {
      method: 'POST',
      body: JSON.stringify({ token, newPassword }),
    })
  },

  me() {
    return request('/auth/me')
  },

  activateAssignment(assignmentId) {
    return request('/auth/active-assignment', {
      method: 'POST',
      body: JSON.stringify({ assignmentId }),
    })
  },
}

export const employeeApi = {
  list() {
    return request('/employees')
  },

  getById(id) {
    return request(`/employees/${id}`)
  },

  create(employee) {
    return request('/employees', {
      method: 'POST',
      body: JSON.stringify(employee),
    })
  },

  update(id, employee) {
    return request(`/employees/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(employee),
    })
  },

  salary(id) {
    return request(`/employees/${id}/salary`)
  },

  setSalaryComponent(id, componentId, body) {
    return request(`/employees/${id}/salary/${componentId}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },

  componentValues() {
    return request('/employees/salary-values')
  },
}

export const salaryComponentApi = {
  list() {
    return request('/salary-components')
  },
}

export const divisionApi = {
  myScope() {
    return request('/divisions/my-scope')
  },
}

export const organizationApi = {
  parentGroups() {
    return request('/organization/parent-groups')
  },

  createParentGroup(body) {
    return request('/organization/parent-groups', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },

  updateParentGroup(id, body) {
    return request(`/organization/parent-groups/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    })
  },

  payrollUnits(params = {}) {
    return request(
      `/organization/payroll-units${query({
        parentGroupId: params.parentGroupId,
        unitType: params.unitType,
        status: params.status,
        verificationStatus: params.verificationStatus,
        q: params.q,
      })}`,
    )
  },

  payrollUnit(id) {
    return request(`/organization/payroll-units/${id}`)
  },

  createPayrollUnit(body) {
    return request('/organization/payroll-units', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },

  updatePayrollUnit(id, body) {
    return request(`/organization/payroll-units/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    })
  },

  changePayrollUnitStatus(id, status, reason) {
    return request(`/organization/payroll-units/${id}/status`, {
      method: 'POST',
      body: JSON.stringify({ status, reason }),
    })
  },
}

export const periodApi = {
  list() {
    return request('/payroll/periods')
  },

  scopeSummary(periodId, divisionId) {
    return request(`/payroll/periods/${periodId}/scope-summary${query({ divisionId })}`)
  },

  create(year, month) {
    return request('/payroll/periods', {
      method: 'POST',
      body: JSON.stringify({ year, month }),
    })
  },
}

export const payrollApi = {
  listBatches(periodId) {
    return request(`/payroll/batches${query({ periodId })}`)
  },

  getBatch(id) {
    return request(`/payroll/batches/${id}`)
  },

  getBatchSummary(id) {
    return request(`/payroll/batches/${id}/summary`)
  },

  listTransactions(id, params = {}) {
    return request(`/payroll/batches/${id}/transactions${query(params)}`)
  },

  getTransaction(id, transactionId) {
    return request(`/payroll/batches/${id}/transactions/${transactionId}`)
  },

  approvals(id) {
    return request(`/payroll/batches/${id}/approvals`)
  },

  readiness(id) {
    return request(`/payroll/batches/${id}/readiness`)
  },

  finalizationEligibility(id, version) {
    return request(`/payroll/batches/${id}/finalization-eligibility${query({ version })}`)
  },

  generate(periodId, divisionId, runType = 'MONTHLY') {
    return request('/payroll/batches/generate', {
      method: 'POST',
      body: JSON.stringify({ periodId, divisionId, runType }),
    })
  },

  verify(id) {
    return request(`/payroll/batches/${id}/verify`, { method: 'POST' })
  },

  submit(id) {
    return request(`/payroll/batches/${id}/submit`, { method: 'POST' })
  },

  approve(id) {
    return request(`/payroll/batches/${id}/approve`, { method: 'POST' })
  },

  reject(id, reason) {
    return request(`/payroll/batches/${id}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    })
  },

  finalize(id, version, confirmed = true) {
    return request(`/payroll/batches/${id}/finalize`, {
      method: 'POST',
      body: JSON.stringify({ confirmed, version }),
    })
  },

  reopen(id, reason) {
    return request(`/payroll/batches/${id}/reopen`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    })
  },
}

export const reportApi = {
  divisionSummary(periodId) {
    return request(`/reports/division-summary${query({ periodId })}`)
  },

  payrollSummary(periodId, status) {
    return request(`/reports/payroll-summary${query({ periodId, status })}`)
  },

  employeeHistory(employeeId) {
    return request(`/reports/employees/${employeeId}/history`)
  },

  downloadDivisionSummary(periodId) {
    return downloadBlob(`/reports/division-summary.csv${query({ periodId })}`)
  },

  downloadPayrollSummary(periodId, status) {
    return downloadBlob(`/reports/payroll-summary.csv${query({ periodId, status })}`)
  },
}

export const auditApi = {
  list(params = {}) {
    return request(`/audit-events${query(params)}`)
  },
}

export const statementApi = {
  list({ periodId, divisionId } = {}) {
    return request(`/statements${query({ periodId, divisionId })}`)
  },

  get(id) {
    return request(`/statements/${id}`)
  },

  generateForBatch(batchId) {
    return request(`/payroll/batches/${batchId}/statements`, { method: 'POST' })
  },

  reissue(id) {
    return request(`/statements/${id}/reissue`, { method: 'POST' })
  },

  download(id) {
    return downloadBlob(`/statements/${id}/download`)
  },
}

export const importApi = {
  upload(file) {
    const formData = new FormData()
    formData.append('file', file)
    return requestForm('/import/jobs/upload', formData)
  },

  listJobs() {
    return request('/import/jobs')
  },
  analyze(jobId) {
    return request(`/import/jobs/${jobId}/analyze`, { method: 'POST' })
  },

  confirmMapping(jobId, sheetIndex, body) {
    return request(`/import/jobs/${jobId}/sheets/${sheetIndex}/mapping`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  },

  validateSheet(jobId, sheetIndex) {
    return request(`/import/jobs/${jobId}/sheets/${sheetIndex}/validate`, {
      method: 'POST',
    })
  },

  assign(jobId, body) {
    return request(`/import/jobs/${jobId}/assignments`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },

  submit(jobId) {
    return request(`/import/jobs/${jobId}/submit`, { method: 'POST' })
  },

  approve(jobId, remarks) {
    return request(`/import/jobs/${jobId}/approve`, {
      method: 'POST',
      body: JSON.stringify({ remarks }),
    })
  },

  reject(jobId, remarks) {
    return request(`/import/jobs/${jobId}/reject`, {
      method: 'POST',
      body: JSON.stringify({ remarks }),
    })
  },

  finalize(jobId) {
    return request(`/import/jobs/${jobId}/finalize`, { method: 'POST' })
  },

  progress(jobId) {
    return request(`/import/jobs/${jobId}/progress`)
  },

  autoMap(jobId, sheetIndex) {
    return request(`/import/jobs/${jobId}/sheets/${sheetIndex}/automap`)
  },

  preview(jobId, sheetIndex) {
    return request(`/import/jobs/${jobId}/sheets/${sheetIndex}/preview`)
  },

  downloadIssues(jobId, sheetIndex) {
    return downloadBlob(`/import/jobs/${jobId}/sheets/${sheetIndex}/issues.csv`)
  },
}

export const mappingProfileApi = {
  list(importType) {
    return request(`/import/mapping-profiles${query({ importType })}`)
  },

  create(body) {
    return request('/import/mapping-profiles', {
      method: 'POST',
      body: JSON.stringify(body),
    })
  },

  update(id, body) {
    return request(`/import/mapping-profiles/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    })
  },

  delete(id) {
    return request(`/import/mapping-profiles/${id}`, { method: 'DELETE' })
  },
}

export const systemApi = {
  health() {
    return request('/health')
  },
}
