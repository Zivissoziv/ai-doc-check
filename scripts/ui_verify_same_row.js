/* 验证：搜索工单与快捷设置同行（一左一右），下拉功能正常 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(600);

  const layout = await page.evaluate(() => {
    const s = document.getElementById('orderSearchBtn').getBoundingClientRect();
    const q = document.getElementById('quickRangeBtn').getBoundingClientRect();
    const aside = document.querySelector('aside').getBoundingClientRect();
    return {
      sameRow: Math.abs(s.top - q.top) < 3,
      searchLeft: s.left < q.left,
      quickRight: Math.abs(q.right - aside.right + 16) < 20, // 贴右缘（含 padding 16px）
      searchW: Math.round(s.width),
      quickW: Math.round(q.width),
    };
  });
  console.log('布局:', JSON.stringify(layout));

  await page.click('#quickRangeBtn');
  await page.waitForTimeout(300);
  const dd = await page.evaluate(() => {
    const d = document.getElementById('quickRangesDropdown');
    const qr = d.getBoundingClientRect();
    const qb = document.getElementById('quickRangeBtn').getBoundingClientRect();
    return { open: !d.classList.contains('hidden'), alignedRight: Math.abs(qr.right - qb.right) < 3, rows: d.querySelectorAll('.quick-range-row').length };
  });
  console.log('下拉:', JSON.stringify(dd));

  await page.click('#quickRangesDropdown .quick-range-row >> nth=0');
  await page.waitForTimeout(200);
  await page.click('#orderSearchBtn');
  await page.waitForTimeout(1200);
  const res = await page.evaluate(() => ({
    hint: document.getElementById('orderSearchResult').textContent,
    total: document.getElementById('orderTotalCount').textContent.trim(),
  }));
  console.log('选预设后搜索:', JSON.stringify(res));
  await page.screenshot({ path: 'scripts/same_row.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
