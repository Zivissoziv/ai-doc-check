package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.AsyncTaskStatusDto;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.AuditTicketRecord;
import com.smartdoc.entity.Rule;
import com.smartdoc.mapper.AuditTicketRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AsyncAuditService {

    private static final int LOCK_STRIPES = 64;
    private static final Object[] TASK_LOCKS = new Object[LOCK_STRIPES];

    static {
        for (int i = 0; i < TASK_LOCKS.length; i++) {
            TASK_LOCKS[i] = new Object();
        }
    }

    private final ApiConfigService apiConfigService;
    private final AuditTicketRecordMapper auditTicketRecordMapper;
    private final RuleGroupService ruleGroupService;
    private final DocumentParserService documentParserService;
    private final AiAuditService aiAuditService;
    private final AuditFeedbackService auditFeedbackService;
    private final ObjectMapper objectMapper;

    private final ThreadPoolTaskExecutor asyncAuditExecutor;

    public AsyncAuditService(ApiConfigService apiConfigService,
                             AuditTicketRecordMapper auditTicketRecordMapper,
                             RuleGroupService ruleGroupService,
                             DocumentParserService documentParserService,
                             AiAuditService aiAuditService,
                             AuditFeedbackService auditFeedbackService,
                             ObjectMapper objectMapper,
                             @Qualifier("asyncAuditExecutor") ThreadPoolTaskExecutor asyncAuditExecutor) {
        this.apiConfigService = apiConfigService;
        this.auditTicketRecordMapper = auditTicketRecordMapper;
        this.ruleGroupService = ruleGroupService;
        this.documentParserService = documentParserService;
        this.aiAuditService = aiAuditService;
        this.auditFeedbackService = auditFeedbackService;
        this.objectMapper = objectMapper;
        this.asyncAuditExecutor = asyncAuditExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedTasks() {
        List<AuditTicketRecord> unfinished = auditTicketRecordMapper.findUnfinishedAsyncTasks();
        if (unfinished == null || unfinished.isEmpty()) {
            return;
        }
        String message = "服务重启，异步审核任务已中断，请重新提交";
        int count = auditTicketRecordMapper.markUnfinishedTasksFailed(message);
        log.warn("Marked {} unfinished async audit tasks as FAILED on startup", count);
    }

    @Transactional
    public String createAsyncTask(String ticketId, String ts, String ruleGroupId) {
        String taskId;
        boolean created;
        Object lock = lockFor(ticketId, ts);
        synchronized (lock) {
            AuditTicketRecord latest = auditTicketRecordMapper.findLatestByTicketIdAndTsForUpdate(ticketId, ts);
            if (latest != null && latest.getTaskId() != null
                    && (AuditTicketRecord.STATUS_PENDING.equals(latest.getStatus())
                    || AuditTicketRecord.STATUS_RUNNING.equals(latest.getStatus()))) {
                log.info("Async audit task already running: taskId={}, ticketId={}, ts={}",
                        latest.getTaskId(), ticketId, ts);
                return latest.getTaskId();
            }

            taskId = UUID.randomUUID().toString();
            AuditTicketRecord record = AuditTicketRecord.builder()
                    .ticketId(ticketId)
                    .ts(ts)
                    .taskId(taskId)
                    .status(AuditTicketRecord.STATUS_PENDING)
                    .build();
            auditTicketRecordMapper.insert(record);
            created = true;
        }

        if (created) {
            String finalTaskId = taskId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitAsyncTask(finalTaskId, ticketId, ts, ruleGroupId);
                }
            });
            log.info("Created async audit task: taskId={}, ticketId={}, ts={}", taskId, ticketId, ts);
        }
        return taskId;
    }

    public AsyncTaskStatusDto getTaskStatus(String taskId) {
        AuditTicketRecord record = auditTicketRecordMapper.findByTaskId(taskId);
        if (record == null) {
            return null;
        }
        return AsyncTaskStatusDto.builder()
                .taskId(record.getTaskId())
                .ticketId(record.getTicketId())
                .ts(record.getTs())
                .status(record.getStatus())
                .errorMessage(record.getErrorMessage())
                .auditBatchNo(record.getAuditBatchNo())
                .documentName(record.getDocumentName())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private void submitAsyncTask(String taskId, String ticketId, String ts, String ruleGroupId) {
        try {
            asyncAuditExecutor.execute(() -> doAsyncAudit(taskId, ticketId, ts, ruleGroupId));
        } catch (RuntimeException e) {
            String message = "异步审核队列已满，请稍后重试";
            log.warn("Rejected async audit task: taskId={}, ticketId={}, ts={}", taskId, ticketId, ts, e);
            updateStatus(taskId, AuditTicketRecord.STATUS_FAILED, message);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, e);
        }
    }

    private void doAsyncAudit(String taskId, String ticketId, String ts, String ruleGroupId) {
        updateStatus(taskId, AuditTicketRecord.STATUS_RUNNING, null);

        try {
            ApiConfig config = apiConfigService.getRawApiConfig();
            if (config == null || config.getTicketEndpoint() == null || config.getTicketEndpoint().isEmpty()) {
                throw new RuntimeException("工单服务未配置");
            }

            Map<String, Object> ticketInfo = fetchTicketInfo(config, ticketId);
            String documentName = (String) ticketInfo.getOrDefault("documentName", "ticket_" + ticketId);

            String documentText = asNonBlankString(ticketInfo.get("documentText"));
            if (documentText == null) {
                byte[] documentBytes;
                if (ticketInfo.get("documentUrl") != null) {
                    documentBytes = downloadFile((String) ticketInfo.get("documentUrl"));
                } else if (ticketInfo.get("documentBase64") != null) {
                    documentBytes = java.util.Base64.getDecoder().decode((String) ticketInfo.get("documentBase64"));
                } else {
                    throw new RuntimeException("工单没有文档");
                }

                String documentType = documentParserService.detectFileType(documentBytes, "auto");
                documentText = documentParserService.parseDocument(documentBytes, documentType);
            }
            if (documentText == null) {
                throw new RuntimeException("无法解析文档内容");
            }

            Map<String, Object> userData = new HashMap<>();
            if (ticketInfo.get("data") != null) {
                userData = objectMapper.convertValue(ticketInfo.get("data"), Map.class);
            }

            List<RuleDto> ruleDtos = ruleGroupService.getRulesByGroupId(ruleGroupId);
            if (ruleDtos.isEmpty()) {
                throw new RuntimeException("规则组为空: " + ruleGroupId);
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

            List<com.smartdoc.entity.AuditFeedback> savedFeedbacks =
                    auditFeedbackService.saveAuditResults(results, ruleGroupId, null, "async");

            String batchNo = savedFeedbacks != null && !savedFeedbacks.isEmpty()
                    ? savedFeedbacks.get(0).getAuditBatchNo()
                    : UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "_async";

            AuditTicketRecord record = auditTicketRecordMapper.findByTaskId(taskId);
            if (record != null) {
                record.setAuditBatchNo(batchNo);
                record.setDocumentName(documentName);
                record.setStatus(AuditTicketRecord.STATUS_COMPLETED);
                record.setErrorMessage(null);
                auditTicketRecordMapper.updateById(record);
            }

            log.info("Async audit task completed: taskId={}, batchNo={}", taskId, batchNo);

        } catch (Exception e) {
            log.error("Async audit task failed: taskId={}, error={}", taskId, e.getMessage(), e);
            updateStatus(taskId, AuditTicketRecord.STATUS_FAILED, e.getMessage());
        }
    }

    private void updateStatus(String taskId, String status, String errorMessage) {
        AuditTicketRecord record = auditTicketRecordMapper.findByTaskId(taskId);
        if (record != null) {
            record.setStatus(status);
            record.setErrorMessage(errorMessage);
            auditTicketRecordMapper.updateById(record);
        }
    }

    private Map<String, Object> fetchTicketInfo(ApiConfig config, String ticketId) throws Exception {
        String url = config.getTicketEndpoint().replace("{id}", ticketId);
        if (!url.contains(ticketId)) {
            url = url.replaceAll("\\{ticketId}", ticketId);
        }

        RestTemplate rt = createRestTemplate(15000);
        HttpHeaders headers = new HttpHeaders();
        if (config.getTicketToken() != null && !config.getTicketToken().isEmpty()) {
            headers.set("Authorization", "Bearer " + config.getTicketToken());
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = rt.exchange(url, HttpMethod.GET, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("工单系统返回异常: " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        Map<String, Object> result = new HashMap<>();
        if (root.has("documentUrl")) result.put("documentUrl", root.get("documentUrl").asText());
        if (root.has("documentBase64")) result.put("documentBase64", root.get("documentBase64").asText());
        if (root.has("documentText")) result.put("documentText", root.get("documentText").asText());
        if (root.has("documentName")) result.put("documentName", root.get("documentName").asText());
        if (root.has("data")) result.put("data", objectMapper.convertValue(root.get("data"), Map.class));

        return result;
    }

    private String asNonBlankString(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return text.trim().isEmpty() ? null : text;
    }

    private byte[] downloadFile(String url) {
        String lowerUrl = url.toLowerCase();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            throw new RuntimeException("不支持的URL协议");
        }
        RestTemplate rt = createRestTemplate(60000);
        ResponseEntity<byte[]> response = rt.getForEntity(url, byte[].class);
        return response.getBody();
    }

    private RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
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

    private Object lockFor(String ticketId, String ts) {
        int index = Math.abs((ticketId + ":" + ts).hashCode() % LOCK_STRIPES);
        return TASK_LOCKS[index];
    }
}
