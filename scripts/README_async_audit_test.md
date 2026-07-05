# Async Audit Local Test

This folder contains a small mock server for local async-audit testing.

## 1. Start the mock server

```powershell
python scripts/async_audit_mock_server.py --host 127.0.0.1 --port 18080
```

It exposes:

- Ticket endpoint: `http://127.0.0.1:18080/ticket/{id}`
- Download URL returned by ticket endpoint: `http://127.0.0.1:18080/files/sample.txt`
- OpenAI-compatible mock LLM endpoint: `http://127.0.0.1:18080/v1/chat/completions`

## 2. Configure SmartDoc

Set API config in the app or database:

- `ticketEndpoint`: `http://127.0.0.1:18080/ticket/{id}`
- `endpoint`: `http://127.0.0.1:18080/v1/chat/completions`
- `apiKey`: any non-empty value, for example `mock-key`
- `model`: any value, for example `mock-model`

Ensure at least one enabled rule exists in the rule group used by the test.

## 3. Submit async audit

```powershell
$body = @{
  ticketId = "T-1001"
  ts = "20260705183000"
  ruleGroupId = "default"
} | ConvertTo-Json

Invoke-RestMethod "http://127.0.0.1:8080/api/ticket/async-audit" `
  -Method Post `
  -ContentType "application/json" `
  -Body $body
```

Poll the returned task:

```powershell
Invoke-RestMethod "http://127.0.0.1:8080/api/ticket/async-task/<taskId>"
```

Read the latest ticket audit record:

```powershell
Invoke-RestMethod "http://127.0.0.1:8080/api/ticket/audit-record?ticketId=T-1001&ts=20260705183000"
```
