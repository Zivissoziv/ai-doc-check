/* 精确诊断：点开工单后中间列为何变宽 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(400);
  await page.click('#orderSearchBtn');
  await page.waitForTimeout(1500);
  const firstOrder = await page.$('#structureTree button');
  await firstOrder.click();
  await page.waitForTimeout(800);

  const r = await page.evaluate(() => {
    const info = el => {
      if (!el) return null;
      const cs = getComputedStyle(el);
      const r2 = el.getBoundingClientRect();
      return { cls: el.className.slice ? el.className.slice(0, 80) : '', w: Math.round(r2.width), minW: cs.minWidth, flex: cs.flex, overflowX: cs.overflowX };
    };
    const main = document.querySelector('main');
    return {
      docSW: document.documentElement.scrollWidth,
      main: info(main),
      mainChildren: [...(main ? main.children : [])].map(info),
      centerArea: info(document.querySelector('#view-ticket')?.parentElement),
      sheetParent: info(document.getElementById('ticketContent')),
      wideRows: [...document.querySelectorAll('.ticket-json-row')].slice(0, 200)
        .filter(el => el.scrollWidth > 900).length,
      maxRowScrollW: Math.max(...[...document.querySelectorAll('.ticket-json-row')].map(el => el.scrollWidth)),
      jsonScrollSW: document.querySelector('.ticket-json-scroll')?.scrollWidth,
      jsonScrollCW: document.querySelector('.ticket-json-scroll')?.clientWidth,
    };
  });
  console.log(JSON.stringify(r, null, 1));
  await page.screenshot({ path: 'scripts/layout_ticket.png', fullPage: false });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
