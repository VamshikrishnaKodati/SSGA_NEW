import { test, expect } from '@playwright/test'

// These specs mock the API and share one dev server; running them serially with a
// longer timeout avoids cold-compile races when many workers hit Vite at once.
test.describe.configure({ mode: 'serial' })
test.setTimeout(30000)

const USER = {
  userId: 1,
  username: 'org_admin',
  authorities: [
    'ROLE_ORG_ADMIN',
    'employee.view',
    'division.view',
    'payroll.view',
    'report.view',
  ],
  activeAssignmentId: 1,
  assignments: [{ assignmentId: 1, roleName: 'Organization Administrator', scopeLabel: 'Organization-wide' }],
  activeAssignment: {
    assignmentId: 1,
    roleCode: 'ROLE_ORG_ADMIN',
    roleName: 'Organization Administrator',
    scopeLabel: 'Organization-wide',
  },
}

const SYSTEM_ADMIN = {
  ...USER,
  username: 'sysadmin',
  authorities: ['ROLE_SYSTEM_ADMIN'],
  activeAssignment: {
    ...USER.activeAssignment,
    roleCode: 'ROLE_SYSTEM_ADMIN',
    roleName: 'System Administrator',
  },
  assignments: [{
    ...USER.assignments[0],
    roleName: 'System Administrator',
  }],
}

const API_FIXTURES = {
  '/api/auth/me': USER,
  '/api/employees': [
    { id: 1, divisionId: 10, divisionName: 'Water', wingId: 1, wingName: 'Operations', status: 'ACTIVE' },
    { id: 2, divisionId: 10, divisionName: 'Water', wingId: 1, wingName: 'Operations', status: 'INACTIVE' },
    { id: 3, divisionId: 20, divisionName: 'Payroll', wingId: 2, wingName: 'Finance', status: 'ACTIVE' },
  ],
  '/api/divisions/my-scope': [
    { id: 10, wingId: 1, wingName: 'Operations', name: 'Water' },
    { id: 20, wingId: 2, wingName: 'Finance', name: 'Payroll' },
  ],
  '/api/payroll/periods': [
    { id: 1, year: 2026, month: 1, label: 'Jan 2026', startDate: '2026-01-01', endDate: '2026-01-31', status: 'CLOSED' },
    { id: 2, year: 2026, month: 2, label: 'Feb 2026', startDate: '2026-02-01', endDate: '2026-02-28', status: 'CLOSED' },
  ],
  '/api/payroll/batches': [
    { batchId: 1, periodId: 1, periodLabel: 'Jan 2026', divisionId: 10, divisionName: 'Water', status: 'FINALIZED', employeeCount: 2, grossTotal: 120, deductionTotal: 20, netTotal: 100 },
    { batchId: 2, periodId: 2, periodLabel: 'Feb 2026', divisionId: 10, divisionName: 'Water', status: 'DRAFT', employeeCount: 2, grossTotal: 220, deductionTotal: 20, netTotal: 200 },
    { batchId: 3, periodId: 2, periodLabel: 'Feb 2026', divisionId: 20, divisionName: 'Payroll', status: 'APPROVED', employeeCount: 1, grossTotal: 60, deductionTotal: 10, netTotal: 50 },
    { batchId: 4, periodId: 2, periodLabel: 'Feb 2026', divisionId: 20, divisionName: 'Payroll', status: 'SUBMITTED', submittedAt: new Date().toISOString(), employeeCount: 1 },
  ],
  '/api/reports/division-summary': [],
}

async function mockDashboardAPIs(page, user) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const response = path === '/api/auth/me' ? user : API_FIXTURES[path]
    await route.fulfill(response === undefined
      ? { status: 404, body: `No dashboard fixture for ${path}` }
      : { json: response })
  })
}

test('Organization Admin sees all six scoped charts and filters them', async ({ page }) => {
  await mockDashboardAPIs(page, USER)

  await page.goto('/#/dashboard')

  await expect(page.getByRole('heading', { name: 'Organization dashboard' })).toBeVisible({ timeout: 20000 })
  for (const title of [
    'Employee count by Wing',
    'Active vs inactive employees',
    'Monthly net salary trend',
    'Earnings vs deductions',
    'Payroll batch status',
    'Pending approvals by age',
  ]) {
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
  }

  await page.getByLabel('From date').fill('2026-02-01')
  await page.getByLabel('To date').fill('2026-02-28')
  await page.getByRole('combobox', { name: 'Wing' }).selectOption('2')
  await page.getByRole('combobox', { name: 'Division' }).selectOption('20')

  await expect(page.getByRole('img', { name: 'Employee count by Wing' })).toContainText('Finance')
  await expect(page.getByRole('img', { name: 'Monthly net salary trend' })).toContainText('Feb 2026')
  await expect(page.getByRole('img', { name: 'Monthly net salary trend' })).not.toContainText('Jan 2026')
  await expect(page.getByRole('img', { name: 'Monthly earnings and deductions stacked bar chart' })).toContainText('Feb 2026')
  await expect(page.getByRole('img', { name: 'Pending approvals by age' })).toContainText('0-2 days')

  await page.getByRole('button', { name: 'Reset filters' }).click()
  await expect(page.getByRole('img', { name: 'Employee count by Wing' })).toContainText('Operations')
  await expect(page.getByRole('img', { name: 'Employee count by Wing' })).toContainText('Finance')
})

test('System Administrator sees the analytics section and all six charts', async ({ page }) => {
  await mockDashboardAPIs(page, SYSTEM_ADMIN)

  await page.goto('/#/dashboard')

  await expect(page.getByRole('heading', { name: 'Administrator dashboard' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Organization analytics' })).toBeVisible()
  for (const title of [
    'Employee count by Wing',
    'Active vs inactive employees',
    'Monthly net salary trend',
    'Earnings vs deductions',
    'Payroll batch status',
    'Pending approvals by age',
  ]) {
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
  }
})
