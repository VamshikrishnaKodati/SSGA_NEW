import { chromium } from 'playwright';

const out = 'C:/Users/vamsh/AppData/Local/Temp/opencode';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 });

await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' });
await page.fill('#username', 'sysadmin');
await page.fill('#password', 'Password@123');
await page.click('.login-submit');
await page.waitForTimeout(2500);

const routes = ['dashboard', 'payroll', 'statements', 'approvals', 'imports', 'employees', 'profile', 'reports', 'organization', 'salary-components', 'access', 'audit', 'notifications', 'system'];

for (const route of routes) {
  await page.goto(`http://localhost:5173/#/${route}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);
  await page.screenshot({ path: `${out}/audit-${route}.png`, fullPage: true });
  console.log(route, await page.locator('.page-title').first().textContent().catch(() => '?'));
}

await browser.close();
