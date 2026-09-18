# -*- coding: utf-8 -*-
"""端到端验证变更简报批量综合流程：搜索全部工单 -> 流式生成 -> 校验落库"""
import json, sys, time, io, urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
BASE = 'http://localhost:8081'

def get(url, timeout=20):
    return json.load(urllib.request.urlopen(url, timeout=timeout))

def stream(url, payload, timeout=600):
    """流式读取 NDJSON，逐事件 yield 已解析的 dict"""
    req = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    resp = urllib.request.urlopen(req, timeout=timeout)
    print('   HTTP', resp.status, resp.headers.get('Content-Type'))
    for raw in resp:
        line = raw.decode('utf-8').strip()
        if line:
            yield json.loads(line)

# 1. 搜索工单（复用 order_audit_endpoint）
res = get(BASE + '/api/order/search?startTime=2026-09-01%2000:00:00&endTime=2026-09-03%2023:59:59')
orders = res['orders']
print('1. 搜索到工单数:', len(orders))
assert len(orders) >= 2, '批量简报至少需要 2 条工单'

# 2. 取 brief 规则组（前端逻辑：下拉选组，传业务 groupId）
groups = get(BASE + '/api/config/rules?groupType=brief')['groups']
assert groups, '无 BRIEF 规则组'
gid = groups[0]['groupId']
print('   使用规则组:', gid, groups[0]['name'], '| 简报风格:', groups[0].get('briefStyle', '')[:30])

# 3. 一次性直传所有工单，流式生成综合简报
ts = time.strftime('%Y%m%d%H%M%S')
t0 = time.time()
content, delta_events, first_delta_at, failure = '', 0, None, None
for evt in stream(BASE + '/api/order/summarize-stream', {'ts': ts, 'ruleGroupId': gid, 'orders': orders}):
    t = evt.get('type')
    if t == 'status':
        print('2. 状态:', evt.get('message'))
    elif t == 'delta':
        if first_delta_at is None:
            first_delta_at = time.time() - t0
        delta_events += 1
        content += evt.get('text', '')
    elif t == 'error':
        failure = evt.get('message')
        break
    elif t == 'done':
        content = evt.get('briefContent') or content
        print('3. 完成: 正文 %d 字, 名称=%s, 批次=%s' % (len(content), evt.get('documentName'), evt.get('briefBatchNo')))

assert not failure, '简报生成失败: %s' % failure
assert content.strip(), '简报正文为空'
print('   流式统计: delta 事件 %d 个, 首个 delta 在 %.1fs, 总耗时 %.1fs' % (delta_events, first_delta_at or -1, time.time() - t0))
assert delta_events > 1, '未收到多个 delta 事件，流式可能失效'
assert not content.lstrip().startswith('null'), '正文混入了字面量 null（SSE 分片为 JSON null 的解析 bug）'

# 4. 校验已落库（批次记录 orderId=ALL）
rec = get(BASE + '/api/order/brief-record?ts=' + ts)
print('4. 落库校验: status=%s 长度=%d documentName=%s' % (rec.get('status'), len(rec.get('briefContent') or ''), rec.get('documentName')))
assert rec.get('status') == 'COMPLETED', '落库状态不是 COMPLETED'
assert (rec.get('briefContent') or '').strip() == content.strip(), '落库内容与流式内容不一致'

print('--- 简报预览(前800字) ---')
print(content[:800])
print('--- 端到端验证通过 ---')
