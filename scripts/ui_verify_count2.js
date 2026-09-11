/* 验证左侧栏左下角工单总数显示 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(500);
  await page.click('#orderSearchBtn');
  await page.waitForTimeout(1500);

  const r = await page.evaluate(() => {
    const footer = document.getElementById('orderTotalFooter');
    const aside = document.querySelector('aside');
    const fr = footer.getBoundingClientRect();
    const ar = aside.getBoundingClientRect();
    return {
      hidden: footer.classList.contains('hidden'),
      text: footer.textContent.replace(/\s+/g, ' ').trim(),
      // 是否贴在左侧栏左下角
      inAside: fr.left >= ar.left - 1 && fr.left < ar.right,
      atBottom: Math.abs(ar.bottom - fr.bottom) < 2,
      asideW: Math.round(ar.width),
    };
  });
  console.log(JSON.stringify(r));
  await page.screenshot({ path: 'scripts/layout_count.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
