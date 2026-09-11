# -*- coding: utf-8 -*-
"""端到端验证变更简报批量综合流程：搜索全部工单 -> 批量 AI 评审 -> 轮询 -> 取综合简报"""
import json, sys, time, io, urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
BASE = 'http://localhost:8081'

def get(url, timeout=20):
    return json.load(urllib.request.urlopen(url, timeout=timeout))

def post(url, payload, timeout=30):
    req = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    return json.load(urllib.request.urlopen(req, timeout=timeout))

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

# 3. 一次性直传所有工单，发起批量综合简报
ts = time.strftime('%Y%m%d%H%M%S')
res2 = post(BASE + '/api/order/async-summarize', {'ts': ts, 'ruleGroupId': gid, 'orders': orders})
print('2. 提交批量评审任务:', json.dumps(res2, ensure_ascii=False))
task_id = res2.get('taskId')

# 4. 轮询任务状态
status = ''
for i in range(90):
    time.sleep(3)
    t = get(BASE + '/api/order/async-brief-task/' + task_id)
    status = t.get('status')
    if status in ('COMPLETED', 'FAILED'):
        print('3. 任务结束: status=%s 轮询%d次' % (status, i + 1))
        if status == 'FAILED':
            print('   错误:', json.dumps(t, ensure_ascii=False)[:500])
            sys.exit(1)
        break
else:
    print('3. 轮询超时'); sys.exit(1)

# 5. 按 ts 取综合简报（批次记录 orderId=ALL）
rec = get(BASE + '/api/order/brief-record?ts=' + ts)
content = rec.get('briefContent') or ''
print('4. 综合简报: status=%s 长度=%d documentName=%s' % (rec.get('status'), len(content), rec.get('documentName')))
print('--- 简报预览(前800字) ---')
print(content[:800])
print('--- 端到端验证通过 ---')
