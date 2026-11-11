import { test, expect } from '@playwright/test';

const LOGGED_IN_USER = {
  userId: 1,
  username: 'org_admin',
  authorities: ['salary.manage', 'payroll.prepare'],
  activeAssignmentId: 1,
  assignments: [{ assignmentId: 1, roleName: 'Organization Administrator', scopeLabel: 'Organization-wide' }],
  activeAssignment: { assignmentId: 1, roleName: 'Organization Administrator', scopeLabel: 'Organization-wide' },
};

test('capture JS errors when navigating to imports', async ({ page }) => {
  const jsErrors = [];
  const consoleMessages = [];

  page.on('console', msg => {
    consoleMessages.push(`[${msg.type()}] ${msg.text()}`);
  });
  page.on('pageerror', err => {
    jsErrors.push(err.message);
  });

  await page.route('/api/auth/me', async (route) => {
    await route.fulfill({ status: 401, body: 'Unauthorized' });
  });
  await page.route('/api/auth/login', async (route) => {
    await route.fulfill({ json: LOGGED_IN_USER });
  });
  await page.route('/api/auth/active-assignment', async (route) => {
    await route.fulfill({ json: LOGGED_IN_USER });
  });
  await page.route('/api/import/jobs', async (route) => {
    await route.fulfill({ json: [] });
  });
  await page.route('/api/import/jobs/**', async (route) => {
    await route.fulfill({ json: {} });
  });

  await page.goto('/');
  await expect(page.getByText('WELCOME BACK')).toBeVisible();
  await page.getByRole('button', { name: /sysadmin/ }).click();
  await page.getByRole('button', { name: /Sign in securely/ }).click();
  await page.waitForTimeout(2000);

  await page.goto('/#/imports');
  await page.waitForTimeout(3000);

  console.log('JS ERRORS:', jsErrors);
  console.log('CONSOLE MESSAGES:', consoleMessages.slice(0, 20));
  console.log('URL:', page.url());
  console.log('BODY TEXT:', (await page.locator('body').innerText()).substring(0, 500));
});
