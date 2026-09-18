/* 验证：规则训练弹窗的「提示词调整」按钮与编辑弹窗（变更简报模式） */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 950 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));

  await page.goto('http://localhost:8081/?pgroup=admin', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(900);

  // 打开「更多操作」→ 规则训练
  await page.click('#groupActionsDropdown ~ *, button[title="更多操作"]');
  await page.waitForTimeout(300);
  await page.click('#ruleTrainingMenuItem');
  await page.waitForTimeout(1200);

  const layout = await page.evaluate(() => {
    const modal = document.getElementById('ruleTrainingModal');
    const btn = document.getElementById('ruleTrainingPromptBtn');
    const gen = document.getElementById('ruleTrainingGenerateBtn');
    const rb = btn?.getBoundingClientRect();
    const gb = gen?.getBoundingClientRect();
    return {
      modalVisible: modal ? getComputedStyle(modal).display !== 'none' : false,
      promptBtnVisible: !!rb && rb.width > 0,
      buttonText: (btn?.textContent || '').replace(/\s+/g, ' ').trim(),
      // 按钮确实在「生成推荐规则」左侧
      leftOfGenerate: !!rb && !!gb && rb.right <= gb.left + 1,
      sameRow: !!rb && !!gb && Math.abs(rb.top - gb.top) < 3,
    };
  });
  console.log('训练弹窗布局:', JSON.stringify(layout));

  // 打开提示词调整
  await page.click('#ruleTrainingPromptBtn');
  await page.waitForTimeout(1500);
  const promptModal = await page.evaluate(() => {
    const m = document.getElementById('ruleTrainingPromptModal');
    const ta = document.getElementById('ruleTrainingPromptContent');
    return {
      visible: m ? getComputedStyle(m).display !== 'none' : false,
      contentLength: ta ? ta.value.length : 0,
      contentHead: ta ? ta.value.slice(0, 30).replace(/\n/g, ' / ') : '',
      status: document.getElementById('ruleTrainingPromptStatus')?.textContent,
      scope: document.getElementById('ruleTrainingPromptScope')?.textContent,
      placeholders: [...document.querySelectorAll('#ruleTrainingPromptPlaceholders span[title]')].map(s => s.textContent),
    };
  });
  console.log('提示词弹窗:', JSON.stringify(promptModal));

  // 编辑 → 保存（写入一段带标记的自定义内容）
  await page.fill('#ruleTrainingPromptContent', '【UI自定义-标记UI123】\n范围={auditScope}\n样例：{reviewReport}\n已有：{existingRules}');
  await page.click('#ruleTrainingPromptSaveBtn');
  await page.waitForTimeout(1200);

  const afterSave = await page.evaluate(async () => {
    const r = await fetch('/api/config/prompts/rule-training-brief-user').then(x => x.json());
    return { modalClosed: getComputedStyle(document.getElementById('ruleTrainingPromptModal')).display === 'none', isCustom: r.isCustom, head: r.content.split('\n')[0] };
  });
  console.log('保存后:', JSON.stringify(afterSave));

  // 重新打开确认回填 + 按钮角标
  await page.click('#ruleTrainingPromptBtn');
  await page.waitForTimeout(1200);
  const reopened = await page.evaluate(() => ({
    contentHead: document.getElementById('ruleTrainingPromptContent').value.split('\n')[0],
    status: document.getElementById('ruleTrainingPromptStatus')?.textContent,
  }));
  console.log('再次打开:', JSON.stringify(reopened));

  await page.screenshot({ path: 'scripts/prompt_edit.png' });

  // 恢复默认（点击「恢复默认」，自动确认 confirm）
  page.on('dialog', d => d.accept());
  await page.click('#ruleTrainingPromptResetBtn');
  await page.waitForTimeout(1500);
  const afterReset = await page.evaluate(async () => {
    const r = await fetch('/api/config/prompts/rule-training-brief-user').then(x => x.json());
    return { isCustom: r.isCustom, head: r.content.split('\n')[0], textareaHead: document.getElementById('ruleTrainingPromptContent').value.split('\n')[0] };
  });
  console.log('恢复默认后:', JSON.stringify(afterReset));

  console.log('页面 JS 错误:', errors.length ? errors : '无');
  await browser.close();
})();
