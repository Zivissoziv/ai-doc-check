// 临时冒烟脚本：验证变更简报流式接口（NDJSON）的增量事件
const BASE = 'http://localhost:8081';

function fmt(n) { return String(n) + 'ms'; }

(async () => {
  const searchUrl = BASE + '/api/order/search?startTime=2026-09-09%2018:00:00&endTime=2026-09-16%2018:00:00';
  const search = await (await fetch(searchUrl)).json();
  console.log('搜索到工单:', search.orders.length, '条');

  const t0 = Date.now();
  const res = await fetch(BASE + '/api/order/summarize-stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ts: 'SMOKE' + Date.now(), ruleGroupId: 'jianbaozu', orders: search.orders })
  });
  console.log('HTTP', res.status, res.headers.get('content-type'));

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '', counts = {}, chars = 0, firstDeltaAt = null, lastDeltaAt = null;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    const lines = buf.split('\n');
    buf = lines.pop();
    for (const line of lines) {
      if (!line.trim()) continue;
      let e;
      try { e = JSON.parse(line); } catch { continue; }
      counts[e.type] = (counts[e.type] || 0) + 1;
      if (e.type === 'status') console.log('[' + fmt(Date.now() - t0) + '] status:', e.message);
      if (e.type === 'delta') {
        chars += e.text.length;
        if (firstDeltaAt === null) { firstDeltaAt = Date.now() - t0; console.log('[' + fmt(firstDeltaAt) + '] 首个 delta: ' + JSON.stringify(e.text.slice(0, 30))); }
        lastDeltaAt = Date.now() - t0;
      }
      if (e.type === 'error') console.log('[' + fmt(Date.now() - t0) + '] ERROR:', e.message);
      if (e.type === 'done') {
        console.log('[' + fmt(Date.now() - t0) + '] done: 正文 ' + (e.briefContent || '').length + ' 字, 名称=' + e.documentName + ', 批次=' + e.briefBatchNo);
        console.log('--- 正文开头 ---');
        console.log(e.briefContent.slice(0, 150).replace(/\n/g, ' / '));
        console.log('--- 正文结尾 ---');
        console.log(e.briefContent.slice(-120).replace(/\n/g, ' / '));
        const bad = e.briefContent.indexOf('nullnull') >= 0 || e.briefContent.startsWith('null');
        console.log('是否混入字面量 null:', bad);
      }
    }
  }
  console.log('事件统计:', JSON.stringify(counts), '| delta 累计', chars, '字');
  console.log('首个 delta', firstDeltaAt, 'ms / 末个 delta', lastDeltaAt, 'ms / 总耗时', Date.now() - t0, 'ms');
})();
