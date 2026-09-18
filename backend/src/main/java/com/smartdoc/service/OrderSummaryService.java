package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.RuleGroupDto;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.OrderBriefRecord;
import com.smartdoc.mapper.OrderBriefRecordMapper;
import com.smartdoc.template.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 变更简报 AI 总结服务：
 * 与工单审核（OrderAsyncAuditService）独立，输出 Markdown 简报并落库到 order_brief_record。
 * 生成走流式（{@link #streamLLMForSummary}），由 OrderController#summarizeStream 逐段透传给浏览器。
 */
@Slf4j
@Service
public class OrderSummaryService {

    private static final int LOCK_STRIPES = 64;
    private static final Object[] TASK_LOCKS = new Object[LOCK_STRIPES];
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    static {
        for (int i = 0; i < TASK_LOCKS.length; i++) {
            TASK_LOCKS[i] = new Object();
        }
    }

    private final ApiConfigService apiConfigService;
    private final OrderDataService orderDataService;
    private final OrderBriefRecordMapper orderBriefRecordMapper;
    private final RuleGroupService ruleGroupService;
    private final ObjectMapper objectMapper;

    public OrderSummaryService(ApiConfigService apiConfigService,
                               OrderDataService orderDataService,
                               OrderBriefRecordMapper orderBriefRecordMapper,
                               RuleGroupService ruleGroupService,
                               ObjectMapper objectMapper) {
        this.apiConfigService = apiConfigService;
        this.orderDataService = orderDataService;
        this.orderBriefRecordMapper = orderBriefRecordMapper;
        this.ruleGroupService = ruleGroupService;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedTasks() {
        int count = orderBriefRecordMapper.markUnfinishedTasksFailed("Service restarted, please resubmit order summary");
        if (count > 0) {
            log.warn("Marked {} unfinished order summary tasks as FAILED on startup", count);
        }
    }

    /**
     * 查询最近一次简报（含正文），COMPLETED 时返回 briefContent。
     * orderId 与 ts 至少传一个：都传时按工单+ts 精确查；只传 ts 时查该批次（综合简报）。
     */
    public Map<String, Object> getLatestBrief(String orderId, String ts) {
        boolean hasOrder = orderId != null && !orderId.trim().isEmpty();
        boolean hasTs = ts != null && !ts.trim().isEmpty();
        OrderBriefRecord record;
        if (hasOrder && hasTs) {
            record = orderBriefRecordMapper.findLatestByOrderIdAndTs(orderId.trim(), ts.trim());
        } else if (hasOrder) {
            record = orderBriefRecordMapper.findLatestByOrderId(orderId.trim());
        } else if (hasTs) {
            record = orderBriefRecordMapper.findLatestByTs(ts.trim());
        } else {
            record = null;
        }
        Map<String, Object> result = new HashMap<>();
        if (record == null) {
            result.put("found", false);
            return result;
        }
        result.put("found", true);
        result.put("taskId", record.getTaskId());
        result.put("orderId", record.getOrderId());
        result.put("ts", record.getTs());
        result.put("status", record.getStatus());
        result.put("errorMessage", record.getErrorMessage());
        result.put("briefBatchNo", record.getBriefBatchNo());
        result.put("documentName", record.getDocumentName());
        result.put("briefContent", record.getBriefContent());
        result.put("createdAt", record.getCreatedAt());
        result.put("updatedAt", record.getUpdatedAt());
        return result;
    }

    /**
     * 最近 N 次已完成的简报记录（新→旧），供历史简报列表展示
     */
    public java.util.List<Map<String, Object>> getRecentBriefs(int limit) {
        List<OrderBriefRecord> records = orderBriefRecordMapper.findRecentCompleted(Math.max(1, Math.min(limit, 100)));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderBriefRecord record : records) {
            Map<String, Object> item = new HashMap<>();
            item.put("taskId", record.getTaskId());
            item.put("orderId", record.getOrderId());
            item.put("ts", record.getTs());
            item.put("status", record.getStatus());
            item.put("documentName", record.getDocumentName());
            item.put("briefContent", record.getBriefContent());
            item.put("createdAt", record.getCreatedAt());
            item.put("updatedAt", record.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    /**
     * 流式简报的准备工作结果。
     */
    public static class SummaryPrep {
        public final String orderJson;
        public final int orderCount;
        public final String rulesList;
        public final String briefStyle;
        public final Long groupDbId;

        SummaryPrep(String orderJson, int orderCount, String rulesList, String briefStyle, Long groupDbId) {
            this.orderJson = orderJson;
            this.orderCount = orderCount;
            this.rulesList = rulesList;
            this.briefStyle = briefStyle;
            this.groupDbId = groupDbId;
        }
    }

    /**
     * 简报落库结果（供流式接口透出给前端）。
     */
    public static class SummaryResult {
        public final String documentName;
        public final String briefBatchNo;

        SummaryResult(String documentName, String briefBatchNo) {
            this.documentName = documentName;
            this.briefBatchNo = briefBatchNo;
        }
    }

    /**
     * 整理工单数据并加载 BRIEF 总结规则与简报风格（流式生成前调用一次）。
     */
    public SummaryPrep prepareSummary(String ruleGroupId, List<Map<String, Object>> orders) throws Exception {
        if (orders == null || orders.isEmpty()) {
            throw new RuntimeException("No orders to summarize");
        }
        // 整理所有工单详情：优先使用前端透传的 data，缺失时回退到按 orderId 拉取
        List<Map<String, Object>> validOrders = new ArrayList<>();
        for (Map<String, Object> order : orders) {
            Map<String, Object> data = asDataMap(order.get("data"));
            if (data == null || data.isEmpty()) {
                data = asDataMap(order.get("orderData"));
            }
            String orderId = order.get("orderId") == null ? "" : String.valueOf(order.get("orderId"));
            if ((data == null || data.isEmpty()) && !orderId.isEmpty()) {
                ApiConfig config = apiConfigService.getRawApiConfig();
                if (config != null && config.getOrderAuditEndpoint() != null && !config.getOrderAuditEndpoint().isEmpty()) {
                    Map<String, Object> orderInfo = orderDataService.fetchOrderInfo(config, orderId);
                    if (orderInfo != null && orderInfo.get("data") != null) {
                        data = objectMapper.convertValue(orderInfo.get("data"), Map.class);
                    }
                }
            }
            if (data == null || data.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("orderId", orderId);
            if (order.get("documentName") != null) {
                item.put("documentName", order.get("documentName"));
            }
            item.put("data", data);
            validOrders.add(item);
        }
        if (validOrders.isEmpty()) {
            throw new RuntimeException("Orders have no data to summarize");
        }
        String orderJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(validOrders);
        int orderCount = validOrders.size();

        // 读取 BRIEF 类型总结规则与简报风格
        List<RuleDto> rules = ruleGroupService.getRulesByGroupId(ruleGroupId, "brief");
        if (rules.isEmpty()) {
            throw new RuntimeException("Summary rule group is empty: " + ruleGroupId);
        }
        String rulesList = buildRulesList(rules);
        String briefStyle = "";
        Long groupDbId = null;
        try {
            RuleGroupDto group = ruleGroupService.getRuleGroupByGroupId(ruleGroupId);
            if (group != null) {
                groupDbId = group.getId();
                if (group.getBriefStyle() != null) {
                    briefStyle = group.getBriefStyle();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load brief style for group {}: {}", ruleGroupId, e.getMessage());
        }
        return new SummaryPrep(orderJson, orderCount, rulesList, briefStyle, groupDbId);
    }

    /**
     * 简报生成成功后落库（流式生成结束时调用）。
     */
    public SummaryResult completeSummary(String taskId, SummaryPrep prep, String brief) {
        OrderBriefRecord record = orderBriefRecordMapper.findByTaskId(taskId);
        String briefBatchNo = null;
        if (record != null) {
            briefBatchNo = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "_brief";
            record.setRuleGroupId(prep.groupDbId);
            record.setDocumentName("共 " + prep.orderCount + " 条工单");
            record.setBriefContent(brief);
            record.setBriefBatchNo(briefBatchNo);
            record.setStatus(OrderBriefRecord.STATUS_COMPLETED);
            record.setErrorMessage(null);
            orderBriefRecordMapper.updateById(record);
        }
        return new SummaryResult(record == null ? null : record.getDocumentName(), briefBatchNo);
    }

    /**
     * 流式简报：同步创建任务记录（PENDING，orderId=ALL 批次），由调用方在流结束后更新状态。
     * 同一批次已有未完成任务时抛异常，避免并发重复调用 LLM。
     */
    public String createSyncTask(String ts, String ruleGroupId) {
        String normalizedTs = normalizeTs(ts);
        String batchOrderId = "ALL";
        Object lock = lockFor(batchOrderId, normalizedTs);
        synchronized (lock) {
            OrderBriefRecord latest = orderBriefRecordMapper.findLatestByOrderIdAndTs(batchOrderId, normalizedTs);
            if (latest != null && latest.getTaskId() != null
                    && (OrderBriefRecord.STATUS_PENDING.equals(latest.getStatus())
                    || OrderBriefRecord.STATUS_RUNNING.equals(latest.getStatus()))) {
                throw new RuntimeException("该批次已有简报任务在执行，请稍后再试");
            }
            String taskId = UUID.randomUUID().toString();
            OrderBriefRecord record = OrderBriefRecord.builder()
                    .orderId(batchOrderId)
                    .ts(normalizedTs)
                    .taskId(taskId)
                    .status(OrderBriefRecord.STATUS_PENDING)
                    .build();
            orderBriefRecordMapper.insert(record);
            log.info("Created streaming order summary task: taskId={}, ts={}, ruleGroupId={}",
                    taskId, normalizedTs, ruleGroupId);
            return taskId;
        }
    }

    public void markRunning(String taskId) {
        updateStatus(taskId, OrderBriefRecord.STATUS_RUNNING, null);
    }

    public void markFailed(String taskId, String errorMessage) {
        updateStatus(taskId, OrderBriefRecord.STATUS_FAILED, errorMessage);
    }

    private Map<String, Object> asDataMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private String buildRulesList(List<RuleDto> rules) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            RuleDto rule = rules.get(i);
            if (rule.getEnabled() != null && !rule.getEnabled()) {
                continue;
            }
            sb.append("[总结规则").append(i).append("] ")
                    .append(rule.getName() == null ? "" : rule.getName())
                    .append("\n")
                    .append(rule.getPrompt() == null ? "" : rule.getPrompt())
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 构建 LLM 请求（system=summary-system，user=summary-user 模板）。
     * 固定 stream=true：简报只走流式生成，逐段透传给浏览器。
     */
    private HttpEntity<Map<String, Object>> buildSummaryRequest(String orderJson, String rulesList,
                                                                String briefStyle, ApiConfig apiConfig) {
        String endpoint = apiConfig.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("API endpoint 未配置");
        }
        String apiKey = apiConfig.getApiKey();
        String model = apiConfig.getModel();
        String auditRole = apiConfig.getAuditRole();

        Map<String, String> userParams = new HashMap<>();
        userParams.put("orderContent", orderJson);
        userParams.put("rulesList", rulesList);
        userParams.put("briefStyle", briefStyle == null || briefStyle.trim().isEmpty() ? "（无参考样例，自行组织清晰的 Markdown 结构）" : briefStyle);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.3);
        requestBody.put("stream", true);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", PromptTemplate.format("summary-system",
                java.util.Collections.singletonMap("auditRole", auditRole)));
        messages.add(systemMessage);

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", PromptTemplate.format("summary-user", userParams));
        messages.add(userMessage);

        requestBody.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.setBearerAuth(apiKey);
        }
        return new HttpEntity<>(requestBody, headers);
    }

    /**
     * 简报专用 LLM 流式调用：上游 stream=true 返回 SSE，解析 delta.content 逐段回调 onDelta，
     * 同时累积返回完整 Markdown 文本。
     * 用 HttpURLConnection 而非 RestTemplate：需要逐行增量读响应体（流式透传给浏览器）。
     */
    public String streamLLMForSummary(String orderJson, String rulesList, String briefStyle,
                                      ApiConfig apiConfig, Consumer<String> onDelta) {
        HttpEntity<Map<String, Object>> entity = buildSummaryRequest(orderJson, rulesList, briefStyle, apiConfig);
        log.info("Calling LLM for order summary (streaming), model={}", apiConfig.getModel());

        StringBuilder full = new StringBuilder();
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(apiConfig.getEndpoint());
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(120000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            HttpHeaders headers = entity.getHeaders();
            for (Map.Entry<String, List<String>> h : headers.entrySet()) {
                if ("Content-Type".equalsIgnoreCase(h.getKey())) {
                    continue;
                }
                conn.setRequestProperty(h.getKey(), String.join(", ", h.getValue()));
            }
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(objectMapper.writeValueAsBytes(entity.getBody()));
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("LLM returned: " + code);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // SSE 行：data: {...} / data: [DONE]，忽略注释行与 event: 行
                    if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:")) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(payload);
                        JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                        // 必须是字符串节点：部分分片 content 为 JSON null，直接 asText() 会拼进字面量 "null"
                        if (delta.isTextual()) {
                            String text = delta.asText();
                            if (!text.isEmpty()) {
                                full.append(text);
                                if (onDelta != null) {
                                    onDelta.accept(text);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Skip non-JSON SSE line: {}", payload);
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("LLM streaming call failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return full.toString();
    }

    private void updateStatus(String taskId, String status, String errorMessage) {
        OrderBriefRecord record = orderBriefRecordMapper.findByTaskId(taskId);
        if (record != null) {
            record.setStatus(status);
            record.setErrorMessage(errorMessage);
            orderBriefRecordMapper.updateById(record);
        }
    }

    private String normalizeTs(String ts) {
        if (ts != null && !ts.trim().isEmpty()) {
            return ts;
        }
        return LocalDateTime.now().format(TS_FORMATTER);
    }

    private Object lockFor(String orderId, String ts) {
        int index = Math.abs((orderId + ":" + ts).hashCode() % LOCK_STRIPES);
        return TASK_LOCKS[index];
    }
}
