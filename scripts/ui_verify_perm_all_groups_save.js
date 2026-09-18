/* 验证：取消「全部可见」后保存，落库为显式规则组列表（跑完自动恢复原数据） */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.on('pageerror', e => console.log('  [pageerror]', e.message));
  page.on('dialog', d => { console.log('  [alert]', d.message()); d.dismiss(); });

  await page.goto('http://localhost:8081/?pgroup=admin', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  const before = await page.evaluate(() => PermissionAPI.list());
  const orig = (before.groups || []).find(g => g.permKey === 'finance');
  console.log('原始数据:', JSON.stringify(orig));
  if (!orig) throw new Error('财务组不存在，跳过');

  await page.evaluate(async (id) => {
    app.permissionGroups = (await PermissionAPI.list()).groups || [];
    await app.showPermissionGroupModal(id);
  }, orig.id);
  await page.waitForTimeout(800);

  // 确保先处于「全部可见」
  if (!(await page.isChecked('#permAllGroups'))) {
    await page.click('#permAllGroups');
    await page.waitForTimeout(400);
  }
  // 取消「全部可见」
  await page.click('#permAllGroups');
  await page.waitForTimeout(400);

  // 取消第一个规则组
  const droppedId = await page.evaluate(() => document.querySelector('#permGroupChecklist input[type=checkbox]').dataset.groupId);
  await page.click(`#permGroupChecklist input[data-group-id="${droppedId}"]`);
  await page.waitForTimeout(400);

  const expected = await page.evaluate(() =>
    [...document.querySelectorAll('#permGroupChecklist input[type=checkbox]')].filter(i => i.checked).map(i => i.dataset.groupId));
  console.log('去掉的组:', droppedId, '| 期望落库:', JSON.stringify(expected));

  await page.click('#permissionModalBtn');
  await page.waitForTimeout(1500);

  const after = await page.evaluate(() => PermissionAPI.list());
  const saved = (after.groups || []).find(g => g.permKey === 'finance');
  const modalHidden = await page.evaluate(() => document.getElementById('permissionModal').classList.contains('hidden'));
  console.log('实际落库:', JSON.stringify(saved.visibleGroupIds), '| 弹窗已关闭:', modalHidden);

  const ok = JSON.stringify([...saved.visibleGroupIds].sort()) === JSON.stringify([...expected].sort())
    && !saved.visibleGroupIds.includes(droppedId) && modalHidden;

  // 恢复原始数据
  await page.evaluate(async (o) => {
    await PermissionAPI.update(o.id, {
      permKey: o.permKey, permName: o.permName, briefVisible: o.briefVisible, visibleGroupIds: o.visibleGroupIds
    });
  }, orig);
  const restored = await page.evaluate(() => PermissionAPI.list());
  console.log('已恢复为:', JSON.stringify((restored.groups || []).find(g => g.permKey === 'finance').visibleGroupIds));

  console.log(ok ? '\nPASS: 取消「全部可见」后保存，落库为显式列表' : '\nFAIL: 落库数据不符合预期');
  await browser.close();
  process.exit(ok ? 0 : 1);
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
