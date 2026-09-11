/* 验证：快捷时段仅填充、不自动搜索 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(600);

  let searchCount = 0;
  page.on('request', r => { if (r.url().includes('/api/order/search')) searchCount++; });

  // 点击快捷时段：不应触发搜索
  await page.evaluate(() => app.applyQuickRange('f18m18'));
  await page.waitForTimeout(800);
  const afterQuick = await page.evaluate(() => ({
    start: document.getElementById('orderSearchStart').value,
    end: document.getElementById('orderSearchEnd').value,
    hint: document.getElementById('orderSearchResult').textContent,
    totalVisible: !document.getElementById('orderTotalFooter')?.classList.contains('hidden'),
  }));
  console.log('点击快捷后(未搜索):', JSON.stringify(afterQuick));
  console.log('搜索请求数(应为0):', searchCount);

  // 手动点击搜索
  await page.click('#orderSearchBtn');
  await page.waitForTimeout(1500);
  const afterSearch = await page.evaluate(() => ({
    hint: document.getElementById('orderSearchResult').textContent,
    total: document.getElementById('orderTotalCount').textContent.trim(),
    firstShown: app.orderId,
  }));
  console.log('手动搜索后:', JSON.stringify(afterSearch));
  console.log('搜索请求数(应为1):', searchCount);

  await page.screenshot({ path: 'scripts/quick_settings.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
