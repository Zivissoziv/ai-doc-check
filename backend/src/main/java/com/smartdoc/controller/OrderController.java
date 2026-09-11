package com.smartdoc.controller;

import com.smartdoc.dto.AsyncOrderTaskStatusDto;
import com.smartdoc.dto.AuditFeedbackDto;
import com.smartdoc.dto.AuditOrderRecordDto;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.AuditOrderFeedback;
import com.smartdoc.service.ApiConfigService;
import com.smartdoc.service.AuditOrderFeedbackService;
import com.smartdoc.service.AuditOrderRecordService;
import com.smartdoc.service.OrderAsyncAuditService;
import com.smartdoc.service.OrderDataService;
import com.smartdoc.service.OrderSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ApiConfigService apiConfigService;
    private final OrderDataService orderDataService;
    private final OrderAsyncAuditService orderAsyncAuditService;
    private final AuditOrderRecordService auditOrderRecordService;
    private final AuditOrderFeedbackService auditOrderFeedbackService;
    private final OrderSummaryService orderSummaryService;

    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        ApiConfig config = apiConfigService.getRawApiConfig();
        if (config == null || config.getOrderAuditEndpoint() == null || config.getOrderAuditEndpoint().isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(errorMap("Order audit service is not configured, please set orderAuditEndpoint"));
        }

        try {
            return ResponseEntity.ok(orderDataService.fetchOrderInfo(config, orderId));
        } catch (Exception e) {
            log.error("Failed to fetch order audit data: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorMap("Failed to fetch order audit data: " + e.getMessage()));
        }
    }

    /**
     * 变更简报：按时间范围搜索工单列表（复用 order_audit_endpoint，返回 order JSON list，含详情）
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchOrders(
            @RequestParam String startTime,
            @RequestParam String endTime) {
        ApiConfig config = apiConfigService.getRawApiConfig();
        if (config == null || config.getOrderAuditEndpoint() == null || config.getOrderAuditEndpoint().isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(errorMap("Order audit service is not configured, please set orderAuditEndpoint"));
        }
        if (startTime == null || startTime.trim().isEmpty() || endTime == null || endTime.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("Missing required params: startTime, endTime"));
        }

        try {
            List<Map<String, Object>> orders = orderDataService.searchOrders(config, startTime.trim(), endTime.trim());
            Map<String, Object> response = new HashMap<>();
            response.put("orders", orders);
            response.put("total", orders.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to search orders: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorMap("Failed to search orders: " + e.getMessage()));
        }
    }

    @PostMapping("/async-audit")
    public ResponseEntity<Map<String, Object>> submitAsyncAudit(@RequestBody Map<String, String> body) {
        String orderId = body.get("orderId");
        String ts = normalizeTs(body.get("ts"));
        String ruleGroupId = body.get("ruleGroupId");

        if (orderId == null || orderId.isEmpty() || ruleGroupId == null || ruleGroupId.isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("Missing required params: orderId, ruleGroupId"));
        }

        log.info("Submit order async audit: orderId={}, ts={}, ruleGroupId={}", orderId, ts, ruleGroupId);
        String taskId = orderAsyncAuditService.createAsyncTask(orderId, ts, ruleGroupId);

        Map<String, Object> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("orderId", orderId);
        response.put("ts", ts);
        response.put("status", "PENDING");
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/async-task/{taskId}")
    public ResponseEntity<?> getAsyncTaskStatus(@PathVariable String taskId) {
        AsyncOrderTaskStatusDto status = orderAsyncAuditService.getTaskStatus(taskId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/audit-record")
    public ResponseEntity<AuditOrderRecordDto> getAuditRecord(
            @RequestParam String orderId,
            @RequestParam String ts,
            @RequestParam(required = false, defaultValue = "false") Boolean summaryOnly) {
        log.info("Query order audit record: orderId={}, ts={}, summaryOnly={}", orderId, ts, summaryOnly);
        return ResponseEntity.ok(auditOrderRecordService.getAuditResultsByOrderIdAndTs(orderId, ts, summaryOnly));
    }

    @PostMapping("/feedback/save")
    public ResponseEntity<Map<String, Object>> saveAuditResults(
            @RequestBody List<AuditResultDto> results,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) Long durationMs,
            @RequestParam String orderId,
            @RequestParam(required = false) String ts) {
        String normalizedTs = normalizeTs(ts);
        List<AuditOrderFeedback> saved = auditOrderFeedbackService.saveAuditResults(results, groupId, durationMs);
        String batchNo = saved != null && !saved.isEmpty()
                ? saved.get(0).getAuditBatchNo()
                : java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        auditOrderRecordService.saveRecord(orderId, normalizedTs, batchNo, "order_" + orderId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("orderId", orderId);
        response.put("ts", normalizedTs);
        response.put("ids", saved.stream().map(AuditOrderFeedback::getId).collect(Collectors.toList()));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/feedback/{id}")
    public ResponseEntity<Map<String, Object>> submitFeedback(@PathVariable Long id, @RequestBody AuditFeedbackDto dto) {
        AuditOrderFeedback updated = auditOrderFeedbackService.submitFeedback(id, dto.getFeedbackType(), dto.getReason());
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("feedback", updated);
        return ResponseEntity.ok(response);
    }

    /**
     * 变更简报：提交异步 AI 总结任务
     */
    @PostMapping("/async-summarize")
    public ResponseEntity<Map<String, Object>> submitAsyncSummarize(@RequestBody Map<String, Object> body) {
        String ts = normalizeTs(asString(body.get("ts")));
        String ruleGroupId = asString(body.get("ruleGroupId"));
        // 综合简报：前端把搜索结果中的所有工单一次性直传（每项含 orderId/data/documentName）
        List<Map<String, Object>> orders = asMapList(body.get("orders"));
        if (orders == null || orders.isEmpty()) {
            // 兼容旧的单工单直传格式
            Map<String, Object> orderData = asMap(body.get("orderData"));
            if (orderData != null) {
                Map<String, Object> item = new HashMap<>();
                item.put("orderId", asString(body.get("orderId")));
                item.put("documentName", asString(body.get("documentName")));
                item.put("data", orderData);
                orders = new java.util.ArrayList<>();
                orders.add(item);
            }
        }

        if (orders == null || orders.isEmpty() || ruleGroupId == null || ruleGroupId.isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("Missing required params: orders, ruleGroupId"));
        }

        log.info("Submit order async summarize: ts={}, ruleGroupId={}, orderCount={}",
                ts, ruleGroupId, orders.size());
        String taskId = orderSummaryService.createAsyncTask(ts, ruleGroupId, orders);

        Map<String, Object> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("ts", ts);
        response.put("status", "PENDING");
        return ResponseEntity.accepted().body(response);
    }

    /**
     * 变更简报：轮询 AI 总结任务状态
     */
    @GetMapping("/async-brief-task/{taskId}")
    public ResponseEntity<?> getAsyncBriefTaskStatus(@PathVariable String taskId) {
        AsyncOrderTaskStatusDto status = orderSummaryService.getTaskStatus(taskId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * 变更简报：查询最近一次简报（orderId/ts 均可省略，至少传一个；
     * 只传 ts 时查综合简报批次）
     */
    @GetMapping("/brief-record")
    public ResponseEntity<Map<String, Object>> getBriefRecord(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String ts) {
        return ResponseEntity.ok(orderSummaryService.getLatestBrief(orderId, ts));
    }

    /**
     * 变更简报：最近 N 次已完成的简报记录（默认 15，供历史简报列表）
     */
    @GetMapping("/brief-records")
    public ResponseEntity<Map<String, Object>> getRecentBriefRecords(
            @RequestParam(required = false, defaultValue = "15") int limit) {
        Map<String, Object> response = new HashMap<>();
        response.put("records", orderSummaryService.getRecentBriefs(limit));
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List)) {
            return null;
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeTs(String ts) {
        if (ts != null && !ts.trim().isEmpty()) {
            return ts;
        }
        return LocalDateTime.now().format(TS_FORMATTER);
    }

    private Map<String, Object> errorMap(String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("error", message);
        return map;
    }
}
