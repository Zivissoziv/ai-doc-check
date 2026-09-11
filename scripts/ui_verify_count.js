/* 验证左下角工单总条数显示 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });

  // 文档模式：计数应隐藏
  const before = await page.evaluate(() => document.getElementById('orderTotalCount')?.classList.contains('hidden'));
  console.log('文档模式计数隐藏:', before);

  // 切简报 -> 搜索
  await page.click('#mode-brief');
  await page.waitForTimeout(500);
  await page.click('#orderSearchBtn');
  await page.waitForTimeout(1500);

  const after = await page.evaluate(() => ({
    hidden: document.getElementById('orderTotalCount')?.classList.contains('hidden'),
    text: document.getElementById('orderTotalCount')?.textContent.trim(),
    visible: !!document.getElementById('orderTotalCount')?.offsetParent,
  }));
  console.log('搜索后计数:', JSON.stringify(after));

  // 切回文档模式应隐藏
  await page.click('#mode-document');
  await page.waitForTimeout(400);
  const back = await page.evaluate(() => document.getElementById('orderTotalCount')?.classList.contains('hidden'));
  console.log('切回文档模式后隐藏:', back);

  await page.click('#mode-brief');
  await page.waitForTimeout(400);
  const reEnter = await page.evaluate(() => document.getElementById('orderTotalCount')?.textContent.trim());
  console.log('重新进入简报模式显示:', reEnter);

  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
