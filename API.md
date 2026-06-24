# 工单集成 API

基础地址: `http://localhost:8080`

---

### 1. 获取工单信息

```
GET /api/ticket/{ticketId}
```

从配置的外部工单系统获取文档信息。需先在 API 配置中设置 `ticketEndpoint`。

**响应 200:**
```json
{
  "ticketId": "123",
  "documentUrl": "https://...",
  "documentBase64": "...",
  "documentName": "文档.docx",
  "data": { "字段": "值" },
  "templateDocUrl": "https://...",
  "templateBase64": "...",
  "templateDocName": "模板.docx"
}
```

若未配置工单服务，返回 503:
```json
{ "error": "工单服务未配置，请在API配置中设置 ticketEndpoint" }
```

---

### 2. 代理下载文件

```
POST /api/ticket/download
Content-Type: application/json

{ "url": "https://example.com/file.docx" }
```

返回文件字节流。

---

### 3. 查询工单审核记录（历史结果复用）

```
GET /api/ticket/audit-record?ticketId=test123&ts=20250622120000&summaryOnly=false
```

**参数说明:**

| 参数 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `ticketId` | 是 | String | 工单 ID |
| `ts` | 是 | String | 时间戳，格式 `yyyyMMddHHmmss` |
| `summaryOnly` | 否 | Boolean | 默认 `false`。`true` 时只返回统计摘要，不返回全量明细 |

**响应 200（有历史记录，summaryOnly=false）:**
```json
{
  "exists": true,
  "auditBatchNo": "TESTBATCH001",
  "documentName": "测试文档.docx",
  "auditedAt": "2026-06-22T16:23:58",
  "totalCount": 5,
  "passCount": 3,
  "skippedCount": 1,
  "failCount": 1,
  "failures": [
    {
      "ruleName": "关键信息检查",
      "summary": "缺少版本号信息",
      "issues": [
        { "location": "第2章", "problem": "未标注版本号", "suggestion": "建议添加版本号字段" }
      ]
    }
  ],
  "results": [ ... ]
}
```

**响应 200（无历史记录）:**
```json
{ "exists": false }
```

**响应 200（summaryOnly=true）:**
```json
{
  "exists": true,
  "auditBatchNo": "TESTBATCH001",
  "totalCount": 5,
  "passCount": 3,
  "skippedCount": 1,
  "failCount": 1,
  "failures": [ ... ],
  "results": null
}
```

---

### 4. 提交异步审核

```
POST /api/ticket/async-audit
Content-Type: application/json

{
  "ticketId": "xxx",
  "ts": "20250622120000",
  "ruleGroupId": "znywz"
}
```

**响应 202 Accepted:**
```json
{
  "taskId": "a1b2c3d4-...",
  "status": "PENDING"
}
```

### 5. 查询异步任务状态

```
GET /api/ticket/async-task/{taskId}
```

**响应 200:**
```json
// 执行中
{ "taskId": "a1b2c3d4-...", "status": "RUNNING" }

// 已完成
{ "taskId": "a1b2c3d4-...", "status": "COMPLETED", "auditBatchNo": "xxx" }

// 执行失败
{ "taskId": "a1b2c3d4-...", "status": "FAILED", "errorMessage": "工单服务未配置" }
```

**响应 404:** 任务 ID 不存在

### 第三方异步调用流程

```
1. POST /api/ticket/async-audit         → 拿到 taskId（202 Accepted）
2. 轮询 GET /api/ticket/async-task/{id} 直到 COMPLETED 或 FAILED
3. GET /api/ticket/audit-record?ticketId=xxx&ts=yyy&summaryOnly=true  → 取结果摘要
```
