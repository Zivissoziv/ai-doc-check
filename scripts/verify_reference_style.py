# -*- coding: utf-8 -*-
"""验证提示词：简报风格作为人工简报参考样例时，AI 模仿其结构输出"""
import json, sys, time, io, urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
BASE = 'http://localhost:8081'
GID = 'brief_test'
ORIG_STYLE = '升级版风格：表格列出变更项。'

SAMPLE_BRIEF = """【变更情况说明】
一、变更概况：本次共实施变更X项，总体运行平稳。
二、实施结果：逐项说明各变更的实施情况与验证结论。
三、遗留问题及措施：说明未完成事项与后续跟进计划。
四、审批与知会：列明审批人与知会范围。"""

def get(url, timeout=20):
    return json.load(urllib.request.urlopen(url, timeout=timeout))

def post(url, payload, timeout=30):
    req = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    return json.load(urllib.request.urlopen(req, timeout=timeout))

def put_style(style):
    group = next(g for g in get(BASE + '/api/config/rules?groupType=brief')['groups'] if g['groupId'] == GID)
    req = urllib.request.Request(BASE + '/api/config/rules/' + GID + '?auditMode=brief',
        data=json.dumps({'groupId': GID, 'name': group['name'], 'briefStyle': style}, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json'}, method='PUT')
    urllib.request.urlopen(req, timeout=20)

try:
    # 1. 设置人工简报样例作为参考
    put_style(SAMPLE_BRIEF)
    print('1. 已设置人工简报样例作为参考简报')

    # 2. 搜索并批量评审
    orders = get(BASE + '/api/order/search?startTime=2026-09-01%2000:00:00&endTime=2026-09-07%2023:59:59')['orders']
    ts = time.strftime('%Y%m%d%H%M%S')
    res = post(BASE + '/api/order/async-summarize', {'ts': ts, 'ruleGroupId': GID, 'orders': orders})
    task_id = res['taskId']
    print('2. 提交任务:', task_id)

    for i in range(90):
        time.sleep(3)
        t = get(BASE + '/api/order/async-brief-task/' + task_id)
        if t['status'] in ('COMPLETED', 'FAILED'):
            print('3. 任务:', t['status'], '轮询', i + 1, '次')
            if t['status'] == 'FAILED':
                print('   错误:', t.get('errorMessage')); sys.exit(1)
            break

    rec = get(BASE + '/api/order/brief-record?ts=' + ts)
    content = rec.get('briefContent') or ''
    print('4. 简报长度:', len(content))
    print('--- 简报全文 ---')
    print(content[:1200])
    mimic = ('变更情况说明' in content) and ('遗留问题' in content or '审批与知会' in content)
    print('--- 是否模仿样例结构:', '是' if mimic else '否', '---')
finally:
    # 恢复原风格
    put_style(ORIG_STYLE)
    print('5. 已恢复原简报风格:', ORIG_STYLE)
