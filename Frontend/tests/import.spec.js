import { test, expect } from '@playwright/test';

// Mock backend responses for import API
const MOCK_JOB = {
  jobId: 101,
  status: 'UPLOADED',
  fileName: 'salary_january.xlsx',
  sourceType: 'SALARY_VALUES_WIDE',
  uploadedBy: 1,
};

const MOCK_ANALYSIS = {
  jobId: 101,
  uploadedBy: 1,
  status: 'DETECTED',
  sheets: [
    {
      sheetIndex: 0,
      sheetName: 'SALARY_VALUES_WIDE',
      nonEmptyRows: 11,
      dataRows: 10,
      headerRowNumber: 1,
      columnCount: 6,
      suggestedType: 'EMPLOYEE_MASTER_AND_COMPONENTS',
      headers: ['EMPLOYEE_CODE', 'EMPLOYEE_NAME', 'DIVISION', 'BASIC_PAY', 'DA_PAY', 'HRA_PAY'],
      note: 'Suggested classification only.',
    },
  ],
};

const MOCK_PROGRESS = {
  jobId: 101,
  status: 'DETECTED',
  fileName: 'salary_january.xlsx',
  uploadedBy: 1,
  totalRecords: 10,
  processedRecords: 0,
  importedRows: 0,
  pendingRows: 0,
  failedRows: 0,
  skippedRows: 0,
  progressAvailable: false,
  percentage: 0,
  events: [],
  startedAt: null,
};

const LOGGED_IN_USER = {
  userId: 1,
  username: 'org_admin',
  authorities: ['salary.manage', 'payroll.prepare'],
  activeAssignmentId: 1,
  assignments: [{ assignmentId: 1, roleName: 'Organization Administrator', scopeLabel: 'Organization-wide' }],
  activeAssignment: { assignmentId: 1, roleName: 'Organization Administrator', scopeLabel: 'Organization-wide' },
};

async function mockAuthAndImportAPIs(page) {
  // Return logged-in user directly (skip login page)
  await page.route('/api/auth/me', async (route) => {
    await route.fulfill({ json: LOGGED_IN_USER });
  });

  // Import endpoints (register catch-all first so specific routes below win)
  await page.route('/api/import/jobs/**', async (route) => {
    await route.fulfill({ json: MOCK_JOB });
  });
  await page.route('/api/import/jobs/upload', async (route) => {
    await route.fulfill({ json: MOCK_JOB });
  });
  await page.route('/api/import/jobs/101/analyze', async (route) => {
    await route.fulfill({ json: MOCK_ANALYSIS });
  });
  await page.route('/api/import/jobs/101/progress', async (route) => {
    await route.fulfill({ json: MOCK_PROGRESS });
  });
  await page.route('/api/import/jobs', async (route) => {
    await route.fulfill({ json: [MOCK_JOB] });
  });
  await page.route('/api/import/jobs/101/sheets/0/mapping', async (route) => {
    await route.fulfill({ json: { confirmedType: 'EMPLOYEE_MASTER_AND_COMPONENTS', columnMapping: {} } });
  });
  await page.route('/api/import/jobs/101/sheets/0/validate', async (route) => {
    await route.fulfill({ json: { validRows: 10, errorRows: 0, warningRows: 0, issues: [] } });
  });
  await page.route('/api/import/jobs/101/sheets/0/automap', async (route) => {
    await route.fulfill({ json: { importType: 'EMPLOYEE_MASTER_AND_COMPONENTS', fields: [], components: [], structureSignature: 'sig-1' } });
  });
  await page.route('/api/import/jobs/101/sheets/0/preview', async (route) => {
    await route.fulfill({ json: { message: 'Preview', columns: [], rows: [] } });
  });
  await page.route('/api/import/jobs/101/sheets/0/issues.csv', async (route) => {
    await route.fulfill({ body: 'row,field,error\n', contentType: 'text/csv' });
  });
  await page.route('/api/import/mapping-profiles', async (route) => {
    await route.fulfill({ json: [] });
  });
  await page.route('/api/import/mapping-profiles/**', async (route) => {
    await route.fulfill({ json: [] });
  });
  await page.route('/api/import/jobs/101/submit', async (route) => {
    await route.fulfill({ json: { jobId: 101, status: 'AWAITING_APPROVAL' } });
  });
  await page.route('/api/import/jobs/101/approve', async (route) => {
    await route.fulfill({ json: { jobId: 101, status: 'APPROVED' } });
  });
  await page.route('/api/import/jobs/101/finalize', async (route) => {
    await route.fulfill({ json: { jobId: 101, status: 'IMPORTED', importedRows: 10 } });
  });
  await page.route('/api/import/jobs/101/reject', async (route) => {
    await route.fulfill({ json: { jobId: 101, status: 'REJECTED' } });
  });
  await page.route('/api/import/jobs/101/assignments', async (route) => {
    await route.fulfill({ json: { id: 1, userId: 1, assignmentRole: 'RESOLVER', divisionId: 1, active: true, assignedAt: new Date().toISOString() } });
  });
}

test.describe('Login flow', () => {
  test('login page loads and shows demo accounts', async ({ page }) => {
    // No session - returns 401 so login page shows
    await page.route('/api/auth/me', async (route) => {
      await route.fulfill({ status: 401, body: 'Unauthorized' });
    });
    await page.route('/api/auth/login', async (route) => {
      await route.fulfill({ json: LOGGED_IN_USER });
    });
    await page.route('/api/auth/active-assignment', async (route) => {
      await route.fulfill({ json: LOGGED_IN_USER });
    });

    await page.goto('/');
    await expect(page.getByText('WELCOME BACK')).toBeVisible();
    await expect(page.getByText('Sign in to SSGA')).toBeVisible();
    await expect(page.getByText('sysadmin')).toBeVisible();
    await expect(page.getByText('Password@123')).toBeVisible();
  });
});

