package com.smartdoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AsyncOrderTaskStatusDto;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.AuditOrderFeedback;
import com.smartdoc.entity.AuditOrderRecord;
import com.smartdoc.entity.Rule;
import com.smartdoc.mapper.AuditOrderRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class OrderAsyncAuditService {

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
    private final AuditOrderRecordMapper auditOrderRecordMapper;
    private final RuleGroupService ruleGroupService;
    private final AiAuditService aiAuditService;
    private final AuditOrderFeedbackService auditOrderFeedbackService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor asyncAuditExecutor;

    public OrderAsyncAuditService(ApiConfigService apiConfigService,
                                  OrderDataService orderDataService,
                                  AuditOrderRecordMapper auditOrderRecordMapper,
                                  RuleGroupService ruleGroupService,
                                  AiAuditService aiAuditService,
                                  AuditOrderFeedbackService auditOrderFeedbackService,
                                  ObjectMapper objectMapper,
                                  @Qualifier("asyncAuditExecutor") ThreadPoolTaskExecutor asyncAuditExecutor) {
        this.apiConfigService = apiConfigService;
        this.orderDataService = orderDataService;
        this.auditOrderRecordMapper = auditOrderRecordMapper;
        this.ruleGroupService = ruleGroupService;
        this.aiAuditService = aiAuditService;
        this.auditOrderFeedbackService = auditOrderFeedbackService;
        this.objectMapper = objectMapper;
        this.asyncAuditExecutor = asyncAuditExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedTasks() {
        List<AuditOrderRecord> unfinished = auditOrderRecordMapper.findUnfinishedAsyncTasks();
        if (unfinished == null || unfinished.isEmpty()) {
            return;
        }
        int count = auditOrderRecordMapper.markUnfinishedTasksFailed("Service restarted, please resubmit order audit");
        log.warn("Marked {} unfinished async order audit tasks as FAILED on startup", count);
    }

    @Transactional
    public String createAsyncTask(String orderId, String ts, String ruleGroupId) {
        String normalizedTs = normalizeTs(ts);
        String taskId;
        boolean created;
        Object lock = lockFor(orderId, normalizedTs);
        synchronized (lock) {
            AuditOrderRecord latest = auditOrderRecordMapper.findLatestByOrderIdAndTsForUpdate(orderId, normalizedTs);
            if (latest != null && latest.getTaskId() != null
                    && (AuditOrderRecord.STATUS_PENDING.equals(latest.getStatus())
                    || AuditOrderRecord.STATUS_RUNNING.equals(latest.getStatus()))) {
                log.info("Order async audit task already running: taskId={}, orderId={}, ts={}",
                        latest.getTaskId(), orderId, normalizedTs);
                return latest.getTaskId();
            }

            taskId = UUID.randomUUID().toString();
            AuditOrderRecord record = AuditOrderRecord.builder()
                    .orderId(orderId)
                    .ts(normalizedTs)
                    .taskId(taskId)
                    .status(AuditOrderRecord.STATUS_PENDING)
                    .build();
            auditOrderRecordMapper.insert(record);
            created = true;
        }

        if (created) {
            final String finalTaskId = taskId;
            final String finalTs = normalizedTs;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitAsyncTask(finalTaskId, orderId, finalTs, ruleGroupId);
                }
            });
            log.info("Created order async audit task: taskId={}, orderId={}, ts={}", taskId, orderId, normalizedTs);
        }
        return taskId;
    }

    public AsyncOrderTaskStatusDto getTaskStatus(String taskId) {
        AuditOrderRecord record = auditOrderRecordMapper.findByTaskId(taskId);
        if (record == null) {
            return null;
        }
        return AsyncOrderTaskStatusDto.builder()
                .taskId(record.getTaskId())
                .orderId(record.getOrderId())
                .ts(record.getTs())
                .status(record.getStatus())
                .errorMessage(record.getErrorMessage())
                .auditBatchNo(record.getAuditBatchNo())
                .documentName(record.getDocumentName())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private void submitAsyncTask(String taskId, String orderId, String ts, String ruleGroupId) {
        try {
            asyncAuditExecutor.execute(() -> doAsyncAudit(taskId, orderId, ts, ruleGroupId));
        } catch (RuntimeException e) {
            String message = "Async audit queue is full, please retry later";
            log.warn("Rejected order async audit task: taskId={}, orderId={}, ts={}", taskId, orderId, ts, e);
            updateStatus(taskId, AuditOrderRecord.STATUS_FAILED, message);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, e);
        }
    }

    private void doAsyncAudit(String taskId, String orderId, String ts, String ruleGroupId) {
        updateStatus(taskId, AuditOrderRecord.STATUS_RUNNING, null);

        try {
            ApiConfig config = apiConfigService.getRawApiConfig();
            if (config == null || config.getOrderAuditEndpoint() == null || config.getOrderAuditEndpoint().isEmpty()) {
                throw new RuntimeException("Order audit service is not configured");
            }

            Map<String, Object> orderInfo = orderDataService.fetchOrderInfo(config, orderId);
            String documentName = (String) orderInfo.getOrDefault("documentName", "order_" + orderId);
            Map<String, Object> userData = new HashMap<>();
            if (orderInfo.get("data") != null) {
                userData = objectMapper.convertValue(orderInfo.get("data"), Map.class);
            }
            String documentText = buildOrderAuditText(orderId, ts, userData);
            if (documentText == null) {
                throw new RuntimeException("Order has no auditable data");
            }

            List<RuleDto> ruleDtos = ruleGroupService.getRulesByGroupId(ruleGroupId, "ticket");
            if (ruleDtos.isEmpty()) {
                throw new RuntimeException("Rule group is empty: " + ruleGroupId);
            }
            List<Rule> rules = convertDtosToRules(ruleDtos);

            ApiConfig auditConfig = apiConfigService.getRawApiConfig();
            if (auditConfig == null) {
                auditConfig = ApiConfig.builder()
                        .endpoint("https://api.deepseek.com/v1/chat/completions")
                        .model("deepseek-chat")
                        .auditRole("专业文档审核专家")
                        .build();
            }

            List<AuditResultDto> results = aiAuditService.performAudit(
                    rules, documentText, auditConfig, userData, true, 0);

            List<AuditOrderFeedback> savedFeedbacks =
                    auditOrderFeedbackService.saveAuditResults(results, ruleGroupId, null, "async");

            String batchNo = savedFeedbacks != null && !savedFeedbacks.isEmpty()
                    ? savedFeedbacks.get(0).getAuditBatchNo()
                    : UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "_async";

            AuditOrderRecord record = auditOrderRecordMapper.findByTaskId(taskId);
            if (record != null) {
                record.setAuditBatchNo(batchNo);
                record.setDocumentName(documentName);
                record.setStatus(AuditOrderRecord.STATUS_COMPLETED);
                record.setErrorMessage(null);
                auditOrderRecordMapper.updateById(record);
            }

            log.info("Order async audit task completed: taskId={}, batchNo={}", taskId, batchNo);
        } catch (Exception e) {
            log.error("Order async audit task failed: taskId={}, error={}", taskId, e.getMessage(), e);
            updateStatus(taskId, AuditOrderRecord.STATUS_FAILED, e.getMessage());
        }
    }

    private void updateStatus(String taskId, String status, String errorMessage) {
        AuditOrderRecord record = auditOrderRecordMapper.findByTaskId(taskId);
        if (record != null) {
            record.setStatus(status);
            record.setErrorMessage(errorMessage);
            auditOrderRecordMapper.updateById(record);
        }
    }

    private String buildOrderAuditText(String orderId, String ts, Map<String, Object> userData) {
        if (userData == null || userData.isEmpty()) {
            return null;
        }
        try {
            StringBuilder text = new StringBuilder("工单信息");
            text.append("\norderId: ").append(orderId);
            if (ts != null && !ts.isEmpty()) {
                text.append("\nts: ").append(ts);
            }
            text.append("\n\n").append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(userData));
            return text.toString();
        } catch (Exception e) {
            log.error("Failed to serialize order data: {}", e.getMessage(), e);
            return null;
        }
    }

    private String normalizeTs(String ts) {
        if (ts != null && !ts.trim().isEmpty()) {
            return ts;
        }
        return LocalDateTime.now().format(TS_FORMATTER);
    }

    private List<Rule> convertDtosToRules(List<RuleDto> dtos) {
        return dtos.stream()
                .map(dto -> Rule.builder()
                        .id(dto.getId())
                        .ruleName(dto.getName())
                        .prompt(dto.getPrompt())
                        .severity(Rule.Severity.valueOf(dto.getSeverity().toUpperCase()))
                        .isEnabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                        .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                        .triggerCondition(dto.getTriggerCondition())
                        .build())
                .collect(java.util.stream.Collectors.toList());
    }

    private Object lockFor(String orderId, String ts) {
        int index = Math.abs((orderId + ":" + ts).hashCode() % LOCK_STRIPES);
        return TASK_LOCKS[index];
    }
}
