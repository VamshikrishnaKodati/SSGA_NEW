import { chromium } from 'playwright'
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
await page.goto('http://localhost:5173')
await page.fill('input[name="username"], input[autocomplete="username"], input >> nth=0', 'sysadmin')
await page.fill('input[type="password"]', 'Password@123')
await page.click('button[type="submit"]')
await page.waitForTimeout(1500)
await page.goto('http://localhost:5173/#/imports')
await page.waitForTimeout(1500)
const open = page.getByRole('button', { name: 'Open' }).first()
if (await open.count()) {
  await open.click()
  await page.waitForTimeout(2500)
  await page.screenshot({ path: 'C:/Users/vamsh/AppData/Local/Temp/opencode/import-job.png', fullPage: true })
  const steps = await page.locator('.import-step').allTextContents()
  console.log('steps:', JSON.stringify(steps))
  const map = page.locator('.import-step', { hasText: 'Map' }).first()
  if (await map.count() && await map.isEnabled()) {
    await map.click()
    await page.waitForTimeout(2000)
    await page.screenshot({ path: 'C:/Users/vamsh/AppData/Local/Temp/opencode/import-map.png', fullPage: true })
  } else {
    console.log('map step unavailable')
  }
} else {
  console.log('no open button')
}
await browser.close()
