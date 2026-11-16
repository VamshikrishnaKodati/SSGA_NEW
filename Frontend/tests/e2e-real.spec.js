import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

const PASSWORD = process.env.E2E_PASSWORD || 'Password@123'
const FIXTURE = path.join(process.cwd(), 'tests', 'fixtures', 'e2e_employee_circle.xlsx')

async function login(page, username) {
  await page.goto('/')
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('aside.shell-sidebar')).toBeVisible({ timeout: 20000 })
  await expect(page).toHaveURL(/#\/dashboard/)
}

function nav(page) {
  return page.getByRole('navigation', { name: 'Application modules' })
}

test('system administrator sees every module', async ({ page }) => {
  await login(page, 'sysadmin')
  const menu = nav(page)
  for (const label of [
    'Dashboard',
    'Payroll processing',
    'Salary statements',
    'Approval inbox',
    'Data import',
    'Employee master',
    'My profile',
    'Reports',
    'Organization master',
    'Salary components',
    'User management',
    'Audit trail',
    'Notifications',
    'System monitor',
  ]) {
    await expect(menu.getByRole('button', { name: label, exact: true })).toBeVisible()
  }
})

test('payroll operator only sees their responsible modules', async ({ page }) => {
  await login(page, 'payroll_off1')
  const menu = nav(page)

  for (const label of ['Dashboard', 'Payroll processing', 'Data import', 'Employee master', 'My profile']) {
    await expect(menu.getByRole('button', { name: label, exact: true })).toBeVisible()
  }
  for (const label of [
    'Salary statements',
    'Approval inbox',
    'Reports',
    'Organization master',
    'Salary components',
    'User management',
    'Audit trail',
    'System monitor',
  ]) {
    await expect(menu.getByRole('button', { name: label, exact: true })).toHaveCount(0)
  }

  await page.evaluate(() => { window.location.hash = '#/statements' })
  await expect(page).toHaveURL(/#\/dashboard/)
})

test('employee master loads scoped employees', async ({ page }) => {
  await login(page, 'sysadmin')
  await nav(page).getByRole('button', { name: 'Employee master', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Employee master' })).toBeVisible()
  await expect(
    page.getByPlaceholder('Search HRMS ID, employee name, code or designation'),
  ).toBeVisible()
  await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 20000 })
})

test('import validates circle and roman-numeral divisions', async ({ page }) => {
  test.setTimeout(120000)
  await login(page, 'sysadmin')
  await nav(page).getByRole('button', { name: 'Data import', exact: true }).click()

  await page.locator('input[type="file"]').setInputFiles(FIXTURE)
  await expect(page.locator('.import-job-badge')).toContainText('Job #', { timeout: 30000 })
  await expect(page.getByRole('button', { name: 'Save all mappings' })).toBeEnabled({ timeout: 30000 })

  // Auto-map must fill the dropdowns from the real headers.
  await page.getByRole('button', { name: 'Auto-map', exact: true }).click()
  await expect(page.getByLabel('Map Employee code', { exact: true })).toHaveValue('EMP CODE', { timeout: 30000 })
  await expect(page.getByLabel('Map HRMS ID', { exact: true })).toHaveValue('HRMS ID')
  await expect(page.getByLabel('Map Division', { exact: true })).toHaveValue('DIVISION')

  await page.getByLabel('Map Employee code', { exact: true }).selectOption('EMP CODE')
  await page.getByLabel('Map HRMS ID', { exact: true }).selectOption('HRMS ID')
  await page.getByLabel('Map Employee name', { exact: true }).selectOption('EMPLOYEE NAME')
  await page.getByLabel('Map Division', { exact: true }).selectOption('DIVISION')
  await page.getByLabel('Map Designation', { exact: true }).selectOption('DESIGNATION')
  await page.getByLabel('Map Date of joining', { exact: true }).selectOption('DOJ')
  await expect(page.getByText('ready to validate')).toBeVisible()

  await page.getByRole('button', { name: 'Save all mappings' }).click()
  await expect(page.getByRole('heading', { name: 'Validate mapped data' })).toBeVisible({ timeout: 30000 })
  await page.getByRole('button', { name: 'Validate all sheets' }).click()

  await expect(page.getByText('Validated', { exact: true }).first()).toBeVisible({ timeout: 30000 })
  await expect(page.getByText('All importable sheets passed validation.')).toBeVisible()
})

test('payroll batches and approval inbox load real data', async ({ page }) => {
  await login(page, 'sysadmin')
  await nav(page).getByRole('button', { name: 'Payroll processing', exact: true }).click()

  await page.locator('#payroll-period-select').selectOption('10')
  await page.locator('#payroll-division-select').selectOption('25')
  await page.getByRole('button', { name: 'Load batches' }).click()
  await expect(page.getByText('Finalized', { exact: true }).first()).toBeVisible({ timeout: 30000 })
  await expect(page.locator('table tbody tr', { hasText: 'DEWATERING (D25)' }).first()).toBeVisible()

  await nav(page).getByRole('button', { name: 'Approval inbox', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Approval inbox' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Awaiting approval', exact: true })).toBeVisible()
})

test('statements download as PDF and reports render', async ({ page }) => {
  await login(page, 'sysadmin')
  await nav(page).getByRole('button', { name: 'Salary statements', exact: true }).click()

  const row = page.locator('table tbody tr', { hasText: 'STMT-2026-07' }).first()
  await expect(row).toBeVisible({ timeout: 30000 })
  await row.click()
  await expect(page.getByRole('heading', { name: /STMT-2026-07/ })).toBeVisible()

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download PDF' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/\.pdf$/)

  const target = path.join(test.info().outputDir, download.suggestedFilename())
  await download.saveAs(target)
  const bytes = fs.readFileSync(target)
  expect(bytes.length).toBeGreaterThan(1000)
  expect(bytes.subarray(0, 5).toString()).toBe('%PDF-')

  await nav(page).getByRole('button', { name: 'Reports', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Reports' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Division totals' })).toBeVisible()
})

test('login rejects bad credentials and accepts valid ones', async ({ page }) => {
  await page.goto('/')
  await page.locator('#username').fill('sysadmin')
  await page.locator('#password').fill('wrong-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('alert')).toBeVisible({ timeout: 20000 })
  await expect(page).toHaveURL(/#?\/?(\/login)?$/)

  await page.locator('#username').fill('sysadmin')
  await page.locator('#password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('aside.shell-sidebar')).toBeVisible({ timeout: 20000 })
  await expect(page).toHaveURL(/#\/dashboard/)
})

test('approving authority sees the approval inbox with a pending tab', async ({ page }) => {
  await login(page, 'so_finance')
  await nav(page).getByRole('button', { name: 'Approval inbox', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Approval inbox' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Awaiting approval', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Approved', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Import jobs awaiting approval' })).toBeVisible()
})
