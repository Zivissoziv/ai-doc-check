/* 验证：小时级搜索 + 6 个快捷时段 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(600);

  // 记录搜索请求参数
  const reqs = [];
  page.on('request', r => { if (r.url().includes('/api/order/search')) reqs.push(decodeURIComponent(r.url())); });

  const keys = ['y8t8', 't8t8', 't18t18', 'lf8m8', 'f8m8', 'f18m18'];
  for (const k of keys) {
    await page.click(`#orderSearchArea .quick-range-btn:has-text("") >> nth=0`).catch(() => {});
    break;
  }
  for (const k of keys) {
    await page.evaluate(key => app.applyQuickRange(key), k);
    await page.waitForTimeout(1200);
    const state = await page.evaluate(() => ({
      start: document.getElementById('orderSearchStart').value,
      end: document.getElementById('orderSearchEnd').value,
      hint: document.getElementById('orderSearchResult').textContent,
      total: document.getElementById('orderTotalCount').textContent.trim(),
    }));
    console.log(k + ':', JSON.stringify(state));
  }

  console.log('--- 搜索请求 URL ---');
  reqs.slice(-2).forEach(u => console.log(u));
  await page.screenshot({ path: 'scripts/quick_ranges.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
