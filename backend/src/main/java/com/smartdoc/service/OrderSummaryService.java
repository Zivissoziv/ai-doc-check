package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AsyncOrderTaskStatusDto;
import com.smartdoc.dto.RuleGroupDto;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.OrderBriefRecord;
import com.smartdoc.mapper.OrderBriefRecordMapper;
import com.smartdoc.template.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 变更简报 AI 总结服务：
 * 与工单审核（OrderAsyncAuditService）独立，输出 Markdown 简报并落库到 order_brief_record。
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
    private final ThreadPoolTaskExecutor asyncAuditExecutor;

    public OrderSummaryService(ApiConfigService apiConfigService,
                               OrderDataService orderDataService,
                               OrderBriefRecordMapper orderBriefRecordMapper,
                               RuleGroupService ruleGroupService,
                               ObjectMapper objectMapper,
                               @Qualifier("asyncAuditExecutor") ThreadPoolTaskExecutor asyncAuditExecutor) {
        this.apiConfigService = apiConfigService;
        this.orderDataService = orderDataService;
        this.orderBriefRecordMapper = orderBriefRecordMapper;
        this.ruleGroupService = ruleGroupService;
        this.objectMapper = objectMapper;
        this.asyncAuditExecutor = asyncAuditExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedTasks() {
        int count = orderBriefRecordMapper.markUnfinishedTasksFailed("Service restarted, please resubmit order summary");
        if (count > 0) {
            log.warn("Marked {} unfinished async order summary tasks as FAILED on startup", count);
        }
    }

    @Transactional
    public String createAsyncTask(String ts, String ruleGroupId, List<Map<String, Object>> orders) {
        String normalizedTs = normalizeTs(ts);
        // 综合简报：一次任务覆盖搜索结果中的所有工单，记录以 ALL 标识批次
        String batchOrderId = "ALL";
        String taskId;
        boolean created;
        Object lock = lockFor(batchOrderId, normalizedTs);
        synchronized (lock) {
            OrderBriefRecord latest = orderBriefRecordMapper.findLatestByOrderIdAndTs(batchOrderId, normalizedTs);
            if (latest != null && latest.getTaskId() != null
                    && (OrderBriefRecord.STATUS_PENDING.equals(latest.getStatus())
                    || OrderBriefRecord.STATUS_RUNNING.equals(latest.getStatus()))) {
                log.info("Order summary task already running: taskId={}, ts={}",
                        latest.getTaskId(), normalizedTs);
                return latest.getTaskId();
            }

            taskId = UUID.randomUUID().toString();
            OrderBriefRecord record = OrderBriefRecord.builder()
                    .orderId(batchOrderId)
                    .ts(normalizedTs)
                    .taskId(taskId)
                    .status(OrderBriefRecord.STATUS_PENDING)
                    .build();
            orderBriefRecordMapper.insert(record);
            created = true;
        }

        if (created) {
            final String finalTaskId = taskId;
            final String finalTs = normalizedTs;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitAsyncTask(finalTaskId, finalTs, ruleGroupId, orders);
                }
            });
            log.info("Created order summary task: taskId={}, ts={}, ruleGroupId={}, orderCount={}",
                    taskId, normalizedTs, ruleGroupId, orders == null ? 0 : orders.size());
        }
        return taskId;
    }

    public AsyncOrderTaskStatusDto getTaskStatus(String taskId) {
        OrderBriefRecord record = orderBriefRecordMapper.findByTaskId(taskId);
        if (record == null) {
            return null;
        }
        return AsyncOrderTaskStatusDto.builder()
                .taskId(record.getTaskId())
                .orderId(record.getOrderId())
                .ts(record.getTs())
                .status(record.getStatus())
                .errorMessage(record.getErrorMessage())
                .auditBatchNo(record.getBriefBatchNo())
                .documentName(record.getDocumentName())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
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

    private void submitAsyncTask(String taskId, String ts, String ruleGroupId,
                                 List<Map<String, Object>> orders) {
        try {
            asyncAuditExecutor.execute(() -> doAsyncSummarize(taskId, ts, ruleGroupId, orders));
        } catch (RuntimeException e) {
            String message = "Async summary queue is full, please retry later";
            log.warn("Rejected order summary task: taskId={}, ts={}", taskId, ts, e);
            updateStatus(taskId, OrderBriefRecord.STATUS_FAILED, message);
        }
    }

    @SuppressWarnings("unchecked")
    private void doAsyncSummarize(String taskId, String ts, String ruleGroupId,
                                  List<Map<String, Object>> orders) {
        updateStatus(taskId, OrderBriefRecord.STATUS_RUNNING, null);

        try {
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
                    log.warn("Skip order without data in summary task: taskId={}, orderId={}", taskId, orderId);
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

            ApiConfig config = apiConfigService.getRawApiConfig();
            String brief = callLLMForSummary(orderJson, rulesList, briefStyle, config);
            if (brief == null || brief.trim().isEmpty()) {
                throw new RuntimeException("LLM returned empty brief");
            }

            OrderBriefRecord record = orderBriefRecordMapper.findByTaskId(taskId);
            if (record != null) {
                record.setRuleGroupId(groupDbId);
                record.setDocumentName("共 " + orderCount + " 条工单");
                record.setBriefContent(brief);
                record.setBriefBatchNo(UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "_brief");
                record.setStatus(OrderBriefRecord.STATUS_COMPLETED);
                record.setErrorMessage(null);
                orderBriefRecordMapper.updateById(record);
            }

            log.info("Order summary task completed: taskId={}, orderCount={}", taskId, orderCount);
        } catch (Exception e) {
            log.error("Order summary task failed: taskId={}, error={}", taskId, e.getMessage(), e);
            updateStatus(taskId, OrderBriefRecord.STATUS_FAILED, e.getMessage());
        }
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
     * 简报专用 LLM 调用：system 使用 summary-system 模板，输出 Markdown（无 JSON 校验）
     */
    private String callLLMForSummary(String orderJson, String rulesList, String briefStyle, ApiConfig apiConfig) {
        String endpoint = apiConfig.getEndpoint();
        String apiKey = apiConfig.getApiKey();
        String model = apiConfig.getModel();
        String auditRole = apiConfig.getAuditRole();

        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("API endpoint 未配置");
        }

        Map<String, String> userParams = new HashMap<>();
        userParams.put("orderContent", orderJson);
        userParams.put("rulesList", rulesList);
        userParams.put("briefStyle", briefStyle == null || briefStyle.trim().isEmpty() ? "（无参考样例，自行组织清晰的 Markdown 结构）" : briefStyle);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.3);

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

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        log.info("Calling LLM for order summary, model={}", model);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(120000);
        factory.setReadTimeout(120000);
        RestTemplate rt = new RestTemplate(factory);

        ResponseEntity<String> response = rt.exchange(endpoint, HttpMethod.POST, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("LLM returned: " + response.getStatusCode());
        }

        return extractContent(response.getBody());
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode content = choices.get(0).path("message").path("content");
                if (!content.isMissingNode()) {
                    return content.asText();
                }
            }
            throw new RuntimeException("LLM响应中未找到content字段");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析LLM响应失败: " + e.getMessage(), e);
        }
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
