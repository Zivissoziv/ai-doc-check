/* 验证：简报模式下规则弹窗文案切换（总结要点 vs 变量引用） */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(800);
  await page.evaluate(() => { app.addRule(); });
  await page.waitForTimeout(400);

  const brief = await page.evaluate(() => ({
    title: document.getElementById('ruleModalTitle')?.textContent,
    label: document.getElementById('rulePromptLabel')?.textContent,
    placeholder: document.getElementById('rulePrompt')?.placeholder.slice(0, 40),
    namePlaceholder: document.getElementById('ruleName')?.placeholder,
    varHintHidden: document.getElementById('ruleVariableHint')?.classList.contains('hidden'),
    triggerHidden: document.getElementById('ruleTriggerField')?.classList.contains('hidden'),
    severityHidden: document.getElementById('ruleSeverityField')?.classList.contains('hidden'),
  }));
  console.log('简报模式弹窗:', JSON.stringify(brief, null, 1));
  await page.evaluate(() => { app.closeRuleModal(); });

  // 切回文档模式验证还原
  await page.click('#mode-document');
  await page.waitForTimeout(500);
  await page.evaluate(() => { app.addRule(); });
  await page.waitForTimeout(300);
  const doc = await page.evaluate(() => ({
    label: document.getElementById('rulePromptLabel')?.textContent,
    varHintHidden: document.getElementById('ruleVariableHint')?.classList.contains('hidden'),
    triggerHidden: document.getElementById('ruleTriggerField')?.classList.contains('hidden'),
  }));
  console.log('文档模式弹窗:', JSON.stringify(doc));
  await page.evaluate(() => { app.closeRuleModal(); });
  await browser.close();
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
