/* 验证：变更简报流式渲染（搜索工单 → AI评审 → 边生成边渲染，无轮询请求） */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });

  // 记录请求，用于确认没有再打 async-brief-task 轮询接口
  const calls = [];
  page.on('request', r => { const u = r.url(); if (u.includes('/api/order/')) calls.push(r.method() + ' ' + u.replace('http://localhost:8081', '')); });

  await page.goto('http://localhost:8081/?pgroup=admin', { waitUntil: 'networkidle' });
  await page.click('#mode-brief');
  await page.waitForTimeout(800);

  await page.click('#orderSearchBtn');
  await page.waitForFunction(() => document.querySelectorAll('#orderList button, #orderList [data-order-id]').length > 0, { timeout: 20000 }).catch(() => {});
  await page.waitForTimeout(1200);
  const orderCount = await page.evaluate(() => window.app?.orderList?.length || 0);
  console.log('搜索到工单:', orderCount, '条');

  // 点击 AI评审，并在生成过程中采样正文长度变化
  await page.click('#runAuditBtn');
  const samples = [];
  for (let i = 0; i < 40; i++) {
    await page.waitForTimeout(500);
    const s = await page.evaluate(() => {
      const el = document.querySelector('#auditResults .brief-markdown');
      const btn = document.getElementById('runAuditBtn');
      return { len: el ? el.textContent.length : 0, running: btn ? btn.disabled : false, html: !!el };
    });
    samples.push(s.len);
    if (!s.running && s.len > 0) break;
  }
  const growth = samples.filter((v, i) => i === 0 || v !== samples[i - 1]);
  console.log('正文长度采样（去重）:', growth.slice(0, 12).join(' → '), growth.length > 12 ? '...' : '');
  console.log('是否逐步增长（可证明流式）:', new Set(samples).size > 2);

  const final = await page.evaluate(() => {
    const el = document.querySelector('#auditResults .brief-markdown');
    const text = el ? el.textContent : '';
    return {
      chars: text.length,
      head: text.slice(0, 80).replace(/\s+/g, ' '),
      badgeVisible: !document.getElementById('auditBadge')?.classList.contains('hidden'),
      containsLiteralNull: text.includes('nullnull') || text.trimStart().startsWith('null'),
    };
  });
  console.log('最终渲染:', JSON.stringify(final, null, 0));
  console.log('关键字数:', (await page.evaluate(() => document.querySelector('#auditResults')?.innerHTML.length || 0)));

  console.log('期间调用的 /api/order/ 接口:');
  console.log('  ' + calls.join('\n  '));
  console.log('是否还有轮询 async-brief-task:', calls.some(c => c.includes('async-brief-task')));

  await page.screenshot({ path: 'scripts/brief_stream.png', fullPage: false });
  await browser.close();
})();
