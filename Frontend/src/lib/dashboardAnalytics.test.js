import test from 'node:test'
import assert from 'node:assert/strict'
import { buildDashboardAnalytics, getDashboardFilterOptions } from './dashboardAnalytics.js'

const periods = [
  { id: 1, year: 2026, month: 1, label: 'Jan 2026', startDate: '2026-01-01', endDate: '2026-01-31' },
  { id: 2, year: 2026, month: 2, label: 'Feb 2026', startDate: '2026-02-01', endDate: '2026-02-28' },
]

const divisions = [
  { id: 10, wingId: 1, wingName: 'Operations', name: 'Water' },
  { id: 20, wingId: 2, wingName: 'Finance', name: 'Payroll' },
]

const employees = [
  { divisionId: 10, wingId: 1, wingName: 'Operations', status: 'ACTIVE' },
  { divisionId: 10, wingId: 1, wingName: 'Operations', status: 'INACTIVE' },
  { divisionId: 20, wingId: 2, wingName: 'Finance', status: 'ACTIVE' },
]

const batches = [
  { periodId: 1, periodLabel: 'Jan 2026', divisionId: 10, status: 'FINALIZED', grossTotal: 120, deductionTotal: 20, netTotal: 100 },
  { periodId: 2, periodLabel: 'Feb 2026', divisionId: 10, status: 'DRAFT', grossTotal: 999, deductionTotal: 99, netTotal: 900 },
  { periodId: 2, periodLabel: 'Feb 2026', divisionId: 20, status: 'APPROVED', grossTotal: 60, deductionTotal: 10, netTotal: 50 },
  { periodId: 2, periodLabel: 'Feb 2026', divisionId: 20, status: 'SUBMITTED', submittedAt: '2026-02-08T12:00:00Z' },
  { periodId: 1, periodLabel: 'Jan 2026', divisionId: 10, status: 'SUBMITTED', submittedAt: '2026-01-30T12:00:00Z' },
]

test('aggregates chart data within selected wing and division scope', () => {
  const result = buildDashboardAnalytics({
    employees,
    divisions,
    batches,
    periods,
    filters: { wingId: '1', divisionId: '10' },
    now: new Date('2026-02-10T12:00:00Z'),
  })

  assert.equal(result.employeeCount, 2)
  assert.equal(result.activeEmployees, 1)
  assert.deepEqual(result.employeesByWing, [['Operations', 2]])
  assert.deepEqual(
    result.monthlyPayroll.map(({ label, net, gross, deductions }) => [label, net, gross, deductions]),
    [['Jan 2026', 100, 120, 20]],
  )
  assert.deepEqual(result.batchStatuses, [['FINALIZED', 1], ['DRAFT', 1], ['SUBMITTED', 1]])
  assert.deepEqual(result.approvalAges.map(({ label, count }) => [label, count]), [
    ['0-2 days', 0],
    ['3-5 days', 0],
    ['6-10 days', 0],
    ['>10 days', 1],
  ])
})

test('date filters include overlapping payroll periods and exclude unapproved salary records', () => {
  const result = buildDashboardAnalytics({
    employees,
    divisions,
    batches,
    periods,
    filters: { fromDate: '2026-02-10', toDate: '2026-02-12' },
    now: new Date('2026-02-10T12:00:00Z'),
  })

  assert.deepEqual(result.monthlyPayroll.map(({ label, net }) => [label, net]), [['Feb 2026', 50]])
  assert.deepEqual(result.batchStatuses, [['DRAFT', 1], ['APPROVED', 1], ['SUBMITTED', 1]])
})

test('wing scope and submission timestamps populate all approval-age buckets', () => {
  const result = buildDashboardAnalytics({
    employees,
    divisions,
    batches: [
      ...batches.filter((batch) => batch.divisionId === 20),
      { periodId: 2, divisionId: 20, status: 'SUBMITTED', submittedAt: '2026-02-10T12:00:00Z' },
      { periodId: 2, divisionId: 20, status: 'SUBMITTED', submittedAt: '2026-02-07T12:00:00Z' },
      { periodId: 2, divisionId: 20, status: 'SUBMITTED', submittedAt: '2026-02-04T12:00:00Z' },
      { periodId: 2, divisionId: 20, status: 'SUBMITTED', submittedAt: '2026-01-30T12:00:00Z' },
    ],
    periods,
    filters: { wingId: '2' },
    now: new Date('2026-02-10T12:00:00Z'),
  })

  assert.equal(result.employeeCount, 1)
  assert.deepEqual(result.employeesByWing, [['Finance', 1]])
  assert.deepEqual(result.approvalAges.map(({ label, count }) => [label, count]), [
    ['0-2 days', 2],
    ['3-5 days', 1],
    ['6-10 days', 1],
    ['>10 days', 1],
  ])
})

test('filter options are built from the caller-visible records', () => {
  assert.deepEqual(getDashboardFilterOptions({ employees, divisions, batches }), {
    wings: [{ id: '2', name: 'Finance' }, { id: '1', name: 'Operations' }],
    divisions: [
      { id: '20', name: 'Payroll', wingId: '2' },
      { id: '10', name: 'Water', wingId: '1' },
    ],
  })
})
