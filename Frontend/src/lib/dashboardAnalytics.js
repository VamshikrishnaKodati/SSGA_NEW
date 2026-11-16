const APPROVED_PAYROLL_STATUSES = new Set(['APPROVED', 'FINALIZED'])
const APPROVAL_AGE_BUCKETS = [
  { label: '0-2 days', minimum: 0, maximum: 2 },
  { label: '3-5 days', minimum: 3, maximum: 5 },
  { label: '6-10 days', minimum: 6, maximum: 10 },
  { label: '>10 days', minimum: 11, maximum: Infinity },
]

/**
 * How long submitted batches have been waiting for approval.
 *
 * The single definition of this rule. Dashboard.jsx used to keep its own copy
 * keyed on createdAt with different bucket edges, so the two dashboards
 * disagreed about the same batch. Age is measured from submittedAt, because
 * that is when the wait for a checker begins.
 */
export function approvalAgeCounts(batches = [], now = new Date()) {
  const counts = APPROVAL_AGE_BUCKETS.map(({ label }) => ({ label, count: 0 }))
  const nowMs = now instanceof Date ? now.getTime() : new Date(now).getTime()

  batches
    .filter((batch) => batch.status === 'SUBMITTED' && batch.submittedAt)
    .forEach((batch) => {
      const submittedMs = new Date(batch.submittedAt).getTime()
      if (!Number.isFinite(submittedMs)) return
      const days = Math.max(0, Math.floor((nowMs - submittedMs) / 86400000))
      const index = APPROVAL_AGE_BUCKETS.findIndex(
        ({ minimum, maximum }) => days >= minimum && days <= maximum,
      )
      if (index >= 0) counts[index].count += 1
    })

  return counts
}

function rowId(value) {
  return value === null || value === undefined ? '' : String(value)
}

function divisionIndex(divisions) {
  return new Map(divisions.map((division) => [rowId(division.id), division]))
}

function rowWingKey(row, divisionsById) {
  if (row.wingId !== null && row.wingId !== undefined && row.wingId !== '') {
    return rowId(row.wingId)
  }

  const division = divisionsById.get(rowId(row.divisionId))
  if (division?.wingId !== null && division?.wingId !== undefined) {
    return rowId(division.wingId)
  }

  const wingName = row.wingName || division?.wingName
  return wingName ? `name:${wingName}` : ''
}

function periodWindow(period) {
  if (!period) return null

  const year = Number(period.year)
  const month = Number(period.month)
  const start = period.startDate || (
    year && month ? `${year}-${String(month).padStart(2, '0')}-01` : ''
  )
  const end = period.endDate || (
    year && month
      ? new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10)
      : ''
  )

  return start && end ? { start, end } : null
}

function matchesDateRange(batch, periodsById, filters) {
  if (!filters.fromDate && !filters.toDate) return true

  const window = periodWindow(periodsById.get(rowId(batch.periodId)))
  if (!window) return false
  return (!filters.fromDate || window.end >= filters.fromDate)
    && (!filters.toDate || window.start <= filters.toDate)
}

function filteredByScope(rows, filters, divisionsById) {
  return rows.filter((row) => {
    if (filters.divisionId && rowId(row.divisionId) !== filters.divisionId) return false
    if (filters.wingId && rowWingKey(row, divisionsById) !== filters.wingId) return false
    return true
  })
}

export function getDashboardFilterOptions({ employees = [], divisions = [], batches = [] }) {
  const wingOptionsById = new Map()
  const divisionOptionsById = new Map()

  divisions.forEach((division) => {
    divisionOptionsById.set(rowId(division.id), {
      id: rowId(division.id),
      name: division.name,
      wingId: rowId(division.wingId),
    })
    if (division.wingId !== null && division.wingId !== undefined) {
      wingOptionsById.set(rowId(division.wingId), {
        id: rowId(division.wingId),
        name: division.wingName || 'Wing',
      })
    }
  })

  employees.forEach((employee) => {
    if (employee.wingId !== null && employee.wingId !== undefined) {
      const id = rowId(employee.wingId)
      wingOptionsById.set(id, { id, name: employee.wingName || 'Wing' })
    } else if (employee.wingName) {
      const id = `name:${employee.wingName}`
      wingOptionsById.set(id, { id, name: employee.wingName })
    }
    if (employee.divisionId && !divisionOptionsById.has(rowId(employee.divisionId))) {
      divisionOptionsById.set(rowId(employee.divisionId), {
        id: rowId(employee.divisionId),
        name: employee.divisionName || `Division ${employee.divisionId}`,
        wingId: rowId(employee.wingId),
      })
    }
  })

  batches.forEach((batch) => {
    if (!divisionOptionsById.has(rowId(batch.divisionId))) {
      divisionOptionsById.set(rowId(batch.divisionId), {
        id: rowId(batch.divisionId),
        name: batch.divisionName || `Division ${batch.divisionId}`,
        wingId: rowId(batch.wingId),
      })
    }
  })

  return {
    wings: [...wingOptionsById.values()].sort((left, right) => left.name.localeCompare(right.name)),
    divisions: [...divisionOptionsById.values()].sort((left, right) => left.name.localeCompare(right.name)),
  }
}

export function buildDashboardAnalytics({
  employees = [],
  divisions = [],
  batches = [],
  periods = [],
  filters = {},
  now = new Date(),
}) {
  const divisionsById = divisionIndex(divisions)
  const periodsById = new Map(periods.map((period) => [rowId(period.id), period]))
  const scopedEmployees = filteredByScope(employees, filters, divisionsById)
  const scopedBatches = filteredByScope(batches, filters, divisionsById)
    .filter((batch) => matchesDateRange(batch, periodsById, filters))

  const wingCounts = new Map()
  scopedEmployees.forEach((employee) => {
    const wing = employee.wingName
      || divisionsById.get(rowId(employee.divisionId))?.wingName
      || 'Unassigned'
    wingCounts.set(wing, (wingCounts.get(wing) || 0) + 1)
  })

  const activeEmployees = scopedEmployees.filter((employee) => employee.status === 'ACTIVE').length
  const payrollByPeriod = new Map()
  scopedBatches
    .filter((batch) => APPROVED_PAYROLL_STATUSES.has(batch.status))
    .forEach((batch) => {
      const key = rowId(batch.periodId)
      const period = periodsById.get(key)
      const current = payrollByPeriod.get(key) || {
        label: batch.periodLabel || period?.label || '',
        startDate: periodWindow(period)?.start || '',
        net: 0,
        gross: 0,
        deductions: 0,
      }
      current.net += Number(batch.netTotal || 0)
      current.gross += Number(batch.grossTotal || 0)
      current.deductions += Number(batch.deductionTotal || 0)
      payrollByPeriod.set(key, current)
    })

  const batchStatuses = new Map()
  scopedBatches.forEach((batch) => {
    batchStatuses.set(batch.status, (batchStatuses.get(batch.status) || 0) + 1)
  })

  const ageCounts = approvalAgeCounts(scopedBatches, now)

  const monthlyPayroll = [...payrollByPeriod.values()]
    .sort((left, right) => left.startDate.localeCompare(right.startDate))

  return {
    employeeCount: scopedEmployees.length,
    activeEmployees,
    inactiveEmployees: scopedEmployees.length - activeEmployees,
    employeesByWing: [...wingCounts.entries()].sort((left, right) => right[1] - left[1]),
    monthlyPayroll,
    batchStatuses: [...batchStatuses.entries()],
    approvalAges: ageCounts,
  }
}
