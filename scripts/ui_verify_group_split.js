/* 验证：文档审核下拉不含简报组，简报下拉只含简报组 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);

  const opts = () => page.evaluate(() => [...document.getElementById('ruleGroupSelect').options].map(o => o.value));

  const docGroups = await opts();
  console.log('文档审核模式下拉:', JSON.stringify(docGroups));

  await page.click('#mode-brief');
  await page.waitForTimeout(1000);
  const briefGroups = await opts();
  console.log('变更简报模式下拉:', JSON.stringify(briefGroups));

  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