async function openJob(page) {
  await page.getByRole('row', { name: /101/ }).getByRole('button', { name: 'Open' }).click();
  await expect(page.getByText('Step 2 · Map sheet columns')).toBeVisible();
}

async function mapSheet(page) {
  await page.getByLabel('Sheet type').selectOption('EMPLOYEE_MASTER_AND_COMPONENTS');
  await page.getByLabel('Layout').selectOption('LONG');
  await page.getByLabel('Map Employee code', { exact: true }).selectOption('EMPLOYEE_CODE');
  await page.getByLabel('Map Employee name', { exact: true }).selectOption('EMPLOYEE_NAME');
  await page.getByLabel('Map Division', { exact: true }).selectOption('DIVISION');
  await page.getByLabel('Map Component code', { exact: true }).selectOption('BASIC_PAY');
  await page.getByLabel('Map Amount', { exact: true }).selectOption('DA_PAY');
  await page.getByLabel('Map Effective from', { exact: true }).selectOption('HRA_PAY');
  await page.getByRole('button', { name: 'Save all mappings' }).click();
  await expect(page.getByText('Step 3 · Validate mapped data')).toBeVisible();
}

async function validateAll(page) {
  await page.getByRole('button', { name: /Validate all sheets/ }).click();
  await expect(page.getByText('All importable sheets passed validation.')).toBeVisible();
}

test.describe('Import workflow', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuthAndImportAPIs(page);
    await page.goto('/#/imports');
    await page.waitForTimeout(1000); // Let the page hydrate
  });

  test('import page loads with stepper and upload panel', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Data import' })).toBeVisible();
    await expect(page.getByText('Step 1 · Upload a workbook')).toBeVisible();
    await expect(page.getByRole('button', { name: /1 Upload/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /2 Map/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /3 Validate/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /4 Review/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /5 Import/ })).toBeVisible();
  });

  test('upload step shows dropzone and recent jobs table', async ({ page }) => {
    await expect(page.getByText('Step 1 · Upload a workbook')).toBeVisible();
    await expect(page.getByText('Drag and drop a workbook, or choose a file')).toBeVisible();
    await expect(page.getByText('Recent jobs')).toBeVisible();
  });

  test('stepper highlights current step', async ({ page }) => {
    await expect(page.getByRole('button', { name: /1 Upload/ })).toHaveClass(/active/);
  });

  test('opening a job enables the map step and shows sheet tabs', async ({ page }) => {
    await openJob(page);
    await expect(page.getByRole('tab', { name: 'SALARY_VALUES_WIDE' })).toBeVisible();
    await expect(page.getByRole('button', { name: /2 Map/ })).toBeEnabled();
  });

  test('sheet type dropdown shows import types', async ({ page }) => {
    await openJob(page);
    const sheetType = page.getByLabel('Sheet type');
    await expect(sheetType).toContainText('Employee master + salary components');
    await expect(sheetType).toContainText('Salary entitlements');
    await expect(sheetType).toContainText('Reference only (no import)');
  });

  test('auto-map button exists on map step', async ({ page }) => {
    await openJob(page);
    await expect(page.getByRole('button', { name: /Auto-map/ })).toBeVisible();
  });

  test('validate step shows validation button', async ({ page }) => {
    await openJob(page);
    await mapSheet(page);
    await expect(page.getByRole('button', { name: /Validate all sheets/ })).toBeVisible();
  });

  test('review step shows submit button', async ({ page }) => {
    await openJob(page);
    await mapSheet(page);
    await validateAll(page);
    await page.getByRole('button', { name: /4 Review/ }).click();
    await expect(page.getByText('Step 4 · Review & assign')).toBeVisible();
    await expect(page.getByRole('button', { name: /Submit for approval/ })).toBeVisible();
  });

  test('import step shows approve/reject/finalize buttons', async ({ page }) => {
    await openJob(page);
    await mapSheet(page);
    await validateAll(page);
    await page.getByRole('button', { name: /4 Review/ }).click();
    await page.getByRole('button', { name: /Submit for approval/ }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Submit for approval' }).click();
    await expect(page.getByText('Step 5 · Submit, approve & import')).toBeVisible();
    await expect(page.getByRole('button', { name: /Approve/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Reject/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Import approved data/ })).toBeVisible();
  });

  test('open existing job by ID form exists', async ({ page }) => {
    await expect(page.getByLabel('Open an existing job by ID')).toBeVisible();
    await expect(page.getByPlaceholder('Job ID')).toBeVisible();
  });

  test('accessibility - step buttons have aria-current', async ({ page }) => {
    await expect(page.getByRole('button', { name: /1 Upload/ })).toHaveAttribute('aria-current', 'step');
  });

  test('accessibility - tabs have role and aria-selected', async ({ page }) => {
    await openJob(page);
    await expect(page.getByRole('tab', { name: 'SALARY_VALUES_WIDE' })).toHaveAttribute('aria-selected', 'true');
  });

  test('accessibility - required field badges visible', async ({ page }) => {
    await openJob(page);
    await page.getByLabel('Sheet type').selectOption('EMPLOYEE_MASTER_AND_COMPONENTS');
    await expect(page.getByText('Required').first()).toBeVisible();
  });
});
