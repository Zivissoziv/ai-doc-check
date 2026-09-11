/* 验证：快捷设置为按钮 + 下拉，选后关闭、不自动搜索 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(600);

  let searchCount = 0;
  page.on('request', r => { if (r.url().includes('/api/order/search')) searchCount++; });

  const dd = () => page.evaluate(() => document.getElementById('quickRangesDropdown').classList.contains('hidden'));

  console.log('初始下拉隐藏:', await dd());
  await page.click('#quickRangeBtn');
  await page.waitForTimeout(300);
  console.log('点击按钮后下拉展开:', !(await dd()));

  // 点击一个选项
  await page.click('#quickRangesDropdown .quick-range-row >> nth=4'); // 本周五8时~下周一8时
  await page.waitForTimeout(300);
  const after = await page.evaluate(() => ({
    start: document.getElementById('orderSearchStart').value,
    end: document.getElementById('orderSearchEnd').value,
    hint: document.getElementById('orderSearchResult').textContent,
    dropdownHidden: document.getElementById('quickRangesDropdown').classList.contains('hidden'),
  }));
  console.log('选择后:', JSON.stringify(after));
  console.log('搜索请求数(应为0):', searchCount);

  // 再点按钮可重新打开
  await page.click('#quickRangeBtn');
  await page.waitForTimeout(200);
  console.log('再次点击可重新展开:', !(await dd()));

  await page.screenshot({ path: 'scripts/quick_dropdown.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
