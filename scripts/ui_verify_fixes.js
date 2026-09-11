/* 修复验证：1) 规则组状态与下拉一致 2) 中间列不再被撑大 3) 触发条件在简报模式隐藏 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(600);

  const g = await page.evaluate(() => ({
    currentRuleGroup: app.currentRuleGroup,
    selectValue: document.getElementById('ruleGroupSelect')?.value,
    saved: localStorage.getItem('smartdoc_current_group_brief'),
    triggerHidden: document.getElementById('ruleTriggerField')?.classList.contains('hidden'),
    severityHidden: document.getElementById('ruleSeverityField')?.classList.contains('hidden'),
  }));
  console.log('问题1+3 状态:', JSON.stringify(g));

  await page.click('#orderSearchBtn');
  await page.waitForTimeout(1500);
  const firstOrder = await page.$('#structureTree button');
  await firstOrder.click();
  await page.waitForTimeout(800);

  const r = await page.evaluate(() => {
    const w = sel => { const el = document.querySelector(sel); return el ? Math.round(el.getBoundingClientRect().width) : null; };
    return {
      docSW: document.documentElement.scrollWidth,
      main: w('main'),
      centerArea: w('main .flex-1.overflow-hidden'),
      viewTicket: w('#view-ticket'),
      ticketContent: w('#ticketContent'),
      sheet: w('.ticket-audit-sheet'),
      viewTicketHScroll: (() => { const el = document.getElementById('view-ticket'); return el.scrollWidth - el.clientWidth; })(),
      jsonInternalScroll: (() => { const el = document.querySelector('.ticket-json-scroll'); return el.scrollWidth - el.clientWidth; })(),
    };
  });
  console.log('问题2 布局:', JSON.stringify(r));

  // 打开简报风格弹窗验证不再报"请先选择规则组"
  await page.evaluate(() => { app.showBriefStyleModal(); });
  await page.waitForTimeout(300);
  const modal = await page.evaluate(() => ({
    modalVisible: !document.getElementById('briefStyleModal')?.classList.contains('hidden'),
    styleValue: document.getElementById('briefStyleInput')?.value.slice(0, 30),
  }));
  console.log('问题1 弹窗:', JSON.stringify(modal));

  await page.screenshot({ path: 'scripts/layout_fixed.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
