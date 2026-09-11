/* 验证：搜索后自动展示第一条工单 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(500);
  await page.click('#orderSearchBtn');
  await page.waitForTimeout(1500);

  const r = await page.evaluate(() => ({
    orderId: app.orderId,
    ticketTabActive: !document.getElementById('view-ticket')?.classList.contains('hidden'),
    ticketContentShown: (document.querySelector('.ticket-audit-sheet h2')?.textContent || '').trim(),
    firstItemSelected: document.querySelector('#structureTree button')?.className.includes('bg-blue-50'),
    wordCount: document.getElementById('wordCount')?.textContent,
    status: document.getElementById('statusText')?.textContent,
  }));
  console.log(JSON.stringify(r, null, 1));
  await page.screenshot({ path: 'scripts/layout_autoselect.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
