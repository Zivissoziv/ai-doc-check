/* 验证：权限组弹窗「全部可见」可正常取消勾选，并展开为显式全选 */
const { chromium } = require('D:/nodejs/node_global/_npx/423231821c231c73/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.on('console', m => console.log(`  [${m.type()}]`, m.text()));
  page.on('pageerror', e => console.log('  [pageerror]', e.message));

  await page.goto('http://localhost:8081/?pgroup=admin', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  // 直接打开「财务组」的编辑弹窗
  const info = await page.evaluate(async () => {
    const list = await PermissionAPI.list();
    app.permissionGroups = list.groups || [];
    const g = app.permissionGroups.find(x => x.permKey === 'finance') || app.permissionGroups[0];
    if (!g) return { error: '没有可用权限组', list };
    await app.showPermissionGroupModal(g.id);
    return { id: g.id, permKey: g.permKey, visibleGroupIds: g.visibleGroupIds || [] };
  });
  console.log('打开弹窗:', JSON.stringify(info));
  if (info.error) throw new Error(info.error);
  await page.waitForTimeout(800);

  const snap = () => page.evaluate(() => {
    const all = document.getElementById('permAllGroups');
    const items = [...document.querySelectorAll('#permGroupChecklist input[type=checkbox]')];
    return {
      allVisible: all.checked,
      total: items.length,
      checkedItems: items.filter(i => i.checked).length,
      disabledItems: items.filter(i => i.disabled).length,
      _permAllVisible: app._permAllVisible,
      _permSelectedIds: app._permSelectedIds.size
    };
  });

  console.log('初始状态      :', JSON.stringify(await snap()));

  // 先确保「全部可见」处于勾选状态（这正是用户截图里的场景）
  if (!(await page.isChecked('#permAllGroups'))) {
    await page.click('#permAllGroups');
    await page.waitForTimeout(300);
  }
  const beforeUncheck = await snap();
  console.log('勾选全部可见时:', JSON.stringify(beforeUncheck));

  await page.click('#permAllGroups');
  await page.waitForTimeout(400);
  const afterUncheck = await snap();
  console.log('取消全部可见后:', JSON.stringify(afterUncheck));

  // 再取消一个具体规则组
  const firstId = await page.evaluate(() => document.querySelector('#permGroupChecklist input[type=checkbox]').dataset.groupId);
  await page.click(`#permGroupChecklist input[data-group-id="${firstId}"]`);
  await page.waitForTimeout(300);
  const afterUncheckItem = await snap();
  console.log('再取消一项后  :', JSON.stringify(afterUncheckItem));

  await page.screenshot({ path: 'D:/qoder-workplace/test-project/ai-doc-check/scripts/perm_all_groups_check.png' });

  // 边界：全部取消勾选时保存应被拦下（避免空列表被后端当成“全部可见”）
  let alertMsg = '';
  page.on('dialog', d => { alertMsg = d.message(); d.accept(); });
  await page.evaluate(() => {
    document.querySelectorAll('#permGroupChecklist input[type=checkbox]:checked').forEach(i => i.click());
  });
  await page.waitForTimeout(400);
  const emptyState = await snap();
  await page.click('#permissionModalBtn');
  await page.waitForTimeout(600);
  const stillOpen = await page.evaluate(() => !document.getElementById('permissionModal').classList.contains('hidden'));
  console.log('全部取消后保存:', JSON.stringify(emptyState), '| 弹窗仍打开:', stillOpen, '| 提示:', alertMsg);

  // 重新勾回「全部可见」
  await page.click('#permAllGroups');
  await page.waitForTimeout(300);
  const backToAll = await snap();
  console.log('再次勾选全部可见:', JSON.stringify(backToAll));

  const ok = beforeUncheck.allVisible === true && beforeUncheck.disabledItems === beforeUncheck.total
    && afterUncheck.allVisible === false && afterUncheck.disabledItems === 0
    && afterUncheck.checkedItems === afterUncheck.total
    && afterUncheckItem.allVisible === false && afterUncheckItem.checkedItems === afterUncheck.total - 1
    && backToAll.allVisible === true && backToAll._permSelectedIds === 0
    && emptyState.checkedItems === 0 && stillOpen === true && alertMsg.includes('全部可见');
  console.log(ok ? '\nPASS: 「全部可见」可取消，规则组列表可独立勾选，空选被拦下' : '\nFAIL: 行为不符合预期');

  await browser.close();
  process.exit(ok ? 0 : 1);
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
