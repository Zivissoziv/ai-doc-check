/* 验证：历史简报 tab 列表 + 点击跳转 AI简报 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(800);

  const tabState = await page.evaluate(() => ({
    tabVisible: !document.getElementById('tab-briefHistory')?.classList.contains('hidden'),
    viewExists: !!document.getElementById('view-briefHistory'),
  }));
  console.log('tab 状态:', JSON.stringify(tabState));

  // 点击历史简报 tab
  await page.click('#tab-briefHistory');
  await page.waitForTimeout(1000);
  const listState = await page.evaluate(() => ({
    viewShown: !document.getElementById('view-briefHistory')?.classList.contains('hidden'),
    tabActive: document.getElementById('tab-briefHistory')?.className.includes('bg-gray-100'),
    recordCards: document.querySelectorAll('#briefHistoryList button').length,
    headerText: document.querySelector('#briefHistoryList h3')?.textContent.replace(/\s+/g, ' ').trim(),
  }));
  console.log('列表状态:', JSON.stringify(listState));

  // 点击第一条记录 -> 跳转 AI简报
  await page.click('#briefHistoryList button >> nth=0');
  await page.waitForTimeout(600);
  const jump = await page.evaluate(() => ({
    auditViewShown: !document.getElementById('view-audit')?.classList.contains('hidden'),
    historyViewHidden: document.getElementById('view-briefHistory')?.classList.contains('hidden'),
    briefRendered: (document.querySelector('#auditResults .brief-markdown')?.textContent || '').length,
    status: document.getElementById('statusText')?.textContent.slice(0, 40),
  }));
  console.log('点击记录后:', JSON.stringify(jump));
  await page.screenshot({ path: 'scripts/history_brief.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
