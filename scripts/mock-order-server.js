/**
 * Mock order service for testing the brief (change-report) flow.
 * Serves a JSON list of orders with details on port 8090.
 */
const http = require('http');

// 测试期兜底：任何未捕获异常只打日志，不让进程退出
process.on('uncaughtException', err => console.error('[mock-order] uncaughtException:', err));
process.on('unhandledRejection', err => console.error('[mock-order] unhandledRejection:', err));

const ORDERS = [
  {
    orderId: 'ORD20260901001',
    documentName: '9月核心交换机扩容变更工单',
    createTime: '2026-09-01 10:20:00',
    status: '已执行',
    data: {
      orderId: 'ORD20260901001',
      title: '9月核心交换机扩容变更工单',
      applicant: '张伟',
      department: '网络运维部',
      changeType: '容量扩容',
      riskLevel: '中',
      planStartTime: '2026-09-01 22:00',
      planEndTime: '2026-09-02 02:00',
      description: '核心交换机A端口利用率连续一周超过85%，计划新增2块48口万兆板卡，并调整2条上联链路至新板卡以均衡流量。',
      impactScope: ['核心交换机A', '上联链路 L1/L2', '东区办公网段'],
      rollbackPlan: '保留原板卡槽位配置，如异常可30分钟内回退。',
      attachments: 2
    }
  },
  {
    orderId: 'ORD20260902002',
    documentName: '9月数据库慢查询治理变更工单',
    createTime: '2026-09-02 14:05:00',
    status: '待执行',
    data: {
      orderId: 'ORD20260902002',
      title: '9月数据库慢查询治理变更工单',
      applicant: '李娜',
      department: '数据库组',
      changeType: '参数优化',
      riskLevel: '低',
      planStartTime: '2026-09-05 01:00',
      planEndTime: '2026-09-05 03:00',
      description: '订单库出现23条平均执行时间超过2秒的慢查询，计划为 order_item 表增加联合索引，并调大 sort_buffer_size 至 4M。',
      impactScope: ['订单主库', '报表只读实例'],
      rollbackPlan: '索引可在线删除，参数可即时还原。',
      attachments: 0
    }
  },
  {
    orderId: 'ORD20260903003',
    documentName: '9月防火墙策略收紧变更工单',
    createTime: '2026-09-03 09:30:00',
    status: '审批中',
    data: {
      orderId: 'ORD20260903003',
      title: '9月防火墙策略收紧变更工单',
      applicant: '王强',
      department: '安全组',
      changeType: '安全策略',
      riskLevel: '高',
      planStartTime: '2026-09-06 23:00',
      planEndTime: '2026-09-07 01:00',
      description: '互联网出口防火墙存在3条来源为 any 的放行策略，计划收敛为最小化白名单，并开启策略命中日志。',
      impactScope: ['互联网出口', '第三方对接系统 2 个'],
      rollbackPlan: '导出变更前策略快照，一键恢复。',
      attachments: 5
    }
  }
];

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  console.log(`[mock-order] ${req.method} ${req.url}`);
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });

  // 单条工单：/orders/ORD20260901001
  const m = url.pathname.match(/^\/orders\/(.+)$/);
  if (m) {
    const order = ORDERS.find(o => o.orderId === m[1]);
    if (order) {
      res.end(JSON.stringify({ orderId: order.orderId, documentName: order.documentName, data: order.data }));
    } else {
      res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'order not found' }));
    }
    return;
  }

  // 列表：/orders?startTime=..&endTime=..
  res.end(JSON.stringify({ code: 0, orders: ORDERS }));
}).listen(8090, () => console.log('[mock-order] listening on 8090'));
