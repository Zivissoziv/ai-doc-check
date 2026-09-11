/* 验证：简报模式下规则列表正常加载（修复 getRulesByGroupId 后） */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(1200);

  const r = await page.evaluate(() => ({
    currentRuleGroup: app.currentRuleGroup,
    rulesLoaded: (app.rules || []).map(x => ({ name: x.name, enabled: x.enabled, groupType: x.groupType })),
    renderedRuleCards: document.querySelectorAll('#rulesList .rule-item, #rulesList [class*="rule"]').length,
    rulesListText: document.getElementById('rulesList')?.textContent.replace(/\s+/g, ' ').slice(0, 120),
  }));
  console.log(JSON.stringify(r, null, 1));
  await page.screenshot({ path: 'scripts/rules_brief.png' });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
