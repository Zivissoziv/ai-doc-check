/* 布局诊断：打开页面 -> 切换简报模式 -> 搜索 -> 点开工单 -> 测量各容器宽度 */
const path = require('path');
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });

  const m = async (label) => {
    const r = await page.evaluate(() => {
      const w = el => el ? { sw: el.scrollWidth, cw: el.clientWidth, bw: el.getBoundingClientRect().width } : null;
      return {
        doc: { sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth },
        body: w(document.body),
        centerArea: w(document.querySelector('main .flex-1.overflow-hidden')),
        viewTicket: w(document.getElementById('view-ticket')),
        ticketContent: w(document.getElementById('ticketContent')),
        sheet: w(document.querySelector('.ticket-audit-sheet')),
        jsonScroll: w(document.querySelector('.ticket-json-scroll')),
        jsonTree: w(document.querySelector('.ticket-json-tree')),
      };
    });
    console.log('=== ' + label + ' ===');
    console.log(JSON.stringify(r));
  };

  await m('初始(document模式)');

  // 切到变更简报
  await page.click('#mode-brief');
  await page.waitForTimeout(500);
  await m('切换简报模式后');

  // 检查 currentRuleGroup 状态（问题1）
  const groupState = await page.evaluate(() => ({
    currentRuleGroup: app.currentRuleGroup,
    selectValue: document.getElementById('ruleGroupSelect')?.value,
    selectOptions: [...(document.getElementById('ruleGroupSelect')?.options || [])].map(o => o.value),
    saved: localStorage.getItem('smartdoc_current_group_brief'),
  }));
  console.log('=== 规则组状态 ===');
  console.log(JSON.stringify(groupState));

  // 搜索工单
  await page.click('#orderSearchBtn');
  await page.waitForTimeout(1500);

  // 点开第一个工单
  const firstOrder = await page.$('#structureTree button');
  if (firstOrder) {
    await firstOrder.click();
    await page.waitForTimeout(800);
    await m('点开工单后');
    await page.screenshot({ path: 'scripts/layout_ticket.png' });
  } else {
    console.log('NO ORDER BUTTON FOUND');
  }

  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
